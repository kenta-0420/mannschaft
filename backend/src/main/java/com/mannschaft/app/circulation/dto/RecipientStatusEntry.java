package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 受信者ごとの押印状況エントリ DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加（GET /circulations/{id}/status のレスポンス要素）。</p>
 */
@Getter
@RequiredArgsConstructor
public class RecipientStatusEntry {

    /** ユーザー ID。 */
    private final Long userId;

    /** 表示名（display_name）。 */
    private final String displayName;

    /** 押印状態（STAMPED / PENDING / SKIPPED / REJECTED）。 */
    private final String stampStatus;

    /** 押印日時（PENDING の場合は null）。 */
    private final LocalDateTime stampedAt;

    /** 並び順。 */
    private final Integer sortOrder;
}
