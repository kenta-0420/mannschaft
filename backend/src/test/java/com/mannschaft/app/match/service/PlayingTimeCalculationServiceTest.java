package com.mannschaft.app.match.service;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.PlayerAppearanceEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.PlayerAppearanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlayingTimeCalculationService#computeAppearance} の出場区間算出 UT（02 §E.6 ケース表）。
 *
 * <p>純 UT（Repository 不要）。区間組み立てロジック（複数交代/再出場/延長/退場/duration 未設定）を網羅する。</p>
 */
class PlayingTimeCalculationServiceTest {

    private final MatchEventRepository eventRepo = mock(MatchEventRepository.class);
    private final PlayerAppearanceRepository appearanceRepo = mock(PlayerAppearanceRepository.class);
    private final PlayingTimeCalculationService service =
            new PlayingTimeCalculationService(eventRepo, appearanceRepo);

    private MatchEventEntity ev(MatchEventType type, PeriodType period, Integer minute, int seq) {
        return MatchEventEntity.builder()
                .matchId(java.util.UUID.randomUUID())
                .eventType(type)
                .period(period)
                .minute(minute)
                .teamSide(TeamSide.HOME)
                .sortSeq(seq)
                .build();
    }

    @Test
    @DisplayName("フル出場（STARTER・交代なし・duration=90）→ computed=90/first=0/last=90/starter")
    void fullMatch() {
        var result = service.computeAppearance(
                List.of(ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0)), 90);
        assertThat(result.starter()).isTrue();
        assertThat(result.firstInMinute()).isZero();
        assertThat(result.lastOutMinute()).isEqualTo(90);
        assertThat(result.computedMinutes()).isEqualTo(90);
    }

    @Test
    @DisplayName("後半 60 分から出場（SUB_IN@60・duration=90）→ computed=30/first=60/last=90/非先発")
    void subInFromBench() {
        var result = service.computeAppearance(
                List.of(ev(MatchEventType.SUB_IN, PeriodType.SECOND_HALF, 60, 0)), 90);
        assertThat(result.starter()).isFalse();
        assertThat(result.firstInMinute()).isEqualTo(60);
        assertThat(result.lastOutMinute()).isEqualTo(90);
        assertThat(result.computedMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("70 分で交代 OUT（STARTER＋SUB_OUT@70）→ computed=70/first=0/last=70")
    void subbedOut() {
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.SUB_OUT, PeriodType.SECOND_HALF, 70, 1)), 90);
        assertThat(result.firstInMinute()).isZero();
        assertThat(result.lastOutMinute()).isEqualTo(70);
        assertThat(result.computedMinutes()).isEqualTo(70);
    }

    @Test
    @DisplayName("再出場（STARTER→SUB_OUT@30→SUB_IN@60・duration=90）→ computed=30+30=60（区間合計）")
    void reEntry() {
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.SUB_OUT, PeriodType.FIRST_HALF, 30, 1),
                ev(MatchEventType.SUB_IN, PeriodType.SECOND_HALF, 60, 2)), 90);
        assertThat(result.computedMinutes()).isEqualTo(60);
        assertThat(result.firstInMinute()).isZero();
        assertThat(result.lastOutMinute()).isEqualTo(90);
    }

    @Test
    @DisplayName("延長出場（STARTER・交代なし・duration=120）→ computed=120（試合通算分で正規化）")
    void extraTime() {
        var result = service.computeAppearance(
                List.of(ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0)), 120);
        assertThat(result.computedMinutes()).isEqualTo(120);
        assertThat(result.lastOutMinute()).isEqualTo(120);
    }

    @Test
    @DisplayName("80 分で一発退場（STARTER＋RED@80）→ computed=80/last=80（退場で区間確定）")
    void redCard() {
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.RED_CARD, PeriodType.SECOND_HALF, 80, 1)), 90);
        assertThat(result.computedMinutes()).isEqualTo(80);
        assertThat(result.lastOutMinute()).isEqualTo(80);
    }

    @Test
    @DisplayName("2 枚目の警告で退場（STARTER＋SECOND_YELLOW@85）→ computed=85（退場で out 確定）")
    void secondYellow() {
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.SECOND_YELLOW, PeriodType.SECOND_HALF, 85, 1)), 90);
        assertThat(result.computedMinutes()).isEqualTo(85);
    }

    @Test
    @DisplayName("SUB_OUT@70 と RED@65 が両方（異常）→ より早い分 65 を out とする")
    void anomalyEarliestOut() {
        // ソート後は RED@65 が先・SUB_OUT@70 が後。RED@65 で区間が閉じ、SUB_OUT@70 は開区間なしで無視される
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.RED_CARD, PeriodType.SECOND_HALF, 65, 1),
                ev(MatchEventType.SUB_OUT, PeriodType.SECOND_HALF, 70, 2)), 90);
        assertThat(result.computedMinutes()).isEqualTo(65);
        assertThat(result.lastOutMinute()).isEqualTo(70); // 最後の退出イベントが代表 last_out（表示用）
    }

    @Test
    @DisplayName("duration 未設定で out 未確定区間あり → computed=NULL（不明・ゼロ埋めしない）")
    void durationUnsetUnknown() {
        var result = service.computeAppearance(
                List.of(ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0)), null);
        assertThat(result.computedMinutes()).isNull();
        assertThat(result.firstInMinute()).isZero();
    }

    @Test
    @DisplayName("duration 未設定でも SUB_OUT で閉じれば computed 確定（=70）")
    void durationUnsetButClosed() {
        var result = service.computeAppearance(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0),
                ev(MatchEventType.SUB_OUT, PeriodType.SECOND_HALF, 70, 1)), null);
        assertThat(result.computedMinutes()).isEqualTo(70);
    }

    @Test
    @DisplayName("出場影響なしのイベントだけ → 区間ゼロ・computed=0（duration 与えても開区間なし）")
    void noAppearanceEvents() {
        // computeAppearance は呼び出し側で出場影響イベントのみ渡される想定だが、
        // GOAL のみが渡っても区間が開かないため total=0（unknown ではない）
        var result = service.computeAppearance(
                List.of(ev(MatchEventType.GOAL, PeriodType.FIRST_HALF, 20, 0)), 90);
        assertThat(result.computedMinutes()).isZero();
        assertThat(result.starter()).isFalse();
    }

    // ─── 破壊耐性: フル同期削除は編集権限 side に限定（02 §E.5a） ──────

    @Test
    @DisplayName("recalculate: events に無い AWAY appearance も editableSides=HOME のみなら削除しない（相手分破壊防止）")
    void recalcDoesNotDeleteOpponentSide() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder().durationMinutes(90).build();
        match.setId(matchId);

        // 今回イベントは無し（誰も出場記録に現れない）
        when(eventRepo.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).thenReturn(List.of());

        // 既存 appearance: HOME と AWAY 各 1（events に現れなくなった＝削除候補）
        PlayerAppearanceEntity homeAp = PlayerAppearanceEntity.builder()
                .matchId(matchId).playerUserId(11L).teamSide(TeamSide.HOME).owningTeamId(100L).build();
        PlayerAppearanceEntity awayAp = PlayerAppearanceEntity.builder()
                .matchId(matchId).playerUserId(22L).teamSide(TeamSide.AWAY).owningTeamId(200L).build();
        when(appearanceRepo.findByMatchId(matchId)).thenReturn(List.of(homeAp, awayAp));

        // 編集権限は HOME のみ
        service.recalculate(match, EnumSet.of(TeamSide.HOME));

        // HOME appearance は削除されるが、AWAY appearance（相手分）は削除されないこと
        ArgumentCaptor<PlayerAppearanceEntity> captor = ArgumentCaptor.forClass(PlayerAppearanceEntity.class);
        verify(appearanceRepo).delete(captor.capture());
        assertThat(captor.getValue().getTeamSide()).isEqualTo(TeamSide.HOME);
    }

    @Test
    @DisplayName("recalculate: editableSides=null（記録係＝全 side 編集権）なら両 side 削除同期される")
    void recalcNullSidesDeletesAll() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder().durationMinutes(90).build();
        match.setId(matchId);
        when(eventRepo.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).thenReturn(List.of());

        PlayerAppearanceEntity homeAp = PlayerAppearanceEntity.builder()
                .matchId(matchId).playerUserId(11L).teamSide(TeamSide.HOME).owningTeamId(100L).build();
        PlayerAppearanceEntity awayAp = PlayerAppearanceEntity.builder()
                .matchId(matchId).playerUserId(22L).teamSide(TeamSide.AWAY).owningTeamId(200L).build();
        when(appearanceRepo.findByMatchId(matchId)).thenReturn(List.of(homeAp, awayAp));

        service.recalculate(match, null);

        verify(appearanceRepo, org.mockito.Mockito.times(2)).delete(any());
    }

    @Test
    @DisplayName("recalculate: matches.version に触れない（matchRepository.save を呼ばない＝楽観ロック回避）")
    void recalcDoesNotTouchMatchVersion() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder().durationMinutes(90).build();
        match.setId(matchId);
        when(eventRepo.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).thenReturn(List.of(
                ev(MatchEventType.STARTER, PeriodType.FIRST_HALF, 0, 0).toBuilder()
                        .playerUserId(11L).build()));
        when(appearanceRepo.findByMatchId(matchId)).thenReturn(List.of());
        when(appearanceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recalculate(match, null);

        // appearance は保存されるが、matches は本サービスでは保存しない（version 非依存・02 §E.2）
        verify(appearanceRepo).save(any());
    }

    // ─── 状態モデル類型分岐: TURN_BASED は出場時間算出をスキップ（01 §D.6） ──────

    @Test
    @DisplayName("recalculate: TURN_BASED（将棋）は算出を起動しない（イベント取得も appearance 操作もしない）")
    void recalcSkipsTurnBased() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder()
                .sport(com.mannschaft.app.match.domain.Sport.SHOGI)
                .stateModel(com.mannschaft.app.match.domain.StateModel.TURN_BASED)
                .build();
        match.setId(matchId);

        service.recalculate(match, null);

        // ターン制は STARTER/SUB が存在せず区間が組み立たないため、リポジトリに一切触れない
        verify(eventRepo, never()).findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(any());
        verify(appearanceRepo, never()).findByMatchId(any());
        verify(appearanceRepo, never()).save(any());
        verify(appearanceRepo, never()).delete(any());
    }

    @Test
    @DisplayName("recalculate: state_model 未設定でも sport=GO から TURN_BASED を導出してスキップ")
    void recalcSkipsTurnBasedDerivedFromSport() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder()
                .sport(com.mannschaft.app.match.domain.Sport.GO)
                .build(); // state_model 明示なし（古いレコード相当）
        match.setId(matchId);

        service.recalculate(match, null);

        verify(eventRepo, never()).findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(any());
        verify(appearanceRepo, never()).save(any());
    }

    @Test
    @DisplayName("recalculate: SCORED（フィギュア）は算出を起動しない（出場交代概念なし・01 §D.8）")
    void recalcSkipsScored() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder()
                .sport(com.mannschaft.app.match.domain.Sport.FIGURE_SKATING)
                .stateModel(com.mannschaft.app.match.domain.StateModel.SCORED)
                .build();
        match.setId(matchId);

        service.recalculate(match, null);

        // 採点競技は STARTER/SUB が存在せず区間が組み立たないため、リポジトリに一切触れない
        verify(eventRepo, never()).findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(any());
        verify(appearanceRepo, never()).findByMatchId(any());
        verify(appearanceRepo, never()).save(any());
        verify(appearanceRepo, never()).delete(any());
    }

    @Test
    @DisplayName("recalculate: state_model 未設定でも sport=GYMNASTICS から SCORED を導出してスキップ")
    void recalcSkipsScoredDerivedFromSport() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder()
                .sport(com.mannschaft.app.match.domain.Sport.GYMNASTICS)
                .build(); // state_model 明示なし（古いレコード相当）
        match.setId(matchId);

        service.recalculate(match, null);

        verify(eventRepo, never()).findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(any());
        verify(appearanceRepo, never()).save(any());
    }

    @Test
    @DisplayName("recalculate: CONTINUOUS_TIME（サッカー）は通常どおり算出を起動する")
    void recalcRunsForContinuousTime() {
        UUID matchId = UUID.randomUUID();
        MatchEntity match = MatchEntity.builder()
                .sport(com.mannschaft.app.match.domain.Sport.SOCCER)
                .stateModel(com.mannschaft.app.match.domain.StateModel.CONTINUOUS_TIME)
                .durationMinutes(90)
                .build();
        match.setId(matchId);
        when(eventRepo.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId)).thenReturn(List.of());
        when(appearanceRepo.findByMatchId(matchId)).thenReturn(List.of());

        service.recalculate(match, null);

        verify(eventRepo).findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchId);
    }
}
