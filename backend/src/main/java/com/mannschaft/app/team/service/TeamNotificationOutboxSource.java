package com.mannschaft.app.team.service;

import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxSource;
import com.mannschaft.app.team.repository.TeamNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code team_notification_outbox} を通知ドメインの relay に見せる SPI 実装（docs/architecture/notification_outbox.md §4）。
 *
 * <p>触るのは team の Repository だけ。各メソッドは {@code @Transactional(propagation = REQUIRES_NEW)}
 * の独立した tx にする（OB16）。{@code MANDATORY} にしない（D-3P-2）。</p>
 *
 * <p>【試練の骨格・出陣で実装】tx 境界の注釈も出陣で付ける。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamNotificationOutboxSource implements NotificationOutboxSource {

    /** source の名前。 */
    public static final String SOURCE_NAME = "team";

    private final TeamNotificationOutboxRepository repository;

    @Override
    public String name() {
        return SOURCE_NAME;
    }

    @Override
    public List<NotificationOutboxMessage> claim(int limit, Instant now) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#claim");
    }

    @Override
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#markRelayed");
    }

    @Override
    public boolean markFailed(UUID id, UUID claimToken, String error, Instant nextAttemptAt, boolean dead,
                              Instant now) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#markFailed");
    }

    @Override
    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#deferUnsupportedVersion");
    }

    @Override
    public int recoverStuck(Instant claimedBefore, Instant now) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#recoverStuck");
    }

    @Override
    public int sweep(Instant relayedBefore, Instant deadBefore) {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#sweep");
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        throw new UnsupportedOperationException("出陣で実装: TeamNotificationOutboxSource#oldestPendingCreatedAt");
    }
}
