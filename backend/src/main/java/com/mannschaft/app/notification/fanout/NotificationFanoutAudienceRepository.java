package com.mannschaft.app.notification.fanout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * fan-out 宛先集合の見出しリポジトリ（F01.2.1 §5.6）。主キーは宛先集合のキー {@code audience_snapshot_id}。
 *
 * <p>宛先集合はジョブ行と同じく残す（完了ジョブの削除が無いため）。書き込み・解決は部隊 6-D・6-E 以降。</p>
 */
public interface NotificationFanoutAudienceRepository
        extends JpaRepository<NotificationFanoutAudienceEntity, UUID> {
}
