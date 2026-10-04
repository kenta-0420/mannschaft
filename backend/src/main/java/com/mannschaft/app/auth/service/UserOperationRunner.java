package com.mannschaft.app.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/** Auth owns this transaction; calling-domain callback owns its independent transaction. */
@Service
@RequiredArgsConstructor
public class UserOperationRunner {
    private final UserRowLockService userRowLockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withActiveUser(Long userId, Supplier<T> operation) {
        throw new UnsupportedOperationException("User operation runner is not implemented");
    }
}
