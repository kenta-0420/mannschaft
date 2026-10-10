package com.mannschaft.app.team.service;

import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxSource;
import com.mannschaft.app.team.entity.TeamNotificationOutboxEntity;
import com.mannschaft.app.team.repository.TeamNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code team_notification_outbox} を通知ドメインの relay に見せる SPI 実装（docs/architecture/notification_outbox.md §4）。
 *
 * <p>触るのは team の Repository だけ。各メソッドは {@code @Transactional(propagation = REQUIRES_NEW)}
 * の独立した tx にする（OB16。relay は tx を持たず、起こしが CallerRuns で AFTER_COMMIT の中から呼ばれても、
 * 終わった業務の tx に参加しない）。{@code MANDATORY} にしない（D-3P-2）。</p>
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<NotificationOutboxMessage> claim(int limit, Instant now) {
        List<TeamNotificationOutboxEntity> locked = repository.lockClaimable(micros(now), limit);
        if (locked.isEmpty()) {
            return List.of();
        }
        UUID claimToken = UUID.randomUUID();
        List<UUID> ids = locked.stream().map(TeamNotificationOutboxEntity::getId).toList();
        int updated = repository.markClaimed(ids, claimToken, micros(now));
        if (updated != ids.size()) {
            // FOR UPDATE で取った PENDING の行なので必ず全行に当たる。ずれたらロックの前提が崩れている
            throw new IllegalStateException("ロック済みの outbox 行を claim できない: locked=" + ids.size()
                    + " updated=" + updated);
        }
        return locked.stream()
                .map(row -> new NotificationOutboxMessage(row.getId(), claimToken, row.getMessageKind(),
                        row.getPayloadVersion(), row.getPayloadJson(), row.getAttemptCount()))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        return repository.markRelayed(id, claimToken, micros(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID id, UUID claimToken, String error, Instant nextAttemptAt, boolean dead,
                              Instant now) {
        int updated = dead
                ? repository.markDead(id, claimToken, error, micros(now))
                : repository.markFailedForRetry(id, claimToken, error, micros(nextAttemptAt), micros(now));
        return updated == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        return repository.deferUnsupportedVersion(id, claimToken, micros(nextAttemptAt), micros(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverStuck(Instant claimedBefore, Instant now) {
        return repository.recoverStuck(micros(claimedBefore), micros(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sweep(Instant relayedBefore, Instant deadBefore) {
        return repository.deleteRelayedBefore(micros(relayedBefore)) + repository.deleteDeadBefore(micros(deadBefore));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Instant> oldestPendingCreatedAt() {
        Long micros = repository.findOldestPendingCreatedAtMicros();
        return micros == null ? Optional.empty() : Optional.of(Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
    }

    /** エポックからのマイクロ秒（Repository の時刻の渡し方。§3.2）。 */
    static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }
}
