package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 原文は本人の完了済みセッションの保存 snapshot だけを返す。 */
public record RecallSessionResponse(UUID id, UUID entryId, RecallSessionStatus status, String version,
        List<RecallSessionPrompt> prompts,List<RecallSessionAnswer> answers,RecallSelfRating selfRating,
        Instant startedAt,@Schema(nullable=true) Instant completedAt,LocalDate rewardWeek,
        @Schema(nullable=true) Instant expiresAt,@Schema(nullable=true) ReflectionEntryResponse original) {
    public RecallSessionResponse {
        prompts=List.copyOf(prompts);answers=List.copyOf(answers);
        if(status!=RecallSessionStatus.COMPLETED && original!=null)
            throw new IllegalArgumentException("Original requires completed recall session");
    }
}
