package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.repository.MatchScoredComponentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchScoredComponentService} の純 UT
 * （test-first・sports/07_scored.md §4B / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p>採点内訳→合計点の二層正本再導出（フィギュア TES+PCS−DEDUCTION・体操 D+E）・MVP 合計点直接入力との両立・
 * 勝敗導出の不変（合計点大小）・採点競技以外の拒否（MATCH_029）・カタログ列挙整合（MATCH_024）・
 * 採点改竄防止の監査記録・観戦配信を Mockito モックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchScoredComponentService（採点内訳→合計点集計・二層正本）UT")
class MatchScoredComponentServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchScoredComponentRepository componentRepository;
    @Mock
    private MatchRepository matchRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MatchScoredComponentService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM)
                .sport(Sport.FIGURE_SKATING)
                .stateModel(StateModel.SCORED)
                .status(MatchStatus.IN_PROGRESS)
                .createdBy(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(match);
        lenient().when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MatchScoredComponentService.ScoredComponentLine line(
            TeamSide side, ScoredComponentType type, ScoredApparatus apparatus, int points) {
        return MatchScoredComponentService.ScoredComponentLine.builder()
                .competitorSide(side)
                .componentType(type)
                .apparatus(apparatus)
                .pointsScaled(points)
                .build();
    }

    private MatchScoredComponentService.ScoredComponentsCommand cmd(
            MatchScoredComponentService.ScoredComponentLine... lines) {
        return MatchScoredComponentService.ScoredComponentsCommand.builder()
                .lines(List.of(lines))
                .build();
    }

    @Test
    @DisplayName("フィギュア: TES+PCS−DEDUCTION を side ごとに集計し home/away_score へ再導出する")
    void figureAggregatesTesPlusPcsMinusDeduction() {
        // HOME: TES 88.43(88430) + PCS 90.00(90000) − 減点 1.00(1000) = 177.43 → 177430
        // AWAY: TES 80.00(80000) + PCS 85.00(85000) = 165.00 → 165000
        MatchEntity saved = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 88430),
                line(TeamSide.HOME, ScoredComponentType.PCS, ScoredApparatus.SP, 90000),
                line(TeamSide.HOME, ScoredComponentType.DEDUCTION, ScoredApparatus.SP, 1000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.SP, 80000),
                line(TeamSide.AWAY, ScoredComponentType.PCS, ScoredApparatus.SP, 85000)));

        assertThat(saved.getHomeScore()).isEqualTo(177430);
        assertThat(saved.getAwayScore()).isEqualTo(165000);
        assertThat(saved.getWinMethod()).isNull();
        // 全置換: 既存削除 → 5 行保存
        verify(componentRepository).deleteByMatchId(matchId);
        verify(componentRepository, times(5)).save(any());
    }

    @Test
    @DisplayName("体操: D+E を種目別に積み上げて side 合計（個人総合）へ再導出する")
    void gymnasticsAggregatesDPlusEAcrossApparatuses() {
        // HOME 床: D 5.6(5600)+E 8.5(8500) / あん馬: D 6.0(6000)+E 8.0(8000) = 28.1 → 28100
        // AWAY 床: D 5.0(5000)+E 8.0(8000) = 13.0 → 13000
        MatchEntity saved = service.recordComponents(matchId(Sport.GYMNASTICS), ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.D_SCORE, ScoredApparatus.FLOOR, 5600),
                line(TeamSide.HOME, ScoredComponentType.E_SCORE, ScoredApparatus.FLOOR, 8500),
                line(TeamSide.HOME, ScoredComponentType.D_SCORE, ScoredApparatus.POMMEL_HORSE, 6000),
                line(TeamSide.HOME, ScoredComponentType.E_SCORE, ScoredApparatus.POMMEL_HORSE, 8000),
                line(TeamSide.AWAY, ScoredComponentType.D_SCORE, ScoredApparatus.FLOOR, 5000),
                line(TeamSide.AWAY, ScoredComponentType.E_SCORE, ScoredApparatus.FLOOR, 8000)));

        assertThat(saved.getHomeScore()).isEqualTo(28100);
        assertThat(saved.getAwayScore()).isEqualTo(13000);
    }

    /** sport を差し替えた match を返すためのヘルパー（GYMNASTICS など）。 */
    private UUID matchId(Sport sport) {
        match.setSport(sport);
        match.setStateModel(StateModel.SCORED);
        return matchId;
    }

    @Test
    @DisplayName("勝敗は再導出後の合計点大小で決まる（高い側が勝者・§B.1.2 不変）")
    void winnerDeterminedByAggregatedTotal() {
        MatchEntity saved = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.FS, 120000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.FS, 110000)));
        assertThat(saved.getHomeScore()).isGreaterThan(saved.getAwayScore());

        MatchEntity saved2 = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.FS, 100000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.FS, 130000)));
        assertThat(saved2.getAwayScore()).isGreaterThan(saved2.getHomeScore());
    }

    @Test
    @DisplayName("再導出後の合計が同値なら引分（home==away・§6）")
    void equalAggregatedTotalsAreDraw() {
        MatchEntity saved = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 90000),
                line(TeamSide.AWAY, ScoredComponentType.PCS, ScoredApparatus.SP, 90000)));
        assertThat(saved.getHomeScore()).isEqualTo(saved.getAwayScore()).isEqualTo(90000);
    }

    @Test
    @DisplayName("減点が加点を超える異常入力は 0 にクランプ（UNSIGNED・負を格納しない）")
    void clampsNegativeSideTotalToZero() {
        MatchEntity saved = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 1000),
                line(TeamSide.HOME, ScoredComponentType.DEDUCTION, ScoredApparatus.SP, 5000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.SP, 50000)));
        assertThat(saved.getHomeScore()).isZero();
        assertThat(saved.getAwayScore()).isEqualTo(50000);
    }

    @Test
    @DisplayName("採点記録は assertCanEditMeta（採点改竄防止権限）へ委譲する（§11 / 03 §C.7）")
    void delegatesToAssertCanEditMeta() {
        service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 90000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.SP, 80000)));
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    @Test
    @DisplayName("再導出した合計を before/after で監査記録する（MATCH_SCORE_FINALIZED・改竄防止）")
    void recordsAudit() {
        match.setHomeScore(1); // before
        match.setAwayScore(2);
        service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 90000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.SP, 80000)));
        verify(auditLogService).record(
                eq(AuditEventType.MATCH_SCORE_FINALIZED.name()), eq(ACTOR), isNull(),
                eq(TEAM), eq(ORG), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("記録後にスコア更新を観戦者へ配信する（§9・コミット後）")
    void publishesLiveScoreUpdate() {
        service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 90000),
                line(TeamSide.AWAY, ScoredComponentType.TES, ScoredApparatus.SP, 80000)));
        verify(eventPublisher).publishEvent(any(MatchLiveUpdateEvent.class));
    }

    @Test
    @DisplayName("採点競技（SCORED）以外への内訳記録は 400（MATCH_029・症状を隠さない）")
    void rejectsNonScoredSport() {
        match.setSport(Sport.SOCCER);
        match.setStateModel(StateModel.CONTINUOUS_TIME);
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, 90000))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_029);
        verify(componentRepository, never()).save(any());
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("当該競技のカタログ外 component_type は 400（フィギュアに D_SCORE・MATCH_024）")
    void rejectsComponentTypeOutOfCatalog() {
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.D_SCORE, ScoredApparatus.SP, 90000))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("当該競技のカタログ外 apparatus は 400（フィギュアに FLOOR・MATCH_024）")
    void rejectsApparatusOutOfCatalog() {
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.FLOOR, 90000))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("competitor_side 未指定は 400（2 者対戦 MVP・MATCH_024）")
    void rejectsNullSide() {
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(null, ScoredComponentType.TES, ScoredApparatus.SP, 90000))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("負の points_scaled は 400（減点は DEDUCTION 種別＋正の絶対値で表す・MATCH_024）")
    void rejectsNegativePoints() {
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.TES, ScoredApparatus.SP, -1))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("空の内訳は 400（MATCH_024）")
    void rejectsEmptyComponents() {
        MatchScoredComponentService.ScoredComponentsCommand empty =
                MatchScoredComponentService.ScoredComponentsCommand.builder().lines(List.of()).build();
        assertThatThrownBy(() -> service.recordComponents(matchId, ORG, ACTOR, empty))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("listComponents は match_id スコープで取得する（親テナントゲート後の二段アクセス・IDOR）")
    void listScopedByMatchId() {
        when(componentRepository.findByMatchIdOrderByCreatedAtAsc(matchId)).thenReturn(List.of());
        service.listComponents(matchId, ORG);
        verify(matchService).getMatchOrThrow(matchId, ORG);
        verify(componentRepository).findByMatchIdOrderByCreatedAtAsc(matchId);
    }

    @Test
    @DisplayName("state_model 未設定（旧レコード）でも sport から SCORED 導出して記録できる")
    void resolvesStateModelFromSportWhenColumnNull() {
        match.setStateModel(null);
        match.setSport(Sport.GYMNASTICS);
        MatchEntity saved = service.recordComponents(matchId, ORG, ACTOR, cmd(
                line(TeamSide.HOME, ScoredComponentType.D_SCORE, ScoredApparatus.FLOOR, 5000),
                line(TeamSide.HOME, ScoredComponentType.E_SCORE, ScoredApparatus.FLOOR, 8000)));
        assertThat(saved.getHomeScore()).isEqualTo(13000);
    }
}
