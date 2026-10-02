package com.mannschaft.app.config;

/**
 * チームスコープの内部 BIGINT ID を表す型付きパス変数（課題 #12・案A）。
 *
 * <p>設計意図は {@link OrgScopeId} を参照。変換先の型を分けることで、ネスト
 * {@code /organizations/{orgId}/teams/{teamId}} で org/team の slug が同一でも
 * {@code String→TeamScopeId} 変換器が型で一意に選ばれ、team は必ず team として解決される。</p>
 *
 * @param value チームの内部 BIGINT ID
 */
public record TeamScopeId(long value) {
}
