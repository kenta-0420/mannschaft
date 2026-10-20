package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 親domainのcommit済み閉鎖世代を値で受け取り、roleの招待だけを失効する。 */
@Service
@RequiredArgsConstructor
public class InviteTokenClosureCleanupRunner {
    private final InviteTokenRepository inviteTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeBeforeGeneration(String scopeType, Long scopeId, long observedGeneration) {
        if (observedGeneration <= 0) {
            return 0;
        }
        return switch (scopeType) {
            case "TEAM" -> inviteTokenRepository.revokeTeamBeforeGeneration(scopeId, observedGeneration);
            case "ORGANIZATION" -> inviteTokenRepository.revokeOrganizationBeforeGeneration(scopeId, observedGeneration);
            default -> throw new IllegalArgumentException("個人招待のscope種別が不正です");
        };
    }
}
