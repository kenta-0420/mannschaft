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
