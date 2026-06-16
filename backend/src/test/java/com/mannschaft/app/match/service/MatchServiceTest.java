package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchCompletedEvent;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.dto.MatchSummaryResponse;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchService} の純 UT（test-first・02 §E.3 / 03 §C.2/C.7 / 05 §H.2）。
 *
 * <p>COMPLETED 遷移時の duration 必須化・確定再計算・{@link MatchCompletedEvent} 発火、
 * finalizeScore の認可委譲＋before/after 監査、記録モード/記録係変更時のみの監査記録 を実アサートする。
 * 依存はすべて Mockito モック（純 Service 層・@WebMvcTest は使わない）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private PlayingTimeCalculationService playingTimeCalculationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MatchService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.SOCCER)
                .status(MatchStatus.IN_PROGRESS)
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, ORG))
                .thenReturn(Optional.of(match));
        lenient().when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─── (a) COMPLETED 遷移で duration 未設定 → 400 ──────────────

    @Test
    @DisplayName("(a) COMPLETED 遷移で duration_minutes 未設定なら 400（MATCH_023・02 §E.3）")
    void completedWithoutDurationIs400() {
        match.setDurationMinutes(null);
        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("試合時間");
        // 400 で弾かれた場合は再計算もイベント発火もしない（症状を隠さない）
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── (b) COMPLETED 遷移時のみ MatchCompletedEvent 発火＋確定再計算 ──

    @Test
    @DisplayName("(b) COMPLETED 遷移で MatchCompletedEvent 発火＋全 side 確定再計算（recalculate(match, null)）")
    void completedPublishesEventAndRecalculates() {
        match.setDurationMinutes(90);
        match.setTournamentFixtureId(777L);
        match.setHomeScore(2);
        match.setAwayScore(1);
        // 延長同点後の PK 戦（本戦 2-1 だが PK は別軸・F08.10 ② 順位連携）。
        // changeStatus(COMPLETED) は保存済み Entity の PK を MatchCompletedEvent に載せる必要がある。
        match.setHomePenaltyScore(5);
        match.setAwayPenaltyScore(4);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        // 確定再計算は全 side（editableTeamSides=null）
        verify(playingTimeCalculationService).recalculate(eq(match), isNull());

        // COMPLETED 遷移では 2 件 publish される: 順位連携の MatchCompletedEvent と
        // ライブ配信の MatchLiveUpdateEvent(STATUS_CHANGED・07 §J.2)。前者を抽出して検証する。
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        MatchCompletedEvent ev = captor.getAllValues().stream()
                .filter(o -> o instanceof MatchCompletedEvent)
                .map(o -> (MatchCompletedEvent) o)
                .findFirst()
                .orElseThrow(() -> new AssertionError("MatchCompletedEvent が publish されていない"));
        // ライブ配信 STATUS_CHANGED も併せて publish される（07 §J.2）
        boolean liveStatusPublished = captor.getAllValues().stream()
                .anyMatch(o -> o instanceof com.mannschaft.app.match.live.MatchLiveUpdateEvent
                        && ((com.mannschaft.app.match.live.MatchLiveUpdateEvent) o).getType()
                            == com.mannschaft.app.match.live.MatchLiveUpdateType.STATUS_CHANGED);
        assertThat(liveStatusPublished).isTrue();
        assertThat(ev.getMatchId()).isEqualTo(matchId);
        assertThat(ev.getTournamentFixtureId()).isEqualTo(777L);
        assertThat(ev.getHomeScore()).isEqualTo(2);
        assertThat(ev.getAwayScore()).isEqualTo(1);
        // PK 戦スコアが本戦と分離して event に載る（tournament/MatchScoreFixtureListener #1444 が
        // PK 勝敗を fixture 順位へ反映する経路の前提）。
        assertThat(ev.getHomePenaltyScore()).isEqualTo(5);
        assertThat(ev.getAwayPenaltyScore()).isEqualTo(4);
        assertThat(ev.getStatus()).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    @DisplayName("(b) COMPLETED 以外の遷移では MatchCompletedEvent を発火しない・再計算もしない（ライブ配信 STATUS_CHANGED は発火）")
    void nonCompletedDoesNotPublishOrRecalculate() {
        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.POSTPONED);

        // COMPLETED でない遷移では順位連携 MatchCompletedEvent は発火しない・確定再計算もしない
        verify(eventPublisher, never()).publishEvent(any(MatchCompletedEvent.class));
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        // ただしライブ配信の STATUS_CHANGED は全遷移で発火する（07 §J.2・観戦者へ進行を伝える）
        ArgumentCaptor<com.mannschaft.app.match.live.MatchLiveUpdateEvent> liveCaptor =
                ArgumentCaptor.forClass(com.mannschaft.app.match.live.MatchLiveUpdateEvent.class);
        verify(eventPublisher).publishEvent(liveCaptor.capture());
        assertThat(liveCaptor.getValue().getType())
                .isEqualTo(com.mannschaft.app.match.live.MatchLiveUpdateType.STATUS_CHANGED);
        assertThat(liveCaptor.getValue().getStatus()).isEqualTo(MatchStatus.POSTPONED);
        // 全遷移は監査記録される（03 §C.7）
        verify(auditLogService).record(eq(AuditEventType.MATCH_STATUS_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(b) status 遷移は MatchAccessService.assertCanEditMeta に認可委譲する")
    void changeStatusDelegatesAuthz() {
        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.POSTPONED);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    // ─── (b2) COMPLETED 遷移の必須条件は状態モデル類型ごとに異なる（01 §D.6） ──────

    @Test
    @DisplayName("(b2) SET_BASED（バレー）: 獲得セット数が確定し引分けでなければ COMPLETED 可（duration 不要）")
    void setBasedCompletedWithSetCounts() {
        match.setSport(Sport.VOLLEYBALL);
        match.setStateModel(StateModel.SET_BASED);
        match.setDurationMinutes(null); // セット制は duration 不要
        match.setHomeScore(3);
        match.setAwayScore(1);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        verify(playingTimeCalculationService).recalculate(eq(match), isNull());
        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b2) SET_BASED: セット数同数（引分け）は 400（MATCH_026・バレーに D なし）")
    void setBasedDrawIsRejected() {
        match.setSport(Sport.VOLLEYBALL);
        match.setStateModel(StateModel.SET_BASED);
        match.setHomeScore(2);
        match.setAwayScore(2);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("セット");
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) SET_BASED: セット数未確定（NULL）は 400（MATCH_026）")
    void setBasedNullScoreIsRejected() {
        match.setSport(Sport.VOLLEYBALL);
        match.setStateModel(StateModel.SET_BASED);
        match.setHomeScore(null);
        match.setAwayScore(null);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) SET_BASED: 2 セット止まり（2-1・3 セット先取に満たない）は 400（MATCH_026・match_sets 正本で厳密化）")
    void setBasedBelowThresholdIsRejected() {
        match.setSport(Sport.VOLLEYBALL);
        match.setStateModel(StateModel.SET_BASED);
        match.setPeriodFormat("BEST_OF_5");
        // 2-1 はどちらも 3 セット先取に達していない＝試合未決着（recordSet が決着前に COMPLETED 押下した想定）
        match.setHomeScore(2);
        match.setAwayScore(1);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("セット");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) SET_BASED: 3-2（第 5 セットまでもつれて 3 セット先取）は COMPLETED 可")
    void setBasedThreeTwoIsCompletable() {
        match.setSport(Sport.VOLLEYBALL);
        match.setStateModel(StateModel.SET_BASED);
        match.setPeriodFormat("BEST_OF_5");
        match.setDurationMinutes(null);
        match.setHomeScore(3);
        match.setAwayScore(2);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b2) TURN_BASED（将棋）: 勝敗 1-0 ＋ win_method 妥当なら COMPLETED 可（duration 不要・MATCH_023 を要求しない）")
    void turnBasedCompletedWithWinLoss() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setDurationMinutes(null); // ターン制は duration 不要
        match.setHomeScore(1);
        match.setAwayScore(0);
        match.setWinMethod("RESIGNATION"); // 勝敗ありは勝ち方必須（§D.7）

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        // ターン制でも recalculate は呼ばれる（PlayingTimeCalculationService 側で TURN_BASED をスキップする）
        verify(playingTimeCalculationService).recalculate(eq(match), isNull());
        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b2) TURN_BASED: 勝敗 1-0 だが win_method=NULL は 400（MATCH_028・勝ち方必須）")
    void turnBasedWinWithoutWinMethodRejected() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(1);
        match.setAwayScore(0);
        match.setWinMethod(null);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) TURN_BASED: 勝敗 1-0 だが win_method が競技外（POINTS_WIN は将棋に無い）は 400（MATCH_028）")
    void turnBasedWinWithForeignWinMethodRejected() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(0);
        match.setAwayScore(1);
        match.setWinMethod("POINTS_WIN"); // 囲碁の勝ち方を将棋に流用

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) TURN_BASED: 引分 0-0 なのに win_method が付いていると 400（MATCH_028・責務分離）")
    void turnBasedDrawWithWinMethodRejected() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(0);
        match.setAwayScore(0);
        match.setWinMethod("REPETITION"); // 引分なのに勝ち方が付いている矛盾

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b2) TURN_BASED: 引分け 0-0（千日手/持碁）も勝敗確定として COMPLETED 可")
    void turnBasedDrawIsCompletable() {
        match.setSport(Sport.GO);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(0);
        match.setAwayScore(0);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b2) TURN_BASED: 勝敗未確定（スコア NULL）は 400（MATCH_027）")
    void turnBasedNullScoreIsRejected() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(null);
        match.setAwayScore(0);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("勝敗");
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── (b3) TURN_BASED 団体戦の親（子ボード保有）は win_method 検証を免除 ──────
    // 親の勝敗は子ボードの勝ち星集計（home/away_score 勝ち星差）で決まり、win_method=NULL が正常（§4.3）。
    // 親（countByParentMatchId>0）と個人戦（=0）を区別して締めることを検証する。

    @Test
    @DisplayName("(b3) TURN_BASED 団体戦の親: 勝ち星差あり（6-4）かつ win_method=NULL でも COMPLETED 可（§4.3）")
    void turnBasedTeamParentCompletableWithWinStarsAndNullWinMethod() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setDurationMinutes(null);
        // 親は子ボードの勝ち星集計（整数スケール）が反映されている（勝ち星差あり＝勝者あり）
        match.setHomeScore(6);
        match.setAwayScore(4);
        // 団体戦の親は win_method を持たない（§4.3）。
        match.setWinMethod(null);
        // 子ボードを持つ＝団体戦の親（個人戦と区別する根拠）
        when(matchRepository.countByParentMatchId(matchId)).thenReturn(5L);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        // 勝ち星差ありの親が win_method=NULL のまま COMPLETED できる（MATCH_028 を投げない）
        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b3) TURN_BASED 団体戦の親: 勝ち星同数（引分・5-5）も win_method=NULL で COMPLETED 可")
    void turnBasedTeamParentCompletableWithDrawStars() {
        match.setSport(Sport.GO);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(5);
        match.setAwayScore(5);
        match.setWinMethod(null);
        when(matchRepository.countByParentMatchId(matchId)).thenReturn(5L);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b3) TURN_BASED 個人戦（子ボード無し）は従来どおり勝敗あり時の win_method 必須を維持（MATCH_028）")
    void turnBasedIndividualStillRequiresWinMethod() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(1);
        match.setAwayScore(0);
        match.setWinMethod(null);
        // 子ボードを持たない＝個人戦（countByParentMatchId=0 はモックの既定値だが明示）
        when(matchRepository.countByParentMatchId(matchId)).thenReturn(0L);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_028);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b3) TURN_BASED 団体戦の親: 勝敗未確定（スコア NULL）は親でも 400（MATCH_027）")
    void turnBasedTeamParentNullScoreStillRejected() {
        match.setSport(Sport.SHOGI);
        match.setStateModel(StateModel.TURN_BASED);
        match.setHomeScore(null);
        match.setAwayScore(4);
        match.setWinMethod(null);
        // スコア NULL の検証は親/個人戦の区別（countByParentMatchId）より前に行われるため
        // ここでは countByParentMatchId をスタブしない（未確定は親でも個人戦でも MATCH_027）。

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_027);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── (b4) SCORED（採点競技）: 合計点（整数スケール×1000）両確定で COMPLETED 可 ──────
    // 合計点の大小で勝敗を導出（高い側が勝者・同点は DRAW・§B.1.2/§6）。win_method は使わない（§10）。

    @Test
    @DisplayName("(b4) SCORED（フィギュア）: 合計点両確定なら COMPLETED 可（duration 不要・MATCH_023 を要求しない）")
    void scoredCompletedWithBothScores() {
        match.setSport(Sport.FIGURE_SKATING);
        match.setStateModel(StateModel.SCORED);
        match.setDurationMinutes(null); // 採点競技は duration 不要
        // 215.43 → 215430 / 198.45 → 198450（HOME が高い＝HOME 勝ち）
        match.setHomeScore(215430);
        match.setAwayScore(198450);

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        // 採点競技でも recalculate は呼ばれる（PlayingTimeCalculationService 側で SCORED をスキップする）
        verify(playingTimeCalculationService).recalculate(eq(match), isNull());
        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b4) SCORED（体操）: 同点（整数スケール同値）も COMPLETED 可（引分 DRAW・§6）")
    void scoredDrawIsCompletable() {
        match.setSport(Sport.GYMNASTICS);
        match.setStateModel(StateModel.SCORED);
        match.setHomeScore(85332);
        match.setAwayScore(85332); // 整数スケール同値＝引分

        service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED);

        verify(eventPublisher).publishEvent(any(MatchCompletedEvent.class));
    }

    @Test
    @DisplayName("(b4) SCORED: 合計点未確定（home NULL）は 400（MATCH_035・採点未確定）")
    void scoredNullHomeScoreIsRejected() {
        match.setSport(Sport.FIGURE_SKATING);
        match.setStateModel(StateModel.SCORED);
        match.setHomeScore(null);
        match.setAwayScore(198450);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_035);
        verify(playingTimeCalculationService, never()).recalculate(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("(b4) SCORED: 合計点未確定（away NULL）は 400（MATCH_035・採点未確定）")
    void scoredNullAwayScoreIsRejected() {
        match.setSport(Sport.GYMNASTICS);
        match.setStateModel(StateModel.SCORED);
        match.setHomeScore(85332);
        match.setAwayScore(null);

        assertThatThrownBy(() -> service.changeStatus(matchId, ORG, ACTOR, MatchStatus.COMPLETED))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_035);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─── (c) finalizeScore の認可委譲＋before/after 監査 ──────────

    @Test
    @DisplayName("(c) finalizeScore は認可委譲し、before/after・matchId・操作者・teamId を監査 metadata に記録")
    void finalizeScoreAuthzAndAudit() {
        // before スコア
        match.setHomeScore(0);
        match.setAwayScore(0);
        match.setHomePenaltyScore(null);
        match.setAwayPenaltyScore(null);

        service.finalizeScore(matchId, ORG, ACTOR, 3, 2, 5, 4);

        // 認可委譲
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
        // after が反映される
        assertThat(match.getHomeScore()).isEqualTo(3);
        assertThat(match.getAwayScore()).isEqualTo(2);
        assertThat(match.getHomePenaltyScore()).isEqualTo(5);
        assertThat(match.getAwayPenaltyScore()).isEqualTo(4);

        ArgumentCaptor<String> metaCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCORE_FINALIZED.name()),
                eq(ACTOR), isNull(), eq(TEAM), eq(ORG),
                isNull(), isNull(), isNull(), metaCaptor.capture());
        String metadata = metaCaptor.getValue();
        // matchId・teamId
        assertThat(metadata).contains(matchId.toString());
        assertThat(metadata).contains("\"teamId\":" + TEAM);
        // before（0/0/null/null）と after（3/2/5/4）の両方が記録される
        assertThat(metadata).contains("\"before\":{\"home\":0,\"away\":0,\"homePk\":null,\"awayPk\":null}");
        assertThat(metadata).contains("\"after\":{\"home\":3,\"away\":2,\"homePk\":5,\"awayPk\":4}");
    }

    // ─── (d) 記録モード/記録係変更時のみ監査記録 ────────────────

    @Test
    @DisplayName("(d) モード切替（共同記録→公式戦）かつ記録係セット時のみ両イベントを記録")
    void changeRecordingModeRecordsOnlyWhenChanged() {
        match.setHasScorekeeper(false);
        match.setScorekeeperUserId(null);

        service.changeRecordingMode(matchId, ORG, ACTOR, true, 9L);

        // モード変更（false→true）を記録
        verify(auditLogService).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
        // 記録係変更（null→9）を記録
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                eq(ACTOR), eq(9L), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 変更がない場合（同一モード・同一記録係）は監査記録しない")
    void changeRecordingModeNoChangeNoAudit() {
        match.setHasScorekeeper(true);
        match.setScorekeeperUserId(9L);

        // 同じ値で呼ぶ → 変更なし
        service.changeRecordingMode(matchId, ORG, ACTOR, true, 9L);

        verify(auditLogService, never()).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 公式戦→共同記録（true→false）はモード変更を記録し scorekeeper を null 化")
    void changeRecordingModeToCoop() {
        match.setHasScorekeeper(true);
        match.setScorekeeperUserId(9L);

        service.changeRecordingMode(matchId, ORG, ACTOR, false, null);

        assertThat(match.getScorekeeperUserId()).isNull();
        verify(auditLogService).record(eq(AuditEventType.MATCH_RECORDING_MODE_CHANGED.name()),
                eq(ACTOR), any(), eq(TEAM), eq(ORG), any(), any(), any(), any());
        // 記録係 9→null も変更ありなので記録される
        verify(auditLogService).record(eq(AuditEventType.MATCH_SCOREKEEPER_CHANGED.name()),
                eq(ACTOR), isNull(), eq(TEAM), eq(ORG), any(), any(), any(), any());
    }

    @Test
    @DisplayName("(d) 記録モード切替は認可委譲する")
    void changeRecordingModeDelegatesAuthz() {
        match.setHasScorekeeper(false);
        service.changeRecordingMode(matchId, ORG, ACTOR, false, null);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    // ─── create バリデーション（最小必須） ────────────────────

    @Test
    @DisplayName("create: kind が null なら 400（MATCH_024）")
    void createWithoutKind400() {
        MatchService.CreateCommand cmd = MatchService.CreateCommand.builder()
                .organizationId(ORG).teamId(TEAM).createdBy(ACTOR)
                .opponentName("相手FC").build();
        assertThatThrownBy(() -> service.create(cmd, ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("create: 相手（opponentTeamId / opponentName）が両方欠落なら 400")
    void createWithoutOpponent400() {
        MatchService.CreateCommand cmd = MatchService.CreateCommand.builder()
                .organizationId(ORG).teamId(TEAM).createdBy(ACTOR)
                .kind(com.mannschaft.app.match.domain.MatchKind.PRACTICE)
                .build();
        assertThatThrownBy(() -> service.create(cmd, ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    // ─── softDelete 認可委譲 ──────────────────────────────────

    @Test
    @DisplayName("softDelete は認可委譲し deleted_at をセットする")
    void softDeleteDelegatesAndMarks() {
        service.softDelete(matchId, ORG, ACTOR);
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
        assertThat(match.getDeletedAt()).isNotNull();
    }

    // ─── listMatches（一覧・Phase2C） ──────────────────────────

    @Test
    @DisplayName("listMatches: 認可委譲（assertCanListTeamMatches）＋テナント/チーム＋フィルタをリポジトリに渡し DTO へ変換")
    void listMatchesDelegatesAuthzAndPassesFilters() {
        LocalDateTime from = LocalDateTime.parse("2026-01-01T00:00:00");
        LocalDateTime to = LocalDateTime.parse("2026-12-31T23:59:59");
        MatchEntity row = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.TOURNAMENT).status(MatchStatus.COMPLETED).createdBy(ACTOR)
                .opponentName("相手FC").build();
        row.setId(UUID.randomUUID());
        Pageable pageable = PageRequest.of(0, 20);
        when(matchRepository.findTeamMatches(
                eq(ORG), eq(TEAM), eq(MatchStatus.COMPLETED), eq(MatchKind.TOURNAMENT),
                eq(Sport.SOCCER), eq(from), eq(to), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        MatchService.ListFilter filter = MatchService.ListFilter.builder()
                .status(MatchStatus.COMPLETED).kind(MatchKind.TOURNAMENT).sport(Sport.SOCCER)
                .from(from).to(to).build();

        var result = service.listMatches(ORG, TEAM, ACTOR, filter, pageable);

        // 認可委譲（第一防御）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        // Entity → サマリ DTO 変換
        assertThat(result.getTotalElements()).isEqualTo(1);
        MatchSummaryResponse dto = result.getContent().get(0);
        assertThat(dto.getId()).isEqualTo(row.getId());
        assertThat(dto.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(dto.getKind()).isEqualTo(MatchKind.TOURNAMENT);
        assertThat(dto.getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("listMatches: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void listMatchesNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.listMatches(ORG, TEAM, ACTOR,
                MatchService.ListFilter.builder().build(), PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never()).findTeamMatches(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("listMatches: filter が null でも全 null フィルタとしてリポジトリに渡す（NPE にならない）")
    void listMatchesNullFilterDefaultsToAllNull() {
        Pageable pageable = PageRequest.of(0, 20);
        when(matchRepository.findTeamMatches(
                eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var result = service.listMatches(ORG, TEAM, ACTOR, null, pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(matchRepository).findTeamMatches(
                eq(ORG), eq(TEAM), isNull(), isNull(), isNull(), isNull(), isNull(), eq(pageable));
    }

    // ─── resolveByScheduleId（入口④・予定からの解決・二重起票防止） ────

    @Test
    @DisplayName("resolveByScheduleId: 既存試合があれば認可委譲のうえサマリ DTO を返す")
    void resolveByScheduleIdReturnsExisting() {
        long scheduleId = 9001L;
        MatchEntity existing = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.PRACTICE).status(MatchStatus.SCHEDULED).createdBy(ACTOR)
                .scheduleId(scheduleId).opponentName("相手FC").build();
        existing.setId(UUID.randomUUID());
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(ORG, TEAM, scheduleId))
                .thenReturn(Optional.of(existing));

        var result = service.resolveByScheduleId(ORG, TEAM, ACTOR, scheduleId);

        // 認可委譲（一覧と同水準のメンバー以上）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(existing.getId());
        assertThat(result.get().getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("resolveByScheduleId: 既存が無ければ Optional.empty（FE は作成へ分岐）")
    void resolveByScheduleIdEmptyWhenNone() {
        long scheduleId = 9002L;
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(ORG, TEAM, scheduleId))
                .thenReturn(Optional.empty());

        var result = service.resolveByScheduleId(ORG, TEAM, ACTOR, scheduleId);

        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveByScheduleId: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void resolveByScheduleIdNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.resolveByScheduleId(ORG, TEAM, ACTOR, 9003L))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never())
                .findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(any(), any(), any());
    }

    // ─── resolveByFixtureId（入口①・大会の対戦カードからの解決・二重起票防止） ────

    @Test
    @DisplayName("resolveByFixtureId: 既存試合があれば認可委譲のうえサマリ DTO を返す")
    void resolveByFixtureIdReturnsExisting() {
        long fixtureId = 8001L;
        MatchEntity existing = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM).sport(Sport.SOCCER)
                .kind(MatchKind.TOURNAMENT).status(MatchStatus.SCHEDULED).createdBy(ACTOR)
                .tournamentFixtureId(fixtureId).opponentName("相手FC").build();
        existing.setId(UUID.randomUUID());
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        ORG, TEAM, fixtureId))
                .thenReturn(Optional.of(existing));

        var result = service.resolveByFixtureId(ORG, TEAM, ACTOR, fixtureId);

        // 認可委譲（一覧・入口④と同水準のメンバー以上）
        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(existing.getId());
        assertThat(result.get().getKind()).isEqualTo(MatchKind.TOURNAMENT);
        assertThat(result.get().getOpponentName()).isEqualTo("相手FC");
    }

    @Test
    @DisplayName("resolveByFixtureId: 既存が無ければ Optional.empty（FE は作成へ分岐）")
    void resolveByFixtureIdEmptyWhenNone() {
        long fixtureId = 8002L;
        when(matchRepository
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        ORG, TEAM, fixtureId))
                .thenReturn(Optional.empty());

        var result = service.resolveByFixtureId(ORG, TEAM, ACTOR, fixtureId);

        verify(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveByFixtureId: 非メンバー（認可 403）ならリポジトリを呼ばずに伝播する")
    void resolveByFixtureIdNonMemberThrows() {
        doThrow(new BusinessException(MatchErrorCode.MATCH_010))
                .when(matchAccessService).assertCanListTeamMatches(ACTOR, TEAM);

        assertThatThrownBy(() -> service.resolveByFixtureId(ORG, TEAM, ACTOR, 8003L))
                .isInstanceOf(BusinessException.class);

        verify(matchRepository, never())
                .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                        any(), any(), any());
    }

    @Test
    @DisplayName("getMatchOrThrow: 不在・テナント越境は 404（MATCH_001）")
    void getMatchNotFound404() {
        UUID other = UUID.randomUUID();
        when(matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(other, ORG))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getMatchOrThrow(other, ORG))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("見つかりません");
    }

    // ─────────────────────────────────────────────
    // recordTournamentScore（系統B の match 正本化・Phase5b-2'・05 §H.1〜H.2.3）
    // ─────────────────────────────────────────────

    @org.junit.jupiter.api.Nested
    @DisplayName("recordTournamentScore（大会スコアの match 正本化）")
    class RecordTournamentScore {

        private static final long FIXTURE_ID = 9001L;
        private static final long HOME_TEAM = 200L;
        private static final long AWAY_TEAM = 300L;

        private MatchService.RecordTournamentScoreCommand cmd(Integer home, Integer away,
                                                              Integer homePk, Integer awayPk) {
            return MatchService.RecordTournamentScoreCommand.builder()
                    .organizationId(ORG)
                    .teamId(HOME_TEAM)
                    .opponentTeamId(AWAY_TEAM)
                    .sport(Sport.SOCCER)
                    .tournamentFixtureId(FIXTURE_ID)
                    .homeScore(home)
                    .awayScore(away)
                    .homePenaltyScore(homePk)
                    .awayPenaltyScore(awayPk)
                    .actorUserId(ACTOR)
                    .build();
        }

        @Test
        @DisplayName("既存 match 無し → 新規作成（kind=TOURNAMENT・home/away_score＋PK 反映・status=COMPLETED）")
        void createsNewMatchWhenAbsent() {
            when(matchRepository
                    .findFirstByOrganizationIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                            ORG, FIXTURE_ID))
                    .thenReturn(Optional.empty());
            UUID newId = UUID.randomUUID();
            // save 後に id が割り当てられる体（UuidV7Entity は永続化時に id 生成）。
            when(matchRepository.save(any())).thenAnswer(inv -> {
                MatchEntity m = inv.getArgument(0);
                org.springframework.test.util.ReflectionTestUtils.setField(m, "id", newId);
                return m;
            });

            UUID result = service.recordTournamentScore(cmd(2, 1, null, null));

            ArgumentCaptor<MatchEntity> captor = ArgumentCaptor.forClass(MatchEntity.class);
            verify(matchRepository).save(captor.capture());
            MatchEntity saved = captor.getValue();
            assertThat(saved.getOrganizationId()).isEqualTo(ORG);
            assertThat(saved.getTeamId()).isEqualTo(HOME_TEAM);
            assertThat(saved.getOpponentTeamId()).isEqualTo(AWAY_TEAM);
            assertThat(saved.getKind()).isEqualTo(MatchKind.TOURNAMENT);
            assertThat(saved.getTournamentFixtureId()).isEqualTo(FIXTURE_ID);
            assertThat(saved.getHomeScore()).isEqualTo(2);
            assertThat(saved.getAwayScore()).isEqualTo(1);
            assertThat(saved.getStatus()).isEqualTo(MatchStatus.COMPLETED);
            // 大会直接入力は記録係なし（共同記録扱い）
            assertThat(saved.isHasScorekeeper()).isFalse();
            assertThat(result).isEqualTo(newId);
            // MatchCompletedEvent は発火させない（系統B は fixture 同期書込・二重発火回避）
            verify(eventPublisher, never()).publishEvent(any(MatchCompletedEvent.class));
        }

        @Test
        @DisplayName("冪等: 同一 fixture の既存 match があれば新規作成せず更新（二重起票しない）")
        void updatesExistingMatchIdempotent() {
            MatchEntity existing = MatchEntity.builder()
                    .organizationId(ORG).teamId(HOME_TEAM).sport(Sport.SOCCER)
                    .stateModel(StateModel.CONTINUOUS_TIME).kind(MatchKind.TOURNAMENT)
                    .tournamentFixtureId(FIXTURE_ID).homeAway(com.mannschaft.app.match.domain.HomeAway.HOME)
                    .homeScore(1).awayScore(0).status(MatchStatus.COMPLETED)
                    .hasScorekeeper(false).createdBy(ACTOR).build();
            when(matchRepository
                    .findFirstByOrganizationIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                            ORG, FIXTURE_ID))
                    .thenReturn(Optional.of(existing));
            when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // 訂正入力 3-2 + PK 5-4
            service.recordTournamentScore(cmd(3, 2, 5, 4));

            ArgumentCaptor<MatchEntity> captor = ArgumentCaptor.forClass(MatchEntity.class);
            verify(matchRepository).save(captor.capture());
            MatchEntity saved = captor.getValue();
            // 同一インスタンス（新規作成していない＝二重起票しない）
            assertThat(saved).isSameAs(existing);
            // 全列上書き（置換・冪等）
            assertThat(saved.getHomeScore()).isEqualTo(3);
            assertThat(saved.getAwayScore()).isEqualTo(2);
            assertThat(saved.getHomePenaltyScore()).isEqualTo(5);
            assertThat(saved.getAwayPenaltyScore()).isEqualTo(4);
            assertThat(saved.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        }

        @Test
        @DisplayName("幽霊重複根治: 入口①で away team 帰属の match が既存でも fixtureId 基準で引き当て、新規作成せず更新（team帰属不変）")
        void resolvesAwayTeamAttributedExistingMatchWithoutDuplicate() {
            // 入口①（match UI）で、当該 fixture に対し away participant の team が主体（team_id=AWAY_TEAM）の
            // match が先に作られている状況を再現する。系統B（home team_id で正本化）が team_id 違いで lookup すると
            // この既存 match を引けず home 帰属 skeletal match を新規作成し、1 fixture に match 2 件の幽霊重複となる。
            // 冪等キーを (org, fixtureId) に堅牢化したことで、team 帰属によらず既存 match を引き当て更新に徹する。
            MatchEntity existingAwayAttributed = MatchEntity.builder()
                    .organizationId(ORG).teamId(AWAY_TEAM).sport(Sport.SOCCER)
                    .stateModel(StateModel.CONTINUOUS_TIME).kind(MatchKind.TOURNAMENT)
                    .tournamentFixtureId(FIXTURE_ID)
                    .homeAway(com.mannschaft.app.match.domain.HomeAway.AWAY)
                    .opponentTeamId(HOME_TEAM).opponentName("相手FC")
                    .homeScore(0).awayScore(0).status(MatchStatus.SCHEDULED)
                    .hasScorekeeper(false).createdBy(ACTOR).build();
            UUID existingId = UUID.randomUUID();
            org.springframework.test.util.ReflectionTestUtils.setField(existingAwayAttributed, "id", existingId);

            // team 帰属に依存しない fixtureId 基準の lookup で既存 match を引き当てる。
            when(matchRepository
                    .findFirstByOrganizationIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                            ORG, FIXTURE_ID))
                    .thenReturn(Optional.of(existingAwayAttributed));
            when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // 系統B は home participant の team_id（HOME_TEAM）でコマンドを組む（cmd は teamId=HOME_TEAM）。
            UUID result = service.recordTournamentScore(cmd(2, 1, null, null));

            ArgumentCaptor<MatchEntity> captor = ArgumentCaptor.forClass(MatchEntity.class);
            verify(matchRepository).save(captor.capture());
            MatchEntity saved = captor.getValue();
            // 既存（away帰属）の同一インスタンスを更新＝新規作成していない（幽霊重複が生じない）。
            assertThat(saved).isSameAs(existingAwayAttributed);
            assertThat(saved.getId()).isEqualTo(existingId);
            assertThat(result).isEqualTo(existingId);
            // team 帰属（team_id / home_away / opponent）は維持する（§H.1.2 side 帰属不変・系統B はスコア更新に徹する）。
            assertThat(saved.getTeamId()).isEqualTo(AWAY_TEAM);
            assertThat(saved.getHomeAway()).isEqualTo(com.mannschaft.app.match.domain.HomeAway.AWAY);
            assertThat(saved.getOpponentTeamId()).isEqualTo(HOME_TEAM);
            // スコアは正本として置換され status は COMPLETED 確定（home participant=HOME 固定ゆえ割当不変）。
            assertThat(saved.getHomeScore()).isEqualTo(2);
            assertThat(saved.getAwayScore()).isEqualTo(1);
            assertThat(saved.getStatus()).isEqualTo(MatchStatus.COMPLETED);
            // team 帰属付きの旧 lookup は使わない（fixtureId 基準のみ）。
            verify(matchRepository, never())
                    .findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                            any(), any(), any());
        }

        @Test
        @DisplayName("異常系: org/team/fixtureId が欠けると MATCH_024（正本化に必要な帰属不足）")
        void missingAttributionThrows() {
            assertThatThrownBy(() -> service.recordTournamentScore(
                    MatchService.RecordTournamentScoreCommand.builder()
                            .organizationId(ORG).teamId(null).tournamentFixtureId(FIXTURE_ID)
                            .homeScore(1).awayScore(0).build()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchErrorCode.MATCH_024);

            verify(matchRepository, never()).save(any());
        }

        @Test
        @DisplayName("duration 非要求: CONTINUOUS_TIME でも duration なしで COMPLETED 確定（assertCompletable 非適用）")
        void doesNotRequireDuration() {
            when(matchRepository
                    .findFirstByOrganizationIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
                            ORG, FIXTURE_ID))
                    .thenReturn(Optional.empty());
            UUID newId = UUID.randomUUID();
            when(matchRepository.save(any())).thenAnswer(inv -> {
                MatchEntity m = inv.getArgument(0);
                org.springframework.test.util.ReflectionTestUtils.setField(m, "id", newId);
                return m;
            });

            // duration を一切渡さない。assertCompletable が走るなら MATCH_023 で落ちるはずだが、走らない。
            UUID result = service.recordTournamentScore(cmd(1, 0, null, null));

            assertThat(result).isEqualTo(newId);
            ArgumentCaptor<MatchEntity> captor = ArgumentCaptor.forClass(MatchEntity.class);
            verify(matchRepository).save(captor.capture());
            assertThat(captor.getValue().getDurationMinutes()).isNull();
            assertThat(captor.getValue().getStatus()).isEqualTo(MatchStatus.COMPLETED);
        }
    }
}
