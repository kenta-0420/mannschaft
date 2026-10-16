package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 親domainのcommit済み閉鎖世代を値で受け取り、roleの招待だけを失効する。 */
@Service
@RequiredArgsConstructor
public class InviteTokenClosureCleanupRunner {
    private final InviteTokenRepository inviteTokenRepository;

    @Transactional
    public int revokeBeforeGeneration(String scopeType, Long scopeId, long observedGeneration) {
        // 試練用signature骨格。既存行保持・strict世代差・冪等性の実DB REDを先行する。
        return 0;
    }
}
