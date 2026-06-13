package com.mannschaft.app.match.live;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * F08.10 / 07 §J.2 ライブ観戦の WebSocket 配信リスナー（記録 HTTP 正本のコミット後に観戦者へ push）。
 *
 * <p><b>配信専用（書き込みなし）</b>: 記録経路（{@code MatchEventService}/{@code MatchService}）が
 * {@link MatchLiveUpdateEvent} を publish し、本リスナーが {@code @TransactionalEventListener(AFTER_COMMIT)} で
 * 受けて {@code SimpMessagingTemplate.convertAndSend("/topic/matches/{matchId}/live", payload)} で配信する
 * （07 §J.2 配信フロー）。正本は HTTP であり WebSocket は best-effort 配信に徹する（07 §J.1）。</p>
 *
 * <h3>{@code @Transactional(REQUIRES_NEW)} を付けない理由（地雷の逆ケース）</h3>
 * <p>入口①の {@code MatchScoreFixtureListener} は AFTER_COMMIT で<b>tournament_matches を更新（DB 書き込み）</b>
 * するため、コミット済み TX が無い AFTER_COMMIT では新規 TX が必須で {@code @Transactional(REQUIRES_NEW)} を
 * 付けて根治した（素の {@code @Transactional(REQUIRED)} は起動時バリデーションで弾かれ ApplicationContext 全滅・
 * feedback: TransactionalEventListener に素の @Transactional(REQUIRED) は context 全滅）。</p>
 * <p>一方、<b>本リスナーは配信のみで DB 書き込みを一切行わない</b>。よって新規 TX は不要であり、
 * {@code @Transactional} を<b>付けない</b>のが正道（付ければ無意味に TX を張る・REQUIRED を付ければ起動失敗）。
 * 素の {@code @TransactionalEventListener(AFTER_COMMIT)} のままで ApplicationContext は壊れない
 * （{@code MatchLiveBroadcastListenerTest} のアノテーション検証 UT で担保）。</p>
 *
 * <h3>配信失敗は記録を巻き戻さない（症状は隠さない）</h3>
 * <p>AFTER_COMMIT 後は記録 TX が既にコミット済みのため、配信失敗で記録を巻き戻すことは不可能かつ不要
 * （07 §J.2）。broker エラー等は {@code try-catch} で捕捉し<b>WARN ログに残す</b>（握り潰さない・症状を隠さない・
 * feedback: 根治治療）。観戦者は次の更新 or 再接続時のスナップショット（07 §J.4）で追従できるため実害は限定的。</p>
 *
 * <p><b>serverSeq</b>: 配信のたびに単調増加で採番する（記録スレッドではなく配信時点で一元採番＝配信順序の一貫性）。
 * 観戦者は seq の飛びを検知したらスナップショット再取得で整合を回復する（07 §J.2.1 / §J.4）。</p>
 *
 * <p><b>ブローカー差し替え非依存（07 §J.5）</b>: 配信は {@code SimpMessagingTemplate.convertAndSend} の抽象に
 * 閉じており、SimpleBroker→外部ブローカー（Valkey/RabbitMQ）へ差し替えても本クラスは不変。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2 / §J.3.3 / §J.5</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchLiveBroadcastListener {

    /** 配信先トピックの書式（07 §J.2・観戦者の SUBSCRIBE 宛先と一致）。 */
    static final String DESTINATION_FORMAT = "/topic/matches/%s/live";

    /** 配信シーケンス（単調増加・順序検出・07 §J.2.1）。インメモリ単調増加で十分（本番の厳密化は J.5 と併せて将来余地）。 */
    private final AtomicLong serverSeq = new AtomicLong(0L);

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 記録経路のコミット後にライブ更新を観戦者へ配信する（07 §J.2）。
     *
     * <p>AFTER_COMMIT 発火により、記録 TX がコミット済みの差分のみ配信する（未コミットのイベントを観戦者へ
     * 見せない・ロールバック時の幻イベント残存を防ぐ・07 §J.2）。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchLiveUpdate(MatchLiveUpdateEvent event) {
        MatchLiveUpdatePayload payload = MatchLiveUpdatePayload.builder()
                .type(event.getType())
                .matchId(event.getMatchId())
                .serverSeq(serverSeq.incrementAndGet())
                .event(event.getEvent())
                .eventId(event.getEventId())
                .score(event.getScore())
                .status(event.getStatus())
                .build();

        String destination = String.format(DESTINATION_FORMAT, event.getMatchId());
        try {
            messagingTemplate.convertAndSend(destination, payload);
            log.debug("ライブ配信: destination={}, type={}, serverSeq={}",
                    destination, payload.getType(), payload.getServerSeq());
        } catch (RuntimeException e) {
            // 配信は best-effort（正本は HTTP・07 §J.1）。記録は既にコミット済みゆえ巻き戻さない（07 §J.2）。
            // 症状は握り潰さず WARN に残す（観戦者は次回更新 or 再接続スナップショットで追従・07 §J.4）。
            log.warn("ライブ配信失敗（記録は確定済み・観戦リアルタイム性のみ劣化）: destination={}, type={}, matchId={}",
                    destination, event.getType(), event.getMatchId(), e);
        }
    }
}
