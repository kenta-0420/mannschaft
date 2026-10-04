package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/** authがusers行を保持し、別ドメインの独立処理のcommit完了まで待つ。 */
@Service
@RequiredArgsConstructor
public class UserOperationRunner {
    private final UserRowLockService userRowLockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withActiveUser(Long userId, Supplier<T> operation) {
        if (userRowLockService.lock(userId) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(UserOperationErrorCode.NOT_ALLOWED);
        }
        return operation.get();
    }
}
