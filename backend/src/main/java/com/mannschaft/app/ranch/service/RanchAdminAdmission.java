package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/** ACTIVE本人の認証PRIMARY admission内で、tokenのroleではなく現在の管理者資格を確認する。 */
@Service
@RequiredArgsConstructor
public class RanchAdminAdmission {
    private final UserOperationGuard activeUser;
    private final AccessControlService accessControl;

    public <T> T checked(Long actorId, Supplier<T> action) {
        return activeUser.withActiveUser(actorId, () -> {
            accessControl.checkSystemAdmin(actorId);
            return action.get();
        });
    }
}