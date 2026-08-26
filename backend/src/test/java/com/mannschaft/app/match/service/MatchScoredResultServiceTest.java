package com.mannschaft.app.match.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.live.MatchLiveUpdateEvent;
import com.mannschaft.app.match.repository.MatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchScoredResultService} の純 UT
 * （test-first・sports/07_scored.md §4 / §11 / 01 §B.1.2 / §D.8）。
 *
 * <p>採点競技（フィギュア/体操）の合計点（整数スケール×1000）格納・勝敗導出（高い側が勝者・同点 DRAW）・
 * 採点競技以外への操作拒否（MATCH_029）・負値拒否（MATCH_024）・採点改竄防止の監査記録・観戦配信を
 * Mockito モックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchScoredResultService（採点競技・合計点記録）UT")
class MatchScoredResultServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM = 100L;
    private static final long ACTOR = 1L;

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
    private MatchScoredResultService service;

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

    private MatchScoredResultService.ScoredResultCommand cmd(Integer home, Integer away) {
        return MatchScoredResultService.ScoredResultCommand.builder()
                .homeScoreScaled(home)
                .awayScoreScaled(away)
                .build();
    }

    @Test
    @DisplayName("合計点（整数スケール×1000）を home/away_score へそのまま格納する（フィギュア 215.43→215430）")
    void storesScaledScores() {
        // 215.43 → 215430（HOME）/ 198.45 → 198450（AWAY）
        MatchEntity saved = service.recordScore(matchId, ORG, ACTOR, cmd(215430, 198450));

        assertThat(saved.getHomeScore()).isEqualTo(215430);
        assertThat(saved.getAwayScore()).isEqualTo(198450);
        // 採点競技は勝ち方を持たない（NULL を保証・§10）
        assertThat(saved.getWinMethod()).isNull();
    }

    @Test
    @DisplayName("勝敗は合計点の大小で導出される（高い側が勝者・§B.1.2）— 整数スケールの大小が保たれる")
    void higherScaledScoreWinsByContract() {
        // fig 215.43(215430) vs 198.45(198450) → HOME の格納値が大きい＝resolveResult で HOME 勝ち
        MatchEntity saved = service.recordScore(matchId, ORG, ACTOR, cmd(215430, 198450));
        assertThat(saved.getHomeScore()).isGreaterThan(saved.getAwayScore());

        // 逆順（AWAY が高い）も格納値の大小が保たれる
        MatchEntity saved2 = service.recordScore(matchId, ORG, ACTOR, cmd(198450, 215430));
        assertThat(saved2.getAwayScore()).isGreaterThan(saved2.getHomeScore());
    }

    @Test
    @DisplayName("同点（整数スケール同値）は引分扱いとなる格納（home==away・§6）")
    void equalScaledScoresStoredAsDraw() {
        // 体操 85.332 → 85332（両者同値＝引分）
        MatchEntity saved = service.recordScore(matchId, ORG, ACTOR, cmd(85332, 85332));
        assertThat(saved.getHomeScore()).isEqualTo(saved.getAwayScore()).isEqualTo(85332);
    }

    @Test
    @DisplayName("採点記録は assertCanEditMeta（採点改竄防止権限）へ委譲する（§11 / 03 §C.7）")
    void delegatesToAssertCanEditMeta() {
        service.recordScore(matchId, ORG, ACTOR, cmd(100000, 90000));
        verify(matchAccessService).assertCanEditMeta(ACTOR, match);
    }

    @Test
    @DisplayName("採点記録は before/after を監査記録する（MATCH_SCORE_FINALIZED・改竄防止）")
    void recordsAudit() {
        match.setHomeScore(100000); // before
        match.setAwayScore(90000);
        service.recordScore(matchId, ORG, ACTOR, cmd(215430, 198450));
        verify(auditLogService).record(
                eq(AuditEventType.MATCH_SCORE_FINALIZED.name()), eq(ACTOR), isNull(),
                eq(TEAM), eq(ORG), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("採点記録後にスコア更新を観戦者へ配信する（§9・コミット後）")
    void publishesLiveScoreUpdate() {
        service.recordScore(matchId, ORG, ACTOR, cmd(215430, 198450));
        verify(eventPublisher).publishEvent(any(MatchLiveUpdateEvent.class));
    }

    @Test
    @DisplayName("採点競技（SCORED）以外への記録は 400（MATCH_029・症状を隠さない）")
    void rejectsNonScoredSport() {
        match.setSport(Sport.SOCCER);
        match.setStateModel(StateModel.CONTINUOUS_TIME);

        assertThatThrownBy(() -> service.recordScore(matchId, ORG, ACTOR, cmd(1, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_029);
        verify(matchRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("負の合計点は 400（MATCH_024・整数スケールは非負）")
    void rejectsNegativeScore() {
        assertThatThrownBy(() -> service.recordScore(matchId, ORG, ACTOR, cmd(-1, 100000)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("合計点が NULL（未入力）は 400（MATCH_024）")
    void rejectsNullScore() {
        assertThatThrownBy(() -> service.recordScore(matchId, ORG, ACTOR, cmd(null, 100000)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(MatchErrorCode.MATCH_024);
        verify(matchRepository, never()).save(any());
    }

    @Test
    @DisplayName("state_model 未設定（旧レコード）でも sport から SCORED 導出して記録できる")
    void resolvesStateModelFromSportWhenColumnNull() {
        match.setStateModel(null); // 列未設定
        match.setSport(Sport.GYMNASTICS);

        MatchEntity saved = service.recordScore(matchId, ORG, ACTOR, cmd(85332, 84100));
        assertThat(saved.getHomeScore()).isEqualTo(85332);
    }
}
