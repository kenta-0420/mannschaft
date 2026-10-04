package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.ranch.dto.RanchState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 認証のACTIVE本人lock内で外部投影とRanch独立取引を順次実行する。 */
@Service
@RequiredArgsConstructor
public class RanchSelfFacade {
    private final UserOperationGuard guard;
    private final RanchExternalProjectionProvider projection;
    private final RanchStateReader state;
    private final RanchEnrollmentWriter enrollment;
    private final RanchEnrollmentReplayReader replay;
    private final Clock clock;

    public RanchState read(Long userId) {
        Objects.requireNonNull(userId);
        return guard.withActiveUser(userId, () -> {
            Instant now = now();
            var external = projection.current(userId, now);
            return state.read(userId, now, external);
        });
    }

    public RanchEnrollmentWriter.EnrollmentOutcome enroll(Long userId, UUID key) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        return guard.withActiveUser(userId, () -> {
            var saved = replay.saved(userId, key);
            if (saved.isPresent()) return saved.orElseThrow();
            Instant now = now();
            var external = projection.current(userId, now);
            return enrollment.enroll(userId, key, now, external);
        });
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
