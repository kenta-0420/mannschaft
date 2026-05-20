package com.mannschaft.app.publicview.dto;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import jakarta.validation.constraints.NotNull;

/**
 * F19.1 Phase 2: Admin 向け supporter_name_disclosure 切替リクエスト DTO。
 *
 * <p>{@code confirmed=true} が必須。DISPLAY_NAME → REAL_NAME の切替には警告ダイアログで
 * ユーザーが確認チェックをオンにしてから送信する（設計書 §6.2）。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6 / §6.2</p>
 */
public record SupporterNameDisclosurePatchRequest(
        @NotNull(message = "mode は必須です")
        NameDisclosureMode mode,

        /**
         * 変更確認フラグ。{@code true} でなければ
         * {@link com.mannschaft.app.publicview.error.PublicViewErrorCode#NAME_DISCLOSURE_CONFIRM_REQUIRED}
         * (400) を返す。
         */
        boolean confirmed
) {
}
