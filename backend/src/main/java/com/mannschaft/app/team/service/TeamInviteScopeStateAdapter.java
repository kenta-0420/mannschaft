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
        // primary固定の新TXの最初の単独SELECT。外側roleのRR snapshot/親row lockを引き継がない。
        return teamRepository.findByIdIncludingDeleted(scopeId)
                .map(team -> new InviteScopeState(team.getArchivedAt() != null, team.getDeletedAt() != null,
                        team.isProvisioned(), team.getClosureGeneration()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InviteScopeState> recheckCommittedStateAfterInviteIssue(Long scopeId) {
        // 呼出し時はroleの発行TXがcommit済み。user/token lockを持ったjoinからは呼ばない。
        return teamRepository.findByIdForShare(scopeId)
                .map(team -> new InviteScopeState(team.getArchivedAt() != null, false,
                        team.isProvisioned(), team.getClosureGeneration()));
    }
}
