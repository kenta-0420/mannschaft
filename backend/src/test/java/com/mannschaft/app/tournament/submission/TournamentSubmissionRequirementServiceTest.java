package com.mannschaft.app.tournament.submission;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.SubmissionStatus;
import com.mannschaft.app.forms.dto.CreateFormSubmissionRequest;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.entity.FormSubmissionEntity;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.service.FormSubmissionService;
import com.mannschaft.app.forms.service.FormTemplateService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.TournamentFeeService;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.submission.dto.CreateSubmissionRequirementRequest;
import com.mannschaft.app.tournament.submission.dto.SubmissionRequirementResponse;
import com.mannschaft.app.tournament.submission.dto.SubmissionStatusDashboardResponse;
import com.mannschaft.app.tournament.submission.dto.UpdateSubmissionRequirementRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentSubmissionRequirementService} の単体テスト（test-first）。
 *
 * <p>F08.7.1/06 大会ごとの書類提出受付 設計書に準拠。汎用の提出／承認エンジンは新設せず F05.6 を再利用する
 * ファサードの「提出枠の連結作成・一覧の段階開示・更新・削除・提出状況ダッシュボード・自チーム提出の委譲・
 * 締切超過・requires_payment ゲート・連結（form_submission ↔ requirement(UUID)）・認可（提出=自チーム /
 * 受理・状況=主催者 / 他チーム提出閲覧不可）」を red→green で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentSubmissionRequirementService 単体テスト")
class TournamentSubmissionRequirementServiceTest {

    @Mock
    private TournamentSubmissionRequirementRepository requirementRepository;
    @Mock
    private TournamentSubmissionRequirementTargetRepository targetRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private FormTemplateService formTemplateService;
    @Mock
    private FormSubmissionService formSubmissionService;
    @Mock
    private FormSubmissionRepository formSubmissionRepository;
    @Mock
    private TournamentFeeService tournamentFeeService;

    @InjectMocks
    private TournamentSubmissionRequirementService service;

    private static final Long ORG_ID = 1L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long FORM_TEMPLATE_ID = 300L;
    private static final Long TEAM_ID = 400L;
    private static final Long OTHER_TEAM_ID = 401L;
    private static final Long ORG_ADMIN_ID = 10L;
    private static final Long TEAM_REP_ID = 20L;
    private static final Long OUTSIDER_ID = 30L;

    // =========================================================
    // フィクスチャ
    // =========================================================

    private TournamentEntity tournament(Long orgId) {
        return TournamentEntity.builder().organizationId(orgId).name("テスト大会").build();
    }

    private TournamentDivisionEntity division(Long tournamentId) {
        return TournamentDivisionEntity.builder().tournamentId(tournamentId).name("1部").build();
    }

    private FormTemplateEntity orgTemplate(Long orgId) {
        return FormTemplateEntity.builder()
                .scopeType("ORGANIZATION").scopeId(orgId).name("参加申込書").build();
    }

    private TournamentSubmissionRequirementEntity requirement(SubmissionTargetScope scope, boolean requiresPayment,
                                                              LocalDateTime deadline, Long divisionId) {
        TournamentSubmissionRequirementEntity r = TournamentSubmissionRequirementEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .divisionId(divisionId)
                .formTemplateId(FORM_TEMPLATE_ID)
                .title("参加申込書")
                .targetScope(scope)
                .requiresPayment(requiresPayment)
                .deadline(deadline)
                .organizationId(ORG_ID)
                .createdBy(ORG_ADMIN_ID)
                .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    private CreateSubmissionRequirementRequest createReq(String scope, Long divisionId, Boolean requiresPayment,
                                                         List<Long> teamIds) {
        return new CreateSubmissionRequirementRequest(FORM_TEMPLATE_ID, "参加申込書", "補足",
                divisionId, LocalDateTime.now().plusDays(7), scope, requiresPayment, teamIds);
    }

    private FormSubmissionEntity submission(Long teamId, SubmissionStatus status, LocalDateTime createdAt) {
        FormSubmissionEntity s = FormSubmissionEntity.builder()
                .templateId(FORM_TEMPLATE_ID)
                .scopeType("TEAM")
                .scopeId(teamId)
                .status(status)
                .submittedBy(TEAM_REP_ID)
                .submissionCountForUser(1)
                .build();
        setBaseId(s, teamId);  // 任意の非 null id
        setCreatedAt(s, createdAt);
        return s;
    }

    private void setBaseId(FormSubmissionEntity entity, Long id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setCreatedAt(FormSubmissionEntity entity, LocalDateTime createdAt) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // =========================================================
    // 提出枠の定義
    // =========================================================

    @Nested
    @DisplayName("提出枠の定義（createRequirement）")
    class CreateRequirement {

        @Test
        @DisplayName("主催組織 ADMIN は form_template を大会に連結して提出枠を定義できる")
        void createByOrgAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(ORG_ADMIN_ID)).willReturn(false);
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(formTemplateService.getTemplateEntity(FORM_TEMPLATE_ID)).willReturn(orgTemplate(ORG_ID));
            given(requirementRepository.save(any(TournamentSubmissionRequirementEntity.class))).willAnswer(inv -> {
                TournamentSubmissionRequirementEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(UUID.randomUUID());
                return e;
            });

            SubmissionRequirementResponse res = service.createRequirement(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, false, null));

            assertThat(res.formTemplateId()).isEqualTo(FORM_TEMPLATE_ID);
            assertThat(res.targetScope()).isEqualTo("ALL_TEAMS");
            assertThat(res.requiresPayment()).isFalse();

            ArgumentCaptor<TournamentSubmissionRequirementEntity> captor =
                    ArgumentCaptor.forClass(TournamentSubmissionRequirementEntity.class);
            verify(requirementRepository).save(captor.capture());
            assertThat(captor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(captor.getValue().getCreatedBy()).isEqualTo(ORG_ADMIN_ID);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS では対象チーム明細を保存する（重複排除）")
        void createSpecificTeams() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(formTemplateService.getTemplateEntity(FORM_TEMPLATE_ID)).willReturn(orgTemplate(ORG_ID));
            given(requirementRepository.save(any(TournamentSubmissionRequirementEntity.class))).willAnswer(inv -> {
                TournamentSubmissionRequirementEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            SubmissionRequirementResponse res = service.createRequirement(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("SPECIFIC_TEAMS", null, false, List.of(TEAM_ID, TEAM_ID, OTHER_TEAM_ID)));

            assertThat(res.targetTeamIds()).containsExactlyInAnyOrder(TEAM_ID, OTHER_TEAM_ID);
            verify(targetRepository, Mockito.times(2))
                    .save(any(TournamentSubmissionRequirementTargetEntity.class));
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは定義できず 403（SUBMISSION_REQ_MANAGE_FORBIDDEN）")
        void createForbiddenForNonAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.createRequirement(ORG_ID, TOURNAMENT_ID, OUTSIDER_ID,
                    createReq("ALL_TEAMS", null, false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
            verify(requirementRepository, never()).save(any());
        }

        @Test
        @DisplayName("他組織の大会を指定すると 404（TOURNAMENT_NOT_FOUND・IDOR 対策）")
        void createCrossOrgTournament404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(999L)));

            assertThatThrownBy(() -> service.createRequirement(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("form_template が他組織所属/別スコープなら 422（SUBMISSION_TEMPLATE_SCOPE_MISMATCH）")
        void createTemplateScopeMismatch() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(formTemplateService.getTemplateEntity(FORM_TEMPLATE_ID)).willReturn(orgTemplate(999L));

            assertThatThrownBy(() -> service.createRequirement(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_TEMPLATE_SCOPE_MISMATCH);
            verify(requirementRepository, never()).save(any());
        }

        @Test
        @DisplayName("指定ディビジョンが当該大会配下でないと 404（DIVISION_NOT_FOUND）")
        void createDivisionMismatch404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division(999L)));

            assertThatThrownBy(() -> service.createRequirement(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", DIVISION_ID, false, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.DIVISION_NOT_FOUND);
        }
    }

    // =========================================================
    // 提出枠一覧（段階開示）
    // =========================================================

    @Nested
    @DisplayName("提出枠一覧（段階開示）")
    class ListRequirements {

        @Test
        @DisplayName("主催組織 ADMIN は全件を取得できる")
        void listForOrganizer() {
            TournamentSubmissionRequirementEntity r = requirement(SubmissionTargetScope.ALL_TEAMS, false, null, null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(requirementRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID))
                    .willReturn(List.of(r));

            List<SubmissionRequirementResponse> res =
                    service.listRequirementsForOrganizer(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).formTemplateId()).isEqualTo(FORM_TEMPLATE_ID);
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは 403（SUBMISSION_REQ_MANAGE_FORBIDDEN）で一覧できない")
        void listForOrganizerForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.listRequirementsForOrganizer(ORG_ID, TOURNAMENT_ID, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
            verify(requirementRepository, never()).findByTournamentIdOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("参加チーム代表は自チームが対象の枠のみ取得し、他チーム限定の枠は除外される（情報開示の最小化）")
        void listForTeamOnlyTargeted() {
            TournamentSubmissionRequirementEntity allTeams =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, null, null);
            TournamentSubmissionRequirementEntity specMine =
                    requirement(SubmissionTargetScope.SPECIFIC_TEAMS, false, null, null);
            TournamentSubmissionRequirementEntity specOther =
                    requirement(SubmissionTargetScope.SPECIFIC_TEAMS, false, null, null);

            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(requirementRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID))
                    .willReturn(List.of(allTeams, specMine, specOther));
            given(targetRepository.existsByRequirementIdAndTeamId(specMine.getId(), TEAM_ID)).willReturn(true);
            given(targetRepository.existsByRequirementIdAndTeamId(specOther.getId(), TEAM_ID)).willReturn(false);

            List<SubmissionRequirementResponse> res =
                    service.listRequirementsForTeam(ORG_ID, TOURNAMENT_ID, TEAM_ID, TEAM_REP_ID);

            // ALL_TEAMS（全チーム対象）＋自チーム指定の SPECIFIC のみ。他チーム限定は出ない。
            assertThat(res).extracting(SubmissionRequirementResponse::id)
                    .containsExactlyInAnyOrder(allTeams.getId(), specMine.getId());
        }

        @Test
        @DisplayName("チーム代表でないユーザーは 403（SUBMISSION_REQ_VIEW_FORBIDDEN）で自チーム枠も取得不可")
        void listForTeamForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.listRequirementsForTeam(ORG_ID, TOURNAMENT_ID, TEAM_ID, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_VIEW_FORBIDDEN);
            verify(requirementRepository, never()).findByTournamentIdOrderByCreatedAtAsc(any());
        }
    }

    // =========================================================
    // 自チーム分の提出（submitForTeam）
    // =========================================================

    @Nested
    @DisplayName("自チーム分の提出（submitForTeam）")
    class SubmitForTeam {

        private CreateFormSubmissionRequest submitReq() {
            return new CreateFormSubmissionRequest(FORM_TEMPLATE_ID, true, List.of());
        }

        @Test
        @DisplayName("自チーム代表（ADMIN/DEPUTY）は F05.6 の提出作成へ委譲される（requirement と連結）")
        void submitByTeamRep() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, LocalDateTime.now().plusDays(3), null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            FormSubmissionResponse expected = FormSubmissionResponse.builder().id(999L).status("SUBMITTED").build();
            given(formSubmissionService.createSubmissionForRequirement(
                    eq("TEAM"), eq(TEAM_ID), eq(TEAM_REP_ID), eq(r.getId()), any()))
                    .willReturn(expected);

            FormSubmissionResponse res = service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, submitReq());

            assertThat(res).isSameAs(expected);
            verify(formSubmissionService).createSubmissionForRequirement(
                    eq("TEAM"), eq(TEAM_ID), eq(TEAM_REP_ID), eq(r.getId()), any());
        }

        @Test
        @DisplayName("チーム代表でないユーザーは 403（SUBMISSION_SUBMIT_FORBIDDEN）で委譲されない")
        void submitForbiddenForNonRep() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, null, null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, OUTSIDER_ID, submitReq()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.SUBMISSION_SUBMIT_FORBIDDEN);
            verifyNoInteractions(formSubmissionService);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS で対象外チームの提出は 403（SUBMISSION_TEAM_NOT_TARGET）")
        void submitTeamNotTarget() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.SPECIFIC_TEAMS, false, null, null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(targetRepository.existsByRequirementIdAndTeamId(r.getId(), TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, submitReq()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.SUBMISSION_TEAM_NOT_TARGET);
            verifyNoInteractions(formSubmissionService);
        }

        @Test
        @DisplayName("締切超過の提出枠への提出は 403/422（SUBMISSION_DEADLINE_PASSED）")
        void submitDeadlinePassed() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, LocalDateTime.now().minusDays(1), null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, submitReq()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.SUBMISSION_DEADLINE_PASSED);
            verifyNoInteractions(formSubmissionService);
        }

        @Test
        @DisplayName("requires_payment=TRUE かつ未払いの提出は 403/422（SUBMISSION_PAYMENT_REQUIRED）でブロック")
        void submitPaymentRequiredUnpaid() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, true, LocalDateTime.now().plusDays(3), DIVISION_ID);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(tournamentFeeService.isTeamPaidForTournament(TOURNAMENT_ID, DIVISION_ID, TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, submitReq()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.SUBMISSION_PAYMENT_REQUIRED);
            verifyNoInteractions(formSubmissionService);
        }

        @Test
        @DisplayName("requires_payment=TRUE かつ支払い済みなら提出は委譲される")
        void submitPaymentRequiredPaid() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, true, LocalDateTime.now().plusDays(3), null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(tournamentFeeService.isTeamPaidForTournament(TOURNAMENT_ID, null, TEAM_ID)).willReturn(true);
            FormSubmissionResponse expected = FormSubmissionResponse.builder().id(1L).status("SUBMITTED").build();
            given(formSubmissionService.createSubmissionForRequirement(
                    eq("TEAM"), eq(TEAM_ID), eq(TEAM_REP_ID), eq(r.getId()), any())).willReturn(expected);

            FormSubmissionResponse res = service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, submitReq());

            assertThat(res).isSameAs(expected);
        }

        @Test
        @DisplayName("提出枠の form_template と提出 template_id が不一致なら 422（SUBMISSION_TEMPLATE_SCOPE_MISMATCH）")
        void submitTemplateMismatch() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, LocalDateTime.now().plusDays(3), null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);

            CreateFormSubmissionRequest wrongTemplate =
                    new CreateFormSubmissionRequest(99999L, true, List.of());

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, r.getId(), TEAM_ID, TEAM_REP_ID, wrongTemplate))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_TEMPLATE_SCOPE_MISMATCH);
            verifyNoInteractions(formSubmissionService);
        }

        @Test
        @DisplayName("存在しない / 他組織の提出枠は 404（SUBMISSION_REQ_NOT_FOUND）")
        void submitRequirementNotFound() {
            UUID reqId = UUID.randomUUID();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(requirementRepository.findByIdAndOrganizationId(reqId, ORG_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitForTeam(
                    ORG_ID, TOURNAMENT_ID, reqId, TEAM_ID, TEAM_REP_ID, submitReq()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.SUBMISSION_REQ_NOT_FOUND);
        }
    }

    // =========================================================
    // 提出状況ダッシュボード
    // =========================================================

    @Nested
    @DisplayName("提出状況ダッシュボード（getStatusDashboard）")
    class StatusDashboard {

        @Test
        @DisplayName("ALL_TEAMS は参加チーム母集団に対し 未提出/提出済/受理/差戻し を集計する")
        void dashboardAllTeams() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, LocalDateTime.now().minusDays(1), null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));
            // 参加チーム母集団: 400(提出済), 401(受理), 402(未提出), 403(差戻し)
            given(participantRepository.findDistinctParticipantTeamIdsByTournamentId(TOURNAMENT_ID))
                    .willReturn(List.of(400L, 401L, 402L, 403L));
            given(formSubmissionRepository.findByTournamentSubmissionRequirementId(r.getId()))
                    .willReturn(List.of(
                            submission(400L, SubmissionStatus.SUBMITTED, LocalDateTime.now().minusHours(2)),
                            submission(401L, SubmissionStatus.APPROVED, LocalDateTime.now().minusHours(3)),
                            submission(403L, SubmissionStatus.RETURNED, LocalDateTime.now().minusHours(1))));

            SubmissionStatusDashboardResponse res =
                    service.getStatusDashboard(ORG_ID, TOURNAMENT_ID, r.getId(), ORG_ADMIN_ID);

            assertThat(res.totalTargets()).isEqualTo(4);
            assertThat(res.submitted()).isEqualTo(1);
            assertThat(res.approved()).isEqualTo(1);
            assertThat(res.returned()).isEqualTo(1);
            assertThat(res.notSubmitted()).isEqualTo(1);
            assertThat(res.deadlinePassed()).isTrue();
            assertThat(res.teams()).extracting(SubmissionStatusDashboardResponse.TeamSubmissionStatus::teamId)
                    .containsExactlyInAnyOrder(400L, 401L, 402L, 403L);
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは 403（SUBMISSION_REQ_MANAGE_FORBIDDEN）で状況を見られない")
        void dashboardForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getStatusDashboard(
                    ORG_ID, TOURNAMENT_ID, UUID.randomUUID(), OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
            verifyNoInteractions(formSubmissionRepository);
        }
    }

    // =========================================================
    // 削除
    // =========================================================

    @Nested
    @DisplayName("提出枠の削除（deleteRequirement）")
    class DeleteRequirement {

        @Test
        @DisplayName("主催組織 ADMIN は論理削除でき、対象チーム明細も削除される")
        void deleteByOrgAdmin() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.SPECIFIC_TEAMS, false, null, null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));

            service.deleteRequirement(ORG_ID, TOURNAMENT_ID, r.getId(), ORG_ADMIN_ID);

            assertThat(r.getDeletedAt()).isNotNull();
            verify(targetRepository).deleteByRequirementId(r.getId());
            verify(requirementRepository).save(r);
        }

        @Test
        @DisplayName("非 ADMIN の削除は 403（SUBMISSION_REQ_MANAGE_FORBIDDEN）")
        void deleteForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.deleteRequirement(
                    ORG_ID, TOURNAMENT_ID, UUID.randomUUID(), OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
        }
    }

    // =========================================================
    // targetScope バリデーション（@Pattern で不正値を 400 に倒す）
    // =========================================================

    @Nested
    @DisplayName("targetScope バリデーション")
    class TargetScopeValidation {

        private Set<ConstraintViolation<CreateSubmissionRequirementRequest>> validate(String scope) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                return validator.validate(new CreateSubmissionRequirementRequest(
                        FORM_TEMPLATE_ID, "参加申込書", null, null,
                        LocalDateTime.now().plusDays(7), scope, false, null));
            }
        }

        @Test
        @DisplayName("不正な targetScope はバリデーションエラー（→ 400 / 500 化しない）")
        void invalidScopeRejected() {
            assertThat(validate("INVALID_SCOPE"))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("targetScope"));
        }

        @Test
        @DisplayName("ALL_TEAMS / SPECIFIC_TEAMS / NULL は許容される")
        void validScopesAccepted() {
            assertThat(validate("ALL_TEAMS")).noneMatch(scopeViolation());
            assertThat(validate("SPECIFIC_TEAMS")).noneMatch(scopeViolation());
            assertThat(validate(null)).noneMatch(scopeViolation());
        }

        private java.util.function.Predicate<ConstraintViolation<CreateSubmissionRequirementRequest>> scopeViolation() {
            return v -> v.getPropertyPath().toString().equals("targetScope");
        }
    }

    // =========================================================
    // 更新
    // =========================================================

    @Nested
    @DisplayName("提出枠の更新（updateRequirement）")
    class UpdateRequirement {

        @Test
        @DisplayName("主催組織 ADMIN は締切・支払い条件を更新できる")
        void updateByOrgAdmin() {
            TournamentSubmissionRequirementEntity r =
                    requirement(SubmissionTargetScope.ALL_TEAMS, false, null, null);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(requirementRepository.findByIdAndOrganizationId(r.getId(), ORG_ID)).willReturn(Optional.of(r));

            LocalDateTime newDeadline = LocalDateTime.now().plusDays(14);
            UpdateSubmissionRequirementRequest req = new UpdateSubmissionRequirementRequest(
                    "選手登録一覧", "更新", null, newDeadline, null, true, null);

            SubmissionRequirementResponse res =
                    service.updateRequirement(ORG_ID, TOURNAMENT_ID, r.getId(), ORG_ADMIN_ID, req);

            assertThat(res.title()).isEqualTo("選手登録一覧");
            assertThat(res.requiresPayment()).isTrue();
            assertThat(res.deadline()).isEqualTo(newDeadline);
            verify(requirementRepository).save(r);
        }

        @Test
        @DisplayName("非 ADMIN の更新は 403（SUBMISSION_REQ_MANAGE_FORBIDDEN）")
        void updateForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            UpdateSubmissionRequirementRequest req = new UpdateSubmissionRequirementRequest(
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateRequirement(
                    ORG_ID, TOURNAMENT_ID, UUID.randomUUID(), OUTSIDER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.SUBMISSION_REQ_MANAGE_FORBIDDEN);
        }
    }
}
