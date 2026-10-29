package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.notification.outbox.NotificationOutboxSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** L1-M の公開窓口を既存通知 relay に接続する。先行試練用の未実装骨格。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeOutboxSource implements NotificationOutboxSource {
    public static final String SOURCE_NAME = "moduleevent";
    private final ModuleEventNoticeOutboxApi api;
    private final NotificationOutboxPayloadCodec codec;

    @Override public String name() { return SOURCE_NAME; }
    @Override public List<NotificationOutboxMessage> claim(int limit, Instant now) { return List.of(); }
    @Override public boolean markRelayed(UUID id, UUID token, Instant now) { return false; }
    @Override public boolean markFailed(UUID id, UUID token, String error, Instant next, boolean dead, Instant now) {
        return false;
    }
    @Override public boolean deferUnsupportedVersion(UUID id, UUID token, Instant next, Instant now) { return false; }
    @Override public int recoverStuck(Instant before, Instant now) { return 0; }
    /** 強い保持 gate は未提供。時刻だけによる削除をしない。 */
    @Override public int sweep(Instant relayedBefore, Instant deadBefore) {
        throw new UnsupportedOperationException("L1-M retention gate は未提供。通知予約を保持する");
    }
    @Override public Optional<Instant> oldestPendingCreatedAt() { return Optional.empty(); }
}
