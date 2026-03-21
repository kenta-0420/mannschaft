package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 投票リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CastVoteRequest {

    @NotEmpty
    @Valid
    private final List<VoteItem> votes;

    /**
     * 個別の投票項目。
     */
    @Getter
    @RequiredArgsConstructor
    public static class VoteItem {

        @NotNull
        private final Long motionId;

        @NotNull
        private final String voteType;
    }
}
