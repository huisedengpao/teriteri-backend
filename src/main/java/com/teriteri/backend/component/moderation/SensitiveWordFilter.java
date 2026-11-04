package com.teriteri.backend.component.moderation;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词过滤器：基于 Aho-Corasick 自动机，一次扫描即可命中词表中全部敏感词，
 * 时间复杂度 O(文本长度 + 命中数)，与词表大小无关，适合弹幕这类高并发同步链路。
 * <p>
 * 词表格式（每行一个）：
 * <pre>
 *   敏感词            -> 默认 BLOCK 级（命中直接拒绝）
 *   敏感词|REVIEW     -> REVIEW 级（先放行，交由 LLM 异步复审）
 * </pre>
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 敏感词级别 */
    public enum Level {
        /** 疑似内容，先放行后由 LLM 复审 */
        REVIEW,
        /** 明确违规，直接拦截 */
        BLOCK
    }

    /** 命中的敏感词条目 */
    public record WordEntry(String word, Level level) {}

    /** AC 自动机节点 */
    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        List<WordEntry> outputs;
    }

    /** 常见用于规避过滤的干扰字符，匹配前先剔除 */
    private static final String NOISE_CHARS = " \t\r\n*~!！.。,，-—_+=$%^&|/\\[]{}()（）<>？?;；:：'\"`·.【】〔〕「」『』《》〈〉、";

    /** 根节点引用用 volatile 保证构建完成后对其他线程立即可见 */
    private volatile Node root = new Node();

    @Autowired
    private ModerationProperties properties;

    /** 启动时加载词表并构建自动机 */
    @PostConstruct
    public void init() {
        try {
            Resource resource = new DefaultResourceLoader().getResource(properties.getWordList());
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }
            build(lines);
        } catch (Exception e) {
            log.error("加载敏感词词表失败: {}，敏感词快筛将不生效", properties.getWordList(), e);
        }
    }

    /**
     * 根据词表行构建 AC 自动机（整体替换，构建期间旧自动机继续可用）
     * @param lines 词表行，格式见类注释
     */
    public void build(List<String> lines) {
        Node newRoot = new Node();
        int count = 0;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Level level = Level.BLOCK;
            int sep = line.lastIndexOf('|');
            if (sep > 0) {
                String suffix = line.substring(sep + 1).trim();
                if ("REVIEW".equalsIgnoreCase(suffix)) {
                    level = Level.REVIEW;
                    line = line.substring(0, sep).trim();
                }
            }
            if (line.isEmpty()) {
                continue;
            }
            addWord(newRoot, normalize(line), level);
            count++;
        }
        buildFailLinks(newRoot);
        this.root = newRoot;
        log.info("敏感词 AC 自动机构建完成，共 {} 个敏感词", count);
    }

    private void addWord(Node root, String word, Level level) {
        Node cur = root;
        for (int i = 0; i < word.length(); i++) {
            cur = cur.children.computeIfAbsent(word.charAt(i), c -> new Node());
        }
        if (cur.outputs == null) {
            cur.outputs = new ArrayList<>(2);
        }
        cur.outputs.add(new WordEntry(word, level));
    }

    /** BFS 构建 fail 指针 */
    private void buildFailLinks(Node root) {
        Deque<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            for (Map.Entry<Character, Node> entry : cur.children.entrySet()) {
                Node child = entry.getValue();
                Node f = cur.fail;
                while (f != root && !f.children.containsKey(entry.getKey())) {
                    f = f.fail;
                }
                child.fail = f.children.getOrDefault(entry.getKey(), root);
                if (child.fail == child) {
                    child.fail = root;
                }
                queue.add(child);
            }
        }
    }

    /**
     * 文本归一化：NFKC 统一全角/兼容字符，转小写，并剔除干扰字符、表情与零宽字符，
     * 对抗"傻 逼"、"傻*逼"、"傻【逼】"、"傻😊逼"、"ＦＵＣＫ"这类变形规避
     */
    private String normalize(String text) {
        String unified = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(unified.length());
        for (int i = 0; i < unified.length(); ) {
            int cp = unified.codePointAt(i);
            i += Character.charCount(cp);
            if (isNoise(cp)) {
                continue;
            }
            sb.appendCodePoint(Character.toLowerCase(cp));
        }
        return sb.toString();
    }

    /** 干扰字符：显式标点 + 符号类字符（含 emoji）+ 零宽/组合字符 */
    private boolean isNoise(int cp) {
        if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT && NOISE_CHARS.indexOf((char) cp) >= 0) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.OTHER_SYMBOL
                || type == Character.MATH_SYMBOL
                || type == Character.MODIFIER_SYMBOL
                || type == Character.CURRENCY_SYMBOL
                || type == Character.FORMAT
                || type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /**
     * 扫描文本，返回全部命中的敏感词（按首次命中位置去重）
     */
    public List<WordEntry> match(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, WordEntry> hits = new LinkedHashMap<>();
        Node cur = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            while (cur != root && !cur.children.containsKey(c)) {
                cur = cur.fail;
            }
            cur = cur.children.getOrDefault(c, root);
            // 沿 fail 链收集所有命中（包含更短的后缀词）
            for (Node n = cur; n != root; n = n.fail) {
                if (n.outputs != null) {
                    for (WordEntry e : n.outputs) {
                        hits.putIfAbsent(e.word(), e);
                    }
                }
            }
        }
        return new ArrayList<>(hits.values());
    }

    /**
     * 审核检查：返回最严格的判定（任一 BLOCK 命中即 BLOCK，否则有命中为 REVIEW）
     */
    public ModerationResult check(String text) {
        List<WordEntry> hits = match(text);
        if (hits.isEmpty()) {
            return ModerationResult.pass();
        }
        boolean block = false;
        List<String> words = new ArrayList<>(hits.size());
        for (WordEntry e : hits) {
            if (e.level() == Level.BLOCK) {
                block = true;
            }
            words.add(e.word());
        }
        return ModerationResult.of(block ? ModerationResult.Action.BLOCK : ModerationResult.Action.REVIEW, words);
    }
}
