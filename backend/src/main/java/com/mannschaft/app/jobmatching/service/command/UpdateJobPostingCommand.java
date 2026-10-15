package com.mannschaft.app.jobmatching.service.command;

import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;

import java.time.LocalDateTime;

/**
 * 求人投稿更新コマンド（Service 層入力 DTO）。
 *
 * <p>全フィールド nullable。指定されたフィールドのみ上書きする「部分更新」として扱う。
 * ただし応募者が 1 件でも存在する状態では、報酬・業務日時・公開範囲の変更は不可（業務奉行組により検証）。</p>
 */
public record UpdateJobPostingCommand(
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
        LocalDateTime publishAt
) {
}
