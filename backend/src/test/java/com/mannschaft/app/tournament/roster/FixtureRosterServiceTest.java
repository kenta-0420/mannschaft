package com.mannschaft.app.tournament.roster;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamUniformSetEntity;
import com.mannschaft.app.team.repository.TeamUniformSetRepository;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureRosterEntity;
import com.mannschaft.app.tournament.entity.TournamentMatchdayEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberRepository;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentFixtureRosterRepository;
import com.mannschaft.app.tournament.repository.TournamentMatchdayRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.roster.dto.ApplyRosterTemplateRequest;
import com.mannschaft.app.tournament.roster.dto.FixtureRosterResponse;
import com.mannschaft.app.tournament.roster.dto.OrganizerRosterView;
import com.mannschaft.app.tournament.roster.dto.SubmitRosterRequest;
import com.mannschaft.app.tournament.roster.dto.UpdateFixtureRosterDeadlineRequest;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FixtureRosterService} 単体テスト（test-first）。
 *
 * <p>設計書 docs/features/F08.7.1_tournament_extensions/05_match_roster.md §4 / §5 / §8 に準拠。
 * 自チーム提出の認可（他チーム不可・主催者閲覧）・締切ロック・apply-template の複製
 * （登録番号/ユニフォーム/staff）・IDOR を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FixtureRosterService 単体テスト")
class FixtureRosterServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentFixtureRepository matchRepository;
    @Mock private TournamentMatchdayRepository matchdayRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentFixtureRosterRepository rosterRepository;
    @Mock private FixtureRosterStaffRepository staffRepository;
    @Mock private TournamentEntryTemplateRepository templateRepository;
    @Mock private TournamentEntryTemplateMemberRepository templateMemberRepository;
    @Mock private TournamentEntryTemplateStaffRepository templateStaffRepository;
    @Mock private TeamUniformSetRepository uniformSetRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private FixtureRosterService service;

    private static final Long TID = 100L;
    private static final Long DIV_ID = 200L;
    private static final Long MD_ID = 300L;
    private static final Long MATCH_ID = 400L;
    private static final Long ORG_ID = 1L;

    private static final Long HOME_PID = 10L;
    private static final Long AWAY_PID = 11L;
    private static final Long HOME_TEAM = 1000L;
    private static final Long AWAY_TEAM = 1001L;

    private static final Long HOME_REP = 20L;     // home team ADMIN/DEPUTY
    private static final Long HOME_MEMBER = 21L;   // home team member（編集権限なし）
    private static final Long OUTSIDER = 30L;      // どちらのチームにも属さない
    private static final Long ORG_ADMIN = 40L;

    // =========================================================
    // フィクスチャ
    // =========================================================

    private TournamentEntity tournament() {
        return TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").build();
    }

    private TournamentFixtureEntity match(LocalDateTime deadline) {
        TournamentFixtureEntity m = TournamentFixtureEntity.builder()
                .matchdayId(MD_ID)
                .homeParticipantId(HOME_PID)
                .awayParticipantId(AWAY_PID)
                .build();
        // BaseEntity の id を反射的に設定できないため、findById のスタブで返すだけにする
        m.setRosterDeadline(deadline);
        return m;
    }

    private TournamentMatchdayEntity matchday() {
        return TournamentMatchdayEntity.builder().divisionId(DIV_ID).name("第1節").matchdayNumber(1).build();
    }

    private TournamentDivisionEntity division(Long tournamentId) {
        return TournamentDivisionEntity.builder().tournamentId(tournamentId).name("1部").build();
    }

    private TournamentParticipantEntity participant(Long id, Long teamId) {
        TournamentParticipantEntity p = TournamentParticipantEntity.builder()
                .divisionId(DIV_ID).teamId(teamId).displayName("チーム" + teamId).build();
        setId(p, id);
        return p;
    }

    /** id を持たない Entity（IDENTITY 採番）に id を反射的に設定する。 */
    private void setId(Object entity, Long id) {
        try {
            var f = findIdField(entity.getClass());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private java.lang.reflect.Field findIdField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id");
    }

    @BeforeEach
    void setUpChain() {
        TournamentFixtureEntity m = match(null);
        setId(m, MATCH_ID);
        given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(m));
        given(matchdayRepository.findById(MD_ID)).willReturn(Optional.of(matchday()));
        given(divisionRepository.findById(DIV_ID)).willReturn(Optional.of(division(TID)));
        given(participantRepository.findById(HOME_PID)).willReturn(Optional.of(participant(HOME_PID, HOME_TEAM)));
        given(participantRepository.findById(AWAY_PID)).willReturn(Optional.of(participant(AWAY_PID, AWAY_TEAM)));
        given(rosterRepository.findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(anyLong(), anyLong()))
                .willReturn(List.of());
        given(staffRepository.findByMatchIdAndParticipantIdOrderByCreatedAtAsc(anyLong(), anyLong()))
                .willReturn(List.of());
        given(rosterRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
        given(staffRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
    }

    /** deadline 付きの match を findById に差し替える。 */
    private void matchWithDeadline(LocalDateTime deadline) {
        TournamentFixtureEntity m = match(deadline);
        setId(m, MATCH_ID);
        given(matchRepository.findById(MATCH_ID)).willReturn(Optional.of(m));
    }

    // =========================================================
    // 自チーム取得（rosters/me GET）の認可
    // =========================================================

    @Nested
    @DisplayName("自チーム取得（getMyRoster）")
    class GetMyRoster {

        @Test
        @DisplayName("対戦当事者チームのメンバーは自チーム分を取得できる")
        void memberCanGet() {
            given(accessControlService.isMember(HOME_MEMBER, HOME_TEAM, "TEAM")).willReturn(true);

            FixtureRosterResponse res = service.getMyRoster(TID, MATCH_ID, HOME_MEMBER);

            assertThat(res.matchId()).isEqualTo(MATCH_ID);
            assertThat(res.participantId()).isEqualTo(HOME_PID);
            assertThat(res.teamId()).isEqualTo(HOME_TEAM);
        }

        @Test
        @DisplayName("どちらの対戦当事者チームにも属さないユーザーは 403（ROSTER_TEAM_NOT_IN_MATCH）")
        void outsiderForbidden() {
            given(accessControlService.isMember(eq(OUTSIDER), anyLong(), eq("TEAM"))).willReturn(false);

            assertThatThrownBy(() -> service.getMyRoster(TID, MATCH_ID, OUTSIDER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_TEAM_NOT_IN_MATCH);
        }

        @Test
        @DisplayName("他大会の matchId を渡すと 404（MATCH_NOT_FOUND・IDOR）")
        void crossTournament404() {
            given(divisionRepository.findById(DIV_ID)).willReturn(Optional.of(division(999L)));

            assertThatThrownBy(() -> service.getMyRoster(TID, MATCH_ID, HOME_MEMBER))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.MATCH_NOT_FOUND);
        }
    }

    // =========================================================
    // 自チーム提出（PUT rosters/me）の認可・締切・他チーム不可
    // =========================================================

    @Nested
    @DisplayName("自チーム提出（submitMyRoster）")
    class SubmitMyRoster {

        private SubmitRosterRequest req() {
            SubmitRosterRequest r = new SubmitRosterRequest();
            SubmitRosterRequest.PlayerEntry p = new SubmitRosterRequest.PlayerEntry();
            p.setUserId(HOME_MEMBER);
            p.setJerseyNumber(10);
            p.setRegistrationNumber("REG-001");
            r.setPlayers(List.of(p));
            SubmitRosterRequest.StaffEntry s = new SubmitRosterRequest.StaffEntry();
            s.setRole("監督");
            s.setName("山田太郎");
            r.setStaff(List.of(s));
            return r;
        }

        @Test
        @DisplayName("自チーム ADMIN/DEPUTY は提出でき、選手・ベンチ役員が全置換保存され監査ログが残る")
        void repCanSubmit() {
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);

            service.submitMyRoster(TID, MATCH_ID, HOME_REP, req());

            // 全置換: 既存自チーム分を削除してから保存
            verify(rosterRepository).deleteByMatchIdAndParticipantId(MATCH_ID, HOME_PID);
            verify(staffRepository).deleteByMatchIdAndParticipantId(MATCH_ID, HOME_PID);

            ArgumentCaptor<List<TournamentFixtureRosterEntity>> rosterCaptor = ArgumentCaptor.forClass(List.class);
            verify(rosterRepository).saveAll(rosterCaptor.capture());
            assertThat(rosterCaptor.getValue()).hasSize(1);
            TournamentFixtureRosterEntity saved = rosterCaptor.getValue().get(0);
            assertThat(saved.getParticipantId()).isEqualTo(HOME_PID);
            assertThat(saved.getRegistrationNumber()).isEqualTo("REG-001");

            ArgumentCaptor<List<FixtureRosterStaffEntity>> staffCaptor = ArgumentCaptor.forClass(List.class);
            verify(staffRepository).saveAll(staffCaptor.capture());
            assertThat(staffCaptor.getValue()).hasSize(1);
            assertThat(staffCaptor.getValue().get(0).getRole()).isEqualTo("監督");

            verify(auditLogService).record(eq(AuditEventType.TOURNAMENT_ROSTER_SUBMITTED.name()),
                    eq(HOME_REP), any(), eq(HOME_TEAM), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("自チームの一般メンバー（ADMIN/DEPUTY でない）は提出できず 403（ROSTER_EDIT_FORBIDDEN）")
        void memberCannotSubmit() {
            given(accessControlService.isMember(HOME_MEMBER, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_MEMBER, HOME_TEAM, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.submitMyRoster(TID, MATCH_ID, HOME_MEMBER, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_EDIT_FORBIDDEN);
            verify(rosterRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("対戦当事者でないチームの代表は他チーム roster を編集できず 403（ROSTER_TEAM_NOT_IN_MATCH）")
        void outsiderCannotSubmit() {
            given(accessControlService.isMember(eq(OUTSIDER), anyLong(), eq("TEAM"))).willReturn(false);

            assertThatThrownBy(() -> service.submitMyRoster(TID, MATCH_ID, OUTSIDER, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_TEAM_NOT_IN_MATCH);
            verify(rosterRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("締切（roster_deadline）超過の試合への提出は 409（ROSTER_DEADLINE_PASSED）でロック")
        void deadlinePassedLocks() {
            matchWithDeadline(LocalDateTime.now().minusMinutes(1));
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> service.submitMyRoster(TID, MATCH_ID, HOME_REP, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_DEADLINE_PASSED);
            verify(rosterRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("締切前（未来の締切）なら提出できる")
        void beforeDeadlineOk() {
            matchWithDeadline(LocalDateTime.now().plusDays(1));
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);

            service.submitMyRoster(TID, MATCH_ID, HOME_REP, req());
            verify(rosterRepository).saveAll(any());
        }

        @Test
        @DisplayName("他チームのユニフォームセットを指定すると 404（UNIFORM_SET_NOT_FOUND）")
        void foreignUniformSetRejected() {
            UUID foreignSet = UUID.randomUUID();
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(uniformSetRepository.findByIdAndTeamId(foreignSet, HOME_TEAM)).willReturn(Optional.empty());

            SubmitRosterRequest r = req();
            r.getPlayers().get(0).setUniformSetId(foreignSet);

            assertThatThrownBy(() -> service.submitMyRoster(TID, MATCH_ID, HOME_REP, r))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.UNIFORM_SET_NOT_FOUND);
        }

        @Test
        @DisplayName("自チームのユニフォームセットは受理される")
        void ownUniformSetAccepted() {
            UUID ownSet = UUID.randomUUID();
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            TeamUniformSetEntity set = TeamUniformSetEntity.builder().teamId(HOME_TEAM).build();
            given(uniformSetRepository.findByIdAndTeamId(ownSet, HOME_TEAM)).willReturn(Optional.of(set));

            SubmitRosterRequest r = req();
            r.getPlayers().get(0).setUniformSetId(ownSet);

            service.submitMyRoster(TID, MATCH_ID, HOME_REP, r);

            ArgumentCaptor<List<TournamentFixtureRosterEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(rosterRepository).saveAll(captor.capture());
            assertThat(captor.getValue().get(0).getUniformSetId()).isEqualTo(ownSet);
        }
    }

    // =========================================================
    // テンプレ適用（apply-template）— 登録番号/ユニフォーム/staff の複製
    // =========================================================

    @Nested
    @DisplayName("テンプレ適用（applyTemplate）")
    class ApplyTemplate {

        private final UUID templateId = UUID.randomUUID();

        private ApplyRosterTemplateRequest req(boolean overwrite, UUID defaultUniform) {
            ApplyRosterTemplateRequest r = new ApplyRosterTemplateRequest();
            r.setTemplateId(templateId);
            r.setOverwriteExisting(overwrite);
            r.setDefaultUniformSetId(defaultUniform);
            return r;
        }

        private TournamentEntryTemplateEntity template(Long teamId) {
            TournamentEntryTemplateEntity t = TournamentEntryTemplateEntity.builder()
                    .teamId(teamId).name("基本布陣").build();
            t.setId(templateId);
            return t;
        }

        private TournamentEntryTemplateMemberEntity templateMember() {
            return TournamentEntryTemplateMemberEntity.builder()
                    .templateId(templateId).userId(HOME_MEMBER)
                    .jerseyNumber(7).position("FW").registrationNumber("REG-777").build();
        }

        private TournamentEntryTemplateStaffEntity templateStaff() {
            return TournamentEntryTemplateStaffEntity.builder()
                    .templateId(templateId).role("コーチ").name("佐藤次郎").build();
        }

        @Test
        @DisplayName("自チーム ADMIN は登録番号・ベンチ役員を含めてテンプレを roster へ複製できる")
        void applyCopiesRegistrationAndStaff() {
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, HOME_TEAM))
                    .willReturn(Optional.of(template(HOME_TEAM)));
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(List.of(templateMember()));
            given(templateStaffRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(List.of(templateStaff()));
            given(rosterRepository.findByMatchIdAndParticipantId(MATCH_ID, HOME_PID)).willReturn(List.of());

            service.applyTemplate(TID, MATCH_ID, HOME_REP, req(false, null));

            ArgumentCaptor<List<TournamentFixtureRosterEntity>> rosterCaptor = ArgumentCaptor.forClass(List.class);
            verify(rosterRepository).saveAll(rosterCaptor.capture());
            TournamentFixtureRosterEntity r = rosterCaptor.getValue().get(0);
            assertThat(r.getRegistrationNumber()).isEqualTo("REG-777");
            assertThat(r.getJerseyNumber()).isEqualTo(7);
            assertThat(r.getParticipantId()).isEqualTo(HOME_PID);

            ArgumentCaptor<List<FixtureRosterStaffEntity>> staffCaptor = ArgumentCaptor.forClass(List.class);
            verify(staffRepository).saveAll(staffCaptor.capture());
            assertThat(staffCaptor.getValue().get(0).getName()).isEqualTo("佐藤次郎");
        }

        @Test
        @DisplayName("既定ユニフォームセットが自チームのものなら roster の uniform_set_id に複製される")
        void applyCopiesDefaultUniform() {
            UUID set = UUID.randomUUID();
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, HOME_TEAM))
                    .willReturn(Optional.of(template(HOME_TEAM)));
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(List.of(templateMember()));
            given(templateStaffRepository.findByTemplateIdOrderBySortOrderAsc(templateId)).willReturn(List.of());
            given(rosterRepository.findByMatchIdAndParticipantId(MATCH_ID, HOME_PID)).willReturn(List.of());
            given(uniformSetRepository.findByIdAndTeamId(set, HOME_TEAM))
                    .willReturn(Optional.of(TeamUniformSetEntity.builder().teamId(HOME_TEAM).build()));

            service.applyTemplate(TID, MATCH_ID, HOME_REP, req(false, set));

            ArgumentCaptor<List<TournamentFixtureRosterEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(rosterRepository).saveAll(captor.capture());
            assertThat(captor.getValue().get(0).getUniformSetId()).isEqualTo(set);
        }

        @Test
        @DisplayName("他チームのテンプレ（自チーム所有でない）を適用しようとすると 404（ENTRY_TEMPLATE_NOT_FOUND・IDOR）")
        void foreignTemplate404() {
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, HOME_TEAM))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.applyTemplate(TID, MATCH_ID, HOME_REP, req(false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND);
            verify(rosterRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("既存 roster があり overwriteExisting=false なら現状維持（複製しない）")
        void existingNoOverwriteKeeps() {
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, HOME_TEAM))
                    .willReturn(Optional.of(template(HOME_TEAM)));
            TournamentFixtureRosterEntity existing = TournamentFixtureRosterEntity.builder()
                    .matchId(MATCH_ID).participantId(HOME_PID).userId(HOME_MEMBER).build();
            given(rosterRepository.findByMatchIdAndParticipantId(MATCH_ID, HOME_PID)).willReturn(List.of(existing));

            service.applyTemplate(TID, MATCH_ID, HOME_REP, req(false, null));

            verify(rosterRepository, never()).deleteByMatchIdAndParticipantId(any(), any());
            verify(rosterRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("締切超過の試合へのテンプレ適用は 409（ROSTER_DEADLINE_PASSED）")
        void deadlinePassedRejectsApply() {
            matchWithDeadline(LocalDateTime.now().minusMinutes(1));
            given(accessControlService.isMember(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(HOME_REP, HOME_TEAM, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> service.applyTemplate(TID, MATCH_ID, HOME_REP, req(false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_DEADLINE_PASSED);
        }
    }

    // =========================================================
    // 主催者ビュー（GET rosters）— 認可
    // =========================================================

    @Nested
    @DisplayName("主催者ビュー（listAllRosters）")
    class ListAllRosters {

        @Test
        @DisplayName("主催組織 ADMIN は全対戦当事者チームの提出状況・内容を閲覧できる")
        void orgAdminCanView() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isSystemAdmin(ORG_ADMIN)).willReturn(false);
            given(accessControlService.isAdmin(ORG_ADMIN, ORG_ID, "ORGANIZATION")).willReturn(true);

            List<OrganizerRosterView> views = service.listAllRosters(TID, MATCH_ID, ORG_ADMIN);

            assertThat(views).hasSize(2);
            assertThat(views).extracting(OrganizerRosterView::participantId)
                    .containsExactlyInAnyOrder(HOME_PID, AWAY_PID);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN も閲覧できる")
        void systemAdminCanView() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isSystemAdmin(ORG_ADMIN)).willReturn(true);

            assertThat(service.listAllRosters(TID, MATCH_ID, ORG_ADMIN)).hasSize(2);
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザー（参加チーム代表含む）は 403（ROSTER_MANAGE_FORBIDDEN）で内容を読めない")
        void nonOrgAdminForbidden() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isSystemAdmin(HOME_REP)).willReturn(false);
            given(accessControlService.isAdmin(HOME_REP, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.listAllRosters(TID, MATCH_ID, HOME_REP))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_MANAGE_FORBIDDEN);
            // 認可で弾かれるため roster 内容の読み取り（情報開示）は発生しない
            verify(rosterRepository, never())
                    .findByMatchIdAndParticipantIdOrderByJerseyNumberAscIdAsc(anyLong(), anyLong());
        }

        @Test
        @DisplayName("未認証（userId=null）は 403（ROSTER_MANAGE_FORBIDDEN）")
        void anonymousForbidden() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));

            assertThatThrownBy(() -> service.listAllRosters(TID, MATCH_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_MANAGE_FORBIDDEN);
        }
    }

    // =========================================================
    // 締切設定（PATCH matches/{matchId}）— 認可・監査
    // =========================================================

    @Nested
    @DisplayName("締切設定（updateRosterDeadline）")
    class UpdateDeadline {

        private UpdateFixtureRosterDeadlineRequest req(LocalDateTime deadline) {
            UpdateFixtureRosterDeadlineRequest r = new UpdateFixtureRosterDeadlineRequest();
            r.setRosterDeadline(deadline);
            return r;
        }

        @Test
        @DisplayName("主催組織 ADMIN は締切を設定でき、監査ログが残る")
        void orgAdminCanSetDeadline() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isSystemAdmin(ORG_ADMIN)).willReturn(false);
            given(accessControlService.isAdmin(ORG_ADMIN, ORG_ID, "ORGANIZATION")).willReturn(true);

            LocalDateTime dl = LocalDateTime.now().plusDays(3);
            service.updateRosterDeadline(TID, MATCH_ID, ORG_ADMIN, req(dl));

            ArgumentCaptor<TournamentFixtureEntity> captor = ArgumentCaptor.forClass(TournamentFixtureEntity.class);
            verify(matchRepository).save(captor.capture());
            assertThat(captor.getValue().getRosterDeadline()).isEqualTo(dl);
            verify(auditLogService).record(eq(AuditEventType.TOURNAMENT_ROSTER_DEADLINE_UPDATED.name()),
                    eq(ORG_ADMIN), any(), any(), eq(ORG_ID), any(), any(), any(), any());
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは締切を設定できず 403（ROSTER_MANAGE_FORBIDDEN）")
        void nonOrgAdminForbidden() {
            given(tournamentRepository.findById(TID)).willReturn(Optional.of(tournament()));
            given(accessControlService.isSystemAdmin(HOME_REP)).willReturn(false);
            given(accessControlService.isAdmin(HOME_REP, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.updateRosterDeadline(TID, MATCH_ID, HOME_REP, req(LocalDateTime.now())))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.ROSTER_MANAGE_FORBIDDEN);
            verify(matchRepository, never()).save(any());
        }
    }
}
