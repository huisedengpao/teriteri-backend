package com.teriteri.backend.component.moderation;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容审核离线评测脚本：用标注样本量化快筛通道（可选叠加 LLM 复审）的召回率、误报率与延迟。
 * <p>
 * 运行方式：
 * <pre>
 *   mvn -Dtest=ModerationEvalTest test
 *   # 叠加 LLM 复审（会产生真实调用费用，默认关闭）
 *   MODERATION_EVAL_LLM=true DASHSCOPE_API_KEY=xxx mvn -Dtest=ModerationEvalTest test
 * </pre>
 * 评测集格式（CSV，UTF-8）：{@code label,category,text}，label=1 表示违规，label=0 表示正常。
 * 报告会输出到控制台，同时写入 {@code target/moderation-eval-report.md}，可直接作为 README 的评测证据。
 * <p>
 * 指标口径：快筛阶段把 BLOCK 与 REVIEW 都视为“已拦截/已进入复审”，PASS 视为放行；
 * 因此快筛召回率衡量的是“违规内容被审核链路捕获的比例”，不代表最终处置准确率，最终效果需叠加 LLM 复审后评估。
 */
class ModerationEvalTest {

    private static final String DATASET_PATH = "/moderation-eval-samples.csv";
    private static final String WORD_LIST_PATH = "/sensitive-words.txt";

    private record Sample(int label, String category, String text) {}

    private record Metrics(int tp, int fp, int fn, int tn) {
        double precision() {
            return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        }

        double recall() {
            return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        }

        double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0 : 2 * p * r / (p + r);
        }

        double falsePositiveRate() {
            return fp + tn == 0 ? 0 : (double) fp / (fp + tn);
        }

        double accuracy() {
            int total = tp + fp + fn + tn;
            return total == 0 ? 0 : (double) (tp + tn) / total;
        }
    }

    @Test
    void evaluate() throws Exception {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.build(readLines(WORD_LIST_PATH));

        List<Sample> samples = loadSamples();
        assertTrue(samples.size() >= 20, "评测集样本过少，至少需要 20 条");

        int positives = 0;
        int negatives = 0;
        int block = 0;
        int review = 0;
        int pass = 0;
        int blockTruePositive = 0;
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        List<String> misses = new ArrayList<>();
        List<String> falsePositives = new ArrayList<>();
        List<ModerationResult> fastResults = new ArrayList<>();

        for (Sample sample : samples) {
            ModerationResult result = filter.check(sample.text());
            fastResults.add(result);

            if (result.isBlock()) {
                block++;
            } else if (result.needReview()) {
                review++;
            } else {
                pass++;
            }

            boolean flagged = result.getAction() != ModerationResult.Action.PASS;
            if (sample.label() == 1) {
                positives++;
                if (result.isBlock()) {
                    blockTruePositive++;
                }
                if (flagged) {
                    tp++;
                } else {
                    fn++;
                    misses.add(formatSample(sample));
                }
            } else {
                negatives++;
                if (flagged) {
                    fp++;
                    falsePositives.add(formatSample(sample) + " -> " + result.getAction()
                            + " " + result.getHitWords());
                } else {
                    tn++;
                }
            }
        }

        Metrics metrics = new Metrics(tp, fp, fn, tn);
        long[] latency = measureLatency(filter, samples);

        StringBuilder report = new StringBuilder();
        report.append("# 内容审核离线评测报告\n\n");
        report.append("- 样本总数：").append(samples.size())
                .append("（违规 ").append(positives).append(" / 正常 ").append(negatives).append("）\n");
        report.append("- 词表：`sensitive-words.txt`，快筛判定：BLOCK 直接拒绝，REVIEW 进入异步复审，PASS 放行\n\n");

        report.append("## 快筛通道（AC 自动机）\n\n");
        report.append("| 指标 | 数值 |\n|---|---|\n");
        report.append("| 命中分布 | BLOCK ").append(block)
                .append(" / REVIEW ").append(review)
                .append(" / PASS ").append(pass).append(" |\n");
        report.append("| 混淆矩阵 | TP ").append(tp).append(" / FP ").append(fp)
                .append(" / FN ").append(fn).append(" / TN ").append(tn).append(" |\n");
        report.append("| 召回率 | ").append(pct(metrics.recall())).append(" |\n");
        report.append("| 精确率 | ").append(pct(metrics.precision())).append(" |\n");
        report.append("| F1 | ").append(pct(metrics.f1())).append(" |\n");
        report.append("| 误报率 | ").append(pct(metrics.falsePositiveRate())).append(" |\n");
        report.append("| 准确率 | ").append(pct(metrics.accuracy())).append(" |\n");
        report.append("| 强拦截召回 | ")
                .append(pct(positives == 0 ? 0 : (double) blockTruePositive / positives))
                .append("（仅统计 BLOCK，不含 REVIEW） |\n");
        report.append("| 单次快筛延迟 | 平均 ").append(latency[0]).append(" µs / P99 ")
                .append(latency[1]).append(" µs |\n\n");

        report.append("### 漏放样本（label=1 但快筛 PASS）\n\n");
        appendList(report, misses, "无");

        report.append("\n### 误报样本（label=0 但快筛 BLOCK/REVIEW）\n\n");
        appendList(report, falsePositives, "无");

        appendLlmSection(report, samples, fastResults);

        System.out.println(report);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/moderation-eval-report.md"), report.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 可选：叠加 LLM 复审，计算端到端指标。
     * 仅在 MODERATION_EVAL_LLM=true 且配置 DASHSCOPE_API_KEY 时执行，避免默认产生调用费用。
     */
    private void appendLlmSection(StringBuilder report, List<Sample> samples, List<ModerationResult> fastResults) {
        report.append("\n## LLM 复审通道（可选）\n\n");
        boolean enabled = "true".equalsIgnoreCase(System.getenv("MODERATION_EVAL_LLM"));
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (!enabled) {
            report.append("未启用：设置 `MODERATION_EVAL_LLM=true` 与 `DASHSCOPE_API_KEY` 后重跑，可得到端到端指标。\n");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            report.append("已启用但未设置 `DASHSCOPE_API_KEY`，按 fail-open 跳过。\n");
            return;
        }

        ModerationProperties properties = new ModerationProperties();
        properties.getLlm().setApiKey(apiKey.trim());
        String model = System.getenv("MODERATION_EVAL_MODEL");
        if (model != null && !model.isBlank()) {
            properties.getLlm().setModel(model.trim());
        }
        LlmReviewClient client = new LlmReviewClient(properties);

        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        int failures = 0;
        int llmConfirmedReview = 0;
        int llmRescuedPass = 0;
        int llmFalsePositive = 0;
        boolean[] llmReject = new boolean[samples.size()];
        double[] sampleU = new double[samples.size()];
        Random sampling = new Random(20261104L);
        for (int i = 0; i < samples.size(); i++) {
            sampleU[i] = sampling.nextDouble();
        }
        List<String> llmMisses = new ArrayList<>();
        List<String> llmFalsePositives = new ArrayList<>();

        long started = System.nanoTime();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            ModerationResult fast = fastResults.get(i);
            boolean flagged;
            if (fast.isBlock()) {
                flagged = true;
            } else {
                Optional<LlmReviewClient.Verdict> verdict = client.review(sample.text());
                if (verdict.isEmpty()) {
                    failures++;
                    flagged = false;
                } else if (verdict.get().reject()) {
                    flagged = true;
                    llmReject[i] = true;
                    if (sample.label() == 1) {
                        if (fast.getAction() == ModerationResult.Action.PASS) {
                            llmRescuedPass++;
                        } else {
                            llmConfirmedReview++;
                        }
                    } else {
                        llmFalsePositive++;
                    }
                } else {
                    flagged = false;
                }
            }

            if (sample.label() == 1) {
                if (flagged) {
                    tp++;
                } else {
                    fn++;
                    llmMisses.add(formatSample(sample));
                }
            } else if (flagged) {
                fp++;
                llmFalsePositives.add(formatSample(sample));
            } else {
                tn++;
            }
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        Metrics metrics = new Metrics(tp, fp, fn, tn);
        report.append("| 指标 | 数值 |\n|---|---|\n");
        report.append("| 端到端召回率 | ").append(pct(metrics.recall())).append(" |\n");
        report.append("| 端到端精确率 | ").append(pct(metrics.precision())).append(" |\n");
        report.append("| 端到端 F1 | ").append(pct(metrics.f1())).append(" |\n");
        report.append("| 端到端误报率 | ").append(pct(metrics.falsePositiveRate())).append(" |\n");
        report.append("| LLM 确认 REVIEW 级违规 | ").append(llmConfirmedReview).append(" 条 |\n");
        report.append("| LLM 救回 PASS 级漏放 | ").append(llmRescuedPass).append(" 条 |\n");
        report.append("| LLM 误判正常样本 | ").append(llmFalsePositive).append(" 条 |\n");
        report.append("| LLM 调用失败（按 fail-open 放行） | ").append(failures).append(" 条 |\n");
        report.append("| 复审总耗时 | ").append(elapsedMillis).append(" ms |\n\n");
        report.append("### 端到端仍漏放\n\n");
        appendList(report, llmMisses, "无");
        report.append("\n### 端到端误报\n\n");
        appendList(report, llmFalsePositives, "无");

        if ("true".equalsIgnoreCase(System.getenv("MODERATION_EVAL_CURVE"))) {
            appendCostCurve(report, samples, fastResults, llmReject, sampleU);
        }
    }

    /**
     * 用同一批 LLM 判定结果重放不同抽样率，避免重复调用。
     * 抽样只作用于快筛 PASS 的样本，REVIEW 级始终复审。
     */
    private void appendCostCurve(StringBuilder report, List<Sample> samples, List<ModerationResult> fastResults,
                                 boolean[] llmReject, double[] sampleU) {
        report.append("\n## 复审抽样成本曲线（同一批评审结果重放）\n\n");
        report.append("| 抽样率 | 复审调用 | 召回率 | 精确率 | 误报率 | F1 |\n");
        report.append("| --- | --- | --- | --- | --- | --- |\n");
        for (double rate : new double[]{0.0, 0.1, 0.3, 0.5, 1.0}) {
            int calls = 0;
            int tp = 0;
            int fp = 0;
            int fn = 0;
            int tn = 0;
            for (int i = 0; i < samples.size(); i++) {
                Sample sample = samples.get(i);
                ModerationResult fast = fastResults.get(i);
                boolean flagged;
                if (fast.isBlock()) {
                    flagged = true;
                } else if (fast.getAction() == ModerationResult.Action.REVIEW) {
                    calls++;
                    flagged = llmReject[i];
                } else if (sampleU[i] < rate) {
                    calls++;
                    flagged = llmReject[i];
                } else {
                    flagged = false;
                }
                if (sample.label() == 1) {
                    if (flagged) {
                        tp++;
                    } else {
                        fn++;
                    }
                } else if (flagged) {
                    fp++;
                } else {
                    tn++;
                }
            }
            Metrics metrics = new Metrics(tp, fp, fn, tn);
            report.append("| ").append(pct(rate)).append(" | ").append(calls)
                    .append(" | ").append(pct(metrics.recall()))
                    .append(" | ").append(pct(metrics.precision()))
                    .append(" | ").append(pct(metrics.falsePositiveRate()))
                    .append(" | ").append(pct(metrics.f1())).append(" |\n");
        }
        report.append("\n- 本评测集快筛无漏放，抽样率主要影响误报与调用成本；若存在 PASS 级漏放，抽样率也会影响召回\n");
    }

    /** 预热后循环扫描评测集，统计单次快筛的平均与 P99 延迟 */
    private long[] measureLatency(SensitiveWordFilter filter, List<Sample> samples) {
        for (int i = 0; i < 2000; i++) {
            filter.check(samples.get(i % samples.size()).text());
        }
        int rounds = 20000;
        long[] times = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            String text = samples.get(i % samples.size()).text();
            long start = System.nanoTime();
            filter.check(text);
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        long sum = 0;
        for (long time : times) {
            sum += time;
        }
        long averageMicros = sum / rounds / 1000;
        long p99Micros = times[(int) (rounds * 0.99)] / 1000;
        return new long[]{averageMicros, p99Micros};
    }

    private List<Sample> loadSamples() throws Exception {
        List<Sample> samples = new ArrayList<>();
        for (String raw : readLines(DATASET_PATH)) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("label,")) {
                continue;
            }
            String[] columns = parseCsv(line);
            if (columns.length < 3) {
                continue;
            }
            samples.add(new Sample(Integer.parseInt(columns[0].trim()), columns[1].trim(), columns[2]));
        }
        return samples;
    }

    private List<String> readLines(String resource) throws Exception {
        InputStream input = ModerationEvalTest.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("找不到资源: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    /** 极简 CSV 解析：支持逗号分隔与双引号包裹、双引号转义 */
    private String[] parseCsv(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        columns.add(current.toString());
        return columns.toArray(new String[0]);
    }

    private String formatSample(Sample sample) {
        return "[" + sample.category() + "] " + sample.text();
    }

    private void appendList(StringBuilder report, List<String> items, String emptyText) {
        if (items.isEmpty()) {
            report.append("- ").append(emptyText).append("\n");
            return;
        }
        for (String item : items) {
            report.append("- ").append(item).append("\n");
        }
    }

    private String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
