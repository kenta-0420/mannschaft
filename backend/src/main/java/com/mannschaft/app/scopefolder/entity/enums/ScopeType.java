package com.mannschaft.app.scopefolder.entity.enums;

/**
 * スコープフォルダのスコープ種別。チームまたは組織の2種類を表す。
 *
 * <p>本 enum は状態・振る舞いを持たない純粋な値オブジェクトであり、
 * scopefolder ドメイン外（notification / role / dashboard 等）からも参照される。
 * ArchUnit の D-1 番人（{@code CrossDomainEntityImportArchTest}）は
 * {@code ..entity.enums..} 配下を「共有される値オブジェクト」として
 * ドメイン越境importの対象外に除外している。この enum を {@code entity} 直下から
 * {@code entity.enums} に移設したのは、その除外規定に実体を合わせるためである
 * （2026-08: D-1 凍結ストアの既存違反 chip-away の副産物としてではなく、
 * 規約遵守そのものを目的とした移設）。
 */
public enum ScopeType {
    TEAM,
    ORGANIZATION
}
