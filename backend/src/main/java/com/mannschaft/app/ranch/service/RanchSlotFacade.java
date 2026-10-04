package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.ranch.dto.RanchSlotRequest;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 認証本人lock後にslot一件の独立牧場TXを順次実行する。 */
@Service
@RequiredArgsConstructor
public class RanchSlotFacade {
    private final UserOperationGuard guard;
    private final RanchSlotReplayReader replay;
    private final RanchAccessGuard access;
    private final RanchSlotWriter writer;
    private final Clock clock;

    public RoomSlotSummary place(Long userId, UUID key, String slotKey, RanchSlotRequest body) {
        return guard.withActiveUser(userId, () -> {
            var saved = replay.placement(userId, key, slotKey, body);
            if (saved.isPresent()) return saved.orElseThrow();
            access.requireOwnedPlacement(userId, slotKey, body.inventoryId());
            return writer.place(userId, key, slotKey, body, now());
        });
    }

    public void clear(Long userId, UUID key, String slotKey, String ifMatchVersion) {
        guard.withActiveUser(userId, () -> {
            var saved = replay.removal(userId, key, slotKey, ifMatchVersion);
            if (saved.isPresent()) return null;
            access.requireOwnedSlot(userId, slotKey);
            writer.clear(userId, key, slotKey, ifMatchVersion, now());
            return null;
        });
    }

    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }
}
