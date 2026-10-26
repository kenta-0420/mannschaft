package com.mannschaft.app.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 単一リソース用レスポンスラッパー。
 * <pre>
 * { "data": { ... } }
 * </pre>
 *
 * @param <T> レスポンスデータの型
 */
@Getter
@RequiredArgsConstructor
public class ApiResponse<T> {

    private final T data;

    /**
     * データを ApiResponse でラップして返す。
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }
}
