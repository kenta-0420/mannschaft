package com.mannschaft.app.match.live;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F08.10 / 07 §J.2 {@link MatchLiveBroadcastListener} の純 Mockito UT（test-first・07 §J.6）。
 *
 * <p>{@code @TransactionalEventListener} は結合テストでは TX 発火しないため、リスナーメソッドを<b>直接呼ぶ</b>
 * （順位連携リスナーと同じ方針・05 §H.0.1）。</p>
 *
 * <p>検証項目（07 §J.6）:
 * <ul>
 *   <li>イベント受領 → {@code convertAndSend("/topic/matches/{id}/live", payload)} が正しい宛先・ペイロードで呼ばれる</li>
 *   <li>ペイロードに機微情報（内部ユーザー ID 等）が含まれない（07 §J.3.3）</li>
 *   <li>serverSeq が単調増加する（07 §J.2.1）</li>
 *   <li>convertAndSend が例外 → 記録経路へ伝播させずログのみ（07 §J.2・巻き戻さない）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} かつ {@code @Transactional(REQUIRES_NEW)} を付けていない
 *       （配信のみで DB 書き込みなしゆえ新規 TX 不要・地雷の逆ケース）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchLiveBroadcastListener 単体テスト (F08.10 / 07 §J)")
class MatchLiveBroadcastListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MatchLiveBroadcastListener listener;

    private SimpleMeterRegistry meterRegistry;

    private final UUID matchId = UUID.randomUUID();

    /**
     * serverSeq 採番は Valkey INCR（websocket_external_broker_valkey.md §4.6）に差し替わったため、
     * INCR セマンティクス（キー単位の原子的インクリメント・初回 = 1）のフェイクを注入する（§7.3 の追随）。
     * lenient: 機微情報除外テスト等、採番に到達しないテストでも安全に共存させる。
     */
    @BeforeEach
    void setUpSeqCounter() {
        AtomicLong counter = new AtomicLong();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());
        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        meterRegistry = new SimpleMeterRegistry();
        listener = new MatchLiveBroadcastListener(messagingTemplate, redisTemplate, meterRegistry);
    }

    // ============================================================
    // 配信宛先・ペイロード
    // ============================================================

    @Nested
    @DisplayName("配信宛先・ペイロード（07 §J.2 / §J.2.1）")
    class Broadcast {

        @Test
        @DisplayName("EVENT_ADDED を /topic/matches/{id}/live へ正しいペイロードで配信する")
        void eventAdded_配信宛先とペイロード() {
            MatchEventEntity entity = goalEntity();
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.eventAdded(matchId, entity));

            ArgumentCaptor<MatchLiveUpdatePayload> captor = ArgumentCaptor.forClass(MatchLiveUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/matches/" + matchId + "/live"), captor.capture());

            MatchLiveUpdatePayload payload = captor.getValue();
            assertThat(payload.getType()).isEqualTo(MatchLiveUpdateType.EVENT_ADDED);
            assertThat(payload.getMatchId()).isEqualTo(matchId);
            assertThat(payload.getServerSeq()).isEqualTo(1L);
            assertThat(payload.getEvent()).isNotNull();
            assertThat(payload.getEvent().getEventType()).isEqualTo(MatchEventType.GOAL);
            assertThat(payload.getEvent().getPlayerName()).isEqualTo("山田");
            assertThat(payload.getEvent().getTeamSide()).isEqualTo(TeamSide.HOME);
        }

        @Test
        @DisplayName("SCORE_UPDATED は機微情報を含まないスコアサマリのみを配信する")
        void scoreUpdated_スコアサマリ() {
            MatchEntity match = MatchEntity.builder()
                    .homeScore(2).awayScore(1).homePenaltyScore(5).awayPenaltyScore(4)
                    .build();
            match.setId(matchId);

            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.scoreUpdated(match));

            ArgumentCaptor<MatchLiveUpdatePayload> captor = ArgumentCaptor.forClass(MatchLiveUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/matches/" + matchId + "/live"), captor.capture());
            MatchLiveUpdatePayload payload = captor.getValue();
            assertThat(payload.getType()).isEqualTo(MatchLiveUpdateType.SCORE_UPDATED);
            assertThat(payload.getScore()).isNotNull();
            assertThat(payload.getScore().getHomeScore()).isEqualTo(2);
            assertThat(payload.getScore().getAwayScore()).isEqualTo(1);
            assertThat(payload.getScore().getHomePenaltyScore()).isEqualTo(5);
            assertThat(payload.getScore().getAwayPenaltyScore()).isEqualTo(4);
            assertThat(payload.getEvent()).isNull();
        }

        @Test
        @DisplayName("STATUS_CHANGED は遷移後ステータスを配信する")
        void statusChanged_ステータス() {
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.IN_PROGRESS));

            ArgumentCaptor<MatchLiveUpdatePayload> captor = ArgumentCaptor.forClass(MatchLiveUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MatchLiveUpdateType.STATUS_CHANGED);
            assertThat(captor.getValue().getStatus()).isEqualTo(MatchStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("EVENT_DELETED は削除イベント ID のみを配信する（イベント本体は載せない）")
        void eventDeleted_IDのみ() {
            UUID eventId = UUID.randomUUID();
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.eventDeleted(matchId, eventId));

            ArgumentCaptor<MatchLiveUpdatePayload> captor = ArgumentCaptor.forClass(MatchLiveUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MatchLiveUpdateType.EVENT_DELETED);
            assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
            assertThat(captor.getValue().getEvent()).isNull();
        }

        @Test
        @DisplayName("serverSeq は配信のたびに単調増加する（07 §J.2.1）")
        void serverSeq_単調増加() {
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.IN_PROGRESS));
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.COMPLETED));
            listener.onMatchLiveUpdate(MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.POSTPONED));

            ArgumentCaptor<MatchLiveUpdatePayload> captor = ArgumentCaptor.forClass(MatchLiveUpdatePayload.class);
            verify(messagingTemplate, org.mockito.Mockito.times(3))
                    .convertAndSend(any(String.class), captor.capture());
            assertThat(captor.getAllValues()).extracting(MatchLiveUpdatePayload::getServerSeq)
                    .containsExactly(1L, 2L, 3L);
        }
    }

    // ============================================================
    // 機微情報除外（07 §J.3.3）
    // ============================================================

    @Nested
    @DisplayName("機微情報除外（07 §J.3.3 二重防御）")
    class SensitiveExclusion {

        @Test
        @DisplayName("ペイロードのイベントビューに内部ユーザー ID・recorded_by_team_id 相当の getter が存在しない")
        void payload_機微フィールド除外() {
            // MatchLiveEventView は内部ユーザー ID（playerUserId/relatedPlayerUserId）と
            // recorded_by_team_id を「型として持たない」（getter が存在しない）ことで機微情報非搬送を保証する。
            assertThat(hasGetter(MatchLiveEventView.class, "getPlayerUserId")).isFalse();
            assertThat(hasGetter(MatchLiveEventView.class, "getRelatedPlayerUserId")).isFalse();
            assertThat(hasGetter(MatchLiveEventView.class, "getRecordedByTeamId")).isFalse();
            // 公開可能な選手表示名は持つ
            assertThat(hasGetter(MatchLiveEventView.class, "getPlayerName")).isTrue();
            // スコアサマリにも所有チーム ID 相当の getter が存在しない
            assertThat(hasGetter(MatchLiveScoreSummary.class, "getOwningTeamId")).isFalse();
            assertThat(hasGetter(MatchLiveScoreSummary.class, "getTeamId")).isFalse();
        }

        @Test
        @DisplayName("from() は内部ユーザー ID を写し取らない（実値検証）")
        void from_内部ユーザーIDを写さない() {
            MatchEventEntity entity = goalEntity();
            entity.setPlayerUserId(999L);
            entity.setRelatedPlayerUserId(888L);
            entity.setRecordedByTeamId(777L);

            MatchLiveEventView view = MatchLiveEventView.from(entity);
            // ビューには内部 ID 系の getter 自体が無く、表示名のみが転写される
            assertThat(view.getPlayerName()).isEqualTo("山田");
            assertThat(view.getEventType()).isEqualTo(MatchEventType.GOAL);
        }

        private boolean hasGetter(Class<?> type, String getter) {
            try {
                type.getMethod(getter);
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    }

    // ============================================================
    // 配信失敗は記録へ伝播させない（07 §J.2・症状を隠さずログ）
    // ============================================================

    @Nested
    @DisplayName("配信失敗の取り扱い（07 §J.2・best-effort）")
    class FailureHandling {

        @Test
        @DisplayName("convertAndSend が例外を投げても記録経路へ再スローしない（巻き戻さない・WARN ログのみ）")
        void 配信例外_再スローしない() {
            doThrow(new org.springframework.messaging.MessagingException("broker down"))
                    .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

            // リスナーは例外を飲み込み、呼び出し元（=記録 TX のコミット後フック）へ伝播させない。
            assertThatCode(() -> listener.onMatchLiveUpdate(
                    MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.IN_PROGRESS)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Valkey 断（INCR 失敗）時は MatchLive 配信をスキップし例外を伝播させない（§4.6 フェイルオープン）")
        void Valkey断_配信スキップ() {
            when(valueOperations.increment(anyString()))
                    .thenThrow(new QueryTimeoutException("valkey down"));

            assertThatCode(() -> listener.onMatchLiveUpdate(
                    MatchLiveUpdateEvent.statusChanged(matchId, MatchStatus.IN_PROGRESS)))
                    .doesNotThrowAnyException();

            // 採番不能時はローカル独自採番で配り続けず、配信自体をスキップする（seq 汚染防止・HTTP 再取得へ委譲・§4.6）
            verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
            // スキップは沈黙させず matchlive.serverseq.skipped に計上する（§4.6・症状の可視化）
            assertThat(meterRegistry.counter("matchlive.serverseq.skipped").count()).isEqualTo(1.0d);
        }
    }

    // ============================================================
    // REQUIRES_NEW 不要の確証（地雷の逆ケース・07 §J.2）
    // ============================================================

    @Nested
    @DisplayName("トランザクション注釈（REQUIRES_NEW 不要の確証）")
    class TransactionAnnotation {

        @Test
        @DisplayName("onMatchLiveUpdate は @TransactionalEventListener(AFTER_COMMIT) である")
        void afterCommitフェーズ() throws NoSuchMethodException {
            Method m = MatchLiveBroadcastListener.class.getMethod("onMatchLiveUpdate", MatchLiveUpdateEvent.class);
            TransactionalEventListener anno = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anno).isNotNull();
            assertThat(anno.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }

        @Test
        @DisplayName("配信のみで DB 書き込みが無いため @Transactional(REQUIRES_NEW) を付けていない（素の AFTER_COMMIT で context は壊れない）")
        void Transactional注釈なし() throws NoSuchMethodException {
            // 入口① MatchScoreFixtureListener は DB 書き込みありゆえ REQUIRES_NEW 必須だったが、
            // 本リスナーは配信のみゆえ @Transactional を付けない（付けて REQUIRED だと起動時バリデで context 全滅）。
            Method m = MatchLiveBroadcastListener.class.getMethod("onMatchLiveUpdate", MatchLiveUpdateEvent.class);
            assertThat(m.getAnnotation(Transactional.class))
                    .as("配信専用リスナーに @Transactional は不要（REQUIRES_NEW も付けない）")
                    .isNull();
            assertThat(MatchLiveBroadcastListener.class.getAnnotation(Transactional.class))
                    .as("クラスレベルにも @Transactional を付けない")
                    .isNull();
        }
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private MatchEventEntity goalEntity() {
        MatchEventEntity entity = MatchEventEntity.builder()
                .matchId(matchId)
                .eventType(MatchEventType.GOAL)
                .teamSide(TeamSide.HOME)
                .playerName("山田")
                .minute(23)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
