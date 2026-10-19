package com.teriteri.backend.component.moderation;

import java.util.Collections;
import java.util.List;

/**
 * 内容审核快筛结果（敏感词 AC 自动机产出）
 */
public class ModerationResult {

    /**
     * 审核动作
     * PASS   通过，未命中任何敏感词
     * REVIEW 命中复审级敏感词，内容先放行，交由 LLM 异步复审
     * BLOCK  命中拦截级敏感词，直接拒绝
     */
    public enum Action { PASS, REVIEW, BLOCK }

    private final Action action;
    private final List<String> hitWords;

    private ModerationResult(Action action, List<String> hitWords) {
        this.action = action;
        this.hitWords = hitWords;
    }

    public static ModerationResult pass() {
        return new ModerationResult(Action.PASS, Collections.emptyList());
    }

    public static ModerationResult of(Action action, List<String> hitWords) {
        return new ModerationResult(action, Collections.unmodifiableList(hitWords));
    }

    public Action getAction() {
        return action;
    }

    public List<String> getHitWords() {
        return hitWords;
    }

    public boolean isBlock() {
        return action == Action.BLOCK;
    }

    public boolean needReview() {
        return action == Action.REVIEW;
    }
}
