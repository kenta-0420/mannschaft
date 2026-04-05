package com.mannschaft.app.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ページネーション付きレスポンス。
 * <pre>
 * { "data": [...], "meta": { "total": 100, "page": 1, "size": 20, "totalPages": 5 } }
 * </pre>
 *
 * @param <T> リスト要素の型
 */
@Getter
public class PagedResponse<T> extends ApiResponse<List<T>> {

    private final PageMeta meta;

    private PagedResponse(List<T> data, PageMeta meta) {
        super(data);
        this.meta = meta;
    }

    /**
     * データとページメタ情報から PagedResponse を生成する。
     */
    public static <T> PagedResponse<T> of(List<T> data, PageMeta meta) {
        return new PagedResponse<>(data, meta);
    }

    /**
     * ページネーションメタ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class PageMeta {
        private final long total;
        private final int page;
        private final int size;
        private final int totalPages;
    }
}
