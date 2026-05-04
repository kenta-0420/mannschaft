package com.mannschaft.app.common.visibility;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleProjection;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MembershipBatchQueryService} の単体テスト（Mock ベース）。
 *
 * <p>F00 Phase A-3b — メンバーシップバッチ取得サービスの SQL 発行回数最適化と
 * 各シナリオでの分岐挙動を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipBatchQueryService — メンバーシップバッチ取得")
class MembershipBatchQueryServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private ScopeAncestorResolver scopeAncestorResolver;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private MembershipBatchQueryService service;

    private static final long USER_ID = 100L;
    private static final ScopeKey TEAM_1 = new ScopeKey("TEAM", 1L);
    private static final ScopeKey TEAM_2 = new ScopeKey("TEAM", 2L);
    private static final ScopeKey ORG_10 = new ScopeKey("ORGANIZATION", 10L);

    @Nested
    @DisplayName("早期 return: 匿名 / SystemAdmin")
    class EarlyReturn {

        @Test
        @DisplayName("userId=null → empty() を返し SQL を一切発行しない")
        void userIdNull_emptyかつSQL未発行() {
            UserScopeRoleSnapshot result = service.snapshotForUser(null, Set.of(TEAM_1), Set.of(ORG_10));

            assertThat(result.isSystemAdmin()).isFalse();
            assertThat(result.roleByScope()).isEmpty();
            verifyNoInteractions(userRoleRepository, roleRepository, scopeAncestorResolver, organizationRepository);
        }

        @Test
        @DisplayName("SystemAdmin → forSystemAdmin() を返し後続 SQL は呼ばれない")
        void SystemAdmin_早期return() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(1L);

            UserScopeRoleSnapshot result = service.snapshotForUser(
                    USER_ID, Set.of(TEAM_1), Set.of(ORG_10));

            assertThat(result.isSystemAdmin()).isTrue();
            verify(userRoleRepository).existsSystemAdminByUserId(USER_ID);
            // 後続呼び出しは一切無し
            verify(userRoleRepository, never()).findByUserIdAndScopes(anyLong(), anySet(), anySet());
            verify(userRoleRepository, never()).findByUserIdAndOrganizationIdIn(anyLong(), anySet());
            verifyNoInteractions(roleRepository, scopeAncestorResolver, organizationRepository);
        }
    }

    @Nested
    @DisplayName("一般ユーザー: directScopes 経由のメンバーシップ解決")
    class GeneralUserDirect {

        @Test
        @DisplayName("TEAM/ORG 混在 directScopes が teamIds と organizationIds に分割されて呼ばれる")
        void TEAM_ORG混在の分割呼出し() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            // TEAM_1 + TEAM_2 + ORG_10 を渡し、ORG は分割されることを確認
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of(1L, 2L)), eq(Set.of(10L))))
                    .thenReturn(List.of(projection(1L, 1L, null, 50L)));
            when(roleRepository.findAllById(Set.of(50L)))
                    .thenReturn(List.of(role(50L, "MEMBER")));

            UserScopeRoleSnapshot result = service.snapshotForUser(
                    USER_ID, Set.of(TEAM_1, TEAM_2, ORG_10), Set.of());

            assertThat(result.isSystemAdmin()).isFalse();
            assertThat(result.roleByScope()).containsEntry(TEAM_1, "MEMBER");
            verify(userRoleRepository).findByUserIdAndScopes(USER_ID, Set.of(1L, 2L), Set.of(10L));
        }

        @Test
        @DisplayName("directScopes 空なら findByUserIdAndScopes を呼ばない")
        void directScopes空_呼ばない() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of());

            assertThat(result.roleByScope()).isEmpty();
            verify(userRoleRepository, never()).findByUserIdAndScopes(anyLong(), anySet(), anySet());
        }

        @Test
        @DisplayName("ORGANIZATION スコープへの直接所属が roleByScope に登録される")
        void ORGスコープ直接所属() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of(projection(1L, null, 10L, 51L)));
            when(roleRepository.findAllById(Set.of(51L)))
                    .thenReturn(List.of(role(51L, "ADMIN")));

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(ORG_10), Set.of());

            assertThat(result.roleByScope()).containsEntry(ORG_10, "ADMIN");
            assertThat(result.hasRoleOrAbove(ORG_10, "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("ロール名が解決できない不整合行はスキップされる（fail-closed）")
        void 不整合行はスキップ() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of(projection(1L, 1L, null, 999L)));
            when(roleRepository.findAllById(Set.of(999L))).thenReturn(List.of()); // role 不存在

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            assertThat(result.roleByScope()).isEmpty();
        }
    }

    @Nested
    @DisplayName("orgWideScopes 経由の親 ORG 解決と §11.6 連鎖判定")
    class OrgWideAndSuspended {

        @Test
        @DisplayName("orgWideScopes 空 → ScopeAncestorResolver 等は一切呼ばれない")
        void orgWideScopes空_親解決スキップ() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(any(), any(), any()))
                    .thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            assertThat(result.parentOrgByScope()).isEmpty();
            assertThat(result.orgMemberOf()).isEmpty();
            assertThat(result.suspendedOrgIds()).isEmpty();
            verifyNoInteractions(scopeAncestorResolver);
            verify(organizationRepository, never()).findInactiveIdsByIdIn(any());
        }

        @Test
        @DisplayName("親 ORG 解決 + 親 ORG メンバーシップ + 非アクティブ抽出が連動する")
        void 親ORG解決とメンバーシップと非アクティブ() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);

            // TEAM_1 → ORG_10
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            // 親 ORG メンバーシップ取得
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of(projection(2L, null, 10L, 50L)));
            // 親 ORG 非アクティブ判定
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of()); // 全てアクティブ

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.parentOrgByScope()).containsEntry(TEAM_1, 10L);
            assertThat(result.orgMemberOf()).containsExactly(ORG_10);
            assertThat(result.suspendedOrgIds()).isEmpty();
            assertThat(result.isMemberOfParentOrg(TEAM_1)).isTrue();
        }

        @Test
        @DisplayName("親 ORG が非アクティブなら suspendedOrgIds に含まれ isParentOrgInactive=true")
        void 親ORG非アクティブ_isParentOrgInactiveTrue() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);

            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of(10L)); // 削除済

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.suspendedOrgIds()).containsExactly(10L);
            assertThat(result.isParentOrgInactive(TEAM_1)).isTrue();
            assertThat(result.isMemberOfParentOrg(TEAM_1)).isFalse();
        }

        @Test
        @DisplayName("orgWideScopes に対し親 ORG が解決できなければ後続クエリも省略")
        void 親ORG未解決_後続スキップ() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            // TEAM 所属未解決 → 空マップ
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.parentOrgByScope()).isEmpty();
            verify(userRoleRepository, never()).findByUserIdAndOrganizationIdIn(anyLong(), anySet());
            verify(organizationRepository, never()).findInactiveIdsByIdIn(any());
        }
    }

    @Nested
    @DisplayName("null/empty の堅牢性")
    class NullSafety {

        @Test
        @DisplayName("directScopes/orgWideScopes が null なら空集合として扱う")
        void null引数で例外にならない() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, null, null);

            assertThat(result.isSystemAdmin()).isFalse();
            assertThat(result.roleByScope()).isEmpty();
            verify(userRoleRepository, never()).findByUserIdAndScopes(anyLong(), anySet(), anySet());
            verifyNoInteractions(scopeAncestorResolver);
        }
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private static UserRoleProjection projection(Long id, Long teamId, Long orgId, Long roleId) {
        return new UserRoleProjection() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Long getUserId() {
                return USER_ID;
            }

            @Override
            public Long getTeamId() {
                return teamId;
            }

            @Override
            public Long getOrganizationId() {
                return orgId;
            }

            @Override
            public Long getRoleId() {
                return roleId;
            }
        };
    }

    private static RoleEntity role(Long id, String name) {
        // RoleEntity は Builder.toBuilder() を持つので Builder 経由で生成。
        // 必須 NotNull 列も埋める。
        return RoleEntity.builder()
                .id(id)
                .name(name)
                .displayName(name)
                .priority(RolePriority.priority(name))
                .isSystem(false)
                .build();
    }
}
