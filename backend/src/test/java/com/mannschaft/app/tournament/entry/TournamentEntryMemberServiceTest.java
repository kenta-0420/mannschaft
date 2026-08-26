package com.mannschaft.app.tournament.entry;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.entry.dto.EntryLoadResponse;
import com.mannschaft.app.tournament.entry.dto.EntryMemberListResponse;
import com.mannschaft.app.tournament.entry.dto.LoadFromTeamRequest;
import com.mannschaft.app.tournament.entry.dto.UpsertEntryMembersRequest;
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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentEntryMemberService} の単体テスト。
 *
 * <p>F08.7 Phase 9 設計書 §エントリー表メンバー管理 に準拠。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentEntryMemberService 単体テスト")
class TournamentEntryMemberServiceTest {

    @Mock
    private TournamentEntryMemberRepository entryMemberRepository;
    @Mock
    private TournamentEntryTemplateRepository templateRepository;
    @Mock
    private TournamentEntryTemplateMemberRepository templateMemberRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private MemberQueryDispatcher memberQueryDispatcher;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PdfGeneratorService pdfGeneratorService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentEntryMemberService service;

    private static final Long ORG_ID = 1L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long PARTICIPANT_ID = 300L;
    private static final Long TEAM_ID = 400L;
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

    private TournamentDivisionEntity buildDivision(Integer minCount, Integer maxCount) {
        return TournamentDivisionEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .name("A部門")
                .minEntryCount(minCount)
                .maxEntryCount(maxCount)
                .build();
    }

    private TournamentParticipantEntity buildParticipant() {
        return TournamentParticipantEntity.builder()
                .teamId(TEAM_ID)
                .divisionId(DIVISION_ID)
                .build();
    }

    private TournamentEntryMemberEntity buildEntryMember(Long userId) {
        return TournamentEntryMemberEntity.builder()
                .participantId(PARTICIPANT_ID)
                .userId(userId)
                .sortOrder((short) 0)
                .build();
    }

    private void setupIDORMocks(TournamentEntity tournament) {
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
        given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                .willReturn(Optional.of(buildDivision(null, null)));
        TournamentParticipantEntity participant = buildParticipant();
        given(participantRepository.findById(PARTICIPANT_ID)).willReturn(Optional.of(participant));
    }

    // =========================================================
    // getEntryMembers
    // =========================================================

    @Nested
    @DisplayName("getEntryMembers")
    class GetEntryMembers {

        @Test
        @DisplayName("正常系: includeTeamMembers=false の場合 teamMemberCandidates は null")
        void エントリー一覧を取得できる_候補なし() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            TournamentDivisionEntity division = buildDivision(1, 10);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));

            TournamentEntryMemberEntity member = buildEntryMember(USER_ID);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of(member));
            given(userRepository.findMemberSummaryById(USER_ID)).willReturn(Optional.empty());

            // when
            EntryMemberListResponse result = service.getEntryMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, false, USER_ID);

            // then
            assertThat(result.getEntryMembers()).hasSize(1);
            assertThat(result.getTeamMemberCandidates()).isNull();
            assertThat(result.getEntryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: includeTeamMembers=true の場合 teamMemberCandidates が返る（isAlreadyEntered 設定）")
        void エントリー一覧にチームメンバー候補が含まれる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            TournamentDivisionEntity division = buildDivision(null, null);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));

            TournamentEntryMemberEntity enteredMember = buildEntryMember(USER_ID);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of(enteredMember));
            given(userRepository.findMemberSummaryById(USER_ID)).willReturn(Optional.empty());

            Long notEnteredUserId = 99L;
            List<MemberDto> teamMembers = List.of(
                    new MemberDto(USER_ID, "エントリー済みユーザー", null, null, null),
                    new MemberDto(notEnteredUserId, "未エントリーユーザー", null, null, null)
            );
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(teamMembers);

            // when
            EntryMemberListResponse result = service.getEntryMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, true, USER_ID);

            // then
            assertThat(result.getTeamMemberCandidates()).hasSize(2);
            boolean enteredFlag = result.getTeamMemberCandidates().stream()
                    .filter(c -> c.getUserId().equals(USER_ID))
                    .findFirst()
                    .map(c -> c.isAlreadyEntered())
                    .orElse(false);
            assertThat(enteredFlag).isTrue();
        }
    }

    // =========================================================
    // loadFromTeamMembers
    // =========================================================

    @Nested
    @DisplayName("loadFromTeamMembers")
    class LoadFromTeamMembers {

        @Test
        @DisplayName("正常系: 全メンバーロード（added=5, skipped=2, total=7）")
        void チームメンバーから全員ロードできる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            TournamentDivisionEntity division = buildDivision(null, null);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));

            // 既存2名
            Set<Long> existingIds = Set.of(1L, 2L);
            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID)).willReturn(existingIds);

            // チームメンバー7名（既存2名 + 新規5名）
            List<MemberDto> activeMembers = List.of(
                    new MemberDto(1L, "既存1", null, null, null), new MemberDto(2L, "既存2", null, null, null),
                    new MemberDto(3L, "新規3", null, null, null), new MemberDto(4L, "新規4", null, null, null),
                    new MemberDto(5L, "新規5", null, null, null), new MemberDto(6L, "新規6", null, null, null),
                    new MemberDto(7L, "新規7", null, null, null)
            );
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            given(entryMemberRepository.countByParticipantId(PARTICIPANT_ID)).willReturn(7L);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().overwriteExisting(false).build();

            // when
            EntryLoadResponse result = service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then
            assertThat(result.getAdded()).isEqualTo(5);
            assertThat(result.getSkipped()).isEqualTo(2);
            assertThat(result.getTotal()).isEqualTo(7);
        }

        @Test
        @DisplayName("正常系: overwriteExisting=true で既存エントリーを上書き（skipped=2）")
        void overwriteExistingTrueで既存をスキップ確認() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            TournamentDivisionEntity division = buildDivision(null, null);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));

            Set<Long> existingIds = Set.of(1L, 2L);
            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID)).willReturn(existingIds);

            List<MemberDto> activeMembers = List.of(
                    new MemberDto(1L, "既存1", null, null, null), new MemberDto(2L, "既存2", null, null, null),
                    new MemberDto(3L, "新規3", null, null, null)
            );
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            given(entryMemberRepository.countByParticipantId(PARTICIPANT_ID)).willReturn(3L);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().overwriteExisting(true).build();

            // when
            EntryLoadResponse result = service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then（overwriteExisting=trueでも既存はskipped扱い）
            assertThat(result.getAdded()).isEqualTo(1);
            assertThat(result.getSkipped()).isEqualTo(2);
        }

        @Test
        @DisplayName("異常系: 大会が ENTRY_LOCKED → TOUR_020")
        void 大会がロック中はエントリー変更不可() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.COMPLETED);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(buildDivision(null, null)));

            // IN_PROGRESSに変更して確認（checkEntryLockで即スロー → 以降のメソッドは呼ばれない）
            TournamentEntity inProgress = buildTournament(TournamentStatus.IN_PROGRESS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(inProgress));

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().build();

            // COMPLETED大会のIDOR検証は PARTICIPANT_NOT_FOUND を返すため
            // ここでは IN_PROGRESS で hasTournamentAdminRole=false のケースをテスト
            assertThatThrownBy(() -> service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.ENTRY_LOCKED);
        }

        @Test
        @DisplayName("異常系: max_entry_count超過 → TOUR_023")
        void max超過でMAX_ENTRY_COUNT_EXCEEDED() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            // max=2に設定
            TournamentDivisionEntity division = buildDivision(null, 2);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division));

            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID)).willReturn(Set.of());
            List<MemberDto> activeMembers = List.of(
                    new MemberDto(1L, "user1", null, null, null), new MemberDto(2L, "user2", null, null, null), new MemberDto(3L, "user3", null, null, null)
            );
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            // 保存後のカウントが3 → max=2超過
            given(entryMemberRepository.countByParticipantId(PARTICIPANT_ID)).willReturn(3L);

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().build();

            // when & then
            assertThatThrownBy(() -> service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.MAX_ENTRY_COUNT_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: チーム非所属ユーザーのロード → TOUR_021")
        void チーム非所属ユーザーのロード() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(buildDivision(null, null)));

            // アクティブメンバーは 1L のみ
            List<MemberDto> activeMembers = List.of(new MemberDto(1L, "user1", null, null, null));
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);

            // リクエストでは非メンバーの 999L を指定
            LoadFromTeamRequest req = LoadFromTeamRequest.builder()
                    .userIds(List.of(999L))
                    .build();

            // when & then
            assertThatThrownBy(() -> service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.USER_NOT_TEAM_MEMBER);
        }

        @Test
        @DisplayName("異常系: min=null かつ max=null の場合は制限なし（正常終了）")
        void min_max_nullの場合は制限なし() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            // min=null, max=null
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(buildDivision(null, null)));

            given(entryMemberRepository.findUserIdsByParticipantId(PARTICIPANT_ID)).willReturn(Set.of());
            List<MemberDto> activeMembers = List.of(
                    new MemberDto(1L, "user1", null, null, null), new MemberDto(2L, "user2", null, null, null)
            );
            given(memberQueryDispatcher.queryMembers(eq(TEAM_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(activeMembers);
            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            given(entryMemberRepository.countByParticipantId(PARTICIPANT_ID)).willReturn(2L);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of());

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().build();

            // when（例外が投げられないことを確認）
            EntryLoadResponse result = service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then
            assertThat(result.getAdded()).isEqualTo(2);
        }
    }

    // =========================================================
    // upsertEntryMembers
    // =========================================================

    @Nested
    @DisplayName("upsertEntryMembers")
    class UpsertEntryMembers {

        @Test
        @DisplayName("正常系: エントリーを全置換できる")
        void エントリーを全置換できる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));
            // max=5
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(buildDivision(1, 5)));

            UpsertEntryMembersRequest.EntryMemberItem item = UpsertEntryMembersRequest.EntryMemberItem.builder()
                    .userId(USER_ID).build();
            UpsertEntryMembersRequest req = UpsertEntryMembersRequest.builder()
                    .members(List.of(item))
                    .build();

            given(entryMemberRepository.saveAll(any())).willReturn(List.of());
            TournamentEntryMemberEntity saved = buildEntryMember(USER_ID);
            given(entryMemberRepository.findByParticipantIdOrderBySortOrderAsc(PARTICIPANT_ID))
                    .willReturn(List.of(saved));
            given(userRepository.findMemberSummaryById(USER_ID)).willReturn(Optional.empty());

            // when
            EntryMemberListResponse result = service.upsertEntryMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID);

            // then
            verify(entryMemberRepository).deleteByParticipantId(PARTICIPANT_ID);
            assertThat(result.getEntryMembers()).hasSize(1);
        }
    }

    // =========================================================
    // deleteEntryMember
    // =========================================================

    @Nested
    @DisplayName("deleteEntryMember")
    class DeleteEntryMember {

        @Test
        @DisplayName("正常系: 個別削除できる")
        void 個別削除できる() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID entryMemberId = UUID.randomUUID();
            TournamentEntryMemberEntity entry = buildEntryMember(USER_ID);
            given(entryMemberRepository.findById(entryMemberId)).willReturn(Optional.of(entry));
            // participantId が一致していることをシミュレート
            try {
                var field = TournamentEntryMemberEntity.class.getDeclaredField("participantId");
                field.setAccessible(true);
                field.set(entry, PARTICIPANT_ID);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // when
            service.deleteEntryMember(ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID,
                    entryMemberId, false, USER_ID);

            // then
            verify(entryMemberRepository).delete(entry);
        }

        @Test
        @DisplayName("異常系: 存在しない entryMemberId の削除 → TOUR_019")
        void 存在しないentryMemberIdの削除() {
            // given
            TournamentEntity tournament = buildTournament(TournamentStatus.OPEN);
            setupIDORMocks(tournament);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament));

            UUID entryMemberId = UUID.randomUUID();
            given(entryMemberRepository.findById(entryMemberId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.deleteEntryMember(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, entryMemberId, false, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.ENTRY_MEMBER_NOT_FOUND);
        }
    }

    // =========================================================
    // IDOR防止
    // =========================================================

    @Nested
    @DisplayName("IDOR防止")
    class IdorPrevention {

        @Test
        @DisplayName("異常系: 他チームparticipantへのアクセス → PARTICIPANT_NOT_FOUND")
        void 他チームparticipantへのアクセスは404() {
            // given: tournament の orgId が ORG_ID 以外
            TournamentEntity wrongOrgTournament = TournamentEntity.builder()
                    .organizationId(999L)   // 別組織
                    .name("別組織大会")
                    .build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(wrongOrgTournament));

            LoadFromTeamRequest req = LoadFromTeamRequest.builder().build();

            // when & then
            assertThatThrownBy(() -> service.loadFromTeamMembers(
                    ORG_ID, TOURNAMENT_ID, DIVISION_ID, PARTICIPANT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.PARTICIPANT_NOT_FOUND);
        }
    }
}
