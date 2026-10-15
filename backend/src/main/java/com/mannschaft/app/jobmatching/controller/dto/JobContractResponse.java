package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobContractStatus;

import java.time.LocalDateTime;

/**
 * 求人契約レスポンス。
 */
public record JobContractResponse(
        Long id,
        Long jobPostingId,
        Long jobApplicationId,
        Long requesterUserId,
        Long workerUserId,
        Long chatRoomId,
        Integer baseRewardJpy,
        LocalDateTime workStartAt,
        LocalDateTime workEndAt,
        JobContractStatus status,
        LocalDateTime matchedAt,
        LocalDateTime completionReportedAt,
        LocalDateTime completionApprovedAt,
        LocalDateTime cancelledAt,
        Integer rejectionCount,
        String lastRejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
