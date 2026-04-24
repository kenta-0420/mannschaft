package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 求人投稿作成リクエスト。
 *
 * <p>F13.1 Phase 13.1.1 MVP における求人の新規登録（DRAFT 作成）API のリクエストボディ。
 * バリデーションは jakarta.validation により Controller 層で実施する。業務的な整合性
 * （公開範囲 MVP 制限・報酬範囲・日時整合性）は Service 層で再度検証する。</p>
 *
 * <p>フィールド:</p>
 * <ul>
 *   <li>{@code teamId} — 投稿先チームID</li>
 *   <li>{@code title} — 求人タイトル（100 文字まで）</li>
 *   <li>{@code description} — 求人説明文（2000 文字まで）</li>
 *   <li>{@code category} — 求人カテゴリ（任意、50 文字まで）</li>
 *   <li>{@code workLocationType} — 業務場所種別（ONSITE / ONLINE / HYBRID）</li>
 *   <li>{@code workAddress} — 業務住所（任意、255 文字まで）</li>
 *   <li>{@code workStartAt} — 業務開始日時（未来）</li>
 *   <li>{@code workEndAt} — 業務終了日時（workStartAt より後、Service で検証）</li>
 *   <li>{@code rewardType} — 報酬タイプ（HOURLY / DAILY / LUMP_SUM）</li>
 *   <li>{@code baseRewardJpy} — 業務報酬（500 ～ 1,000,000 円）</li>
 *   <li>{@code capacity} — 募集定員（1 以上）</li>
 *   <li>{@code applicationDeadlineAt} — 応募締切日時（未来・workStartAt 以前）</li>
 *   <li>{@code visibilityScope} — 公開範囲。MVP では TEAM_MEMBERS または TEAM_MEMBERS_SUPPORTERS のみ</li>
 *   <li>{@code publishAt} — 予約公開日時（任意、指定時は未来）</li>
 * </ul>
 */
public record CreateJobPostingRequest(
        @NotNull Long teamId,
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 2000) String description,
        @Size(max = 50) String category,
        @NotNull WorkLocationType workLocationType,
        @Size(max = 255) String workAddress,
        @NotNull @Future LocalDateTime workStartAt,
        @NotNull LocalDateTime workEndAt,
        @NotNull RewardType rewardType,
        @NotNull @Min(500) @Max(1_000_000) Integer baseRewardJpy,
        @NotNull @Min(1) Integer capacity,
        @NotNull @Future LocalDateTime applicationDeadlineAt,
        @NotNull VisibilityScope visibilityScope,
        LocalDateTime publishAt
) {
}
