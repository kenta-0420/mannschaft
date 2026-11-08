package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteScopeState;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.provisioning.ProvisioningErrorCode;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 個人招待の親状態を、親domain専属の独立TXへ委譲する。自身はTX/親Repositoryを持たない。 */
@Service
@RequiredArgsConstructor
public class InviteScopeStateService {
    private final List<InviteScopeStatePort> ports;

    public Optional<InviteScopeState> read(String scopeType, Long scopeId) {
        if (scopeId == null || scopeId <= 0) {
            return Optional.empty();
        }
        return port(scopeType).flatMap(port -> port.readCommittedStateForInviteDecision(scopeId));
    }

    public InviteScopeState requireAvailable(String scopeType, Long scopeId) {
        InviteScopeState state = read(scopeType, scopeId).filter(value -> !value.deleted())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
        if (state.provisioned()) {
            throw new BusinessException(ProvisioningErrorCode.PROV_008);
        }
        if (state.archived()) {
            throw "TEAM".equals(scopeType)
                    ? new BusinessException(TeamErrorCode.TEAM_002, HttpStatus.UNPROCESSABLE_ENTITY)
                    : new BusinessException(OrgErrorCode.ORG_003, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return state;
    }

    public boolean isCurrent(String scopeType, Long scopeId, long generation) {
        return read(scopeType, scopeId).filter(state -> state.accepts(generation)).isPresent();
    }

    /** role発行TX終了後だけ呼ぶ。親共有lockはこの呼出し内の短い独立TXで解放される。 */
    public boolean recheckIssued(String scopeType, Long scopeId, long generation) {
        return port(scopeType).flatMap(port -> port.recheckCommittedStateAfterInviteIssue(scopeId))
                .filter(state -> state.accepts(generation)).isPresent();
    }

    private Optional<InviteScopeStatePort> port(String type) {
        return ports.stream().filter(port -> port.scopeType().equals(type)).findFirst();
    }
}
