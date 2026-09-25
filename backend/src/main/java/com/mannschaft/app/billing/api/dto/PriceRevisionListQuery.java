package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@code GET /price-revisions} 検索条件（試練D群・AC-60〜AC-65）。
 *
 * <p>ページサイズは既定20・上限100（AC-61）。ソートは常に
 * {@code effectiveFrom DESC, id DESC} の複合キーへ固定する（AC-62・AC-62a: 同値時のページ境界安定化）。
 * 呼び出し側が渡した {@link Pageable} のソート指定は無視し、このソートで上書きする。</p>
 */
public record PriceRevisionListQuery(
        BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind, String status,
        Pageable pageRequest) {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort SORT = Sort.by(Sort.Order.desc("effectiveFrom"), Sort.Order.desc("id"));

    public PriceRevisionListQuery {
        if (pageRequest == null) {
            throw new IllegalArgumentException("pageRequest must not be null");
        }
        if (pageRequest.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page size must not exceed " + MAX_PAGE_SIZE + " but was " + pageRequest.getPageSize());
        }
        pageRequest = PageRequest.of(pageRequest.getPageNumber(), pageRequest.getPageSize(), SORT);
    }
}
