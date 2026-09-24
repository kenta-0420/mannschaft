package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040: 確認通知の fan-out チャンク出力先（軍議第8版確定稿 §3.4・§8.1〜§8.4・§9.2・§10・§11）。
 *
 * <p><b>骨格のみ（試練B）。実装は出陣で行う</b>。1チャンクの処理契約:
 * <ol>
 *   <li>既に受信者行がある user_id を除いた新規分だけを求める（再開時の二重防止・AC-23）</li>
 *   <li>新規分だけ、確認トークン付きで受信者行を多値 INSERT する</li>
 *   <li>新規分だけ notifications を多値 INSERT する</li>
 *   <li>組織スコープなら新規分だけ consume する</li>
 *   <li>total_recipient_count / unconfirmed_count に加算し、delivery_status を DELIVERING にする</li>
 *   <li>コミット後、新規分だけメール outbox へ {@link EmailOutboxService#enqueueAll} で登録する</li>
 * </ol>
 * 親の行を扱うトランザクションは、最初の読み取りを {@code findByIdForUpdate} にする（軍議第8版確定稿 §11.1）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableFanoutChunkSink implements FanoutChunkSink {

    /** {@link com.mannschaft.app.notification.fanout.NotificationFanoutJob#getNotificationType()} と一致させるキー。 */
    public static final String NOTIFICATION_TYPE = "CONFIRMABLE_NOTIFICATION_FANOUT";

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationTargetRepository targetRepository;
    private final EmailOutboxService emailOutboxService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public String notificationType() {
        return NOTIFICATION_TYPE;
    }

    @Override
    @Transactional
    public ChunkResult processChunk(UUID jobId, Long notificationId, List<Long> userIds) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    @Override
    @Transactional
    public void finish(UUID jobId, Long notificationId) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
