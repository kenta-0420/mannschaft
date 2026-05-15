package com.mannschaft.app.team.dto;

/**
 * F15.4 組織内チーム（店舗）検索のクエリ条件。
 *
 * <p>すべて任意項目。null または空文字を渡した場合は当該条件をスキップする。
 * 文字数上限は API 入力検証用の最終ガードであり、コントローラ側の
 * {@code @Valid} と二重に確保する目的でコンストラクタでも検査する。</p>
 *
 * <p>各上限値の根拠は設計書 §3.2「クエリパラメータ」を参照。</p>
 *
 * @param keyword    名称・フリガナ部分一致キーワード（最大 100 文字）
 * @param prefecture 都道府県完全一致（最大 20 文字）
 * @param city       市町村完全一致（最大 50 文字、{@code prefecture} 未指定時はサービス側で無視）
 * @param template   業種テンプレート完全一致（最大 30 文字）
 */
public record TeamSearchCriteria(
        String keyword,
        String prefecture,
        String city,
        String template
) {
    private static final int KEYWORD_MAX = 100;
    private static final int PREFECTURE_MAX = 20;
    private static final int CITY_MAX = 50;
    private static final int TEMPLATE_MAX = 30;

    public TeamSearchCriteria {
        if (keyword != null && keyword.length() > KEYWORD_MAX) {
            throw new IllegalArgumentException("keyword too long");
        }
        if (prefecture != null && prefecture.length() > PREFECTURE_MAX) {
            throw new IllegalArgumentException("prefecture too long");
        }
        if (city != null && city.length() > CITY_MAX) {
            throw new IllegalArgumentException("city too long");
        }
        if (template != null && template.length() > TEMPLATE_MAX) {
            throw new IllegalArgumentException("template too long");
        }
    }
}
