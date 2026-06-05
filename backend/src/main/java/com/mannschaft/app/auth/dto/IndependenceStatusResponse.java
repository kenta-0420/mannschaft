package com.mannschaft.app.auth.dto;

import java.time.LocalDate;

/**
 * F08.9 P3c-2 自立移行ステータス（02_api_design §2.3）。
 *
 * <p>{@code GET /api/v1/me/guardianship/children/{childUserId}/independence-status} のレスポンス。
 * 保護者が「子がいつ自立段階に入るか（切替が封じられるか）」「引き継ぎ（パスワード設定）が
 * 済んでいるか」を把握するための情報を返す。camelCase 1:1。</p>
 *
 * @param childUserId      子（受益者）のユーザーID
 * @param stageKey         子の現在の年齢段階の i18n ラベルキー（日本＝{@code elementary}/{@code junior_high}）
 * @param switchAllowed    現在後見切替が可能か（{@code false}＝既に封印段階）
 * @param sealDate         切替が封印される境界日（その日以降 {@code switchAllowed=false}）。
 *                         既に封印済みでも過去日として返す（生年月日から一意・国別ポリシーが算出）
 * @param passwordSet      子がパスワードを設定済みか（引き継ぎ完了の目安・{@code users.password_hash} の有無）
 */
public record IndependenceStatusResponse(
        Long childUserId,
        String stageKey,
        boolean switchAllowed,
        LocalDate sealDate,
        boolean passwordSet) {
}
