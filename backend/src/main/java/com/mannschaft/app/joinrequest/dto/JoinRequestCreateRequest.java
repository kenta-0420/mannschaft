package com.mannschaft.app.joinrequest.dto;

import jakarta.validation.constraints.Size;

/**
 * MEMBER 参加申請の作成リクエスト（柱③-A・CMP-260901-1538）。
 *
 * @param message 申請時の任意の一言メッセージ（500字以内）
 */
public record JoinRequestCreateRequest(
        @Size(max = 500)
        String message
) {
}
