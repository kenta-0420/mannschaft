package com.mannschaft.app.reflection.dto;

import java.util.UUID;

/** 開始時に凍結するcue専用設問。答え側の本文を追加しない。 */
public record RecallSessionPrompt(UUID id, Kind kind, String heading, String promptSide,
                                 String promptText, int maxAnswerLength) {
    public enum Kind { TERM_CARD, FREE_RECALL }
    public RecallSessionPrompt {
        if (id == null || kind == null || heading == null || promptText == null
                || (kind == Kind.TERM_CARD && maxAnswerLength != 200)
                || (kind == Kind.FREE_RECALL && maxAnswerLength != 10000)
                || (kind == Kind.TERM_CARD && !"TERM".equals(promptSide) && !"MEANING".equals(promptSide))
                || (kind == Kind.FREE_RECALL && promptSide != null)) {
            throw new IllegalArgumentException("想起設問の定義が不正です");
        }
    }
}
