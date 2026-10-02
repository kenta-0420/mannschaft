package com.mannschaft.app.config;

/**
 * 組織スコープの内部 BIGINT ID を表す型付きパス変数（課題 #12・案A）。
 *
 * <p><b>なぜ型を分けるのか:</b> Spring MVC の {@code Converter<String,Long>} は、変換対象の
 * 「変数名」も「型」も変換器へ渡さない。そのため team/organization を単一の {@code String→Long}
 * 変換器で扱うと、ネスト {@code /organizations/{orgId}/teams/{teamId}} で org と team の slug が
 * 同一文字列のとき teamId が組織 ID へ誤解決される（URI の直前セグメント推定に依存するため）。
 * 変換先の型を org 用・team 用に分けることで、Spring が {@code String→OrgScopeId} /
 * {@code String→TeamScopeId} の適切な変換器を <b>型で一意に</b>選び、推定を排して根治する。</p>
 *
 * <p>値のみを保持する。Controller 境界で {@link #value()} を取り出し、Service 以下は従来どおり
 * {@code Long} を受ける（改修を Controller に閉じ込める）。OpenAPI 上は {@code Long}（integer int64）へ
 * マップされる（{@link OpenApiConfig} の springdoc 型置換）。</p>
 *
 * @param value 組織の内部 BIGINT ID
 */
public record OrgScopeId(long value) {
}
