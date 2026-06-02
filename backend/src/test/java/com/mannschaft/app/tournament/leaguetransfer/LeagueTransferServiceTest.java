package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.tournament.ParticipantStatus;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entity.TournamentStandingEntity;
import com.mannschaft.app.tournament.leaguetransfer.dto.LeagueTransferResponse;
import com.mannschaft.app.tournament.leaguetransfer.dto.PromoteRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.RelegateRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.TransferCandidateResponse;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStandingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link LeagueTransferService} の単体テスト（test-first）。
 *
 * <p>F08.7.1 / 03 リーグ・ピラミッド＋昇降格移籍 設計書に準拠。状態遷移（DISPATCHED→各終端）・
 * 境界枠判定（最上位昇格枠/最下位降格枠）・送り先解決（祖先/子孫 ASSOCIATION 限定・0 件警告）・
 * 認可（送り出し/承認の役割分離・他 org 操作 404・チーム横取り不可）・二重起票 UNIQUE を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LeagueTransferService 単体テスト")
class LeagueTransferServiceTest {

    @Mock private LeagueTransferRepository transferRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentStandingRepository standingRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private OrganizationHierarchyService organizationHierarchyService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @InjectMocks private LeagueTransferService service;

    // org 階層: 九州協会(上位) ⊃ 大分県協会(下位)
    private static final Long KYUSHU_ORG = 1L;       // 上位 org（昇格の受け入れ / 降格の手放す側）
    private static final Long OITA_ORG = 2L;         // 下位 org（昇格の手放す側 / 降格の受け入れ）
    private static final Long UNRELATED_ORG = 99L;   // 無関係 org

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long TOP_DIVISION_ID = 200L;     // 最上位（昇格枠の源）
    private static final Long BOTTOM_DIVISION_ID = 201L;  // 最下位（降格枠の源）
    private static final Long TARGET_DIVISION_ID = 300L;  // 受け入れ側の配属先

    private static final Long TEAM_A = 400L;  // 昇格/降格枠チーム
    private static final Long TEAM_MID = 401L; // 枠外チーム

    private static final Long ADMIN_ID = 10L;
    private static final Long OUTSIDER_ID = 30L;

    private static final Long PARTICIPANT_A = 5000L;
    private static final Long PARTICIPANT_MID = 5001L;

    @BeforeEach
    void setUpDefaults() {
        // 既定: SYSTEM_ADMIN ではない
        lenient().when(accessControlService.isSystemAdmin(any())).thenReturn(false);
    }

    // =========================================================
    // フィクスチャ
    // =========================================================

    private TournamentEntity tournament(Long orgId, String season) {
        return TournamentEntity.builder().organizationId(orgId).name("テスト大会").season(season).build();
    }

    private TournamentDivisionEntity division(Long id, Long tournamentId, int level,
                                              int promotionSlots, int relegationSlots) {
        TournamentDivisionEntity d = TournamentDivisionEntity.builder()
                .tournamentId(tournamentId).name("D" + level).level(level)
                .promotionSlots(promotionSlots).relegationSlots(relegationSlots).build();
        ReflectionTestUtils.setField(d, "id", id);
        return d;
    }

    private TournamentStandingEntity standing(Long divisionId, Long participantId, int rank) {
        return TournamentStandingEntity.builder()
                .divisionId(divisionId).participantId(participantId).rank(rank).build();
    }

    private TournamentParticipantEntity participant(Long id, Long teamId) {
        return TournamentParticipantEntity.builder()
                .id(id).divisionId(TOP_DIVISION_ID).teamId(teamId).status(ParticipantStatus.ACTIVE).build();
    }

    private OrganizationEntity org(Long id, OrganizationEntity.OrgType type) {
        OrganizationEntity o = OrganizationEntity.builder().name("org" + id).orgType(type).build();
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    // =========================================================
    // 昇格送り出し（promote）
    // =========================================================

    @Nested
    @DisplayName("昇格送り出し（promote）")
    class Promote {

        private void givenTournamentAndTopDivision() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(OITA_ORG, "2026")));
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(division(TOP_DIVISION_ID, TOURNAMENT_ID, 1, 1, 0)));
            // 最上位部: TEAM_A=1位(昇格枠), TEAM_MID=2位(枠外)
            given(standingRepository.findByDivisionIdOrderByRankAsc(TOP_DIVISION_ID))
                    .willReturn(List.of(standing(TOP_DIVISION_ID, PARTICIPANT_A, 1),
                            standing(TOP_DIVISION_ID, PARTICIPANT_MID, 2)));
            given(participantRepository.findById(PARTICIPANT_A)).willReturn(Optional.of(participant(PARTICIPANT_A, TEAM_A)));
            given(participantRepository.findById(PARTICIPANT_MID)).willReturn(Optional.of(participant(PARTICIPANT_MID, TEAM_MID)));
            given(standingRepository.findByDivisionIdAndParticipantId(TOP_DIVISION_ID, PARTICIPANT_A))
                    .willReturn(Optional.of(standing(TOP_DIVISION_ID, PARTICIPANT_A, 1)));
        }

        @Test
        @DisplayName("下位 org ADMIN は昇格枠チームを親 org へ DISPATCHED 起票できる（祖先 org 解決）")
        void promoteByLowerOrgAdmin() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            // 既定の送り先＝直近の親 org（九州協会）
            given(organizationRepository.findParentOrganizationIdById(OITA_ORG)).willReturn(Optional.of(KYUSHU_ORG));
            given(transferRepository.findByTeamIdAndSeasonAndDirection(TEAM_A, "2026", LeagueTransferDirection.PROMOTION))
                    .willReturn(Optional.empty());
            given(transferRepository.save(any(LeagueTransferEntity.class))).willAnswer(inv -> {
                LeagueTransferEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(UUID.randomUUID());
                return e;
            });

            List<LeagueTransferResponse> res = service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), null, "昇格おめでとう"));

            assertThat(res).hasSize(1);
            assertThat(res.get(0).direction()).isEqualTo("PROMOTION");
            assertThat(res.get(0).status()).isEqualTo("DISPATCHED");
            assertThat(res.get(0).toOrganizationId()).isEqualTo(KYUSHU_ORG);
            assertThat(res.get(0).fromOrganizationId()).isEqualTo(OITA_ORG);
            assertThat(res.get(0).finalRank()).isEqualTo(1);

            ArgumentCaptor<LeagueTransferEntity> captor = ArgumentCaptor.forClass(LeagueTransferEntity.class);
            verify(transferRepository).save(captor.capture());
            assertThat(captor.getValue().getInitiatedBy()).isEqualTo(ADMIN_ID);
            assertThat(captor.getValue().getSeason()).isEqualTo("2026");
        }

        @Test
        @DisplayName("明示指定 targetOrganizationId が祖先 org なら採用される")
        void promoteWithExplicitAncestorTarget() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(organizationHierarchyService.isAncestorOf(KYUSHU_ORG, OITA_ORG)).willReturn(true);
            given(transferRepository.findByTeamIdAndSeasonAndDirection(any(), any(), any())).willReturn(Optional.empty());
            given(transferRepository.save(any(LeagueTransferEntity.class))).willAnswer(inv -> inv.getArgument(0));

            List<LeagueTransferResponse> res = service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), KYUSHU_ORG, null));

            assertThat(res.get(0).toOrganizationId()).isEqualTo(KYUSHU_ORG);
        }

        @Test
        @DisplayName("明示指定 targetOrganizationId が祖先でなければ解決不能で 422（無関係 org への送り出し防止）")
        void promoteWithUnrelatedExplicitTargetRejected() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(organizationHierarchyService.isAncestorOf(UNRELATED_ORG, OITA_ORG)).willReturn(false);

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), UNRELATED_ORG, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
            verify(transferRepository, never()).save(any());
        }

        @Test
        @DisplayName("親 org が存在しない（最上位）と解決不能で 422（症状を握りつぶさず例外化）")
        void promoteNoParentOrg() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findParentOrganizationIdById(OITA_ORG)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
        }

        @Test
        @DisplayName("昇格枠外のチーム（順位 > promotion_slots）は 422（横取り・枠外起票の防止）")
        void promoteTeamNotInSlot() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findParentOrganizationIdById(OITA_ORG)).willReturn(Optional.of(KYUSHU_ORG));

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_MID), null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT);
            verify(transferRepository, never()).save(any());
        }

        @Test
        @DisplayName("二重起票（同一 team×season×direction）は 409 相当（LEAGUE_TRANSFER_ALREADY_DISPATCHED）")
        void promoteDuplicateDispatch() {
            givenTournamentAndTopDivision();
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(organizationRepository.findParentOrganizationIdById(OITA_ORG)).willReturn(Optional.of(KYUSHU_ORG));
            LeagueTransferEntity existing = LeagueTransferEntity.builder()
                    .direction(LeagueTransferDirection.PROMOTION).teamId(TEAM_A)
                    .fromOrganizationId(OITA_ORG).toOrganizationId(KYUSHU_ORG).season("2026")
                    .initiatedBy(ADMIN_ID).build();
            given(transferRepository.findByTeamIdAndSeasonAndDirection(TEAM_A, "2026", LeagueTransferDirection.PROMOTION))
                    .willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_ALREADY_DISPATCHED);
            verify(transferRepository, never()).save(any());
        }

        @Test
        @DisplayName("非 ADMIN は昇格送り出しできず 403（LEAGUE_TRANSFER_DISPATCH_FORBIDDEN）")
        void promoteForbiddenForNonAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(OITA_ORG, "2026")));
            given(accessControlService.isAdmin(OUTSIDER_ID, OITA_ORG, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, OUTSIDER_ID,
                    new PromoteRequest(List.of(TEAM_A), null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN);
        }

        @Test
        @DisplayName("他組織の大会を指定すると 404（TOURNAMENT_NOT_FOUND・IDOR）")
        void promoteCrossOrgTournament404() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(UNRELATED_ORG, "2026")));

            assertThatThrownBy(() -> service.promote(OITA_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new PromoteRequest(List.of(TEAM_A), null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    // =========================================================
    // 降格送り出し（relegate）
    // =========================================================

    @Nested
    @DisplayName("降格送り出し（relegate）")
    class Relegate {

        private void givenTournamentAndBottomDivision() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(division(BOTTOM_DIVISION_ID, TOURNAMENT_ID, 1, 0, 1)));
            // 最下位部: 2チーム中、TEAM_A=2位(降格枠 rank>2-1=1), TEAM_MID=1位(枠外)
            given(standingRepository.findByDivisionIdOrderByRankAsc(BOTTOM_DIVISION_ID))
                    .willReturn(List.of(standing(BOTTOM_DIVISION_ID, PARTICIPANT_MID, 1),
                            standing(BOTTOM_DIVISION_ID, PARTICIPANT_A, 2)));
            given(participantRepository.findById(PARTICIPANT_A)).willReturn(Optional.of(participant(PARTICIPANT_A, TEAM_A)));
            given(participantRepository.findById(PARTICIPANT_MID)).willReturn(Optional.of(participant(PARTICIPANT_MID, TEAM_MID)));
            given(standingRepository.findByDivisionIdAndParticipantId(BOTTOM_DIVISION_ID, PARTICIPANT_A))
                    .willReturn(Optional.of(standing(BOTTOM_DIVISION_ID, PARTICIPANT_A, 2)));
        }

        @Test
        @DisplayName("上位 org ADMIN は降格枠チームを出身県協会（子孫 ASSOCIATION）へ DISPATCHED 起票できる")
        void relegateByUpperOrgAdmin() {
            givenTournamentAndBottomDivision();
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            // TEAM_A は大分県協会(子孫 ASSOCIATION)に所属
            given(teamOrgMembershipRepository.findByTeamIdAndStatus(TEAM_A, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(TeamOrgMembershipEntity.builder()
                            .teamId(TEAM_A).organizationId(OITA_ORG)
                            .status(TeamOrgMembershipEntity.Status.ACTIVE).build()));
            given(organizationHierarchyService.isDescendantOf(OITA_ORG, KYUSHU_ORG)).willReturn(true);
            given(organizationRepository.findById(OITA_ORG))
                    .willReturn(Optional.of(org(OITA_ORG, OrganizationEntity.OrgType.ASSOCIATION)));
            given(transferRepository.findByTeamIdAndSeasonAndDirection(TEAM_A, "2026", LeagueTransferDirection.RELEGATION))
                    .willReturn(Optional.empty());
            given(transferRepository.save(any(LeagueTransferEntity.class))).willAnswer(inv -> inv.getArgument(0));

            List<LeagueTransferResponse> res = service.relegate(KYUSHU_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new RelegateRequest(List.of(TEAM_A), null));

            assertThat(res).hasSize(1);
            assertThat(res.get(0).direction()).isEqualTo("RELEGATION");
            assertThat(res.get(0).status()).isEqualTo("DISPATCHED");
            assertThat(res.get(0).fromOrganizationId()).isEqualTo(KYUSHU_ORG);
            assertThat(res.get(0).toOrganizationId()).isEqualTo(OITA_ORG);
        }

        @Test
        @DisplayName("出身県協会が 0 件なら降格送り出しを保留し 422（症状を握りつぶさず例外化・§5.2）")
        void relegateNoOriginAssociation() {
            givenTournamentAndBottomDivision();
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            given(teamOrgMembershipRepository.findByTeamIdAndStatus(TEAM_A, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of());

            assertThatThrownBy(() -> service.relegate(KYUSHU_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new RelegateRequest(List.of(TEAM_A), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
            verify(transferRepository, never()).save(any());
        }

        @Test
        @DisplayName("子孫だが ASSOCIATION でない org は送り先から除外され、結果 0 件で 422")
        void relegateDescendantButNotAssociation() {
            givenTournamentAndBottomDivision();
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            given(teamOrgMembershipRepository.findByTeamIdAndStatus(TEAM_A, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(TeamOrgMembershipEntity.builder()
                            .teamId(TEAM_A).organizationId(OITA_ORG)
                            .status(TeamOrgMembershipEntity.Status.ACTIVE).build()));
            given(organizationHierarchyService.isDescendantOf(OITA_ORG, KYUSHU_ORG)).willReturn(true);
            // SCHOOL 等の非 ASSOCIATION は送り先にしない
            given(organizationRepository.findById(OITA_ORG))
                    .willReturn(Optional.of(org(OITA_ORG, OrganizationEntity.OrgType.SCHOOL)));

            assertThatThrownBy(() -> service.relegate(KYUSHU_ORG, TOURNAMENT_ID, ADMIN_ID,
                    new RelegateRequest(List.of(TEAM_A), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE);
        }

        @Test
        @DisplayName("非 ADMIN は降格送り出しできず 403（LEAGUE_TRANSFER_DISPATCH_FORBIDDEN）")
        void relegateForbiddenForNonAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(accessControlService.isAdmin(OUTSIDER_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.relegate(KYUSHU_ORG, TOURNAMENT_ID, OUTSIDER_ID,
                    new RelegateRequest(List.of(TEAM_A), null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN);
        }
    }

    // =========================================================
    // 受信箱（listInbound）
    // =========================================================

    @Nested
    @DisplayName("受信箱（listInbound）")
    class Inbound {

        @Test
        @DisplayName("受け入れ側 org ADMIN は自 org 宛 DISPATCHED 一覧を取得できる")
        void inboundByOrgAdmin() {
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            LeagueTransferEntity t = LeagueTransferEntity.builder()
                    .direction(LeagueTransferDirection.PROMOTION).teamId(TEAM_A)
                    .fromOrganizationId(OITA_ORG).toOrganizationId(KYUSHU_ORG).season("2026")
                    .status(LeagueTransferStatus.DISPATCHED).initiatedBy(ADMIN_ID).build();
            t.setId(UUID.randomUUID());
            given(transferRepository.findByToOrganizationIdAndStatusOrderByCreatedAtDesc(
                    KYUSHU_ORG, LeagueTransferStatus.DISPATCHED)).willReturn(List.of(t));

            List<LeagueTransferResponse> res = service.listInbound(KYUSHU_ORG, null, ADMIN_ID);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).toOrganizationId()).isEqualTo(KYUSHU_ORG);
        }

        @Test
        @DisplayName("非 ADMIN は受信箱を閲覧できず 403（情報漏洩防止）")
        void inboundForbiddenForNonAdmin() {
            given(accessControlService.isAdmin(OUTSIDER_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.listInbound(KYUSHU_ORG, null, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN);
            verify(transferRepository, never())
                    .findByToOrganizationIdAndStatusOrderByCreatedAtDesc(any(), any());
        }
    }

    // =========================================================
    // 承認・配属（approve）
    // =========================================================

    @Nested
    @DisplayName("承認・配属（approve）")
    class Approve {

        private LeagueTransferEntity inboundTransfer() {
            LeagueTransferEntity t = LeagueTransferEntity.builder()
                    .direction(LeagueTransferDirection.PROMOTION).teamId(TEAM_A)
                    .fromOrganizationId(OITA_ORG).toOrganizationId(KYUSHU_ORG).season("2026")
                    .status(LeagueTransferStatus.DISPATCHED).initiatedBy(ADMIN_ID).build();
            t.setId(UUID.randomUUID());
            return t;
        }

        @Test
        @DisplayName("受け入れ側 org ADMIN は承認して PLACED にし、participant を REGISTERED で作成する")
        void approveByRecvOrgAdmin() {
            LeagueTransferEntity t = inboundTransfer();
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(divisionRepository.findById(TARGET_DIVISION_ID))
                    .willReturn(Optional.of(division(TARGET_DIVISION_ID, TOURNAMENT_ID, 1, 0, 0)));
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            given(participantRepository.findByDivisionIdAndTeamId(TARGET_DIVISION_ID, TEAM_A))
                    .willReturn(Optional.empty());
            given(transferRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            LeagueTransferResponse res = service.approve(KYUSHU_ORG, TOURNAMENT_ID, TARGET_DIVISION_ID, t.getId(), ADMIN_ID);

            assertThat(res.status()).isEqualTo("PLACED");
            assertThat(res.targetDivisionId()).isEqualTo(TARGET_DIVISION_ID);
            assertThat(res.respondedBy()).isEqualTo(ADMIN_ID);

            ArgumentCaptor<TournamentParticipantEntity> pc = ArgumentCaptor.forClass(TournamentParticipantEntity.class);
            verify(participantRepository).save(pc.capture());
            assertThat(pc.getValue().getStatus()).isEqualTo(ParticipantStatus.REGISTERED);
            assertThat(pc.getValue().getDivisionId()).isEqualTo(TARGET_DIVISION_ID);
            assertThat(pc.getValue().getTeamId()).isEqualTo(TEAM_A);
        }

        @Test
        @DisplayName("他 org（to ≠ orgId）の移籍を承認しようとすると 404（IDOR）")
        void approveCrossOrg404() {
            LeagueTransferEntity t = inboundTransfer(); // to = KYUSHU
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(OITA_ORG, "2026")));
            given(divisionRepository.findById(TARGET_DIVISION_ID))
                    .willReturn(Optional.of(division(TARGET_DIVISION_ID, TOURNAMENT_ID, 1, 0, 0)));
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));

            // orgId=OITA で承認しようとする（to は KYUSHU なので 404）
            assertThatThrownBy(() -> service.approve(OITA_ORG, TOURNAMENT_ID, TARGET_DIVISION_ID, t.getId(), ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("受け入れ側でも非 ADMIN は承認できず 403（LEAGUE_TRANSFER_RESPOND_FORBIDDEN）")
        void approveForbiddenForNonAdmin() {
            LeagueTransferEntity t = inboundTransfer();
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(divisionRepository.findById(TARGET_DIVISION_ID))
                    .willReturn(Optional.of(division(TARGET_DIVISION_ID, TOURNAMENT_ID, 1, 0, 0)));
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));
            given(accessControlService.isAdmin(OUTSIDER_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.approve(KYUSHU_ORG, TOURNAMENT_ID, TARGET_DIVISION_ID, t.getId(), OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_RESPOND_FORBIDDEN);
            verify(participantRepository, never()).save(any());
        }

        @Test
        @DisplayName("divId が tId 配下でないと 404（DIVISION_NOT_FOUND・IDOR チェーン）")
        void approveDivisionMismatch404() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(divisionRepository.findById(TARGET_DIVISION_ID))
                    .willReturn(Optional.of(division(TARGET_DIVISION_ID, 999L, 1, 0, 0)));

            assertThatThrownBy(() -> service.approve(KYUSHU_ORG, TOURNAMENT_ID, TARGET_DIVISION_ID,
                    UUID.randomUUID(), ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.DIVISION_NOT_FOUND);
        }

        @Test
        @DisplayName("既に応答済み（PLACED 等）の移籍は承認できず状態違反（LEAGUE_TRANSFER_NOT_DISPATCHED）")
        void approveAlreadyResponded() {
            LeagueTransferEntity t = inboundTransfer();
            t.place(999L, 11L); // 既に PLACED
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(KYUSHU_ORG, "2026")));
            given(divisionRepository.findById(TARGET_DIVISION_ID))
                    .willReturn(Optional.of(division(TARGET_DIVISION_ID, TOURNAMENT_ID, 1, 0, 0)));
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);

            assertThatThrownBy(() -> service.approve(KYUSHU_ORG, TOURNAMENT_ID, TARGET_DIVISION_ID, t.getId(), ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_NOT_DISPATCHED);
        }
    }

    // =========================================================
    // 拒否・取消（decline / cancel）
    // =========================================================

    @Nested
    @DisplayName("拒否・取消（decline / cancel）")
    class DeclineCancel {

        private LeagueTransferEntity dispatched() {
            LeagueTransferEntity t = LeagueTransferEntity.builder()
                    .direction(LeagueTransferDirection.PROMOTION).teamId(TEAM_A)
                    .fromOrganizationId(OITA_ORG).toOrganizationId(KYUSHU_ORG).season("2026")
                    .status(LeagueTransferStatus.DISPATCHED).initiatedBy(ADMIN_ID).build();
            t.setId(UUID.randomUUID());
            return t;
        }

        @Test
        @DisplayName("受け入れ側 org ADMIN は拒否して DECLINED にできる")
        void declineByRecvOrgAdmin() {
            LeagueTransferEntity t = dispatched();
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));
            given(accessControlService.isAdmin(ADMIN_ID, KYUSHU_ORG, "ORGANIZATION")).willReturn(true);
            given(transferRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            LeagueTransferResponse res = service.decline(KYUSHU_ORG, t.getId(), ADMIN_ID);

            assertThat(res.status()).isEqualTo("DECLINED");
            assertThat(res.respondedBy()).isEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("手放す側 org ADMIN は応答前に取消して CANCELLED にできる（from_org スコープ）")
        void cancelByPushOrgAdmin() {
            LeagueTransferEntity t = dispatched();
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(transferRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            LeagueTransferResponse res = service.cancel(OITA_ORG, t.getId(), ADMIN_ID);

            assertThat(res.status()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("取消を受け入れ側 org（to_org）から行おうとすると 404（from_org スコープのため IDOR）")
        void cancelFromWrongOrg404() {
            LeagueTransferEntity t = dispatched(); // from = OITA
            given(transferRepository.findById(t.getId())).willReturn(Optional.of(t));

            assertThatThrownBy(() -> service.cancel(KYUSHU_ORG, t.getId(), ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND);
        }

        @Test
        @DisplayName("存在しない移籍 ID は 404（LEAGUE_TRANSFER_NOT_FOUND）")
        void declineNotFound() {
            UUID id = UUID.randomUUID();
            given(transferRepository.findById(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.decline(KYUSHU_ORG, id, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND);
        }
    }

    // =========================================================
    // チーム側閲覧（listTeamTransfers）
    // =========================================================

    @Nested
    @DisplayName("チーム側閲覧（listTeamTransfers）")
    class TeamView {

        @Test
        @DisplayName("当該チーム MEMBER 以上は自チームの移籍状況を閲覧できる")
        void teamViewByMember() {
            given(accessControlService.hasRoleOrAbove(ADMIN_ID, TEAM_A, "TEAM", "MEMBER")).willReturn(true);
            LeagueTransferEntity t = LeagueTransferEntity.builder()
                    .direction(LeagueTransferDirection.PROMOTION).teamId(TEAM_A)
                    .fromOrganizationId(OITA_ORG).toOrganizationId(KYUSHU_ORG).season("2026")
                    .status(LeagueTransferStatus.DISPATCHED).initiatedBy(ADMIN_ID).build();
            t.setId(UUID.randomUUID());
            given(transferRepository.findByTeamIdOrderByCreatedAtDesc(TEAM_A)).willReturn(List.of(t));

            List<LeagueTransferResponse> res = service.listTeamTransfers(TEAM_A, ADMIN_ID);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).teamId()).isEqualTo(TEAM_A);
        }

        @Test
        @DisplayName("非メンバーは閲覧できず 403（LEAGUE_TRANSFER_VIEW_FORBIDDEN・情報漏洩防止）")
        void teamViewForbiddenForNonMember() {
            given(accessControlService.hasRoleOrAbove(OUTSIDER_ID, TEAM_A, "TEAM", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.listTeamTransfers(TEAM_A, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_VIEW_FORBIDDEN);
            verifyNoInteractions(transferRepository);
        }

        @Test
        @DisplayName("未認証（userId=null）は 403")
        void teamViewForbiddenForAnonymous() {
            assertThatThrownBy(() -> service.listTeamTransfers(TEAM_A, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_VIEW_FORBIDDEN);
        }
    }

    // =========================================================
    // 候補導出（getTransferCandidates）
    // =========================================================

    @Nested
    @DisplayName("境界候補導出（getTransferCandidates）")
    class Candidates {

        @Test
        @DisplayName("昇格候補は最上位部の昇格枠チームのみ返し、送り先（祖先 org）を解決する")
        void promotionCandidates() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(OITA_ORG, "2026")));
            given(accessControlService.isAdmin(ADMIN_ID, OITA_ORG, "ORGANIZATION")).willReturn(true);
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(division(TOP_DIVISION_ID, TOURNAMENT_ID, 1, 1, 0)));
            given(standingRepository.findByDivisionIdOrderByRankAsc(TOP_DIVISION_ID))
                    .willReturn(List.of(standing(TOP_DIVISION_ID, PARTICIPANT_A, 1),
                            standing(TOP_DIVISION_ID, PARTICIPANT_MID, 2)));
            given(participantRepository.findById(PARTICIPANT_A)).willReturn(Optional.of(participant(PARTICIPANT_A, TEAM_A)));
            given(standingRepository.findByDivisionIdAndParticipantId(TOP_DIVISION_ID, PARTICIPANT_A))
                    .willReturn(Optional.of(standing(TOP_DIVISION_ID, PARTICIPANT_A, 1)));
            given(organizationRepository.findParentOrganizationIdById(OITA_ORG)).willReturn(Optional.of(KYUSHU_ORG));

            List<TransferCandidateResponse> res = service.getTransferCandidates(
                    OITA_ORG, TOURNAMENT_ID, LeagueTransferDirection.PROMOTION, ADMIN_ID);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).teamId()).isEqualTo(TEAM_A);
            assertThat(res.get(0).direction()).isEqualTo("PROMOTION");
            assertThat(res.get(0).resolvedTargetOrganizationId()).isEqualTo(KYUSHU_ORG);
        }

        @Test
        @DisplayName("非 ADMIN は候補を取得できず 403")
        void candidatesForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(tournament(OITA_ORG, "2026")));
            given(accessControlService.isAdmin(OUTSIDER_ID, OITA_ORG, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getTransferCandidates(
                    OITA_ORG, TOURNAMENT_ID, LeagueTransferDirection.PROMOTION, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN);
        }
    }
}
