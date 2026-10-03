package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.MemberPermissionSetting;
import com.mannschaft.app.role.dto.MemberPermissionUpdateItem;
import com.mannschaft.app.role.dto.MemberPermissionUpdateRequest;
import com.mannschaft.app.role.dto.MemberPermissionsResponse;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.TeamRolePermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.TeamRolePermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** チーム・組織ごとの MEMBER 既定3権限を管理する。 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberDefaultPermissionService {

    public static final List<String> DEFAULT_PERMISSION_NAMES = List.of(
            "MANAGE_SCHEDULES", "MANAGE_FILES", "MANAGE_POSTS");

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final TeamRolePermissionRepository teamRolePermissionRepository;
    private final RolePermissionCacheGenerationService cacheGenerationService;
    private final AccessControlService accessControlService;

    /** 指定スコープの MEMBER 既定3権限を返す。 */
    public MemberPermissionsResponse get(String scopeType, Long scopeId, Long actorUserId) {
        validateScope(scopeType, scopeId);
        RoleEntity memberRole = memberRole();
        accessControlService.checkScopeAdminOnly(actorUserId, scopeId, scopeType);
        return getAuthorized(scopeType, scopeId, memberRole);
    }

    private MemberPermissionsResponse getAuthorized(String scopeType, Long scopeId, RoleEntity memberRole) {
        Map<Long, Boolean> overrides = teamRolePermissionRepository
                .findByScopeTypeAndScopeIdAndRoleId(scopeType, scopeId, memberRole.getId())
                .stream().collect(Collectors.toMap(
                        TeamRolePermissionEntity::getPermissionId,
                        TeamRolePermissionEntity::getIsEnabled));
        List<PermissionEntity> permissions = defaultPermissions();
        Map<Long, Boolean> globalDefaults = memberCeilingDefaults(memberRole, permissions);
        List<MemberPermissionSetting> settings = permissions.stream()
                .map(permission -> {
                    boolean inherited = !overrides.containsKey(permission.getId());
                    boolean enabled = inherited
                            ? globalDefaults.getOrDefault(permission.getId(), false)
                            : overrides.get(permission.getId());
                    return new MemberPermissionSetting(permission.getName(), permission.getDisplayName(),
                            enabled, inherited);
                })
                .toList();
        return new MemberPermissionsResponse(scopeType, scopeId, "MEMBER", settings);
    }

    /** 3権限を完全指定で保存し、同一トランザクション内で認可キャッシュ世代を進める。 */
    @Transactional
    public MemberPermissionsResponse update(String scopeType, Long scopeId, Long actorUserId,
                                            MemberPermissionUpdateRequest request) {
        validateScope(scopeType, scopeId);
        RoleEntity memberRole = memberRole();
        accessControlService.checkScopeAdminOnly(actorUserId, scopeId, scopeType);
        Map<String, Boolean> requested = validateCompleteRequest(request);
        List<PermissionEntity> permissions = defaultPermissions();
        memberCeilingDefaults(memberRole, permissions);
        Instant now = Instant.now();
        for (PermissionEntity permission : permissions) {
            var existing = teamRolePermissionRepository
                    .findByScopeTypeAndScopeIdAndRoleIdAndPermissionId(
                            scopeType, scopeId, memberRole.getId(), permission.getId());
            TeamRolePermissionEntity entity;
            if (existing.isPresent()) {
                entity = existing.get().toBuilder()
                        .isEnabled(requested.get(permission.getName()))
                        .updatedAt(now)
                        .build();
            } else {
                entity = TeamRolePermissionEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .roleId(memberRole.getId())
                        .permissionId(permission.getId())
                        .isEnabled(requested.get(permission.getName()))
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
            }
            teamRolePermissionRepository.save(entity);
        }
        cacheGenerationService.incrementGeneration(scopeType, scopeId);
        return new MemberPermissionsResponse(scopeType, scopeId, "MEMBER", permissions.stream()
                .map(permission -> new MemberPermissionSetting(permission.getName(), permission.getDisplayName(),
                        requested.get(permission.getName()), false))
                .toList());
    }

    private Map<String, Boolean> validateCompleteRequest(MemberPermissionUpdateRequest request) {
        if (request == null || request.permissions() == null || request.permissions().size() != 3) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        Map<String, Boolean> requested = new LinkedHashMap<>();
        for (MemberPermissionUpdateItem setting : request.permissions()) {
            if (setting == null || setting.name() == null || setting.enabled() == null
                    || requested.putIfAbsent(setting.name(), setting.enabled()) != null) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
        }
        if (!requested.keySet().equals(Set.copyOf(DEFAULT_PERMISSION_NAMES))) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return requested;
    }

    private List<PermissionEntity> defaultPermissions() {
        Map<String, PermissionEntity> byName = permissionRepository.findByNameIn(DEFAULT_PERMISSION_NAMES)
                .stream().collect(Collectors.toMap(PermissionEntity::getName, Function.identity()));
        if (!byName.keySet().containsAll(DEFAULT_PERMISSION_NAMES)) {
            throw new BusinessException(RoleErrorCode.ROLE_007);
        }
        return DEFAULT_PERMISSION_NAMES.stream().map(byName::get).toList();
    }

    private RoleEntity memberRole() {
        return roleRepository.findByName("MEMBER")
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
    }

    private Map<Long, Boolean> memberCeilingDefaults(
            RoleEntity memberRole, List<PermissionEntity> permissions) {
        Map<Long, Boolean> defaults = rolePermissionRepository.findByRoleId(memberRole.getId())
                .stream().collect(Collectors.toMap(
                        rolePermission -> rolePermission.getPermissionId(),
                        rolePermission -> Boolean.TRUE.equals(rolePermission.getIsDefault())));
        boolean ceilingComplete = permissions.stream().allMatch(permission -> defaults.containsKey(permission.getId()));
        if (!ceilingComplete) {
            throw new IllegalStateException("MEMBER 既定3権限の role_permissions 天井が欠落しています");
        }
        return defaults;
    }

    private void validateScope(String scopeType, Long scopeId) {
        if (!("TEAM".equals(scopeType) || "ORGANIZATION".equals(scopeType))
                || scopeId == null || scopeId <= 0) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
