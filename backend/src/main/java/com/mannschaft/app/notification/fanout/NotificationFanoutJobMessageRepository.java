package com.mannschaft.app.notification.fanout;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
