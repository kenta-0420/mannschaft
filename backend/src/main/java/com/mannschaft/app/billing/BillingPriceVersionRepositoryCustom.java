package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.dto.PriceRevisionListQuery;

import java.util.List;

/**
 * {@code GET /price-revisions} 一覧検索（Spring Data の動的メソッド名派生では表現できない
 * 任意条件＋固定複合ソートのため、フラグメント実装で提供する）。
 */
public interface BillingPriceVersionRepositoryCustom {

    List<BillingPriceVersionEntity> searchSummaries(PriceRevisionListQuery query);
}
