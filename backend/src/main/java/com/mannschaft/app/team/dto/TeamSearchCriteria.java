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
 * <h2>F22.1 市 Phase 2 足場C: 地域コードによる検索（dual-support）</h2>
 * <p>第二陣で {@code prefectureCode}/{@code cityCode}（構造化フィルタ用キー）を追加した。
 * {@link com.mannschaft.app.team.service.TeamSearchSpecifications} は
 * <strong>code が指定されていれば code 一致、未指定なら従来の名称（{@code prefecture}/{@code city}）一致</strong>
 * で絞り込む（Expand 期の後方互換＝新旧両対応）。
 * 旧クライアント（名称送信）と新クライアント（コード送信）を同時に成立させるための過渡的仕様。</p>
 *
 * @param keyword        名称・フリガナ部分一致キーワード（最大 100 文字）
 * @param prefecture     都道府県名称の完全一致（最大 20 文字。{@code prefectureCode} 未指定時のフォールバック）
 * @param city           市町村名称の完全一致（最大 50 文字、{@code prefecture} 未指定時はサービス側で無視）
 * @param template       業種テンプレート完全一致（最大 30 文字）
 * @param prefectureCode 都道府県コード（JIS X 0401、2 桁。指定時は名称より優先）
 * @param cityCode       市区町村コード（JIS X 0402、5 桁。指定時は名称より優先）
 */
public record TeamSearchCriteria(
        String keyword,
        String prefecture,
        String city,
        String template,
        String prefectureCode,
        String cityCode
) {
    private static final int KEYWORD_MAX = 100;
    private static final int PREFECTURE_MAX = 20;
    private static final int CITY_MAX = 50;
    private static final int TEMPLATE_MAX = 30;
    private static final int PREFECTURE_CODE_MAX = 2;
    private static final int CITY_CODE_MAX = 5;

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
        if (prefectureCode != null && prefectureCode.length() > PREFECTURE_CODE_MAX) {
            throw new IllegalArgumentException("prefectureCode too long");
        }
        if (cityCode != null && cityCode.length() > CITY_CODE_MAX) {
            throw new IllegalArgumentException("cityCode too long");
        }
    }

    /**
     * 後方互換用コンストラクタ（名称ベースのみ）。
     *
     * <p>既存呼び出し（コードを渡さない 4 引数）を維持するためのオーバーロード。
     * code は両方 {@code null}（名称フォールバック）となる。</p>
     */
    public TeamSearchCriteria(String keyword, String prefecture, String city, String template) {
        this(keyword, prefecture, city, template, null, null);
    }
}
