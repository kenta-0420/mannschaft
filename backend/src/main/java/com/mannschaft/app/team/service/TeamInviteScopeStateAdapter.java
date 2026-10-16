package com.mannschaft.app.team.service;

import com.mannschaft.app.role.dto.InviteScopeState;
import com.mannschaft.app.role.service.InviteScopeStatePort;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 個人招待が読むチーム自身の閉鎖状態。親組織の休止には連動しない。 */
@Service
@RequiredArgsConstructor
public class TeamInviteScopeStateAdapter implements InviteScopeStatePort {
    private final TeamRepository teamRepository;

    @Override
    public String scopeType() {
        return "TEAM";
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
