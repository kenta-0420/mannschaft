package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 原文は本人の完了済みセッションの保存 snapshot だけを返す。 */
public record RecallSessionResponse(UUID id, UUID entryId, RecallSessionStatus status, String version,
        List<RecallSessionPrompt> prompts,List<RecallSessionAnswer> answers,RecallSelfRating selfRating,
        Instant startedAt,Instant completedAt,LocalDate rewardWeek,Instant expiresAt,
        ReflectionEntryResponse original) {
    public RecallSessionResponse {
        prompts=List.copyOf(prompts);answers=List.copyOf(answers);
        if(status!=RecallSessionStatus.COMPLETED && original!=null)
            throw new IllegalArgumentException("Original requires completed recall session");
    }
}
