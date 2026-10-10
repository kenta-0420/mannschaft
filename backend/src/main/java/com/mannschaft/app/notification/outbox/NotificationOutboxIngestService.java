package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * outbox の依頼を通知ドメインに取り込む（docs/architecture/notification_outbox.md §4.3）。
 *
 * <p>relay とは<b>別の Bean</b>にし、{@code @Transactional(propagation = REQUIRES_NEW)} で独立した tx にする。
 * 起こしの executor が飽和して CallerRuns になり、AFTER_COMMIT の中で同期実行されても、取り込みが既に完了した
 * 業務の tx に参加して消えることがないようにするため（OB15・OB16）。触るのは通知ドメインの表だけ。</p>
 *
 * <p>冪等: {@code FANOUT} は {@link NotificationFanoutJobService} の冪等 SQL 版で登録する（同じ冪等キーの2回目は
 * 既存のジョブに着く）。二重取り込みは結果が1件に収束する。{@code FANOUT_WITH_AUDIENCE} は 6-E' で実装する。</p>
 *
 * <p>【試練の骨格・出陣で実装】tx 境界の注釈も出陣で付ける。</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationOutboxIngestService {

    private final NotificationFanoutJobService fanoutJobService;

    /** 依頼を1件取り込む。失敗は例外として relay へ伝える（relay が outbox 側に失敗を記録する）。 */
    public void ingest(NotificationOutboxPayload payload) {
        throw new UnsupportedOperationException("出陣で実装: NotificationOutboxIngestService#ingest");
    }
}
