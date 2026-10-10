package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Function;

/** users current readを最初にlockし、独立domain commitが戻るまで保持する。 */
@Service
@RequiredArgsConstructor
public class UserRewardDeliveryRunner {
    private final UserRepository users;
    @Transactional(readOnly=false,propagation=Propagation.REQUIRES_NEW)
    public <T> T withLockedDeliveryUser(Long userId,Function<DeliveryUserState,T> operation) {
        // PCへの事前Entity読取を置かない。論理削除行もauth内native current readで取得する。
        var user=users.findByIdForUpdateIncludingDeleted(userId);
        DeliveryUserState state=user.map(this::state).orElseGet(() ->
                new DeliveryUserState(DeliveryUserState.Lifecycle.ABSENT,null));
        return operation.apply(state);
    }
    private DeliveryUserState state(UserEntity user) {
        DeliveryUserState.Lifecycle lifecycle;
        if(user.getPurgedAt()!=null)lifecycle=DeliveryUserState.Lifecycle.PURGED;
        else if(user.getPurgeStartedAt()!=null)lifecycle=DeliveryUserState.Lifecycle.PURGING;
        else if(user.getDeletedAt()!=null)lifecycle=DeliveryUserState.Lifecycle.WITHDRAWAL;
        else if(user.getStatus()==UserEntity.UserStatus.ACTIVE)lifecycle=DeliveryUserState.Lifecycle.ACTIVE;
        else if(user.getStatus()==UserEntity.UserStatus.FROZEN)lifecycle=DeliveryUserState.Lifecycle.FROZEN;
        else lifecycle=DeliveryUserState.Lifecycle.INELIGIBLE;
        return new DeliveryUserState(lifecycle,user.getWithdrawalAttemptId());
    }
    /** Guardが検証したdistinct昇順IDを、一つのauth TXで現在値lockする。 */
    @Transactional(readOnly=false,propagation=Propagation.REQUIRES_NEW)
    public <T> T withLockedDeliveryUsers(java.util.List<Long> ordered,
            java.util.function.Function<java.util.Map<Long,DeliveryUserState>,T> operation) {
        var states=new java.util.LinkedHashMap<Long,DeliveryUserState>();
        for(Long id:ordered) {
            var user=users.findByIdForUpdateIncludingDeleted(id);
            states.put(id,user.map(this::state).orElseGet(() ->
                    new DeliveryUserState(DeliveryUserState.Lifecycle.ABSENT,null)));
        }
        return operation.apply(java.util.Map.copyOf(states));
    }

}
