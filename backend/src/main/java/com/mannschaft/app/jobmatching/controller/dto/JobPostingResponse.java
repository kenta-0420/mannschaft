package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;

import java.time.LocalDateTime;

/**
 * 求人投稿レスポンス（詳細）。
 *
 * <p>応募画面・詳細画面の描画に必要な全フィールドを含める。Entity の ID や監査フィールドも
 * 公開前提でマッピングする。</p>
 */
public record JobPostingResponse(
        Long id,
        Long teamId,
        Long createdByUserId,
        String title,
        String description,
        String category,
        WorkLocationType workLocationType,
        String workAddress,
        LocalDateTime workStartAt,
        LocalDateTime workEndAt,
        RewardType rewardType,
        Integer baseRewardJpy,
        Integer capacity,
        LocalDateTime applicationDeadlineAt,
        VisibilityScope visibilityScope,
        JobPostingStatus status,
        LocalDateTime publishAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
