package com.mannschaft.app.timeline.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * タイムラインフィードページングレスポンスDTO。
 *
 * <p>フロントエンドが期待する以下の構造を返す:</p>
 * <pre>
 * {
 *   "data": {
 *     "pinned": [...],
 *     "posts": [...]
 *   },
 *   "meta": {
 *     "nextCursor": null,
 *     "limit": 20,
 *     "hasNext": false
 *   }
 * }
 * </pre>
 */
@Builder
@Getter
public class TimelineFeedPageResponse {

    private final Data data;
    private final Meta meta;

    /**
     * フィードデータ部。ピン留め投稿と通常投稿を分離して保持する。
     */
    @Builder
    @Getter
    public static class Data {
        private final List<PostResponse> pinned;
        private final List<PostResponse> posts;
    }

    /**
     * ページングメタ情報。
     */
    @Builder
    @Getter
    public static class Meta {
        private final Long nextCursor;
        private final int limit;
        @JsonProperty("hasNext")
        private final boolean hasNext;
    }
}
