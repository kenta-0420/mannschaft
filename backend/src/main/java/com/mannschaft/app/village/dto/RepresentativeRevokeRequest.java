package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * 村代表委任 revoke リクエスト（F17 Phase 2 U3）。
 *
 * <p>取消し理由メモを任意で添える。本人による自主取消し / HEADMAN 強制取消し
 * いずれの場合も同一スキーマでよい。</p>
 *
 * @param note 取消し理由メモ（任意・200 文字以内）
 */
public record RepresentativeRevokeRequest(
        @Size(max = 200) String note
) {
}
