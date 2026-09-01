package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.dto.LastAdminScope;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱①「ADMINゼロ根治」— {@link RoleSuccessionService} の受け入れテスト。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11〜§14。
 * DB・Testcontainers は使わず、依存する Repository/Service をモックした単体テスト
 * （SQL の並び順・フィルタ自体の正しさは {@code UserRoleRepository} の native クエリに
 * 委ねる。ここでは Java 側の選定・ロック・監査・通知ロジックを検証する）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleSuccessionService 受け入れテスト（柱①ADMINゼロ根治）")
class RoleSuccessionServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private AdminRoleMutationLockService adminRoleMutationLockService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private RoleSuccessionService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long WITHDRAWING_USER_ID = 1L;
    private static final Long DEPUTY_ID = 2L;
    private static final Long MEMBER_ID = 3L;
    private static final Long ADMIN_ROLE_ID = 9L;

    private LastAdminScope scope(long otherMembers) {
        return LastAdminScope.builder()
                .scopeType(ScopeType.TEAM.name())
                .scopeId(SCOPE_ID)
                .scopeName("テストチーム")
                .otherMembersCount(otherMembers)
                .build();
    }

    @Nested
    @DisplayName("AC1: 他メンバー1人以上のスコープで唯一のADMINが退会要求 → 409＋新エラーコード")
    class Ac1BlockingLastAdmin {

        @Test
        @DisplayName("AC1: 他メンバー1人以上のlastAdminスコープが残っていればGDPR_011で拒否される")
        void checkNoLastAdminScopes_他メンバーあり_GDPR011() {
            given(userRoleRepository.findLastAdminScopes(WITHDRAWING_USER_ID)).willReturn(List.of(scope(1)));

            assertThatThrownBy(() -> service.checkNoLastAdminScopes(WITHDRAWING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(GdprErrorCode.GDPR_011));
        }

        @Test
        @DisplayName("AC1: deletion-previewにscopeType/scopeId/scopeName/otherMembersCountが列挙される")
        void findBlockingLastAdminScopes_必要フィールドが列挙される() {
            given(userRoleRepository.findLastAdminScopes(WITHDRAWING_USER_ID)).willReturn(List.of(scope(1)));

            List<LastAdminScope> scopes = service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID);

            assertThat(scopes).hasSize(1);
            LastAdminScope s = scopes.get(0);
            assertThat(s.getScopeType()).isEqualTo("TEAM");
            assertThat(s.getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(s.getScopeName()).isEqualTo("テストチーム");
            assertThat(s.getOtherMembersCount()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("AC2: 承諾型委譲完了 or アーカイブ後、lastAdminScopesが空になり退会成功")
    class Ac2ResolvedAfterTransferOrArchive {

        @Test
        @DisplayName("AC2: 委譲/アーカイブ処理後は findBlockingLastAdminScopes が空を返す")
        void 委譲またはアーカイブ完了後_空リストになる() {
            given(userRoleRepository.findLastAdminScopes(WITHDRAWING_USER_ID)).willReturn(List.of());

            assertThat(service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("AC2: lastAdminScopesが空ならcheckNoLastAdminScopesは例外を投げない")
        void lastAdminScopesが空なら例外なし() {
            given(userRoleRepository.findLastAdminScopes(WITHDRAWING_USER_ID)).willReturn(List.of());

            assertThatCode(() -> service.checkNoLastAdminScopes(WITHDRAWING_USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC3: 他メンバー0人のスコープは退会ブロックされない")
    class Ac3ZeroOtherMembersNotBlocked {

        @Test
        @DisplayName("AC3: 他メンバー0人のスコープはfindBlockingLastAdminScopesに含まれない")
        void 他メンバー0人は列挙対象外() {
            given(userRoleRepository.findLastAdminScopes(WITHDRAWING_USER_ID)).willReturn(List.of(scope(0)));

            assertThat(service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC4: purge時、候補資格を満たすDEPUTY_ADMIN最古参がADMIN化・forced=true・通知記録")
    class Ac4DeputyAdminPromoted {

        @Test
        @DisplayName("AC4: DEPUTY_ADMIN最古参がforceTransferForPurgeでADMIN昇格しforced=trueが監査される")
        void 最古参DEPUTY_ADMINが昇格しforcedTrueが記録される() {
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(DEPUTY_ID));
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            verify(userRoleRepository).save(argThatRoleIdAndUser(ADMIN_ROLE_ID, DEPUTY_ID));
            verify(auditLogService).record(anyString(), eq(WITHDRAWING_USER_ID), eq(DEPUTY_ID),
                    eq(SCOPE_ID), isNull(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.contains("\"forced\":true"));
            verify(notificationHelper).notify(eq(DEPUTY_ID), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), eq(SCOPE_ID), any(), eq(WITHDRAWING_USER_ID));
        }
    }

    @Nested
    @DisplayName("AC5: DEPUTY不在ならMEMBER最古参。同一created_atはID昇順で決定的")
    class Ac5MemberFallbackDeterministic {

        @Test
        @DisplayName("AC5: DEPUTY_ADMINが候補資格ゼロならMEMBER最古参が選定される")
        void DEPUTY不在時MEMBER最古参が選定される() {
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of());
            given(userRoleRepository.findMemberCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(MEMBER_ID, 99L));

            Optional<Long> candidate = service.selectSuccessionCandidate(SCOPE_ID, "TEAM");

            assertThat(candidate).contains(MEMBER_ID);
        }

        @Test
        @DisplayName("AC5: 同一created_atの候補が並ぶ場合はリポジトリの並び順（id昇順タイブレーク）どおり先頭が選ばれる")
        void 同一createdAtはID昇順でタイブレークされる() {
            // ORDER BY created_at ASC, id ASC は UserRoleRepository の native クエリが担保する
            // （SQLの並び順自体はDBを要するIT側で検証。ここではリポジトリが返した順序の先頭を
            // 選定コードが正しく採用することを検証する）。
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(10L, 20L));

            Optional<Long> candidate = service.selectSuccessionCandidate(SCOPE_ID, "TEAM");

            assertThat(candidate).contains(10L);
        }
    }

    @Nested
    @DisplayName("AC6: 候補資格を満たさない者（退会予定・匿名化済）は昇格候補から除外される")
    class Ac6DisqualifiedCandidatesExcluded {

        @Test
        @DisplayName("AC6: 退会予定・匿名化済みユーザーのみのスコープでは候補ゼロになる")
        void 退会予定または匿名化済みは候補から除外() {
            // 資格フィルタ（deleted_at IS NULL AND status='ACTIVE'）は native クエリの WHERE 句が
            // 担保する。退会予定/匿名化済みユーザーはそもそも一覧に含まれずリポジトリが空を返す。
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of());
            given(userRoleRepository.findMemberCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of());

            Optional<Long> candidate = service.selectSuccessionCandidate(SCOPE_ID, "TEAM");

            assertThat(candidate).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC7: TEAMの昇格がORGANIZATION側の権限を付与しない")
    class Ac7ScopeIsolation {

        @Test
        @DisplayName("AC7: TEAM承継はORGANIZATION側のリポジトリ・サービスに一切触れない（越境しない）")
        void TEAM承継はORGANIZATION権限に波及しない() {
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(DEPUTY_ID));
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            verify(userRoleRepository, never()).findDeputyAdminCandidateIdsByOrganization(any());
            verify(userRoleRepository, never()).findMemberCandidateIdsByOrganization(any());
            verify(userRoleRepository, never()).findByUserIdAndOrganizationIdForUpdate(any(), any());
            verify(organizationService, never()).archiveOrganization(any());
        }
    }

    @Nested
    @DisplayName("AC8: 候補ゼロ→archive。SYSTEM_ADMINのforce-unarchiveはADMIN指名を伴わない限り拒否される")
    class Ac8ArchiveWhenNoCandidate {

        @Test
        @DisplayName("AC8: 候補資格者が1人もいない場合はarchiveScope経由でteamService.archiveTeamが呼ばれる")
        void 候補ゼロならarchiveされる() {
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of());
            given(userRoleRepository.findMemberCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of());

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            verify(teamService).archiveTeam(SCOPE_ID);
            verify(userRoleRepository, never()).save(any());
        }
        // AC8 の force-unarchive 拒否本体は SystemAdminScopeForceUnarchiveControllerTest で検証する。
    }

    @Nested
    @DisplayName("AC12: 同一purgeイベントの再配送で昇格・通知・監査が重複しない")
    class Ac12IdempotentRedelivery {

        @Test
        @DisplayName("AC12: 冪等キー(scope+userId+purgeId)が同じ再配送は重複昇格を起こさない")
        void 同一冪等キーの再配送は重複しない() {
            UUID purgeId = UUID.randomUUID();
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID))
                    .willReturn(List.of(DEPUTY_ID)); // 2回目: 既にDEPUTYが昇格済でwithdrawingUserIdはもうADMINでない
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(DEPUTY_ID));
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, purgeId);
            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, purgeId);

            verify(auditLogService, times(1)).record(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), anyString());
            verify(notificationHelper, times(1)).notify(any(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), any(), any());
        }
    }

    private com.mannschaft.app.role.entity.UserRoleEntity argThatRoleIdAndUser(Long roleId, Long userId) {
        return org.mockito.ArgumentMatchers.argThat(row ->
                row != null && roleId.equals(row.getRoleId()) && userId.equals(row.getUserId()));
    }
}
