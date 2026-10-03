package com.mannschaft.app.team.dto;

import java.time.OffsetDateTime;

/**
 * 辞退・拒否によって記録された制限の要約（F01.2.1 §10.5 {@code {restriction: {kind, restrictedUntil}}}）。
 *
 * <p>合成規則（§5.4）を適用した後の、いま有効な制限を返す（既にブロック中なら冷却を記録しても BLOCK のまま）。</p>
 *
 * @param restriction 制限の種別と期限
 */
public record TeamOrgRestrictionSummaryResponse(Restriction restriction) {

    /**
     * @param kind            {@code COOLDOWN}（期限付き）/ {@code BLOCK}（無期限）
     * @param restrictedUntil COOLDOWN の期限（オフセット付き）。BLOCK は null
     */
    public record Restriction(String kind, OffsetDateTime restrictedUntil) {
    }
}
