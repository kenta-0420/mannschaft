package com.mannschaft.app.proxyvote.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出席・委任状況一覧レスポンスDTO。
 */
@Getter
@Builder
public class AttendanceResponse {

    private final Long sessionId;
    private final Integer eligibleCount;
    private final SummaryResponse summary;
    private final List<MemberStatusResponse> members;

    /**
     * サマリー。
     */
    @Getter
    @Builder
    public static class SummaryResponse {
        private final Long votedCount;
        private final Long delegatedCount;
        private final Long notRespondedCount;
    }

    /**
     * メンバー状況。
     */
    @Getter
    @Builder
    public static class MemberStatusResponse {
        private final Long userId;
        private final String status;
        private final LocalDateTime votedAt;
        private final DelegationSummaryResponse delegation;
    }

    /**
     * 委任サマリー。
     */
    @Getter
    @Builder
    public static class DelegationSummaryResponse {
        private final Long id;
        private final Long delegateId;
        private final Boolean isBlank;
        private final String status;
        private final Boolean hasElectronicSeal;
    }
}
