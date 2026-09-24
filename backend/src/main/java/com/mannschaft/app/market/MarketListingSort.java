package com.mannschaft.app.market;

import org.springframework.data.domain.Sort;

/**
 * 公開市の札一覧で利用できる並び順。
 *
 * <p>リクエスト値をエンティティのプロパティ名へ直接渡さず、公開列挙値から
 * 許可済みの {@link Sort} だけを組み立てる。全順序に ID の昇順を加え、同値時も
 * ページ間で並びが揺れないようにする。</p>
 */
public enum MarketListingSort {

    /** 従来互換: 開催日時が近い順。 */
    START_AT_ASC(Sort.by(
            Sort.Order.asc("startAt"),
            Sort.Order.asc("id"))),

    /** 応募締切が近い順。 */
    DEADLINE_ASC(Sort.by(
            Sort.Order.asc("applicationDeadline"),
            Sort.Order.asc("id"))),

    /** 応募締切が遠い順。 */
    DEADLINE_DESC(Sort.by(
            Sort.Order.desc("applicationDeadline"),
            Sort.Order.asc("id")));

    private final Sort sort;

    MarketListingSort(Sort sort) {
        this.sort = sort;
    }

    /**
     * Repository の {@code Pageable} に設定する安全な並び順を返す。
     *
     * @return 許可済みプロパティだけで構成した並び順
     */
    public Sort toSort() {
        return sort;
    }
}
