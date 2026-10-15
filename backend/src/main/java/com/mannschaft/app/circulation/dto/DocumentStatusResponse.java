package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 受信者ごとの押印状況一覧レスポンス DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加（GET /circulations/{id}/status の本体）。</p>
 */
@Getter
@RequiredArgsConstructor
public class DocumentStatusResponse {

    /** 文書 ID。 */
    private final Long documentId;

    /** 文書ステータス（DRAFT / IN_PROGRESS / COMPLETED / CANCELLED / ARCHIVED）。 */
    private final String documentStatus;

    /** 受信者ごとの押印状況一覧。 */
    private final List<RecipientStatusEntry> recipients;
}
