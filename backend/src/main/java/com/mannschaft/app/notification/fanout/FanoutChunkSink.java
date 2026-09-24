package com.mannschaft.app.notification.fanout;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040: fan-out ワーカーの「チャンク出力先」戦略インターフェース（軍議第8版確定稿 §3.4）。
 *
 * <p>{@link NotificationFanoutWorker} は既定で {@code NotificationBulkFanoutService#insertAndDispatchChunk}
 * （notifications 行だけを作る）に固定されている。確認通知の非同期 fanout は、1 チャンクごとに
 * 受信者行・notifications・メール outbox・課金・未確認カウンタを同一トランザクションで作る必要があり、
 * 既存の insertAndDispatchChunk では差し込めない。本 IF は notification_type をキーに実装を切り替える
 * seam を提供する（既存ジョブの挙動は変えない・骨格のみ。実装は出陣で行う）。</p>
 *
 * <p><b>本 IF 自体を {@link NotificationFanoutWorker} に配線する変更は本戦役（試練B）の対象外</b>
 * （陣立て書 §3.4「既存は無変更」）。ここでは {@link ConfirmableFanoutChunkSink} が実装として
 * コンパイル可能であることだけを保証する骨格を置く。</p>
 */
public interface FanoutChunkSink {

    /** 本実装が担う {@code notification_type}。 */
    String notificationType();

    /**
     * 1 チャンクを 1 トランザクションで処理する（軍議第8版確定稿 §3.4 の手順1〜6・§8.1〜§8.4）。
     *
     * @param jobId          fan-out ジョブ ID
     * @param notificationId 確認通知 ID（親行のロック対象）
     * @param userIds        このチャンクで配信対象となる user_id（重複してよい。実装が去重する）
     * @return 処理結果（実際に新規作成した受信者数・打ち切ったか）
     */
    ChunkResult processChunk(UUID jobId, Long notificationId, List<Long> userIds);

    /**
     * 配信の終了処理（軍議第8版確定稿 §9.2 の「関所」）。
     *
     * <p>ワーカーが空ページを受け取った時点で、汎用の {@code markDone} を直接呼ばずに本メソッドを通す。
     * 親の行を {@code FOR UPDATE} でロックしたうえで delivery_status・status・ジョブの DONE 化を
     * 1 トランザクションで確定する。</p>
     *
     * @param jobId          fan-out ジョブ ID
     * @param notificationId 確認通知 ID
     */
    void finish(UUID jobId, Long notificationId);

    /**
     * {@link #processChunk} の結果（軍議第8版確定稿 §3.4・§8.1〜§8.3）。
     *
     * @param addedCount 新規に作成した受信者数（既存分・去重分は含まない）
     * @param stopped    このチャンクの処理前に親が CANCELLED/EXPIRED で打ち切られた場合 true
     *                   （このとき addedCount は必ず 0）
     */
    record ChunkResult(long addedCount, boolean stopped) {
    }
}
