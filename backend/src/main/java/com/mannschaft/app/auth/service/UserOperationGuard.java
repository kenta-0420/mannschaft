package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

/** 非TX入口で受付を制限し、別Runnerのauthトランザクションへ委譲する。 */
@Service
@RequiredArgsConstructor
public class UserOperationGuard {
    private final UserOperationAdmission admission;
    private final UserOperationRunner runner;

    public <T> T withActiveUser(Long userId, Supplier<T> operation) {
        if (userId == null || operation == null) {
            throw new IllegalArgumentException("本人操作の指定が不正です");
        }
        try {
            return admission.execute(() -> runner.withActiveUser(userId, operation));
        } catch (UserOperationAdmission.Rejected rejected) {
            throw new BusinessException(UserOperationErrorCode.UNAVAILABLE);
        }
    }
}
