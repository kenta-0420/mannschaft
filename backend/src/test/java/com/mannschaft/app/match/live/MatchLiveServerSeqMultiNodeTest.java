package com.mannschaft.app.match.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AC-10（serverSeq 単調性・欠陥実証 red）: マルチノードで観戦者が受信する {@code serverSeq} の単調増加を検証する
 * （設計書 §2.2 #4 / §4.6 / §5 AC-10）。
 *
 * <h3>欠陥（§4.6・実コードで裏取り済み）</h3>
 * <p>{@link MatchLiveBroadcastListener} の {@code serverSeq} は<b>ノードローカルの {@code AtomicLong}</b>である。
 * マルチノード化すると、同一試合の配信がノードごとに独立採番され、観戦者は 2 系列を混在受信して<b>単調性が破綻</b>する
 * （例: ノード A から 1,2、ノード B から 1,2 が交互に届き 1,1,2,2 となり減少が観測される）。</p>
 *
 * <h3>本テストの再現方法（Docker 不要・決定論的）</h3>
 * <p>別ノード = 別 JVM = 別 {@code AtomicLong} インスタンスであるため、<b>2 個の {@link MatchLiveBroadcastListener}
 * インスタンス</b>を生成すれば 2 ノードの採番衝突を決定論的に再現できる（各インスタンスが独立した {@code AtomicLong} を持つ）。
 * 同一 {@code matchId} のライブ更新を A・B 交互に配信させ、配信ペイロードの {@code serverSeq} を受信順に並べて
 * <b>狭義単調増加</b>をアサートする。</p>
 *
 * <ul>
 *   <li><b>現行（AtomicLong）</b>: A=1, B=1, A=2, B=2, ... となり単調増加が破れる → <b>red</b>。</li>
 *   <li><b>出陣後（§4.6 の Valkey {@code INCR} でノード横断採番）</b>: 全ノードで単一系列となり単調増加で green。
 *       出陣が本テストを Valkey 共有カウンタ版へ更新して green 化する（採番源の差し替えに追随）。</li>
 * </ul>
 */
@DisplayName("AC-10: MatchLive serverSeq のノード横断単調性（欠陥実証 red）")
class MatchLiveServerSeqMultiNodeTest {

    @Test
    @DisplayName("2 ノード交互配信の serverSeq が狭義単調増加である（現行 AtomicLong では系列混在で破綻 → red）")
    void serverSeq_isStrictlyIncreasing_acrossTwoNodes() {
        SimpMessagingTemplate templateA = mock(SimpMessagingTemplate.class);
        SimpMessagingTemplate templateB = mock(SimpMessagingTemplate.class);

        // 別ノード = 別インスタンス（各自が独立した AtomicLong を保持する）
        MatchLiveBroadcastListener nodeA = new MatchLiveBroadcastListener(templateA);
        MatchLiveBroadcastListener nodeB = new MatchLiveBroadcastListener(templateB);

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
