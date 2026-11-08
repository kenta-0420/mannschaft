package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.InviteScopeState;
import com.mannschaft.app.role.service.InviteScopeStatePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 個人招待が読む組織自身の閉鎖状態。所属チームを変更しない。 */
@Service
@RequiredArgsConstructor
public class OrganizationInviteScopeStateAdapter implements InviteScopeStatePort {
    private final OrganizationRepository organizationRepository;

    @Override
    public String scopeType() {
        return "ORGANIZATION";
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InviteScopeState> readCommittedStateForInviteDecision(Long scopeId) {
        // primary固定の新TXの最初の単独SELECT。joinは親lockを待たずcommit済み状態で判定する。
        return organizationRepository.findByIdIncludingDeleted(scopeId)
                .map(org -> new InviteScopeState(org.getArchivedAt() != null, org.getDeletedAt() != null,
                        org.isProvisioned(), org.getClosureGeneration()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InviteScopeState> recheckCommittedStateAfterInviteIssue(Long scopeId) {
        return organizationRepository.findByIdForShare(scopeId)
                .map(org -> new InviteScopeState(org.getArchivedAt() != null, false,
                        org.isProvisioned(), org.getClosureGeneration()));
    }
}
