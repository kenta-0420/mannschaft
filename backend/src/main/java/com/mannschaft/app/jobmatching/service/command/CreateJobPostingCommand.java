package com.mannschaft.app.jobmatching.service.command;

import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;

import java.time.LocalDateTime;

/**
 * 求人投稿作成コマンド（Service 層入力 DTO）。
 *
 * <p>Controller から受け取った検証済みリクエスト DTO を、Service 内部の値オブジェクトへ
 * 詰め替えるための Record。Controller 層の Request DTO と Service 層を疎結合に保つ目的で分離する。</p>
 *
 * <p>{@code publishAt} が null のときは「即時公開（DRAFT のまま保存し明示 publish で OPEN）」を意味する。</p>
 */
public record CreateJobPostingCommand(
        Long teamId,
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
