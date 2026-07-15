package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 枠別のキャンセル待ち件数レスポンス（F03.4.5 §6.1・ADMIN 専用）。
 *
 * <p>会員には人数を見せない（競争心理の煽り防止・§6.1）。ADMIN の管理画面表示専用。</p>
 */
@Getter
@Builder
public class WaitlistCountResponse {

    /** 対象枠ID。 */
    private final Long slotId;

    /** WAITING 件数。 */
    private final long waitingCount;
}
