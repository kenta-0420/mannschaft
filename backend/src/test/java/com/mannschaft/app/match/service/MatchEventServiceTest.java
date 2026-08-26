package com.mannschaft.app.match.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MatchEventService} の検証・IDOR UT（03 §C.4a/C.4b・純 UT）。
 *
 * <p>card_reason_code 二段検証 / event_type カタログ / linked_event_id 同一 match 帰属 /
 * 親子 match_id 不一致 404 / note サニタイズ を網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
class MatchEventServiceTest {

    private static final long ORG = 50L;
    private static final long TEAM_HOME = 100L;
    private static final long ACTOR = 1L;

    @Mock
    private MatchEventRepository matchEventRepository;
    @Mock
    private MatchService matchService;
    @Mock
    private MatchAccessService matchAccessService;
    @Mock
    private PlayingTimeCalculationService playingTimeCalculationService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MatchEventService service;

    private UUID matchId;
    private MatchEntity match;

    @BeforeEach
    void setUp() {
        matchId = UUID.randomUUID();
        match = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM_HOME)
                .sport(Sport.SOCCER)
                .hasScorekeeper(true)
                .scorekeeperUserId(ACTOR)
                .build();
        match.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(match);
    }

    private MatchEventService.EventCommand.EventCommandBuilder baseGoal() {
        return MatchEventService.EventCommand.builder()
                .eventType(MatchEventType.GOAL)
                .period(PeriodType.FIRST_HALF)
                .teamSide(TeamSide.HOME)
                .minute(20)
                .recordedByTeamId(TEAM_HOME);
    }

    @Test
    @DisplayName("正常な GOAL は記録され再計算がトリガされる")
    void recordGoalOk() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR, baseGoal().build());
        // 再計算トリガ（02 §E.2）
        verify(playingTimeCalculationService).recalculate(eq(match), any());
    }

    // ─── event_type カタログ検証（400） ──────────────────────

    @Test
    @DisplayName("バスケ用などカタログ外 event_type は 400（MATCH_020）—サッカーで全 enum 値は許容なので null で検証")
    void eventTypeNullIs400() {
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR, baseGoal().eventType(null).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不備");
    }

    // ─── card_reason_code 二段検証（03 §C.4b） ───────────────

    @Test
    @DisplayName("YELLOW_CARD に CautionCode C2 は整合（OK）")
    void yellowWithCautionCodeOk() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = baseGoal()
                .eventType(MatchEventType.YELLOW_CARD)
                .cardReasonCode("C2")
                .build();
        service.record(matchId, ORG, ACTOR, cmd);
    }

    @Test
    @DisplayName("YELLOW_CARD に退場コード S1 は不整合 → 400（MATCH_021）")
    void yellowWithSendingOffCode400() {
        var cmd = baseGoal()
                .eventType(MatchEventType.YELLOW_CARD)
                .cardReasonCode("S1")
                .build();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("理由コード");
    }

    @Test
    @DisplayName("RED_CARD に S2 は整合（OK）/ CS は一発退場では不可 → 400")
    void redCardCodes() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().eventType(MatchEventType.RED_CARD).cardReasonCode("S2").build());

        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().eventType(MatchEventType.RED_CARD).cardReasonCode("CS").build()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("SECOND_YELLOW は CS のみ整合")
    void secondYellowCs() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().eventType(MatchEventType.SECOND_YELLOW).cardReasonCode("CS").build());
    }

    @Test
    @DisplayName("非カード系 event_type（GOAL）に card_reason_code を付けると 400")
    void nonCardWithCode400() {
        var cmd = baseGoal().eventType(MatchEventType.GOAL).cardReasonCode("C1").build();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR, cmd))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("card_reason_code=null は常に OK（任意・後から補完）")
    void nullCodeOk() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().eventType(MatchEventType.YELLOW_CARD).cardReasonCode(null).build());
    }

    // ─── sport 別 理由コード ディスパッチ（03 §C.4b・バスケ配線） ──

    /** バスケ試合（連続時間制・4Q）。 */
    private MatchEntity basketballMatch() {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM_HOME)
                .sport(Sport.BASKETBALL)
                .hasScorekeeper(true)
                .scorekeeperUserId(ACTOR)
                .build();
        m.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(m);
        return m;
    }

    private MatchEventService.EventCommand.EventCommandBuilder baseBasketballFoul() {
        return MatchEventService.EventCommand.builder()
                .eventType(MatchEventType.PERSONAL_FOUL)
                .period(PeriodType.QUARTER_1)
                .teamSide(TeamSide.HOME)
                .minute(5)
                .recordedByTeamId(TEAM_HOME);
    }

    @Test
    @DisplayName("バスケ: PERSONAL_FOUL に PF は整合（OK・BasketballFoulReasonCatalog 配線）")
    void basketballPersonalFoulWithPfOk() {
        basketballMatch();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR, baseBasketballFoul().cardReasonCode("PF").build());
    }

    @Test
    @DisplayName("バスケ: PERSONAL_FOUL に TF（テクニカル専用コード）は不整合 → 400（MATCH_021）")
    void basketballPersonalFoulWithTf400() {
        basketballMatch();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseBasketballFoul().eventType(MatchEventType.PERSONAL_FOUL).cardReasonCode("TF").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("理由コード");
    }

    @Test
    @DisplayName("バスケ: TECHNICAL_FOUL に TF は整合（OK）")
    void basketballTechnicalFoulWithTfOk() {
        basketballMatch();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseBasketballFoul().eventType(MatchEventType.TECHNICAL_FOUL).cardReasonCode("TF").build());
    }

    @Test
    @DisplayName("バスケ: FOUL_OUT に DF は整合（OK）/ NULL も OK（5 ファウル累積退場）")
    void basketballFoulOutCodes() {
        basketballMatch();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseBasketballFoul().eventType(MatchEventType.FOUL_OUT).cardReasonCode("DF").build());
        service.record(matchId, ORG, ACTOR,
                baseBasketballFoul().eventType(MatchEventType.FOUL_OUT).cardReasonCode(null).build());
    }

    @Test
    @DisplayName("バスケ: サッカーの警告コード C2 をバスケに付けると 400（C/S 流用禁止・03 §5）")
    void basketballWithSoccerCautionCode400() {
        basketballMatch();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseBasketballFoul().eventType(MatchEventType.PERSONAL_FOUL).cardReasonCode("C2").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("理由コード");
    }

    @Test
    @DisplayName("サッカー: バスケのファウルコード PF をサッカーの YELLOW_CARD に付けると 400（逆流用禁止）")
    void soccerWithBasketballFoulCode400() {
        // setUp の match は SOCCER
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().eventType(MatchEventType.YELLOW_CARD).cardReasonCode("PF").build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("理由コード");
    }

    // ─── linked_event_id 同一 match 帰属（03 §C.4a） ──────────

    @Test
    @DisplayName("linked_event_id が同一 match の既存イベントなら OK")
    void linkedSameMatchOk() {
        UUID linkedId = UUID.randomUUID();
        MatchEventEntity linked = MatchEventEntity.builder().matchId(matchId).build();
        linked.setId(linkedId);
        when(matchEventRepository.findById(linkedId)).thenReturn(Optional.of(linked));
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.record(matchId, ORG, ACTOR, baseGoal().linkedEventId(linkedId).build());
    }

    @Test
    @DisplayName("linked_event_id が別 match のイベントなら 404（MATCH_022・越境遮断）")
    void linkedCrossMatch404() {
        UUID linkedId = UUID.randomUUID();
        MatchEventEntity linked = MatchEventEntity.builder().matchId(UUID.randomUUID()).build();
        linked.setId(linkedId);
        when(matchEventRepository.findById(linkedId)).thenReturn(Optional.of(linked));

        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().linkedEventId(linkedId).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("連鎖先");
    }

    @Test
    @DisplayName("linked_event_id が存在しないなら 404")
    void linkedNotFound404() {
        UUID linkedId = UUID.randomUUID();
        when(matchEventRepository.findById(linkedId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().linkedEventId(linkedId).build()))
                .isInstanceOf(BusinessException.class);
    }

    // ─── IDOR: 親子 match_id 不一致（update/delete で 404） ────

    @Test
    @DisplayName("update: イベントの match_id がパス matchId と不一致なら 404（IDOR）")
    void updateParentChildMismatch404() {
        UUID eventId = UUID.randomUUID();
        MatchEventEntity other = MatchEventEntity.builder().matchId(UUID.randomUUID()).build();
        other.setId(eventId);
        when(matchEventRepository.findById(eventId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(matchId, eventId, ORG, ACTOR, baseGoal().build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("イベントが見つかりません");
    }

    @Test
    @DisplayName("delete: 存在しないイベントは 404")
    void deleteNotFound404() {
        UUID eventId = UUID.randomUUID();
        when(matchEventRepository.findById(eventId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(matchId, eventId, ORG, ACTOR))
                .isInstanceOf(BusinessException.class);
    }

    // ─── team_side ↔ recorded_by_team_id 整合不変条件（03 §C.4a・補正1） ──

    private static final long TEAM_AWAY = 200L;

    /** 登録相手あり・共同記録の試合（HOME=100 / AWAY=200）。 */
    private MatchEntity coopMatchWithOpponent() {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG)
                .teamId(TEAM_HOME)
                .opponentTeamId(TEAM_AWAY)
                .sport(Sport.SOCCER)
                .hasScorekeeper(false)
                .build();
        m.setId(matchId);
        lenient().when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(m);
        return m;
    }

    @Test
    @DisplayName("HOME イベントの recorded_by_team_id が match.teamId と一致なら OK")
    void homeSideMatchesHomeTeamOk() {
        coopMatchWithOpponent();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.HOME).recordedByTeamId(TEAM_HOME).build());
    }

    @Test
    @DisplayName("HOME イベントを AWAY チーム名義で記録 → 403（MATCH_025・自名義捏造防止）")
    void homeSideWithAwayTeamForbidden() {
        coopMatchWithOpponent();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.HOME).recordedByTeamId(TEAM_AWAY).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("記録名義");
    }

    @Test
    @DisplayName("登録相手あり: AWAY イベントを自チーム(HOME)名義で記録 → 403（相手サイド捏造遮断）")
    void awaySideWithHomeTeamForbiddenWhenOpponentRegistered() {
        coopMatchWithOpponent();
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.AWAY).recordedByTeamId(TEAM_HOME).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("記録名義");
    }

    @Test
    @DisplayName("登録相手あり: AWAY イベントを相手チーム名義で記録 → OK")
    void awaySideWithAwayTeamOk() {
        coopMatchWithOpponent();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.AWAY).recordedByTeamId(TEAM_AWAY).build());
    }

    @Test
    @DisplayName("未登録相手(opponent_team_id=NULL): AWAY イベントをホーム名義で代行記録 → OK")
    void awaySideHomeProxyAllowedWhenOpponentUnregistered() {
        MatchEntity m = MatchEntity.builder()
                .organizationId(ORG).teamId(TEAM_HOME).opponentTeamId(null)
                .opponentName("未登録FC").sport(Sport.SOCCER).hasScorekeeper(false).build();
        m.setId(matchId);
        when(matchService.getMatchOrThrow(matchId, ORG)).thenReturn(m);
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.AWAY).recordedByTeamId(TEAM_HOME).build());
    }

    @Test
    @DisplayName("recorded_by_team_id=null（名義未確定の縮退）は整合検証をスキップして OK")
    void nullRecordedByTeamSkipsCheck() {
        coopMatchWithOpponent();
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.AWAY).recordedByTeamId(null).build());
    }

    @Test
    @DisplayName("update: team_side を AWAY に付け替えても既存名義(HOME)と矛盾すれば 403（サイド付替え捏造遮断）")
    void updateSideSwapMismatchForbidden() {
        coopMatchWithOpponent();
        UUID eventId = UUID.randomUUID();
        // 既存イベントは HOME 名義で記録済み
        MatchEventEntity existing = MatchEventEntity.builder()
                .matchId(matchId).teamSide(TeamSide.HOME).recordedByTeamId(TEAM_HOME).build();
        existing.setId(eventId);
        when(matchEventRepository.findById(eventId)).thenReturn(Optional.of(existing));

        // team_side だけ AWAY に付け替えようとする → 既存名義 HOME と矛盾 → 403
        assertThatThrownBy(() -> service.update(matchId, eventId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.AWAY).recordedByTeamId(TEAM_AWAY).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("記録名義");
    }

    // ─── linked_event_id の side 整合（03 §C.4a・補正3） ──────

    @Test
    @DisplayName("連鎖相手が同一サイドなら OK")
    void linkedSameSideOk() {
        coopMatchWithOpponent();
        UUID linkedId = UUID.randomUUID();
        MatchEventEntity linked = MatchEventEntity.builder()
                .matchId(matchId).teamSide(TeamSide.HOME).build();
        linked.setId(linkedId);
        when(matchEventRepository.findById(linkedId)).thenReturn(Optional.of(linked));
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.HOME).recordedByTeamId(TEAM_HOME)
                        .linkedEventId(linkedId).build());
    }

    @Test
    @DisplayName("連鎖相手が異サイドなら 404（MATCH_022・相手集計汚染遮断）")
    void linkedCrossSide404() {
        coopMatchWithOpponent();
        UUID linkedId = UUID.randomUUID();
        // 連鎖相手は AWAY サイドのイベント
        MatchEventEntity linked = MatchEventEntity.builder()
                .matchId(matchId).teamSide(TeamSide.AWAY).build();
        linked.setId(linkedId);
        when(matchEventRepository.findById(linkedId)).thenReturn(Optional.of(linked));

        // 記録中は HOME サイド → 異サイド連鎖は遮断
        assertThatThrownBy(() -> service.record(matchId, ORG, ACTOR,
                baseGoal().teamSide(TeamSide.HOME).recordedByTeamId(TEAM_HOME)
                        .linkedEventId(linkedId).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("連鎖先");
    }

    // ─── 入力サニタイズ（HTML 不可・制御文字除去・trim） ──────

    @Test
    @DisplayName("note の HTML タグ除去・制御文字除去・trim が行われる")
    void noteSanitized() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var cmd = baseGoal()
                .eventType(MatchEventType.OTHER)
                .customLabel("ラベル")
                .note("  <script>alert(1)</script>背後から  ")
                .build();
        MatchEventEntity saved = service.record(matchId, ORG, ACTOR, cmd);
        // <script> タグはタグごと中身も除去され（Jsoup none()）、trim 済み・制御文字なし
        assertThat(saved.getNote()).doesNotContain("<").doesNotContain(">");
        assertThat(saved.getNote()).isEqualTo("背後から");
    }

    @Test
    @DisplayName("note の改行（CRLF）は制御文字除去でログインジェクションを防ぐ")
    void noteControlCharsRemoved() {
        when(matchEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // タグを含まない純テキストの改行・タブは制御文字除去で消える
        var cmd = baseGoal().eventType(MatchEventType.OTHER).note("行1\r\n\t行2").build();
        MatchEventEntity saved = service.record(matchId, ORG, ACTOR, cmd);
        // CR/LF/タブ等の制御文字が残らないこと（ログインジェクション防止）。
        // Jsoup の純テキスト化は空白を正規化するため空白 1 個に畳まれるが、制御文字は残らない。
        assertThat(saved.getNote()).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(saved.getNote()).matches("行1\\s?行2");
    }
}
