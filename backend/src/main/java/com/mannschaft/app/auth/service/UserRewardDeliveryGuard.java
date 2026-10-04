package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.function.Function;

/** 本人HTTPとは分離した内部配送入口。同じ受付枠を共有し状態を捏造しない。 */
@Service
@RequiredArgsConstructor
public class UserRewardDeliveryGuard {
    private final UserOperationAdmission admission;
    private final UserRewardDeliveryRunner runner;
    public <T> T withLockedDeliveryUser(Long userId, Function<DeliveryUserState,T> operation) {
        if (userId == null || userId <= 0 || operation == null) throw new IllegalArgumentException("配送入力が不正です");
        try { return admission.execute(() -> runner.withLockedDeliveryUser(userId,operation)); }
        catch (UserOperationAdmission.Rejected error) { throw new BusinessException(UserOperationErrorCode.UNAVAILABLE); }
    }
}
