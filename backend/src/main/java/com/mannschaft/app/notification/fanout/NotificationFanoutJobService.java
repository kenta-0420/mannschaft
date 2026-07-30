package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 通知 fan-out 耐久ジョブの enqueue 口（P2）。
 *
 * <p>村行事作成などの「入口」は受信者を一切展開せず、本サービスで {@link NotificationFanoutJob} を
 * <b>1 行だけ</b> INSERT する（O(1)・AC-7）。実配信は裏ワーカー {@link NotificationFanoutWorker} が担う。
 * 同一 fan-out の二重 enqueue は DB のユニーク制約 {@code uk_fanout_idempotency} に依り、衝突を握って
 * skip する冪等契約とする（AC-1）。</p>
 *
 * <p><b>試練（red）段階では本体は未実装（no-op）。</b> green 化（1 行 INSERT・ユニーク衝突の握り skip）は
 * P2 出陣が行う。シグネチャだけ確定し、テストが叩けるようにしてある。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationFanoutJobService {

    @SuppressWarnings("unused") // green で INSERT／冪等ガードに使う（red では未配線）
    private final NotificationFanoutJobRepository jobRepository;

    /**
     * fan-out ジョブを 1 件 enqueue する（冪等）。
     *
     * @param scopeType        受信者解決の戦略キー（{@link FanoutRecipientSource#scopeType()} と一致）
     * @param scopeId          スコープID（論理参照）
     * @param notificationType 通知種別
     * @param sourceEventUuid  発生元イベント UUID（冪等キーの一部）
     * @param organizationId   テナント（NULL 可）
     * @param title            通知タイトル
     * @param body             通知本文（NULL 可）
     * @param priority         優先度（NULL は NORMAL 相当）
     * @param sourceType       ソース種別（NULL 可）
     * @param sourceId         ソースID（NULL 可）
     * @param actionUrl        アクション URL（NULL 可）
     * @param actorId          実行者ID（NULL 可・システム発火は NULL）
     *
     * @implNote 試練（red）段階では no-op。P2 出陣で「1 行 INSERT ＋ ユニーク衝突の握り skip」を実装する。
     */
    public void enqueue(String scopeType, Long scopeId, String notificationType, UUID sourceEventUuid,
                        Long organizationId, String title, String body, NotificationPriority priority,
                        String sourceType, Long sourceId, String actionUrl, Long actorId) {
        // P2 出陣で実装する。red のため意図的に no-op（ジョブ行を作らない＝AC-1/AC-7 が FAIL する）。
        log.debug("enqueue(no-op/red): scopeType={} scopeId={} type={} sourceEvent={}",
                scopeType, scopeId, notificationType, sourceEventUuid);
    }
}
