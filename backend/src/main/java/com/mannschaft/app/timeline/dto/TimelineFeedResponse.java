package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * タイムラインフィードレスポンスDTO。
 *
 * <p>FE が期待する形式:
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
 * </p>
 *
 * <p>FE 型定義 {@code frontend/app/types/timeline.ts#TimelineFeedResponse} に対応する。</p>
 */
@Getter
@RequiredArgsConstructor
public class TimelineFeedResponse {

    private final FeedData data;
    private final FeedMeta meta;

    /**
     * フィードデータ部。ピン留め投稿リストと通常投稿リストを含む。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FeedData {
        private final List<PostResponse> pinned;
        private final List<PostResponse> posts;
    }

    /**
     * フィードメタ情報。カーソルページネーション用。
     *
     * <p>TODO: 無限スクロール本実装時は nextCursor をポスト ID ベースに変更する。
     * 現在は簡易実装として hasNext のみ判定する（最終ページ判定: posts.size() >= limit）。</p>
     */
    @Getter
    @RequiredArgsConstructor
    public static class FeedMeta {
        /** 次ページの起点カーソル。未実装のため常に null。 */
        private final Long nextCursor;
        private final int limit;
        private final boolean hasNext;
    }

    /**
     * フィードデータとメタを組み立ててレスポンスを生成する。
     *
     * @param pinned    ピン留め投稿リスト
     * @param posts     通常投稿リスト
     * @param limit     リクエスト件数
     * @return タイムラインフィードレスポンス
     */
    public static TimelineFeedResponse of(
            List<PostResponse> pinned,
            List<PostResponse> posts,
            int limit) {
        boolean hasNext = posts.size() >= limit;
        // TODO: 無限スクロール本実装時は最終投稿 ID をカーソルとして返す
        FeedData feedData = new FeedData(pinned, posts);
        FeedMeta feedMeta = new FeedMeta(null, limit, hasNext);
        return new TimelineFeedResponse(feedData, feedMeta);
    }
}
