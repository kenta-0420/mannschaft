package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動リマインドレスポンス DTO。
 *
 * <p>Phase 11 第三陣 3-A で追加。</p>
 */
@Getter
@RequiredArgsConstructor
public class RemindResponse {

    /** 文書 ID。 */
    private final Long documentId;

    /** リマインドを送った未押印受信者数。 */
    private final int remindedCount;
}
