package com.mannschaft.app.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: Webhook 冪等性キーリポジトリ。
 *
 * <p>受信イベントの冪等記録は webhook 処理系から利用される。</p>
 */
public interface StripeWebhookEventRepository
        extends JpaRepository<StripeWebhookEventEntity, UUID> {

    /** 同一 event_id が既に受信済みか判定する（冪等性ゲート）。 */
    boolean existsByEventId(String eventId);

    /** event_id から逆引きする。 */
    Optional<StripeWebhookEventEntity> findByEventId(String eventId);

    /**
     * 処理状態を UPDATE 文で確定する。
     *
     * <p><b>なぜエンティティ読み込み＋save ではないのか（実在した不具合の根治）</b>:
     * 受信記録の INSERT は {@code REQUIRES_NEW} の別トランザクションでコミットされる。
     * 一方この確定は業務トランザクション（webhook 1 件の処理）の中で行いたい
     * （投影と一体に成否させるため）。ところが MySQL の既定分離レベル REPEATABLE READ では、
     * 業務トランザクションのスナップショットは<b>その最初の読み取り時点</b>で固定される。
     * 所有判定のために先に別テーブルを読んでいるため、スナップショットは INSERT より前になり、
     * 素の {@code SELECT} では<b>受信記録の行が見えない</b>。結果 {@code findByEventId} が空を返し、
     * 状態が {@code RECEIVED} のまま取り残されていた（AC-20 の実測赤）。</p>
     *
     * <p>InnoDB では書き込み（と locking read）は<b>最新のコミット済みバージョン</b>を対象にするため、
     * UPDATE 文にすれば確実に当たる。かつ業務トランザクションの中に留まるので、
     * 後段が失敗すれば確定も一緒に巻き戻る（AC-20 の「一体に成否する」を保てる）。</p>
     *
     * @return 更新件数（0 なら受信記録が無い＝異常。呼び出し元は握り潰さずログに残すこと）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE StripeWebhookEventEntity e
               SET e.processStatus = :status, e.processedAt = :processedAt
             WHERE e.eventId = :eventId
            """)
    int updateProcessStatus(@Param("eventId") String eventId,
                            @Param("status") WebhookProcessStatus status,
                            @Param("processedAt") LocalDateTime processedAt);
}
