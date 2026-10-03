package com.mannschaft.app.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Supplier;

/**
 * usersを最初にロックし、本人操作をACTIVE状態へ束縛する認証境界。
 * コールバックは独立ドメインのREQUIRES_NEW writerだけを呼び、authへの再入・ネットワークI/Oをしない。
 * authロックはコールバックのcommitまで保持する。接続プールは最低二接続を必要とする。
 */
@Service
@RequiredArgsConstructor
public class UserOperationGuard {
    private final UserRowLockService userRowLockService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T withActiveUser(Long userId, Supplier<T> operation) {
        throw new UnsupportedOperationException("本人操作境界は未実装");
    }
}
