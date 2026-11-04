package com.teriteri.backend.component.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词 AC 自动机单元测试（不依赖 Spring 容器）
 */
class SensitiveWordFilterTest {

    private SensitiveWordFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SensitiveWordFilter();
        filter.build(List.of(
                "傻逼",
                "杀了你",
                "赌博网站",
                "fuck",
                "你妈",
                "你妈的",
                "加微信|REVIEW",
                "兼职刷单|REVIEW"
        ));
    }

    @Test
    void cleanTextShouldPass() {
        ModerationResult result = filter.check("这个视频做得真好，下次还来");
        assertEquals(ModerationResult.Action.PASS, result.getAction());
        assertFalse(result.isBlock());
        assertTrue(result.getHitWords().isEmpty());
    }

    @Test
    void blockWordShouldBlock() {
        ModerationResult result = filter.check("你个傻逼，滚");
        assertEquals(ModerationResult.Action.BLOCK, result.getAction());
        assertTrue(result.getHitWords().contains("傻逼"));
    }

    @Test
    void reviewWordShouldReview() {
        ModerationResult result = filter.check("想赚钱的加微信");
        assertEquals(ModerationResult.Action.REVIEW, result.getAction());
        assertFalse(result.isBlock());
        assertTrue(result.needReview());
        assertTrue(result.getHitWords().contains("加微信"));
    }

    @Test
    void blockTakesPrecedenceOverReview() {
        ModerationResult result = filter.check("傻逼，想赚钱的加微信");
        assertEquals(ModerationResult.Action.BLOCK, result.getAction());
        assertTrue(result.getHitWords().contains("傻逼"));
        assertTrue(result.getHitWords().contains("加微信"));
    }

    @Test
    void noiseCharsShouldNotEvade() {
        // 插入空格、特殊字符的变形规避应仍被命中
        assertEquals(ModerationResult.Action.BLOCK, filter.check("傻 逼").getAction());
        assertEquals(ModerationResult.Action.BLOCK, filter.check("傻*逼").getAction());
        assertEquals(ModerationResult.Action.BLOCK, filter.check("杀-了-你").getAction());
        // 中文括号、emoji、全角字符规避
        assertEquals(ModerationResult.Action.BLOCK, filter.check("傻【逼】").getAction());
        assertEquals(ModerationResult.Action.BLOCK, filter.check("傻😊逼").getAction());
        assertEquals(ModerationResult.Action.BLOCK, filter.check("ＦＵＣＫ you").getAction());
    }

    @Test
    void matchingShouldBeCaseInsensitive() {
        assertEquals(ModerationResult.Action.BLOCK, filter.check("FuCk you").getAction());
    }

    @Test
    void overlappingWordsShouldAllHit() {
        // “你妈的”包含“你妈”，两个词都应命中
        ModerationResult result = filter.check("你妈的");
        assertTrue(result.getHitWords().contains("你妈"));
        assertTrue(result.getHitWords().contains("你妈的"));
    }

    @Test
    void multipleHitsInOneText() {
        List<SensitiveWordFilter.WordEntry> hits = filter.match("赌博网站招兼职刷单了");
        assertEquals(2, hits.size());
    }

    @Test
    void emptyAndNullTextShouldPass() {
        assertEquals(ModerationResult.Action.PASS, filter.check(null).getAction());
        assertEquals(ModerationResult.Action.PASS, filter.check("").getAction());
    }

    @Test
    void rebuildShouldReplaceWordList() {
        // 重建后旧词表失效
        filter.build(List.of("新词"));
        assertEquals(ModerationResult.Action.PASS, filter.check("傻逼").getAction());
        assertEquals(ModerationResult.Action.BLOCK, filter.check("这是新词").getAction());
    }
}
