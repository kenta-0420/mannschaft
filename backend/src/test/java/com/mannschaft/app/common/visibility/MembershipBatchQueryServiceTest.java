package com.mannschaft.app.common.visibility;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.MembershipScopeRoleProjection;
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
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Mock
    private MembershipRepository membershipRepository;

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

    @Nested
    @DisplayName("F00.5 §8.3 根治: memberships 由来 MEMBER/SUPPORTER の roleByScope マージ")
    class MembershipMerge {

        @Test
        @DisplayName("user_roles 行なし・memberships のみ MEMBER → roleByScope に MEMBER が入り MEMBERS_AND_ABOVE 可視")
        void memberships専属MEMBER_roleByScope登録() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            // user_roles の direct ロールは空（V60.010 で MEMBER 行削除済み）
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of());
            // memberships に MEMBER の active 行
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of(membershipProjection(ScopeType.TEAM, 1L, RoleKind.MEMBER)));

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            assertThat(result.roleByScope()).containsEntry(TEAM_1, "MEMBER");
            // SCOPE_AFFILIATED
            assertThat(result.isMemberOf(TEAM_1)).isTrue();
            // MEMBERS_AND_ABOVE
            assertThat(result.hasRoleOrAbove(TEAM_1, "MEMBER")).isTrue();
            // SUPPORTERS_AND_ABOVE（MEMBER は SUPPORTER 以上）
            assertThat(result.hasRoleOrAbove(TEAM_1, "SUPPORTER")).isTrue();
        }

        @Test
        @DisplayName("memberships のみ SUPPORTER → SUPPORTERS_AND_ABOVE 可視・MEMBERS_AND_ABOVE 不可視・SCOPE_AFFILIATED 可視")
        void memberships専属SUPPORTER() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of());
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of(membershipProjection(ScopeType.TEAM, 1L, RoleKind.SUPPORTER)));

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            assertThat(result.roleByScope()).containsEntry(TEAM_1, "SUPPORTER");
            assertThat(result.isMemberOf(TEAM_1)).isTrue();               // SCOPE_AFFILIATED
            assertThat(result.hasRoleOrAbove(TEAM_1, "SUPPORTER")).isTrue();  // SUPPORTERS_AND_ABOVE
            assertThat(result.hasRoleOrAbove(TEAM_1, "MEMBER")).isFalse();    // MEMBERS_AND_ABOVE 不可視
            assertThat(result.hasRoleOrAbove(TEAM_1, "ADMIN")).isFalse();     // ADMINS_AND_ABOVE 不可視
        }

        @Test
        @DisplayName("user_roles ADMIN + memberships MEMBER 併存 → priority 最強の ADMIN が残る")
        void adminUserRoleとmembershipMember_ADMIN優先() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of(projection(1L, 1L, null, 50L)));
            when(roleRepository.findAllById(Set.of(50L)))
                    .thenReturn(List.of(role(50L, "ADMIN")));
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of(1L)), eq(Set.of())))
                    .thenReturn(List.of(membershipProjection(ScopeType.TEAM, 1L, RoleKind.MEMBER)));

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            // ADMIN(2) < MEMBER(4) → ADMIN を採用
            assertThat(result.roleByScope()).containsEntry(TEAM_1, "ADMIN");
            assertThat(result.hasRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
        }

        @Test
        @DisplayName("親 ORG が memberships 専属所属でも ORGANIZATION_WIDE 可視（isMemberOfParentOrg=true）")
        void 親ORGがmemberships専属_ORGANIZATION_WIDE可視() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            // user_roles の親 ORG メンバーシップは無し
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            // memberships に親 ORG への MEMBER 行
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of(membershipProjection(ScopeType.ORGANIZATION, 10L, RoleKind.MEMBER)));
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.orgMemberOf()).contains(ORG_10);
            assertThat(result.isMemberOfParentOrg(TEAM_1)).isTrue();
        }
    }

    @Nested
    @DisplayName("フェーズ M2: descendantScopes 経由の下向き再帰メンバーシップ解決")
    class DescendantMembership {

        @Test
        @DisplayName("descendantScopes 空 → 下向き再帰バルク SQL は発行されない（従来挙動・SQL 0）")
        void descendant空_バルクSQL未発行() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(userRoleRepository.findByUserIdAndScopes(any(), any(), any()))
                    .thenReturn(List.of());

            // 3 引数版（従来の Resolver 経路）。新段 SQL は決して呼ばれない。
            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(TEAM_1), Set.of());

            assertThat(result.descendantMemberOfOrgIds()).isEmpty();
            verify(userRoleRepository, never())
                    .findOrgRootsWhereUserIsDescendantMember(anySet(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("配下再帰メンバーの ORG 根は descendantMemberOfOrgIds に入り isDescendantMemberOf=true")
        void 配下再帰メンバー_isDescendantMemberOfTrue() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            // 新段の根 ORG_10 は parentOrgs に合流 → membership/非アクティブ判定が走る
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of());
            // 下向き再帰バルク: viewer は ORG_10 の配下再帰メンバー
            when(userRoleRepository.findOrgRootsWhereUserIsDescendantMember(eq(Set.of(10L)), eq(USER_ID), anyInt()))
                    .thenReturn(List.of(10L));

            UserScopeRoleSnapshot result = service.snapshotForUser(
                    USER_ID, Set.of(), Set.of(), Set.of(ORG_10));

            assertThat(result.descendantMemberOfOrgIds()).containsExactly(10L);
            assertThat(result.isDescendantMemberOf(ORG_10)).isTrue();
            // 直接所属軸（orgMemberOf）は汚さない（G3: 昇格しない）
            assertThat(result.orgMemberOf()).doesNotContain(ORG_10);
            // 上向き 1 段（ORGANIZATION_WIDE 経路）とは独立
            assertThat(result.isMemberOfParentOrg(ORG_10)).isFalse();
        }

        @Test
        @DisplayName("配下再帰メンバーでない viewer → isDescendantMemberOf=false")
        void 非配下メンバー_isDescendantMemberOfFalse() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of());
            when(userRoleRepository.findOrgRootsWhereUserIsDescendantMember(eq(Set.of(10L)), eq(USER_ID), anyInt()))
                    .thenReturn(List.of()); // どの根にも属さない

            UserScopeRoleSnapshot result = service.snapshotForUser(
                    USER_ID, Set.of(), Set.of(), Set.of(ORG_10));

            assertThat(result.descendantMemberOfOrgIds()).isEmpty();
            assertThat(result.isDescendantMemberOf(ORG_10)).isFalse();
        }

        @Test
        @DisplayName("§11.6 鏡像: 根 ORG 自身が非アクティブなら isOrgInactive=true（配下メンバーでも閲覧不可へ）")
        void 根ORG非アクティブ_isOrgInactiveTrue() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            // 当該 ORG 自身が削除済
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L)))
                    .thenReturn(List.of(10L));
            when(userRoleRepository.findOrgRootsWhereUserIsDescendantMember(eq(Set.of(10L)), eq(USER_ID), anyInt()))
                    .thenReturn(List.of(10L));

            UserScopeRoleSnapshot result = service.snapshotForUser(
                    USER_ID, Set.of(), Set.of(), Set.of(ORG_10));

            assertThat(result.isOrgInactive(ORG_10)).isTrue();
            // 配下メンバーではあるが ORG 非アクティブ。Resolver 側 case で fail-closed される。
            assertThat(result.isDescendantMemberOf(ORG_10)).isTrue();
        }

        @Test
        @DisplayName("匿名 viewer → 下向き再帰 SQL は発行されない")
        void 匿名_descendant_SQL未発行() {
            UserScopeRoleSnapshot result = service.snapshotForUser(
                    null, Set.of(), Set.of(), Set.of(ORG_10));

            assertThat(result.descendantMemberOfOrgIds()).isEmpty();
            verifyNoInteractions(userRoleRepository);
        }
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private static MembershipScopeRoleProjection membershipProjection(
            ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        return new MembershipScopeRoleProjection() {
            @Override
            public ScopeType getScopeType() {
                return scopeType;
            }

            @Override
            public Long getScopeId() {
                return scopeId;
            }

            @Override
            public RoleKind getRoleKind() {
                return roleKind;
            }
        };
    }

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

    @Nested
    @DisplayName("CMP-017b: 親 ORG のロール名マップ（orgRoleByScope）")
    class ParentOrgRoleNames {

        @Test
        @DisplayName("親 ORG の user_roles ロール名が orgRoleByScope に入り閾値評価できる")
        void 親ORGのuser_rolesロール名が閾値評価に使える() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            // 親 ORG に DEPUTY_ADMIN として所属
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of(projection(2L, null, 10L, 52L)));
            when(roleRepository.findAllById(Set.of(52L)))
                    .thenReturn(List.of(role(52L, "DEPUTY_ADMIN")));
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L))).thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.orgRoleByScope()).containsEntry(ORG_10, "DEPUTY_ADMIN");
            // DEPUTY_ADMIN(3) は DEPUTY_ADMIN 閾値を満たすが ADMIN(2) 閾値は満たさない
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "DEPUTY_ADMIN")).isTrue();
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "MEMBER")).isTrue();
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "ADMIN")).isFalse();
            // direct スコープの roleByScope は汚染されないこと
            assertThat(result.roleByScope()).isEmpty();
        }

        @Test
        @DisplayName("親 ORG の memberships SUPPORTER は MEMBER+ 閾値を満たさない（AC-06 の土台）")
        void 親ORGのSUPPORTERはMEMBER閾値を満たさない() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(
                    eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of(membershipProjection(ScopeType.ORGANIZATION, 10L, RoleKind.SUPPORTER)));
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L))).thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.orgRoleByScope()).containsEntry(ORG_10, "SUPPORTER");
            // 所属はしている（ORGANIZATION_WIDE は従来どおり可視）
            assertThat(result.isMemberOfParentOrg(TEAM_1)).isTrue();
            // だが MEMBER+ の閾値は満たさない ＝ min_view_role=MEMBER+ を弾ける
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "MEMBER")).isFalse();
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "SUPPORTER")).isTrue();
        }

        @Test
        @DisplayName("親 ORG に user_roles ADMIN と memberships MEMBER が併存 → 最強の ADMIN を採用")
        void 親ORGの併存ロールは最強を採用() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of(projection(2L, null, 10L, 51L)));
            when(roleRepository.findAllById(Set.of(51L)))
                    .thenReturn(List.of(role(51L, "ADMIN")));
            when(membershipRepository.findActiveRoleKindsByUserAndScopes(
                    eq(USER_ID), eq(Set.of()), eq(Set.of(10L))))
                    .thenReturn(List.of(membershipProjection(ScopeType.ORGANIZATION, 10L, RoleKind.MEMBER)));
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L))).thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.orgRoleByScope()).containsEntry(ORG_10, "ADMIN");
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
        }

        @Test
        @DisplayName("親 ORG 非所属なら orgRoleByScope は空で閾値評価も false")
        void 親ORG非所属なら閾値false() {
            when(userRoleRepository.existsSystemAdminByUserId(USER_ID)).thenReturn(0L);
            when(scopeAncestorResolver.resolveParentOrgIds(Set.of(TEAM_1)))
                    .thenReturn(Map.of(TEAM_1, 10L));
            when(userRoleRepository.findByUserIdAndOrganizationIdIn(eq(USER_ID), eq(Set.of(10L))))
                    .thenReturn(List.of());
            when(organizationRepository.findInactiveIdsByIdIn(Set.of(10L))).thenReturn(List.of());

            UserScopeRoleSnapshot result = service.snapshotForUser(USER_ID, Set.of(), Set.of(TEAM_1));

            assertThat(result.orgRoleByScope()).isEmpty();
            assertThat(result.hasParentOrgRoleOrAbove(TEAM_1, "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("SystemAdmin は親 ORG 閾値も常に true")
        void systemAdminは親ORG閾値も常にtrue() {
            UserScopeRoleSnapshot admin = UserScopeRoleSnapshot.forSystemAdmin();

            assertThat(admin.hasParentOrgRoleOrAbove(TEAM_1, "ADMIN")).isTrue();
        }
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
