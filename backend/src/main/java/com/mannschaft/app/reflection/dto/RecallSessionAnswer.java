package com.mannschaft.app.reflection.dto;

import java.util.UUID;

/** 本人が明示した回答状態。未回答とFORGOTを混同しない。 */
public record RecallSessionAnswer(UUID promptId, State state, String text) {
    public enum State { ANSWERED, FORGOT }
    public RecallSessionAnswer {
        if (promptId == null || state == null || (state == State.FORGOT && text != null)
                || (state == State.ANSWERED && (text == null || text.isBlank()))) {
            throw new IllegalArgumentException("想起回答の状態が不正です");
        }
    }
}
