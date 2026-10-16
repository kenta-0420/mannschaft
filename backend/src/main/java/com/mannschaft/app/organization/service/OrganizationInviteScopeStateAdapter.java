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
        // 試練用signature骨格。出陣前にDB読取の仕様アサーションREDを確かめる。
        return Optional.empty();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InviteScopeState> recheckCommittedStateAfterInviteIssue(Long scopeId) {
        return Optional.empty();
    }
}
