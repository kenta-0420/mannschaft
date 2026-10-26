package com.mannschaft.app.receipt.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * ページング結果を {@code ApiResponse.data} の内側に載せるための DTO（F08.12 §4.1）。
 *
 * <p>Spring の {@code Page} をそのまま直列化すると構造が Spring のバージョンに依存するため、
 * 契約として必要な項目だけを持つ独自型に写す。</p>
 *
 * @param <T> 要素型
 */
@Getter
@RequiredArgsConstructor
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    /** {@code Page} を写像しつつ変換する。 */
    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
