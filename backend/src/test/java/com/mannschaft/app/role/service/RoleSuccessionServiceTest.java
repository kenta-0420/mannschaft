package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.RoleErrorCode;
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
    @Mock
    private UserRowLockService userRowLockService;
    @Mock
    private MembershipService membershipService;

    @InjectMocks
    private RoleSuccessionService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long WITHDRAWING_USER_ID = 1L;
    private static final Long DEPUTY_ID = 2L;
    private static final Long MEMBER_ID = 3L;
    private static final Long ADMIN_ROLE_ID = 9L;

    /**
     * 候補が資格再検証（P1-2）を通過する状態にする（現役・当該スコープに在籍）。
     * {@code forceTransferForPurge} 用: ロック順序修正（Codex第2巡P1）により
     * {@code userRowLockService.lockAll(withdrawingUserId, candidateId)} 経由で判定するため、
     * その形でスタブする。
     */
    private void givenEligibleForPurge(Long withdrawingUserId, Long candidateId) {
        given(userRowLockService.lockAll(withdrawingUserId, candidateId)).willReturn(java.util.Map.of(
                withdrawingUserId, UserRowLockService.UserState.ACTIVE,
                candidateId, UserRowLockService.UserState.ACTIVE));
        given(membershipService.isActiveMemberForUpdate(candidateId, ScopeType.TEAM, SCOPE_ID)).willReturn(true);
    }

    /** {@code forceAssignInitialAdminOnUnarchive} 用: 単一ユーザーの {@code lock} 経由で判定する。 */
    private void givenEligibleForUnarchive(Long candidateId) {
        given(userRowLockService.lock(candidateId)).willReturn(UserRowLockService.UserState.ACTIVE);
        given(membershipService.isActiveMemberForUpdate(candidateId, ScopeType.TEAM, SCOPE_ID)).willReturn(true);
    }

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
            givenEligibleForPurge(WITHDRAWING_USER_ID, DEPUTY_ID);

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
            givenEligibleForPurge(WITHDRAWING_USER_ID, DEPUTY_ID);

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
            givenEligibleForPurge(WITHDRAWING_USER_ID, DEPUTY_ID);

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, purgeId);
            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, purgeId);

            verify(auditLogService, times(1)).record(anyString(), any(), any(), any(), any(),
                    any(), any(), any(), anyString());
            verify(notificationHelper, times(1)).notify(any(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Codex第2巡P1: ロック取得順序はusers先→ADMIN行後（既存RoleService#transferOwnershipと同順）")
    class LockOrderUsersBeforeAdmin {

        @Test
        @DisplayName("forceTransferForPurge: userRowLockService.lockAllがadminRoleMutationLockServiceより先に呼ばれる"
                + "（逆順だとpurge/バッチ経路とtransfer/leave経路が相互待ちデッドロックする）")
        void forceTransferForPurgeはusersを先にロックする() {
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(DEPUTY_ID));
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());
            givenEligibleForPurge(WITHDRAWING_USER_ID, DEPUTY_ID);

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(userRowLockService, adminRoleMutationLockService);
            inOrder.verify(userRowLockService).lockAll(WITHDRAWING_USER_ID, DEPUTY_ID);
            inOrder.verify(adminRoleMutationLockService).lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM");
            // 一括ロックのみ・追加のロック取得（リトライ）が発生していないことを確認する。
            verify(userRowLockService, times(1)).lockAll(WITHDRAWING_USER_ID, DEPUTY_ID);
        }

        @Test
        @DisplayName("promoteForBatchSuccession: userRowLockService.lockAllがadminRoleMutationLockServiceより先に呼ばれる"
                + "（1回だけ・一括ロック。Codex第3巡P1: リトライによる追加ロック取得は行わない）")
        void promoteForBatchSuccessionはusersを先にロックする() {
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of());
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(List.of(DEPUTY_ID));
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());
            given(userRowLockService.lockAll(DEPUTY_ID)).willReturn(
                    java.util.Map.of(DEPUTY_ID, UserRowLockService.UserState.ACTIVE));
            given(membershipService.isActiveMemberForUpdate(DEPUTY_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(true);

            service.promoteForBatchSuccession(SCOPE_ID, "TEAM");

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(userRowLockService, adminRoleMutationLockService);
            inOrder.verify(userRowLockService).lockAll(DEPUTY_ID);
            inOrder.verify(adminRoleMutationLockService).lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM");
            // 一括ロックのみ・追加のロック取得（リトライ）が発生していないことを確認する。
            verify(userRowLockService, times(1)).lockAll(DEPUTY_ID);
        }

        @Test
        @DisplayName("forceAssignInitialAdminOnUnarchive: userRowLockService.lockがadminRoleMutationLockServiceより先に呼ばれる")
        void forceAssignInitialAdminOnUnarchiveはusersを先にロックする() {
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());
            givenEligibleForUnarchive(DEPUTY_ID);

            service.forceAssignInitialAdminOnUnarchive(SCOPE_ID, "TEAM", DEPUTY_ID, 999L);

            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(userRowLockService, adminRoleMutationLockService);
            inOrder.verify(userRowLockService).lock(DEPUTY_ID);
            inOrder.verify(adminRoleMutationLockService).lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM");
        }
    }

    @Nested
    @DisplayName("P1-2 / Codex第3巡P1: ロック下の実行直前評価で先頭候補が失格していれば優先順で次点が昇格する"
            + "（事前に一括仮決定した集合内での評価。追加ロックは取得しない）")
    class P1_2ReVerificationFallback {

        @Test
        @DisplayName("P1-2: 最古参候補が失格（退会済等）なら、同一lockAllで一括ロック済みの次点候補が昇格する")
        void 最古参が失格なら次点候補が昇格する() {
            Long secondCandidateId = 55L;
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID))
                    .willReturn(List.of(DEPUTY_ID, secondCandidateId));
            // 退会者本人 + 上位2候補（DEPUTY_ID, secondCandidateId）を1回のlockAllで一括ロックする。
            // DEPUTY_ID は状態変化により失格（例: 退会済 → INELIGIBLE_EXISTING）、secondCandidateId は現役。
            given(userRowLockService.lockAll(WITHDRAWING_USER_ID, DEPUTY_ID, secondCandidateId)).willReturn(java.util.Map.of(
                    WITHDRAWING_USER_ID, UserRowLockService.UserState.ACTIVE,
                    DEPUTY_ID, UserRowLockService.UserState.INELIGIBLE_EXISTING,
                    secondCandidateId, UserRowLockService.UserState.ACTIVE));
            given(membershipService.isActiveMemberForUpdate(secondCandidateId, ScopeType.TEAM, SCOPE_ID)).willReturn(true);
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(secondCandidateId, SCOPE_ID)).willReturn(Optional.empty());

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            verify(userRoleRepository).save(argThatRoleIdAndUser(ADMIN_ROLE_ID, secondCandidateId));
            verify(userRoleRepository, never()).save(argThatRoleIdAndUser(ADMIN_ROLE_ID, DEPUTY_ID));
            // lockAllは1回だけ（DEPUTY_ID失格を理由に追加のロック取得＝リトライはしない）。
            verify(userRowLockService, times(1)).lockAll(WITHDRAWING_USER_ID, DEPUTY_ID, secondCandidateId);
        }

        @Test
        @DisplayName("Codex第3巡P1: 仮決定した上位5件が全滅し、他に候補が存在しない場合はarchiveへフォールバックする")
        void 上位5件全滅かつ他候補なしならarchiveされる() {
            List<Long> fiveDeputies = List.of(11L, 12L, 13L, 14L, 15L);
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            // ちょうど5件（それ以上は存在しない）。deputies.size()(5) >= limit(5) のため
            // findMemberCandidateIdsByTeam は呼ばれない（早期return）。
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(fiveDeputies);
            java.util.Map<Long, UserRowLockService.UserState> allAbsent = new java.util.LinkedHashMap<>();
            allAbsent.put(WITHDRAWING_USER_ID, UserRowLockService.UserState.ACTIVE);
            fiveDeputies.forEach(id -> allAbsent.put(id, UserRowLockService.UserState.ABSENT));
            given(userRowLockService.lockAll(
                    WITHDRAWING_USER_ID, 11L, 12L, 13L, 14L, 15L)).willReturn(allAbsent);

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            verify(teamService).archiveTeam(SCOPE_ID);
            verify(userRoleRepository, never()).save(any());
            verify(userRowLockService, times(1)).lockAll(WITHDRAWING_USER_ID, 11L, 12L, 13L, 14L, 15L);
        }

        @Test
        @DisplayName("Codex第3巡P1: 仮決定した上位5件が全滅だが他に候補が存在する場合は"
                + "本トランザクションでは是正せず終了する（archiveしない・追加ロックも取らない）")
        void 上位5件全滅だが他候補があればTXを終了しarchiveしない() {
            // 6件存在（上位5件のみ仮決定・6件目は評価対象外＝moreExist=true）。
            List<Long> sixDeputies = List.of(21L, 22L, 23L, 24L, 25L, 26L);
            given(adminRoleMutationLockService.lockScopeAdminRowsAfterUsersLocked(SCOPE_ID, "TEAM"))
                    .willReturn(List.of(WITHDRAWING_USER_ID));
            given(userRoleRepository.findDeputyAdminCandidateIdsByTeam(SCOPE_ID)).willReturn(sixDeputies);
            java.util.Map<Long, UserRowLockService.UserState> allAbsent = new java.util.LinkedHashMap<>();
            allAbsent.put(WITHDRAWING_USER_ID, UserRowLockService.UserState.ACTIVE);
            List.of(21L, 22L, 23L, 24L, 25L).forEach(id -> allAbsent.put(id, UserRowLockService.UserState.ABSENT));
            given(userRowLockService.lockAll(
                    WITHDRAWING_USER_ID, 21L, 22L, 23L, 24L, 25L)).willReturn(allAbsent);

            service.forceTransferForPurge(SCOPE_ID, "TEAM", WITHDRAWING_USER_ID, UUID.randomUUID());

            // 是正せず終了: archiveもsaveも呼ばれない。lockAllは仮決定した上位5件分の1回だけで、
            // 6件目（26L）を含む追加のロック取得（＝リトライ）は行わない。
            verify(teamService, never()).archiveTeam(any());
            verify(organizationService, never()).archiveOrganization(any());
            verify(userRoleRepository, never()).save(any());
            verify(userRowLockService, times(1)).lockAll(WITHDRAWING_USER_ID, 21L, 22L, 23L, 24L, 25L);
        }
    }

    @Nested
    @DisplayName("P1-3: force-unarchiveは指名ユーザーの現役性・スコープ所属を検証する")
    class P1_3ForceUnarchiveValidatesNominee {

        @Test
        @DisplayName("P1-3: 現役かつ当該スコープの active membership を持つ指名者はADMIN化される")
        void 現役かつ在籍していれば昇格する() {
            Long systemAdminId = 999L;
            given(roleRepository.findByName("ADMIN"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ADMIN_ROLE_ID).name("ADMIN").build()));
            given(userRoleRepository.findByUserIdAndTeamIdForUpdate(DEPUTY_ID, SCOPE_ID)).willReturn(Optional.empty());
            givenEligibleForUnarchive(DEPUTY_ID);

            service.forceAssignInitialAdminOnUnarchive(SCOPE_ID, "TEAM", DEPUTY_ID, systemAdminId);

            verify(userRoleRepository).save(argThatRoleIdAndUser(ADMIN_ROLE_ID, DEPUTY_ID));
        }

        @Test
        @DisplayName("P1-3: 指名者が現役でない場合はROLE_001（404）で拒否されuser_rolesは作られない")
        void 現役でない指名者は拒否される() {
            Long systemAdminId = 999L;
            given(userRowLockService.lock(DEPUTY_ID)).willReturn(UserRowLockService.UserState.INELIGIBLE_EXISTING);

            assertThatThrownBy(() -> service.forceAssignInitialAdminOnUnarchive(SCOPE_ID, "TEAM", DEPUTY_ID, systemAdminId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(RoleErrorCode.ROLE_001));

            verify(userRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1-3: 指名者が当該スコープの active membership を持たない場合はROLE_001（404）で拒否される")
        void スコープ非在籍の指名者は拒否される() {
            Long systemAdminId = 999L;
            given(userRowLockService.lock(DEPUTY_ID)).willReturn(UserRowLockService.UserState.ACTIVE);
            given(membershipService.isActiveMemberForUpdate(DEPUTY_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(false);

            assertThatThrownBy(() -> service.forceAssignInitialAdminOnUnarchive(SCOPE_ID, "TEAM", DEPUTY_ID, systemAdminId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(RoleErrorCode.ROLE_001));

            verify(userRoleRepository, never()).save(any());
        }
    }

    private com.mannschaft.app.role.entity.UserRoleEntity argThatRoleIdAndUser(Long roleId, Long userId) {
        return org.mockito.ArgumentMatchers.argThat(row ->
                row != null && roleId.equals(row.getRoleId()) && userId.equals(row.getUserId()));
    }
}
