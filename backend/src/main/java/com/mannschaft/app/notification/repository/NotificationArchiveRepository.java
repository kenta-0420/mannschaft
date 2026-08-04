package com.mannschaft.app.notification.repository;

import com.mannschaft.app.notification.entity.NotificationArchiveEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 通知アーカイブ（{@code notifications_archive}）リポジトリ。
 *
 * <p>退会即時消去層（{@link com.mannschaft.app.notification.event.NotificationAnonymizationEventListener}）が
 * アーカイブ側の PII 行を削除するための口を提供する。移送本体（バッチ）は JdbcTemplate 直で行うため、
 * 本リポジトリは退会削除・検証用途に限る。</p>
 */
public interface NotificationArchiveRepository extends JpaRepository<NotificationArchiveEntity, Long> {

    /**
     * 指定ユーザーのアーカイブ通知を全件削除する（退会即時消去層の PII 波及用）。
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    int deleteByUserId(Long userId);
}
