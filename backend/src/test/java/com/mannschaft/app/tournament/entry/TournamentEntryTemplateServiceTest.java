package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.ApplyTemplateResponse;
import com.mannschaft.app.tournament.entry.dto.CreateEntryTemplateRequest;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateDetailResponse;
import com.mannschaft.app.tournament.entry.dto.EntryTemplateResponse;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentEntryTemplateService} の単体テスト。
 *
 * <p>F08.7 Phase 9-B 設計書 §エントリーテンプレート管理 に準拠。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentEntryTemplateService 単体テスト")
class TournamentEntryTemplateServiceTest {

    @Mock
    private TournamentEntryTemplateRepository templateRepository;
    @Mock
    private TournamentEntryTemplateMemberRepository templateMemberRepository;
    @Mock
    private TournamentEntryMemberRepository entryMemberRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private MemberQueryDispatcher memberQueryDispatcher;
    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentEntryTemplateService service;

    private static final Long ORG_ID = 1L;
    private static final Long TEAM_ID = 400L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long PARTICIPANT_ID = 300L;
    private static final Long USER_ID = 10L;

    @BeforeEach
    void bypassAuthorization() {
        // 認可（scope 権限）は API 契約テスト TournamentEntryScopeContractIT で担保する。
        // 本 UT は業務ロジックの検証が目的のため SYSTEM_ADMIN 相当で素通りさせる。
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(true);
    }

    // =========================================================
    // テストフィクスチャ生成ヘルパー
    // =========================================================

    private TeamOrgMembershipEntity buildActiveMembership() {
        return TeamOrgMembershipEntity.builder()
                .teamId(TEAM_ID)
                .organizationId(ORG_ID)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(java.time.LocalDateTime.now())
                .build();
    }

    private TournamentEntryTemplateEntity buildTemplate(Long teamId, String name) {
        return TournamentEntryTemplateEntity.builder()
                .teamId(teamId)
                .name(name)
                .sortOrder((short) 0)
                .build();
    }

    private TournamentEntity buildTournament(TournamentStatus status) {
        TournamentEntity entity = TournamentEntity.builder()
                .organizationId(ORG_ID)
                .name("テスト大会")
                .build();
        try {
            var field = TournamentEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(entity, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    private TournamentParticipantEntity buildParticipant(Long teamId) {
        return TournamentParticipantEntity.builder()
                .teamId(teamId)
                .divisionId(DIVISION_ID)
                .build();
    }

    private void setupIDORMocksForApply(TournamentEntity tournament) {
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
        given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                .willReturn(Optional.of(TournamentDivisionEntity.builder()
                        .tournamentId(TOURNAMENT_ID).name("A部門").build()));
        given(participantRepository.findById(PARTICIPANT_ID))
                .willReturn(Optional.of(buildParticipant(TEAM_ID)));
    }

    private void setupTeamOrgMocks() {
        given(teamOrgMembershipRepository.findByTeamIdAndOrganizationId(TEAM_ID, ORG_ID))
                .willReturn(Optional.of(buildActiveMembership()));
    }

    // =========================================================
    // getTemplates
    // =========================================================

    @Nested
    @DisplayName("getTemplates")
    class GetTemplates {

        @Test
        @DisplayName("正常系: テンプレート一覧を取得できる（5件以下）")
        void テンプレート一覧を取得できる() {
            // given
            setupTeamOrgMocks();
            List<TournamentEntryTemplateEntity> templates = List.of(
                    buildTemplate(TEAM_ID, "テンプレート1"),
                    buildTemplate(TEAM_ID, "テンプレート2"),
                    buildTemplate(TEAM_ID, "テンプレート3")
            );
            given(templateRepository.findByTeamIdAndDeletedAtIsNullOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(templates);
            given(templateMemberRepository.countByTemplateId(any())).willReturn(5L);

            // when
            List<EntryTemplateResponse> result = service.getTemplates(ORG_ID, TEAM_ID, USER_ID);

            // then
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getMemberCount()).isEqualTo(5L);
        }
    }

    // =========================================================
    // getTemplate
    // =========================================================

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("正常系: テンプレート詳細を選手一覧付きで取得できる")
        void テンプレート詳細を選手一覧付きで取得できる() {
            // given
            setupTeamOrgMocks();
            UUID templateId = UUID.randomUUID();
            TournamentEntryTemplateEntity template = buildTemplate(TEAM_ID, "詳細テンプレート");
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, TEAM_ID))
                    .willReturn(Optional.of(template));

            TournamentEntryTemplateMemberEntity member = TournamentEntryTemplateMemberEntity.builder()
                    .templateId(templateId)
                    .userId(USER_ID)
                    .sortOrder((short) 0)
                    .build();
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(List.of(member));
            given(userRepository.findMemberSummaryById(USER_ID)).willReturn(Optional.empty());

            // when
            EntryTemplateDetailResponse result = service.getTemplate(ORG_ID, TEAM_ID, templateId, USER_ID);

            // then
            assertThat(result.getName()).isEqualTo("詳細テンプレート");
            assertThat(result.getMembers()).hasSize(1);
        }
    }

    // =========================================================
    // createTemplate
    // =========================================================

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートを作成できる（4件目）")
        void テンプレートを作成できる() {
            // given
            setupTeamOrgMocks();
            // 現在3件 → 4件目を作成
            given(templateRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(3L);

            TournamentEntryTemplateEntity saved = buildTemplate(TEAM_ID, "新テンプレート");
            given(templateRepository.save(any())).willReturn(saved);
            given(templateMemberRepository.saveAll(any())).willReturn(List.of());
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(any()))
                    .willReturn(List.of());

            CreateEntryTemplateRequest req = CreateEntryTemplateRequest.builder()
                    .name("新テンプレート")
                    .sortOrder((short) 3)
                    .members(List.of())
                    .build();

            // when
            EntryTemplateDetailResponse result = service.createTemplate(ORG_ID, TEAM_ID, req, USER_ID);

            // then
            assertThat(result.getName()).isEqualTo("新テンプレート");
        }

        @Test
        @DisplayName("異常系: 6件目の作成 → TOUR_025")
        void 上限超過の作成はエラー() {
            // given
            setupTeamOrgMocks();
            // 現在5件（上限）
            given(templateRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(5L);

            CreateEntryTemplateRequest req = CreateEntryTemplateRequest.builder()
                    .name("6件目テンプレート")
                    .members(List.of())
                    .build();

            // when & then
            assertThatThrownBy(() -> service.createTemplate(ORG_ID, TEAM_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MAX_TEMPLATE_COUNT_EXCEEDED);
        }
    }

    // =========================================================
    // deleteTemplate
    // =========================================================

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("正常系: テンプレートを論理削除できる（deletedAt が設定される）")
        void テンプレートを論理削除できる() {
            // given
            setupTeamOrgMocks();
            UUID templateId = UUID.randomUUID();
            TournamentEntryTemplateEntity template = buildTemplate(TEAM_ID, "削除テンプレート");
            given(templateRepository.findByIdAndTeamIdAndDeletedAtIsNull(templateId, TEAM_ID))
                    .willReturn(Optional.of(template));
            given(templateRepository.save(any())).willReturn(template);

            // when
            service.deleteTemplate(ORG_ID, TEAM_ID, templateId, USER_ID);

            // then: softDelete() が呼ばれた後に save() が呼ばれていること
            verify(templateRepository).save(template);
            assertThat(template.getDeletedAt()).isNotNull();
        }
    }

    // =========================================================
    // applyTemplate
    // =========================================================

    @Nested
    @DisplayName("applyTemplate")
    class ApplyTemplate {

        @Test
        @DisplayName("正常系: apply-template で正常適用できる（applied=18, skipped=2）")
        void applyTemplateで正常適用できる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            TournamentEntryTemplateEntity template = buildTemplate(TEAM_ID, "適用テンプレート");
            given(templateRepository.findById(templateId)).willReturn(Optional.of(template));

            // テンプレートメンバー: 20名
            List<TournamentEntryTemplateMemberEntity> templateMembers = new java.util.ArrayList<>();
            for (long i = 1; i <= 20; i++) {
                templateMembers.add(TournamentEntryTemplateMemberEntity.builder()
                        .templateId(templateId).userId(i).sortOrder((short) 0).build());
            }
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(templateMembers);

            // アクティブメンバー: 20名全員
            List<MemberDto> activeMembers = new java.util.ArrayList<>();
            for (long i = 1; i <= 20; i++) {
                activeMembers.add(new MemberDto(i, "user" + i, null, null, null));
            }
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);

            // 既存エントリー: 2名（id=1,2）
            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID))
                    .willReturn(Set.of(1L, 2L));
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .overwriteExisting(false)
                    .build();

            // when
            ApplyTemplateResponse result = service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then
            assertThat(result.getApplied()).isEqualTo(18);
            assertThat(result.getSkipped()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: apply-template は冪等（同じテンプレートを2回適用してもエントリーが重複しない）")
        void applyTemplateは冪等() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            TournamentEntryTemplateEntity template = buildTemplate(TEAM_ID, "冪等テンプレート");
            given(templateRepository.findById(templateId)).willReturn(Optional.of(template));

            TournamentEntryTemplateMemberEntity member = TournamentEntryTemplateMemberEntity.builder()
                    .templateId(templateId).userId(USER_ID).sortOrder((short) 0).build();
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(List.of(member));

            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(List.of(new MemberDto(USER_ID, "テストユーザー", null, null, null)));

            // 既存エントリーに USER_ID が既に存在 → 2回目は全てskipped
            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID))
                    .willReturn(Set.of(USER_ID));
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .overwriteExisting(false)
                    .build();

            // when（2回目適用をシミュレート）
            ApplyTemplateResponse result = service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then: applied=0 (重複なし), skipped=1
            assertThat(result.getApplied()).isEqualTo(0);
            assertThat(result.getSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: 存在しないテンプレートの apply → TOUR_024")
        void 存在しないテンプレートのapply() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            given(templateRepository.findById(templateId)).willReturn(Optional.empty());

            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .build();

            // when & then
            assertThatThrownBy(() -> service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.ENTRY_TEMPLATE_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 別チームのテンプレートを apply → TOUR_028")
        void 別チームのテンプレートをapplyはエラー() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            // テンプレートは別チーム（TEAM_ID + 1）のもの
            TournamentEntryTemplateEntity wrongTeamTemplate = buildTemplate(TEAM_ID + 1L, "他チームテンプレート");
            given(templateRepository.findById(templateId)).willReturn(Optional.of(wrongTeamTemplate));

            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .build();

            // when & then
            assertThatThrownBy(() -> service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TEMPLATE_TEAM_MISMATCH);
        }

        @Test
        @DisplayName("異常系: COMPLETED 大会への apply → ENTRY_LOCKED (TOUR_020)")
        void COMPLETED大会へのapplyはエラー() {
            // given: IN_PROGRESS（管理者フラグなし）でロック確認
            TournamentEntity tournament = buildTournament(TournamentStatus.IN_PROGRESS);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .build();

            // when & then
            assertThatThrownBy(() -> service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.ENTRY_LOCKED);
        }

        @Test
        @DisplayName("正常系: テンプレート内の非アクティブメンバーは skippedInactive にカウントされスキップ")
        void 非アクティブメンバーはskippedInactiveにカウントされる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocksForApply(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID templateId = UUID.randomUUID();
            TournamentEntryTemplateEntity template = buildTemplate(TEAM_ID, "テンプレート");
            given(templateRepository.findById(templateId)).willReturn(Optional.of(template));

            // テンプレートには2名（USER_ID と 999L）
            List<TournamentEntryTemplateMemberEntity> templateMembers = List.of(
                    TournamentEntryTemplateMemberEntity.builder()
                            .templateId(templateId).userId(USER_ID).sortOrder((short) 0).build(),
                    TournamentEntryTemplateMemberEntity.builder()
                            .templateId(templateId).userId(999L).sortOrder((short) 1).build()
            );
            given(templateMemberRepository.findByTemplateIdOrderBySortOrderAsc(templateId))
                    .willReturn(templateMembers);

            // アクティブメンバーは USER_ID のみ（999L は非アクティブ）
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(List.of(new MemberDto(USER_ID, "アクティブユーザー", null, null, null)));

            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID))
                    .willReturn(Set.of());
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            ApplyTemplateRequest req = ApplyTemplateRequest.builder()
                    .templateId(templateId)
                    .build();

            // when
            ApplyTemplateResponse result = service.applyTemplate(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then
            assertThat(result.getApplied()).isEqualTo(1);
            assertThat(result.getSkippedInactive()).isEqualTo(1);
        }
    }
}
