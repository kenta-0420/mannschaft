package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;

import java.time.LocalDateTime;

/**
 * 求人投稿レスポンス（一覧用の簡略版）。
 *
 * <p>一覧画面描画に必要な主要フィールドに絞り、説明文などの長文や監査フィールドは省略する。
 * 帯域削減と一覧ビュー最適化のための Summary 型。</p>
 */
public record JobPostingSummaryResponse(
        Long id,
        Long teamId,
        String title,
        String category,
        WorkLocationType workLocationType,
        LocalDateTime workStartAt,
        LocalDateTime workEndAt,
        RewardType rewardType,
        Integer baseRewardJpy,
        Integer capacity,
        LocalDateTime applicationDeadlineAt,
        VisibilityScope visibilityScope,
        JobPostingStatus status,
        LocalDateTime publishAt
) {
}
