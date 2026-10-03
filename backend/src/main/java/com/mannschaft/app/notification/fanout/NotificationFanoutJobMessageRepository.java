package com.mannschaft.app.notification.fanout;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * {@link NotificationFanoutJobMessage} のリポジトリ（Issue #2871）。
 *
 * <p>ワーカーはジョブ 1 件につき<b>1 回だけ</b>本リポジトリを引き、6 行のロケール別文面を
 * メモリ上の Map にしてからチャンクループへ入る（チャンクごと・受信者ごとに引かない）。</p>
 */
@Repository
public interface NotificationFanoutJobMessageRepository
        extends JpaRepository<NotificationFanoutJobMessage, UUID> {

    /** 指定ジョブのロケール別文面を全件取得する（高々 6 行）。 */
    List<NotificationFanoutJobMessage> findByJobId(UUID jobId);

    /**
     * 文面行を冪等に登録する（F01.2.1 §6.7）。{@code uk_fanout_job_message_locale} 衝突時は
     * {@code id = id} で何も変えず、例外も投げない（呼び出し側 TX を rollback-only にしない・AC-E11）。
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_fanout_job_messages (id, job_id, locale, title, body)
            VALUES (:id, :jobId, :locale, :title, :body)
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertIdempotent(@Param("id") UUID id, @Param("jobId") UUID jobId,
                         @Param("locale") String locale, @Param("title") String title,
                         @Param("body") String body);
}
