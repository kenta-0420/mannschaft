package com.mannschaft.app.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * カーソルベースページネーション付きレスポンス。
 * <pre>
 * { "data": [...], "meta": { "nextCursor": "msg_12345", "hasNext": true, "limit": 20 } }
 * </pre>
 *
 * @param <T> リスト要素の型
 */
@Getter
public class CursorPagedResponse<T> extends ApiResponse<List<T>> {

    private final CursorMeta meta;

    private CursorPagedResponse(List<T> data, CursorMeta meta) {
        super(data);
        this.meta = meta;
    }

    /**
     * データとカーソルメタ情報から CursorPagedResponse を生成する。
     */
    public static <T> CursorPagedResponse<T> of(List<T> data, CursorMeta meta) {
        return new CursorPagedResponse<>(data, meta);
    }

    /**
     * カーソルページネーションメタ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CursorMeta {
        /** 次ページの起点カーソル。最終ページの場合は null。 */
        private final String nextCursor;
        private final boolean hasNext;
        private final int limit;
    }
}
