package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;

import java.time.LocalDateTime;

/**
 * 求人応募レスポンス。
 */
public record JobApplicationResponse(
        Long id,
        Long jobPostingId,
        Long applicantUserId,
        String selfPr,
        JobApplicationStatus status,
        LocalDateTime appliedAt,
        LocalDateTime decidedAt,
        Long decidedByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
