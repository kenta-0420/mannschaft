package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.repository.MembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 個人横断表示に使う現役所属を、所属ドメイン内で取得する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecentMembershipScopeQueryService {

    private final MembershipRepository membershipRepository;

    public record RecentScope(ScopeType scopeType, Long scopeId, RoleKind roleKind) {
    }

    public List<RecentScope> findRecentTeamAndOrganizationScopes(Long userId, int limit) {
        return membershipRepository.findRecentActiveByUser(
                        userId, Set.of(ScopeType.TEAM, ScopeType.ORGANIZATION), PageRequest.of(0, limit))
                .stream()
                .map(m -> new RecentScope(m.getScopeType(), m.getScopeId(), m.getRoleKind()))
                .toList();
    }
}
