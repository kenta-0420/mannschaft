package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 広告クリック記録リクエスト。
 *
 * <p>F09.7 クリック計測 API {@code POST /api/v1/ads/{adId}/click} のリクエストボディ。
 * 未ログイン状態でのクリックに対応するため {@code userId} と {@code impressionId} は任意。</p>
 */
public record RecordAdClickRequest(

        @NotNull
        Long campaignId,

        Long impressionId,

        Long userId
) {
}
