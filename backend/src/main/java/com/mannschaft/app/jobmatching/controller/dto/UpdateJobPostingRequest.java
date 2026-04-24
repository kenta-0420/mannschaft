package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 求人投稿更新リクエスト。
 *
 * <p>全フィールドを任意（nullable）として扱う部分更新（PATCH）。
 * 指定されたフィールドのみを上書きし、null のフィールドは現状値を維持する。</p>
 *
 * <p>応募者が 1 件以上存在する場合、報酬・業務日時・応募締切・定員・公開範囲の変更は
 * Service 層で拒否される（応募者保護のため）。ここでは形式的なバリデーションのみを行う。</p>
 */
public record UpdateJobPostingRequest(

        @Size(max = 100) String title,

        @Size(max = 2000) String description,

        @Size(max = 50) String category,

        WorkLocationType workLocationType,

        @Size(max = 255) String workAddress,

        LocalDateTime workStartAt,

        LocalDateTime workEndAt,

        RewardType rewardType,

        @Min(500) @Max(1_000_000) Integer baseRewardJpy,

        @Min(1) Integer capacity,

        LocalDateTime applicationDeadlineAt,

        VisibilityScope visibilityScope,

        LocalDateTime publishAt
) {
}
