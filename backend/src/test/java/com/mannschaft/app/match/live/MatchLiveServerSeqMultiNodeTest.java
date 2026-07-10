package com.mannschaft.app.match.live;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-10（serverSeq 単調性）: マルチノードで観戦者が受信する {@code serverSeq} の単調増加を検証する
 * （設計書 §2.2 #4 / §4.6 / §5 AC-10）。
 *
 * <h3>欠陥と根治（§4.6・出陣で green 化済み）</h3>
 * <p>かつての {@link MatchLiveBroadcastListener} はノードローカルの {@code AtomicLong} で採番しており、
 * マルチノード化すると同一試合の配信がノードごとに独立採番され、観戦者は 2 系列を混在受信して
 * <b>単調性が破綻</b>していた（例: A=1, B=1, A=2, B=2 → 1,1,2,2 で減少が観測される・red で実証済み）。
 * 出陣（隊 1）が採番源を <b>Valkey {@code INCR}（試合単位キー {@code mannschaft:ws:matchseq:{matchId}}）</b>
 * に差し替え、全ノードで単一系列となり単調増加が成立する（本テストはその green を固定する番人）。</p>
 *
 * <h3>本テストの再現方法（Docker 不要・決定論的）</h3>
 * <p>別ノード = 別 JVM = 別リスナーインスタンスであるため、<b>2 個の {@link MatchLiveBroadcastListener}
 * インスタンス</b>を生成し、両者に<b>同一の共有 Valkey カウンタ（INCR セマンティクスのフェイク:
 * キー単位の原子的インクリメントで採番値を返す）</b>を注入して 2 ノードの採番を決定論的に再現する。
 * 同一 {@code matchId} のライブ更新を A・B 交互に配信させ、配信ペイロードの {@code serverSeq} を受信順に並べて
 * <b>狭義単調増加</b>をアサートする（アサーションは red 時から不変・採番源のコンストラクタ追随のみ）。</p>
 */
@DisplayName("AC-10: MatchLive serverSeq のノード横断単調性（Valkey INCR 共有採番で green）")
class MatchLiveServerSeqMultiNodeTest {

    @Test
    @DisplayName("2 ノード交互配信の serverSeq が狭義単調増加である（共有 Valkey INCR による単一系列・§4.6）")
    void serverSeq_isStrictlyIncreasing_acrossTwoNodes() {
        SimpMessagingTemplate templateA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate templateB = mock(SimpMessagingTemplate.class);

        // 両ノードが同一の Valkey（INCR セマンティクスの共有フェイク）を参照する（§4.6 の本番構成と同型）
        StringRedisTemplate sharedValkey = newSharedIncrValkeyFake();

        // 別ノード = 別インスタンス（採番源は共有 Valkey・単一系列）
        MatchLiveBroadcastListener nodeA =
                new MatchLiveBroadcastListener(templateA, sharedValkey, new SimpleMeterRegistry());
        MatchLiveBroadcastListener nodeB =
                new MatchLiveBroadcastListener(templateB, sharedValkey, new SimpleMeterRegistry());

        UUID matchId = UUID.randomUUID();

        // 同一試合の更新を A→B→A→B の順で交互に配信する（観戦者が両ノード発を混在受信する状況）
        List<Long> observedSeqInArrivalOrder = new ArrayList<>();
        observedSeqInArrivalOrder.add(dispatchAndCaptureSeq(nodeA, templateA, matchId));
        observedSeqInArrivalOrder.add(dispatchAndCaptureSeq(nodeB, templateB, matchId));
        observedSeqInArrivalOrder.add(dispatchAndCaptureSeq(nodeA, templateA, matchId));
        observedSeqInArrivalOrder.add(dispatchAndCaptureSeq(nodeB, templateB, matchId));

        // 観戦者視点: 受信順に serverSeq は狭義単調増加でなければならない（07 §J.4 の飛び検知が誤発火しない条件）
        assertThat(observedSeqInArrivalOrder)
                .as("両ノード発の serverSeq を受信順に並べたとき狭義単調増加であること（§4.6 の Valkey INCR で根治）: %s",
                        observedSeqInArrivalOrder)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /**
     * Valkey INCR セマンティクスの共有フェイク: キー単位の原子的インクリメント（初回 = 1）を返す。
     * 実 Valkey の {@code INCR} と同じ「全ノードから見える単一系列採番」を Docker 不要で再現する。
     */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate newSharedIncrValkeyFake() {
        ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
        StringRedisTemplate sharedValkey = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(sharedValkey.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenAnswer(invocation ->
                counters.computeIfAbsent(invocation.getArgument(0), key -> new AtomicLong()).incrementAndGet());
        when(sharedValkey.expire(anyString(), any(Duration.class))).thenReturn(true);
        return sharedValkey;
    }

    /**
     * 1 ノードに 1 件のライブ更新を配信させ、その配信ペイロードの {@code serverSeq} を取り出す。
     * 各呼び出しで新しい mock を都度検証するのではなく、呼び出し累積回数に応じた最後の配信を捕捉する。
     */
    private long dispatchAndCaptureSeq(MatchLiveBroadcastListener node,
                                       SimpMessagingTemplate template,
                                       UUID matchId) {
        MatchLiveUpdateEvent event = MatchLiveUpdateEvent.builder()
                .matchId(matchId)
                .type(MatchLiveUpdateType.STATUS_CHANGED)
                .build();
        node.onMatchLiveUpdate(event);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        // 当該ノードの累積配信回数ぶん send され、最後の payload が今回のもの
        verify(template, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(org.mockito.ArgumentMatchers.anyString(), payloadCaptor.capture());
        Object lastPayload = payloadCaptor.getValue();
        assertThat(lastPayload)
                .as("配信ペイロードは MatchLiveUpdatePayload であること")
                .isInstanceOf(MatchLiveUpdatePayload.class);
        return ((MatchLiveUpdatePayload) lastPayload).getServerSeq();
    }
}
