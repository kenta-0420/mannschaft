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
    /** 最大51人だけを同じadmission/Runnerで保護する。 */
    public <T> T withLockedDeliveryUsers(java.util.Collection<Long> userIds,
            java.util.function.Function<java.util.Map<Long,DeliveryUserState>,T> operation) {
        if(userIds==null || userIds.isEmpty() || userIds.size()>51 || operation==null
                || userIds.stream().anyMatch(id -> id==null || id<=0))
            throw new IllegalArgumentException("活動者保護入力が不正です");
        var ordered=userIds.stream().distinct().sorted().toList();
        try { return admission.execute(() -> runner.withLockedDeliveryUsers(ordered,operation)); }
        catch(UserOperationAdmission.Rejected error) { throw new BusinessException(UserOperationErrorCode.UNAVAILABLE); }
    }

}
