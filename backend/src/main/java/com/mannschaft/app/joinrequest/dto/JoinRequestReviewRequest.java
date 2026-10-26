package com.mannschaft.app.joinrequest.dto;

import jakarta.validation.constraints.Size;

/**
 * 参加申請の審査（承認/却下）リクエスト（柱③-A・CMP-260901-1538）。
 *
 * <p>コメントは承認・却下いずれも任意。</p>
 */
public record JoinRequestReviewRequest(
        @Size(max = 500)
        String reviewComment
) {
}
