package com.mannschaft.app.team.dto;

import java.time.OffsetDateTime;

/**
 * 加盟申請の拒否の応答（F01.2.1 §10.5 {@code {restriction: {kind, restrictedUntil}}}）。
 *
 * <p>返す制限は合成後の値である（既に BLOCK の組み合わせを冷却付きで拒否しても BLOCK のまま。§5.4）。</p>
 *
 * @param restriction 記録された再申請の制限
 */
public record RejectTeamApplicationResponse(ApplicationRestriction restriction) {

    /**
     * 再申請の制限。
     *
     * @param kind            {@code COOLDOWN}（期限付き）/ {@code BLOCK}（無期限）
     * @param restrictedUntil COOLDOWN の期限（オフセット付き）。BLOCK は null
     */
    public record ApplicationRestriction(String kind, OffsetDateTime restrictedUntil) {
    }
}
