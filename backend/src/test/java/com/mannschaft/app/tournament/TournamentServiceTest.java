package com.mannschaft.app.tournament;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.*;
import com.mannschaft.app.tournament.service.TournamentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link TournamentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentService 単体テスト")
class TournamentServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentTiebreakerRepository tiebreakerRepository;
    @Mock private TournamentStatDefRepository statDefRepository;
    @Mock private TournamentTemplateRepository templateRepository;
    @Mock private TournamentTemplateTiebreakerRepository templateTiebreakerRepository;
    @Mock private TournamentTemplateStatDefRepository templateStatDefRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentMapper mapper;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private com.mannschaft.app.tournament.service.TournamentContactSpaceProvisioningService contactSpaceProvisioningService;
    /** F08.7.1 / 04: シーズン継続時のデフォルトフォルダ払い出し検証用。 */
    @Mock private com.mannschaft.app.filesharing.service.SharedFolderService sharedFolderService;

    @InjectMocks
    private TournamentService service;

    private static final Long ORG_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long TOURNAMENT_ID = 100L;

    @Nested
    @DisplayName("getTournament")
    class GetTournament {

        @Test
        @DisplayName("異常系: 大会が見つからない場合エラー")
        void 大会不存在() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTournament(TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteTournament")
    class DeleteTournament {

        @Test
        @DisplayName("正常系: 大会の論理削除が成功する")
        void 論理削除成功() {
            TournamentEntity entity = TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").build();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(tournamentRepository.save(any())).willReturn(entity);

            service.deleteTournament(TOURNAMENT_ID);

            verify(tournamentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系: OPEN→IN_PROGRESS で参加チームがACTIVEになる")
        void ステータス変更でACTIVE化() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("テスト大会").build();
            setStatus(entity, TournamentStatus.OPEN);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            TournamentDivisionEntity div = TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).build();
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(div));

            TournamentParticipantEntity participant = TournamentParticipantEntity.builder()
                    .teamId(1L).build();
            given(participantRepository.findByDivisionIdAndStatus(any(), any()))
                    .willReturn(List.of(participant));
            given(participantRepository.saveAll(any())).willReturn(List.of(participant));
            given(tournamentRepository.save(any())).willReturn(entity);
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);

            service.changeStatus(TOURNAMENT_ID, TournamentStatus.IN_PROGRESS);

            verify(participantRepository).saveAll(any());
        }
    }

    @Nested
    @DisplayName("continueTournament")
    class ContinueTournament {

        @Test
        @DisplayName("異常系: COMPLETED/ARCHIVED以外の大会は継続不可")
        void 継続不可ステータス() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("テスト").build();
            // Default status is DRAFT
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.continueTournament(ORG_ID, USER_ID, TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.INVALID_TOURNAMENT_STATUS);
        }

        @Test
        @DisplayName("正常系: 新大会＋複製ディビジョンにデフォルトフォルダを払い出す（F08.7.1/04 §4）")
        void シーズン継続でフォルダ払い出し() {
            // Given: COMPLETED の旧大会 + ディビジョン1件
            TournamentEntity previous = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("旧シーズン").build();
            setStatus(previous, TournamentStatus.COMPLETED);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(previous));

            Long newTournamentId = 200L;
            // save された新大会には ID を採番して返す（後続の getTournament / provision で使う）
            given(tournamentRepository.save(any(TournamentEntity.class))).willAnswer(inv -> {
                TournamentEntity t = inv.getArgument(0);
                setId(t, newTournamentId);
                return t;
            });
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());

            Long newDivisionId = 300L;
            TournamentDivisionEntity prevDiv = TournamentDivisionEntity.builder()
                    .tournamentId(TOURNAMENT_ID).name("1部").build();
            given(divisionRepository.findByTournamentIdOrderByLevelAscSortOrderAsc(TOURNAMENT_ID))
                    .willReturn(List.of(prevDiv));
            given(divisionRepository.save(any(TournamentDivisionEntity.class))).willAnswer(inv -> {
                TournamentDivisionEntity d = inv.getArgument(0);
                setId(d, newDivisionId);
                return d;
            });

            // getTournament(newTournamentId) のための stub
            given(tournamentRepository.findById(newTournamentId)).willReturn(Optional.of(previous));
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(newTournamentId)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(newTournamentId)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);

            // When
            service.continueTournament(ORG_ID, USER_ID, TOURNAMENT_ID);

            // Then: 大会スコープ「大会要項」フォルダが払い出される
            verify(sharedFolderService).provisionDefaultFolder(
                    eq(com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT),
                    eq(ORG_ID), eq(newTournamentId), eq(USER_ID), eq("大会要項"));
            // 複製ディビジョンスコープ「規約」フォルダが払い出される
            verify(sharedFolderService).provisionDefaultFolder(
                    eq(com.mannschaft.app.filesharing.FileScopeType.TOURNAMENT_DIVISION),
                    eq(ORG_ID), eq(newDivisionId), eq(USER_ID), eq("規約"));
        }
    }

    @Nested
    @DisplayName("createTournament（F08.10 多競技 sport）")
    class CreateTournamentSport {

        private com.mannschaft.app.tournament.dto.CreateTournamentRequest createRequest(String sport) {
            return new com.mannschaft.app.tournament.dto.CreateTournamentRequest(
                    null, // templateId
                    "新大会", // name
                    null, // description
                    "LEAGUE", // format
                    sport, // sport
                    null, // season
                    null, null, // start/end
                    null, null, null, // win/draw/loss
                    null, null, null, // hasDraw/hasSets/setsToWin
                    null, null, // hasExtraTime/hasPenalties
                    null, // scoreUnitLabel
                    null, // bonusPointRules
                    null, // leagueRoundType
                    null, // knockoutLegs
                    null, // visibility
                    null, // tiebreakers
                    null // statDefs
            );
        }

        private void stubCreateChain() {
            given(tournamentRepository.save(any(TournamentEntity.class))).willAnswer(inv -> {
                TournamentEntity t = inv.getArgument(0);
                setId(t, TOURNAMENT_ID);
                return t;
            });
            given(tournamentRepository.findById(TOURNAMENT_ID))
                    .willReturn(Optional.of(TournamentEntity.builder()
                            .organizationId(ORG_ID).name("新大会").build()));
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);
        }

        @Test
        @DisplayName("正常系: sport 未指定（null）→ SOCCER 既定で保存される")
        void sport未指定でSOCCER既定() {
            stubCreateChain();

            service.createTournament(ORG_ID, USER_ID, createRequest(null));

            org.mockito.ArgumentCaptor<TournamentEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TournamentEntity.class);
            verify(tournamentRepository).save(captor.capture());
            assertThat(captor.getValue().getSport()).isEqualTo("SOCCER");
        }

        @Test
        @DisplayName("正常系: sport=VOLLEYBALL 指定 → そのまま保存される")
        void sport指定で保存() {
            stubCreateChain();

            service.createTournament(ORG_ID, USER_ID, createRequest("VOLLEYBALL"));

            org.mockito.ArgumentCaptor<TournamentEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(TournamentEntity.class);
            verify(tournamentRepository).save(captor.capture());
            assertThat(captor.getValue().getSport()).isEqualTo("VOLLEYBALL");
        }

        @Test
        @DisplayName("異常系: sport が不正な列挙値 → IllegalArgumentException（@Pattern 突破時の多重防御で 400）")
        void sport不正値で例外() {
            // resolveSport の Sport.valueOf が IllegalArgumentException を投げ、save には到達しない。
            assertThatThrownBy(() -> service.createTournament(ORG_ID, USER_ID, createRequest("HANDBALL")))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(tournamentRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateTournament")
    class UpdateTournament {

        /**
         * F08.7 順位UI Wave1 回帰テスト。
         * 可視性のみ指定した部分 PATCH で {@code setsToWin} / {@code bonusPointRules} が
         * null 上書きで消失しないことを保証する番人。
         * （TournamentService#updateTournament で当該2フィールドの coalesce を外すと落ちる）
         */
        @Test
        @DisplayName("正常系: 可視性のみ更新で setsToWin/bonusPointRules が既存値のまま保持される")
        void 可視性のみ更新で既存値保持() {
            // Given: setsToWin=3, bonusPointRules="..." を持つ既存大会
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID)
                    .name("既存大会")
                    .setsToWin(3)
                    .bonusPointRules("{\"win3sets\":1}")
                    .build();
            setVisibility(entity, TournamentVisibility.MEMBERS_AND_ABOVE);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(tournamentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);

            // When: visibility と version のみ指定（他フィールドは全て null）の部分 PATCH
            com.mannschaft.app.tournament.dto.UpdateTournamentRequest request =
                    new com.mannschaft.app.tournament.dto.UpdateTournamentRequest(
                            null, // name
                            null, // description
                            null, // format
                            null, // sport
                            null, // season
                            null, // startDate
                            null, // endDate
                            null, // winPoints
                            null, // drawPoints
                            null, // lossPoints
                            null, // hasDraw
                            null, // hasSets
                            null, // setsToWin ← 送らない
                            null, // hasExtraTime
                            null, // hasPenalties
                            null, // scoreUnitLabel
                            null, // bonusPointRules ← 送らない
                            null, // leagueRoundType
                            null, // knockoutLegs
                            "PUBLIC", // visibility
                            1L, // version
                            null, // tiebreakers
                            null // statDefs
                    );

            service.updateTournament(TOURNAMENT_ID, request);

            // Then: setsToWin / bonusPointRules は既存値のまま（null 上書きされていない）
            assertThat(entity.getSetsToWin()).isEqualTo(3);
            assertThat(entity.getBonusPointRules()).isEqualTo("{\"win3sets\":1}");
            // visibility は要求どおり更新されている
            assertThat(entity.getVisibility()).isEqualTo(TournamentVisibility.PUBLIC);
            // sport は未指定（null）ゆえ既存値（Builder.Default の SOCCER）が維持される
            assertThat(entity.getSport()).isEqualTo("SOCCER");
        }

        @Test
        @DisplayName("正常系: sport を SHOGI に更新できる（指定時は更新・未指定時は維持の coalesce）")
        void sport更新() {
            // Given: 既存大会（Builder.Default で sport=SOCCER）
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("既存大会").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(tournamentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(tiebreakerRepository.findByTournamentIdOrderByPriorityAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(statDefRepository.findByTournamentIdOrderBySortOrderAsc(TOURNAMENT_ID)).willReturn(List.of());
            given(mapper.toTournamentResponse(any(), any(), any())).willReturn(null);

            com.mannschaft.app.tournament.dto.UpdateTournamentRequest request =
                    new com.mannschaft.app.tournament.dto.UpdateTournamentRequest(
                            null, null, null,
                            "SHOGI", // sport ← 更新する
                            null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, "PUBLIC", 1L, null, null);

            service.updateTournament(TOURNAMENT_ID, request);

            assertThat(entity.getSport()).isEqualTo("SHOGI");
        }

        @Test
        @DisplayName("異常系: 不正な sport で更新 → IllegalArgumentException（多重防御）")
        void sport不正値で更新失敗() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("既存大会").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            com.mannschaft.app.tournament.dto.UpdateTournamentRequest request =
                    new com.mannschaft.app.tournament.dto.UpdateTournamentRequest(
                            null, null, null,
                            "CRICKET", // sport ← 不正値
                            null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, "PUBLIC", 1L, null, null);

            assertThatThrownBy(() -> service.updateTournament(TOURNAMENT_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("listPublicTournaments")
    class ListPublicTournaments {

        @Test
        @DisplayName("正常系: OPEN/IN_PROGRESS/COMPLETED の PUBLIC 大会のみ返却される")
        @SuppressWarnings("unchecked")
        void 公開ステータスのみ返却() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("公開大会").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            setStatus(entity, TournamentStatus.OPEN);

            Pageable pageable = PageRequest.of(0, 20);
            Page<TournamentEntity> page = new PageImpl<>(List.of(entity));

            given(tournamentRepository.findByOrganizationIdAndVisibilityAndStatusInOrderByCreatedAtDesc(
                    eq(ORG_ID),
                    eq(TournamentVisibility.PUBLIC),
                    any(Collection.class),
                    eq(pageable)))
                    .willReturn(page);
            given(mapper.toTournamentSummaryResponse(entity)).willReturn(null);

            Page<?> result = service.listPublicTournaments(ORG_ID, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            // 旧メソッド（findByOrganizationIdAndVisibilityAndStatusNotOrderByCreatedAtDesc）が
            // 呼ばれていないことを確認（CANCELLED/ARCHIVED を誤返却するバグの根治確認）
            verifyNoMoreInteractions(contentVisibilityChecker);
        }
    }

    @Nested
    @DisplayName("verifyPublicAccess")
    class VerifyPublicAccess {

        @Test
        @DisplayName("異常系: 公開アクセス検証失敗（組織不一致）")
        void 組織不一致() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(999L).name("テスト").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyPublicAccess(ORG_ID, TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("漏洩スモーク: 非PUBLIC大会は未認証(viewer=null)で 404 隠蔽される")
        void 非PUBLIC大会は未認証で404隠蔽() {
            // 組織は一致するが、匿名閲覧者では canView(TOURNAMENT, tId, null) が false（PUBLIC 以外）。
            // PUBLIC=誰でも閲覧の約束を破らず、非公開大会の存在自体を 404 で隠す（IDOR 防止）。
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("非公開大会").build();
            setVisibility(entity, TournamentVisibility.MEMBERS_AND_ABOVE);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, null))
                    .willReturn(false);

            assertThatThrownBy(() -> service.verifyPublicAccess(ORG_ID, TOURNAMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("漏洩スモーク: PUBLIC大会は未認証(viewer=null)で閲覧可（例外を投げない）")
        void PUBLIC大会は未認証で閲覧可() {
            TournamentEntity entity = TournamentEntity.builder()
                    .organizationId(ORG_ID).name("公開大会").build();
            setVisibility(entity, TournamentVisibility.PUBLIC);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(entity));
            given(contentVisibilityChecker.canView(
                    com.mannschaft.app.common.visibility.ReferenceType.TOURNAMENT, TOURNAMENT_ID, null))
                    .willReturn(true);

            // 例外を投げずに通過すること（PUBLIC=誰でも閲覧）。
            service.verifyPublicAccess(ORG_ID, TOURNAMENT_ID);
        }
    }

    private void setStatus(TournamentEntity entity, TournamentStatus status) {
        try {
            var field = TournamentEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(entity, status);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void setVisibility(TournamentEntity entity, TournamentVisibility visibility) {
        try {
            var field = TournamentEntity.class.getDeclaredField("visibility");
            field.setAccessible(true);
            field.set(entity, visibility);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** BaseEntity の id フィールドへ反射でセットする（save 後の採番をエミュレート）。 */
    private void setId(Object entity, Long id) {
        try {
            var field = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
