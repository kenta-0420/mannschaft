package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.dto.RoleAliasRequest;
import com.mannschaft.app.family.dto.RoleAliasResponse;
import com.mannschaft.app.family.entity.TeamRoleAliasEntity;
import com.mannschaft.app.family.repository.TeamRoleAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleAliasService {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER");
    private static final Set<String> FORBIDDEN_ROLES = Set.of("SYSTEM_ADMIN", "GUEST");
    private final TeamRoleAliasRepository teamRoleAliasRepository;

    public ApiResponse<List<RoleAliasResponse>> getAliases(Long teamId) {
        List<TeamRoleAliasEntity> aliases = teamRoleAliasRepository.findByTeamId(teamId);
        return ApiResponse.of(aliases.stream().map(a -> new RoleAliasResponse(a.getRoleName(), a.getDisplayAlias())).toList());
    }

    @Transactional
    public ApiResponse<List<RoleAliasResponse>> updateAliases(Long teamId, Long userId, RoleAliasRequest request) {
        for (RoleAliasRequest.AliasEntry entry : request.getAliases()) {
            String roleName = entry.getRoleName();
            if (FORBIDDEN_ROLES.contains(roleName)) { throw new BusinessException(FamilyErrorCode.FAMILY_003); }
            if (!ALLOWED_ROLES.contains(roleName)) { throw new BusinessException(FamilyErrorCode.FAMILY_024); }
            if (entry.getDisplayAlias().isEmpty()) {
                teamRoleAliasRepository.deleteByTeamIdAndRoleName(teamId, roleName);
            } else {
                teamRoleAliasRepository.findByTeamIdAndRoleName(teamId, roleName)
                        .ifPresentOrElse(
                                existing -> existing.updateAlias(entry.getDisplayAlias(), userId),
                                () -> teamRoleAliasRepository.save(TeamRoleAliasEntity.builder()
                                        .teamId(teamId).roleName(roleName).displayAlias(entry.getDisplayAlias()).updatedBy(userId).build()));
            }
        }
        return getAliases(teamId);
    }
}
