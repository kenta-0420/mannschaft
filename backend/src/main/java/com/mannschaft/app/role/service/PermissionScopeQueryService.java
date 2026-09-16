package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.UserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 「DEPUTY_ADMIN かつ指定 Permission 保有」であるスコープを<b>バルクで</b>解決する role ドメインの照会サービス
 * （CMP-041 五番隊）。
 *
 * <p><b>なぜ Service を挟むのか</b> — 呼び出し側の可視性 Resolver は survey ドメインにあり、
 * role ドメインの {@link UserRoleRepository} を直接参照するとモジュラーモノリスの原則
 * （ドメイン間は ID 参照＋Service 経由。番人 {@code CrossDomainRepositoryDependencyArchTest} の D-5）に
 * 反する。既に survey Resolver が organization ドメインの
 * {@code OrganizationMembershipService} を経由しているのと同じ作法である。</p>
 *
 * <p><b>なぜバルクなのか</b> — 可視性の判定は「バッチ 1 回につき必要な集合を先読みし、
 * 行ごとの判定は純メモリで行う」契約（{@code AbstractContentVisibilityResolver
 * #prepareAdditionalAxisContext}）の下にある。スコープごとに単票版を呼ぶと
 * スコープ数比例の SQL となり、Issue #2782 で撤去した実装を再生産する。</p>
 *
 * <p>述語の意味論は単票版
 * {@link UserRoleRepository#existsDeputyAdminWithPermissionInTeam} /
 * {@link UserRoleRepository#existsDeputyAdminWithPermissionInOrganization} と同一である
 * （{@code role_permissions.is_default = 1} 経由、または当該スコープの権限グループ経由）。</p>
 */
@Service
public class PermissionScopeQueryService {

    private final UserRoleRepository userRoleRepository;

    public PermissionScopeQueryService(UserRoleRepository userRoleRepository) {
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 指定チーム群のうち、当該ユーザーが「DEPUTY_ADMIN かつ指定 Permission 保有」であるチーム ID を返す（SQL 1 本）。
     *
     * <p>入力が空・ユーザー未指定なら SQL を発行せず空集合を返す（対象 0 件で 0 本の契約）。</p>
     */
    public Set<Long> findPermittedTeamIds(
            Long userId, Collection<Long> teamIds, String permissionName) {
        if (userId == null || teamIds == null || teamIds.isEmpty() || permissionName == null) {
            return Set.of();
        }
        return toIdSet(userRoleRepository
                .findDeputyAdminPermittedTeamIds(userId, teamIds, permissionName));
    }

    /**
     * 指定組織群のうち、当該ユーザーが「DEPUTY_ADMIN かつ指定 Permission 保有」である組織 ID を返す（SQL 1 本）。
     *
     * <p>入力が空・ユーザー未指定なら SQL を発行せず空集合を返す。</p>
     */
    public Set<Long> findPermittedOrganizationIds(
            Long userId, Collection<Long> organizationIds, String permissionName) {
        if (userId == null || organizationIds == null || organizationIds.isEmpty()
                || permissionName == null) {
            return Set.of();
        }
        return toIdSet(userRoleRepository
                .findDeputyAdminPermittedOrganizationIds(userId, organizationIds, permissionName));
    }

    /**
     * native クエリの単一列結果を {@code Long} 集合へ正規化する。
     *
     * <p>native の数値列は JDBC ドライバ／列型により {@code Long} 以外（{@code Integer} や
     * {@code BigInteger}）で返ることがあり、{@code List<Long>} と宣言していても
     * 実際に取り出した時点で {@code ClassCastException} になる。{@link Number} 経由で
     * 明示的に {@code longValue()} へ落とし、型に依存しない形にする。</p>
     */
    private static Set<Long> toIdSet(List<Long> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new java.util.HashSet<>();
        for (Object raw : rawIds) {
            if (raw instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        return ids;
    }
}
