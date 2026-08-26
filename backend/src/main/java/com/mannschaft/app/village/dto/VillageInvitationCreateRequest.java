package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 村招待の発行リクエスト（骨格）。
 *
 * <p>本クラスは試練（テスト先行）が参照するための最小スタブである。
 * 出陣（実装）時に実体へ差し替えること。</p>
 *
 * @param maxUses        使用可能回数の上限（無制限は作らせない）
 * @param expiresInHours 有効期限（発行時刻からの時間数。無期限は作らせない）
 * @param targetUserId   指名型招待の宛先ユーザーID。null ならリンク型（誰でも使える）
 */
public record VillageInvitationCreateRequest(
        @NotNull @Min(1) @Max(100) Integer maxUses,
        @NotNull @Min(1) @Max(720) Integer expiresInHours,
        Long targetUserId) {
}
