package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;

/**
 * F15.4 組織内チーム（店舗）検索用の {@link Specification} 集。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §4.2} に対応する。</p>
 *
 * <h3>方針</h3>
 * <ul>
 *   <li>各 Specification は null/空文字を渡した場合 {@code cb.conjunction()}（恒真）を返し、無害にスキップできる</li>
 *   <li>LIKE 句のメタ文字（{@code %} / {@code _} / {@code \}）は明示エスケープし {@code cb.like(path, pattern, '\\')} で escape 文字を渡す</li>
 *   <li>{@link #belongsToOrganization(Long)} は {@code team_org_memberships} を {@link Subquery} で参照する。
 *       {@link TeamEntity} 側に JPA 関連が無いためであり、副問合せ EXISTS 形式に展開される。</li>
 * </ul>
 *
 * <p>VillageSearchSpecifications は LIKE エスケープ未実装のため、本クラスでは踏襲せず独自実装している。</p>
 */
public final class TeamSearchSpecifications {

    private TeamSearchSpecifications() {
    }

    /**
     * 論理削除されていないチームに限定する。
     *
     * <p>{@link TeamEntity} には {@code @SQLRestriction("deleted_at IS NULL")} が付いているため
     * 通常クエリでは自動適用されるが、明示的なドキュメント化と将来のレストア用途への保険として残す。</p>
     */
    public static Specification<TeamEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /** アーカイブされていないチームに限定する。 */
    public static Specification<TeamEntity> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }

    /**
     * 指定組織配下に ACTIVE で所属するチームに限定する。
     *
     * <p>{@code team_org_memberships} を Subquery で参照し、{@code status='ACTIVE'} を要求する。
     * 同一チームが複数組織に所属できる構造のため {@link jakarta.persistence.criteria.AbstractQuery#distinct(boolean)} を有効化する。</p>
     */
    public static Specification<TeamEntity> belongsToOrganization(Long orgId) {
        if (orgId == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }
            Subquery<Long> sub = query == null ? null : query.subquery(Long.class);
            if (sub == null) {
                // count クエリ等で query が null の場合は EXISTS を組めないので保守的に false にする
                return cb.disjunction();
            }
            var membershipRoot = sub.from(TeamOrgMembershipEntity.class);
            sub.select(membershipRoot.get("teamId"))
                    .where(cb.and(
                            cb.equal(membershipRoot.get("teamId"), root.get("id")),
                            cb.equal(membershipRoot.get("organizationId"), orgId),
                            cb.equal(membershipRoot.get("status"), TeamOrgMembershipEntity.Status.ACTIVE)
                    ));
            return cb.exists(sub);
        };
    }

    /**
     * 可視性が許可リストに含まれるチームに限定する。
     *
     * <p>{@code allowed} が null または空集合の場合は全件パススルー（恒真）とする。
     * 呼び出し側でフィルタを明示的にスキップしたいケース向け。</p>
     */
    public static Specification<TeamEntity> visibilityIn(Set<TeamEntity.Visibility> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> root.get("visibility").in(allowed);
    }

    /**
     * 名称（{@code name}）またはフリガナ（{@code nameKana}）の部分一致。
     *
     * <p>LIKE メタ文字 {@code %} / {@code _} / {@code \} はバックスラッシュでエスケープしたうえで
     * {@code cb.like(..., '\\')} に escape 文字を明示渡しする。MySQL の {@code utf8mb4_0900_ai_ci}
     * 照合により大文字小文字・全半角差は照合層で吸収される。</p>
     */
    public static Specification<TeamEntity> nameOrKanaContains(String q) {
        if (q == null || q.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        String escaped = escapeLike(q.trim());
        String pattern = "%" + escaped + "%";
        return (root, query, cb) -> cb.or(
                cb.like(root.get("name"), pattern, '\\'),
                cb.like(root.get("nameKana"), pattern, '\\')
        );
    }

    /** 都道府県完全一致。 */
    public static Specification<TeamEntity> prefectureEquals(String pref) {
        if (pref == null || pref.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("prefecture"), pref);
    }

    /** 市町村完全一致。 */
    public static Specification<TeamEntity> cityEquals(String city) {
        if (city == null || city.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("city"), city);
    }

    /** 都道府県コード完全一致（F22.1 市 Phase 2 足場C）。 */
    public static Specification<TeamEntity> prefectureCodeEquals(String prefectureCode) {
        if (prefectureCode == null || prefectureCode.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("prefectureCode"), prefectureCode);
    }

    /** 市区町村コード完全一致（F22.1 市 Phase 2 足場C）。 */
    public static Specification<TeamEntity> cityCodeEquals(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("cityCode"), cityCode);
    }

    /**
     * 都道府県の地域フィルタ（dual-support）。
     *
     * <p>F22.1 市 Phase 2 足場C: code が指定されていれば {@code prefecture_code} 一致、
     * 未指定なら従来の {@code prefecture}（名称）一致に委譲する。Expand 期の後方互換のため、
     * 旧クライアント（名称送信）と新クライアント（コード送信）の双方を成立させる。</p>
     *
     * @param prefectureCode 都道府県コード（優先）
     * @param prefecture     都道府県名称（code 未指定時のフォールバック）
     */
    public static Specification<TeamEntity> prefectureFilter(String prefectureCode, String prefecture) {
        if (prefectureCode != null && !prefectureCode.isBlank()) {
            return prefectureCodeEquals(prefectureCode);
        }
        return prefectureEquals(prefecture);
    }

    /**
     * 市区町村の地域フィルタ（dual-support）。
     *
     * <p>F22.1 市 Phase 2 足場C: code が指定されていれば {@code city_code} 一致、
     * 未指定なら従来の {@code city}（名称）一致に委譲する。</p>
     *
     * @param cityCode 市区町村コード（優先）
     * @param city     市区町村名称（code 未指定時のフォールバック）
     */
    public static Specification<TeamEntity> cityFilter(String cityCode, String city) {
        if (cityCode != null && !cityCode.isBlank()) {
            return cityCodeEquals(cityCode);
        }
        return cityEquals(city);
    }

    /** 業種テンプレート完全一致。 */
    public static Specification<TeamEntity> templateEquals(String template) {
        if (template == null || template.isBlank()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.equal(root.get("template"), template);
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * LIKE 句で安全に利用できるように {@code %} / {@code _} / {@code \} をエスケープする。
     *
     * <p>順序重要: バックスラッシュを最初にエスケープすること（後続置換で
     * 二重エスケープが発生するのを避ける）。</p>
     */
    private static String escapeLike(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
