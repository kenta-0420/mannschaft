package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 権限グループサービス。DEPUTY_ADMINへの権限委譲グループの管理を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PermissionGroupService {

    private final PermissionGroupRepository permissionGroupRepository;
    private final PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionGroupRepository userPermissionGroupRepository;

    /**
     * 権限グループを作成する。
     */
    @Transactional
    public ApiResponse<PermissionGroupResponse> createPermissionGroup(Long scopeId, String scopeType,
                                                                       PermissionGroupRequest req, Long createdBy) {
        // パーミッション存在確認
        validatePermissionIds(req.getPermissionIds());

        PermissionGroupEntity.PermissionGroupEntityBuilder builder = PermissionGroupEntity.builder()
                .name(req.getName())
                .targetRole(PermissionGroupEntity.TargetRole.valueOf(req.getTargetRole()))
                .createdBy(createdBy);
        setScopeField(builder, scopeId, scopeType);
        PermissionGroupEntity group = builder.build();
        permissionGroupRepository.save(group);

        // パーミッション紐付け
        savePermissionGroupPermissions(group.getId(), req.getPermissionIds());

        log.info("権限グループ作成完了: groupId={}, scopeType={}, scopeId={}", group.getId(), scopeType, scopeId);
        return ApiResponse.of(toResponse(group));
    }

    /**
     * 権限グループを更新する。
     */
    @Transactional
    public ApiResponse<PermissionGroupResponse> updatePermissionGroup(Long groupId, PermissionGroupRequest req) {
        PermissionGroupEntity group = permissionGroupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

        // パーミッション存在確認
        validatePermissionIds(req.getPermissionIds());

        // 既存のグループを更新（toBuilderで新オブジェクト作成）
        PermissionGroupEntity updated = group.toBuilder()
                .name(req.getName())
                .targetRole(PermissionGroupEntity.TargetRole.valueOf(req.getTargetRole()))
                .build();
        permissionGroupRepository.save(updated);

        // パーミッション紐付けを差し替え
        permissionGroupPermissionRepository.deleteByGroupId(groupId);
        savePermissionGroupPermissions(groupId, req.getPermissionIds());

        log.info("権限グループ更新完了: groupId={}", groupId);
        return ApiResponse.of(toResponse(updated));
    }

    /**
     * 権限グループを複製する。
     */
    @Transactional
    public ApiResponse<PermissionGroupResponse> duplicatePermissionGroup(Long groupId, Long createdBy) {
        PermissionGroupEntity original = permissionGroupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

        // 複製エンティティ作成
        PermissionGroupEntity.PermissionGroupEntityBuilder builder = PermissionGroupEntity.builder()
                .name(original.getName() + " (コピー)")
                .targetRole(original.getTargetRole())
                .teamId(original.getTeamId())
                .organizationId(original.getOrganizationId())
                .createdBy(createdBy);
        PermissionGroupEntity copy = builder.build();
        permissionGroupRepository.save(copy);

        // パーミッション紐付けを複製
        List<Long> permissionIds = permissionGroupPermissionRepository.findByGroupId(groupId)
                .stream()
                .map(PermissionGroupPermissionEntity::getPermissionId)
                .toList();
        savePermissionGroupPermissions(copy.getId(), permissionIds);

        log.info("権限グループ複製完了: originalId={}, newId={}", groupId, copy.getId());
        return ApiResponse.of(toResponse(copy));
    }

    /**
     * 権限グループを論理削除する。
     */
    @Transactional
    public void deletePermissionGroup(Long groupId) {
        PermissionGroupEntity group = permissionGroupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));
        permissionGroupRepository.delete(group);
        log.info("権限グループ削除完了: groupId={}", groupId);
    }

    /**
     * スコープ内の権限グループ一覧を取得する。
     */
    public List<PermissionGroupResponse> getPermissionGroups(Long scopeId, String scopeType) {
        return findByScope(scopeId, scopeType)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * ユーザーに権限グループを割り当てる。
     */
    @Transactional
    public void assignUserPermissionGroups(Long userId, Long scopeId, String scopeType,
                                           UserPermissionGroupAssignRequest req, Long assignedBy) {
        // 既存の割当を削除
        List<PermissionGroupEntity> scopeGroups = findByScope(scopeId, scopeType);
        List<Long> scopeGroupIds = scopeGroups.stream()
                .map(PermissionGroupEntity::getId).toList();
        if (!scopeGroupIds.isEmpty()) {
            userPermissionGroupRepository.deleteByUserIdAndGroupIdIn(userId, scopeGroupIds);
        }

        // 新しい割当を作成
        for (Long groupId : req.getGroupIds()) {
            permissionGroupRepository.findById(groupId)
                    .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

            UserPermissionGroupEntity entity = UserPermissionGroupEntity.builder()
                    .userId(userId)
                    .groupId(groupId)
                    .assignedBy(assignedBy)
                    .build();
            userPermissionGroupRepository.save(entity);
        }

        log.info("ユーザー権限グループ割当完了: userId={}, scopeType={}, scopeId={}, groupCount={}",
                userId, scopeType, scopeId, req.getGroupIds().size());
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private void validatePermissionIds(List<Long> permissionIds) {
        List<PermissionEntity> found = permissionRepository.findByIdIn(permissionIds);
        if (found.size() != permissionIds.size()) {
            throw new BusinessException(RoleErrorCode.ROLE_007);
        }
    }

    private void savePermissionGroupPermissions(Long groupId, List<Long> permissionIds) {
        for (Long permId : permissionIds) {
            PermissionGroupPermissionEntity entity = PermissionGroupPermissionEntity.builder()
                    .groupId(groupId)
                    .permissionId(permId)
                    .build();
            permissionGroupPermissionRepository.save(entity);
        }
    }

    /**
     * スコープに応じてパーミッショングループを検索する。
     */
    private List<PermissionGroupEntity> findByScope(Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return permissionGroupRepository.findByTeamId(scopeId);
        }
        return permissionGroupRepository.findByOrganizationId(scopeId);
    }

    /**
     * ビルダーにスコープフィールドをセットする。
     */
    private void setScopeField(PermissionGroupEntity.PermissionGroupEntityBuilder builder,
                                Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
    }

    private PermissionGroupResponse toResponse(PermissionGroupEntity group) {
        List<String> permissions = permissionGroupPermissionRepository
                .findByGroupId(group.getId())
                .stream()
                .map(pgp -> permissionRepository.findById(pgp.getPermissionId()).orElse(null))
                .filter(p -> p != null)
                .map(PermissionEntity::getName)
                .toList();

        return new PermissionGroupResponse(
                group.getId(), group.getName(), group.getTargetRole().name(),
                permissions, group.getCreatedAt());
    }
}
