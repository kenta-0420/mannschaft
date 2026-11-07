package com.mannschaft.app.role.authorization;

import com.mannschaft.app.role.repository.UserRoleRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * スコープの権限ロールをrole内部の値へ写像する照会部品。
 * 既存Repositoryが返すcommon.visibility投影はL4-roleで返済する暫定債務であり、認可実装には公開しない。
 */
@Component
@RequiredArgsConstructor
public class RoleScopeRoleQuery {
    private final UserRoleRepository userRoleRepository;

    /** 認可実装へ渡すrole内部の権限ロール値。 */
    public record ScopeUserRole(Long userId, String roleName) { }

    public List<ScopeUserRole> findScopeRolesByTeamIdAndUserIdIn(Long teamId, Collection<Long> userIds) {
        return userRoleRepository.findScopeRolesByTeamIdAndUserIdIn(teamId, userIds).stream()
                .map(row -> new ScopeUserRole(row.getUserId(), row.getRoleName()))
                .toList();
    }

    public List<ScopeUserRole> findScopeRolesByOrganizationIdAndUserIdIn(
            Long organizationId, Collection<Long> userIds) {
        return userRoleRepository.findScopeRolesByOrganizationIdAndUserIdIn(organizationId, userIds).stream()
                .map(row -> new ScopeUserRole(row.getUserId(), row.getRoleName()))
                .toList();
    }
}
