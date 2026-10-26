package com.mannschaft.app.role.dto;

/**
 * F00.5 フェーズ 3 — {@code user_roles} のみに存在する（{@code memberships} 側にアクティブ行が無い）
 * 差分サンプル1行を表す DTO。
 *
 * <p>{@link com.mannschaft.app.role.service.RoleService#sampleUserRolesOnlyDiff} が
 * role ドメイン内部の {@code UserRoleRepository} 射影から詰め替えて返す、ドメイン境界を越えて
 * 安全に渡せる値オブジェクト。membership ドメイン（{@code MembershipConsistencyChecker}）は
 * この DTO のみを参照し、role ドメインの Repository/Entity には直接依存しない。</p>
 */
public record UserRoleOnlyDiffRow(Long userId, String scopeType, Long scopeId) {
}
