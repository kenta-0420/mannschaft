package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.dto.UserPermissionGroupAssignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

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
    private final AccessControlService accessControlService;
    private final CacheManager cacheManager;
    private final CacheErrorHandler cacheErrorHandler;
    private final UserRowLockService userRowLockService;

    private static final List<String> F0914_SENSITIVE_PERMISSIONS =
            List.of("SEND_PAID_TIMELINE", "VIEW_TIMELINE_COST");

    /**
     * 権限グループを作成する。
     */
    @Transactional
    public ApiResponse<PermissionGroupResponse> createPermissionGroup(Long scopeId, String scopeType,
                                                                       PermissionGroupRequest req, Long createdBy) {
        userRowLockService.lock(createdBy);
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみ権限グループを作成できる。
        requireMutationAuthority(createdBy, scopeId, scopeType, req.getPermissionIds());

        // パーミッション存在確認
        validatePermissionIds(req.getPermissionIds());

        var builder = PermissionGroupEntity.builder()
                .name(req.getName())
                .targetRole(PermissionGroupEntity.TargetRole.valueOf(req.getTargetRole()))
                .createdBy(createdBy);
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
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
    public ApiResponse<PermissionGroupResponse> updatePermissionGroup(Long groupId, PermissionGroupRequest req,
                                                                       Long actorUserId) {
        userRowLockService.lock(actorUserId);
        PermissionGroupEntity group = permissionGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

        // 束1 BOLA 根治: グループが属するスコープの ADMIN/DEPUTY_ADMIN のみ更新できる（別スコープ ADMIN の越境改変を遮断）。
        List<Long> oldPermissionIds = permissionIdsForGroup(groupId);
        List<Long> affectedUsers = userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(groupId));
        requireMutationAuthority(actorUserId, group, union(oldPermissionIds, req.getPermissionIds()));

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
        evictAfterCommit(affectedUsers, group);

        log.info("権限グループ更新完了: groupId={}", groupId);
        return ApiResponse.of(toResponse(updated));
    }

    /**
     * 権限グループを複製する。
     */
    @Transactional
    public ApiResponse<PermissionGroupResponse> duplicatePermissionGroup(Long groupId, Long createdBy) {
        userRowLockService.lock(createdBy);
        PermissionGroupEntity original = permissionGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

        List<Long> permissionIds = permissionIdsForGroup(groupId);

        // 束1 BOLA 根治: 複製元が属するスコープの ADMIN/DEPUTY_ADMIN のみ複製できる。
        requireMutationAuthority(createdBy, original, permissionIds);

        // 複製エンティティ作成
        var dupBuilder = PermissionGroupEntity.builder()
                .name(original.getName() + " (コピー)")
                .targetRole(original.getTargetRole())
                .teamId(original.getTeamId())
                .organizationId(original.getOrganizationId())
                .createdBy(createdBy);
        PermissionGroupEntity copy = dupBuilder.build();
        permissionGroupRepository.save(copy);

        // パーミッション紐付けを複製
        savePermissionGroupPermissions(copy.getId(), permissionIds);

        log.info("権限グループ複製完了: originalId={}, newId={}", groupId, copy.getId());
        return ApiResponse.of(toResponse(copy));
    }

    /**
     * 権限グループを論理削除する。
     */
    @Transactional
    public void deletePermissionGroup(Long groupId, Long actorUserId) {
        userRowLockService.lock(actorUserId);
        PermissionGroupEntity group = permissionGroupRepository.findByIdForUpdate(groupId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));

        // 束1 BOLA 根治: グループが属するスコープの ADMIN/DEPUTY_ADMIN のみ削除できる。
        List<Long> permissionIds = permissionGroupPermissionRepository.findByGroupId(groupId).stream()
                .map(PermissionGroupPermissionEntity::getPermissionId).toList();
        List<Long> affectedUsers = userPermissionGroupRepository.findUserIdsByGroupIdIn(List.of(groupId));
        requireMutationAuthority(actorUserId, group, permissionIds);
        permissionGroupRepository.delete(group);
        evictAfterCommit(affectedUsers, group);
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
     * ユーザーが指定スコープ内で特定のパーミッションを保有しているかを判定する。
     *
     * <p>F09.13 Phase 2-α-3: DEPUTY_ADMIN の MANAGE/VIEW 区別など、
     * 権限グループ経由で個別ユーザーに割り当てられたパーミッションの有無を照会する。</p>
     *
     * <p><strong>判定アルゴリズム</strong>:</p>
     * <ol>
     *   <li>当該 scope（TEAM/ORGANIZATION）の {@link PermissionGroupEntity} を全件取得</li>
     *   <li>そのうちユーザーに割当られている group のみに絞り込み（{@code user_permission_groups}）</li>
     *   <li>それら group に紐付く {@code permission_group_permissions} を辿り、{@code permissionName} と一致する {@link PermissionEntity} が含まれるかを判定</li>
     * </ol>
     *
     * <p>ロール直付けの permission（{@code role_permissions} の {@code is_default=1} 等）は
     * 本メソッドでは判定しない。本メソッドは「権限グループ経由の明示付与」のみを対象とする。
     * SystemAdmin / ADMIN への自動付与判定は呼び出し側（例: {@code PropertyWorkPackageMaskingService}）で
     * ロールベースに別途行うこと。</p>
     *
     * @param userId         ユーザーID（null の場合は false）
     * @param scopeType      スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId        スコープID
     * @param permissionName パーミッション名（例: "PROPERTY_HISTORY_MANAGE"）
     * @return 当該スコープ内で当該パーミッションを保有していれば true
     */
    public boolean hasPermission(Long userId, String scopeType, Long scopeId, String permissionName) {
        if (userId == null || scopeType == null || scopeId == null || permissionName == null) {
            return false;
        }
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            return false;
        }
        String effectiveRoleName = accessControlService.resolveEffectiveRoleName(userId, scopeId, scopeType);
        if (effectiveRoleName == null) {
            return false;
        }
        // 1. 当該 scope の権限グループを取得
        List<PermissionGroupEntity> scopeGroups = findByScope(scopeId, scopeType);
        if (scopeGroups.isEmpty()) {
            return false;
        }
        List<Long> scopeGroupIds = scopeGroups.stream().map(PermissionGroupEntity::getId).toList();

        // 2. ユーザーに割当られている group のみに絞り込み
        List<UserPermissionGroupEntity> userAssignments = userPermissionGroupRepository.findByUserId(userId);
        List<Long> userGroupIds = userAssignments.stream()
                .map(UserPermissionGroupEntity::getGroupId)
                .filter(scopeGroupIds::contains)
                .toList();
        if (userGroupIds.isEmpty()) {
            return false;
        }

        // 3. それら group に紐付く permission を辿り、permissionName と一致するか判定
        for (Long groupId : userGroupIds) {
            PermissionGroupEntity group = scopeGroups.stream()
                    .filter(candidate -> candidate.getId().equals(groupId)).findFirst().orElse(null);
            if (group == null || group.getTargetRole() == null
                    || !effectiveRoleName.equals(group.getTargetRole().name())) {
                continue;
            }
            List<PermissionGroupPermissionEntity> pgps = permissionGroupPermissionRepository.findByGroupId(groupId);
            for (PermissionGroupPermissionEntity pgp : pgps) {
                PermissionEntity perm = permissionRepository.findById(pgp.getPermissionId()).orElse(null);
                if (perm != null && permissionName.equals(perm.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * ユーザーに権限グループを割り当てる。
     *
     * <p>Issue #2797 BOLA 根治: 付与対象の group は<b>引数のスコープに属するものに限る</b>。
     * 従来は {@code findById} による存在確認しかしておらず、別スコープの権限グループ ID を
     * 指定すると割当行が作られていた。削除側は既に {@link #findByScope} でスコープを絞っており、
     * 付与側だけが非対称に緩かった。判定を二重に書かず、削除側と<b>同じ集合</b>
     * （{@code scopeGroupIds}）を許可リストとして使う。</p>
     *
     * <p>スコープ外の ID は「見つからない」（{@link RoleErrorCode#ROLE_006} / 404）として扱う。
     * 存在しない ID と他スコープの ID を同一応答へ畳むことで、他組織にどの権限グループが
     * 存在するかを応答差から推し量れないようにする（{@link RoleErrorCode#ROLE_002} と同じ存在秘匿の作法）。</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void assignUserPermissionGroups(Long userId, Long scopeId, String scopeType,
                                           UserPermissionGroupAssignRequest req, Long assignedBy) {
        // 対象userの存在・所属を参照する前にactorのscope管理権限を確認し、情報漏えいを防ぐ。
        Map<Long, UserRowLockService.UserState> lockedUsers = userRowLockService.lockAll(assignedBy, userId);
        accessControlService.checkAdminOrAbove(assignedBy, scopeId, scopeType);
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみ権限グループを割り当てられる。
        // 権限集合の検証は、現行割当と新割当の和集合に対して一度だけ行う。

        // 既存の割当を削除
        UserRowLockService.UserState userState = lockedUsers
                .getOrDefault(userId, UserRowLockService.UserState.ABSENT);
        if (userState == UserRowLockService.UserState.ABSENT) {
            if (req.getGroupIds().isEmpty()) {
                return;
            }
            throw new BusinessException(RoleErrorCode.ROLE_006);
        }
        if (userState == UserRowLockService.UserState.INELIGIBLE_EXISTING
                && !req.getGroupIds().isEmpty()) {
            throw new BusinessException(RoleErrorCode.ROLE_006);
        }
        List<PermissionGroupEntity> scopeGroups = findByScope(scopeId, scopeType);
        List<Long> scopeGroupIds = scopeGroups.stream()
                .map(PermissionGroupEntity::getId).toList();
        List<Long> currentScopeGroupIds = userPermissionGroupRepository.findByUserId(userId).stream()
                .map(UserPermissionGroupEntity::getGroupId)
                .filter(scopeGroupIds::contains)
                .toList();
        List<Long> requestedScopeGroupIds = req.getGroupIds().stream()
                .filter(scopeGroupIds::contains)
                .toList();
        List<Long> lockGroupIds = union(currentScopeGroupIds, requestedScopeGroupIds).stream()
                .sorted().toList();
        List<PermissionGroupEntity> lockedGroups = lockGroupIds.isEmpty()
                ? List.of() : permissionGroupRepository.findByIdInForUpdateOrderByIdAsc(lockGroupIds);
        if (!req.getGroupIds().isEmpty()) {
            if (req.getGroupIds().stream().anyMatch(id -> !scopeGroupIds.contains(id))) {
                throw new BusinessException(RoleErrorCode.ROLE_006);
            }
            validateAssignmentTarget(userId, scopeId, scopeType, req.getGroupIds(), lockedGroups);
        }
        List<Long> oldGroupIds = userPermissionGroupRepository.findByUserId(userId).stream()
                .map(UserPermissionGroupEntity::getGroupId).filter(scopeGroupIds::contains).toList();
        List<Long> oldPermissionIds = oldGroupIds.stream().flatMap(id -> permissionIdsForGroup(id).stream()).toList();
        List<Long> newPermissionIds = req.getGroupIds().stream().filter(scopeGroupIds::contains)
                .flatMap(id -> permissionIdsForGroup(id).stream()).toList();
        if (lockedGroups.size() < lockGroupIds.size()
                && req.getGroupIds().stream().anyMatch(id -> lockedGroups.stream()
                .noneMatch(group -> group.getId().equals(id)))) {
            throw new BusinessException(RoleErrorCode.ROLE_006);
        }
        requireMutationAuthority(assignedBy, scopeId, scopeType, union(oldPermissionIds, newPermissionIds));
        if (!scopeGroupIds.isEmpty()) {
            userPermissionGroupRepository.deleteByUserIdAndGroupIdIn(userId, scopeGroupIds);
        }

        // 新しい割当を作成
        for (Long groupId : req.getGroupIds()) {
            // Issue #2797: 当該スコープに属する group のみ許可（越境付与の遮断）。
            if (!scopeGroupIds.contains(groupId)) {
                throw new BusinessException(RoleErrorCode.ROLE_006);
            }

            UserPermissionGroupEntity entity = UserPermissionGroupEntity.builder()
                    .userId(userId)
                    .groupId(groupId)
                    .assignedBy(assignedBy)
                    .build();
            userPermissionGroupRepository.save(entity);
        }

        log.info("ユーザー権限グループ割当完了: userId={}, scopeType={}, scopeId={}, groupCount={}",
                userId, scopeType, scopeId, req.getGroupIds().size());
        evictAfterCommit(List.of(userId), scopeType, scopeId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * 束1 BOLA 根治: 権限グループが属するスコープ（teamId or organizationId）を entity から導出し、
     * そのスコープの ADMIN/DEPUTY_ADMIN であることを要求する。別スコープの ADMIN が groupId 指定で
     * 越境改変（BOLA）するのを遮断する。
     */
    private void checkScopeAdmin(PermissionGroupEntity group, Long actorUserId) {
        if (group.getTeamId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, group.getTeamId(), "TEAM");
        } else {
            accessControlService.checkAdminOrAbove(actorUserId, group.getOrganizationId(), "ORGANIZATION");
        }
    }

    private void requireMutationAuthority(Long actorUserId, Long scopeId, String scopeType,
                                          List<Long> permissionIds) {
        if (containsF0914Permission(permissionIds)) {
            accessControlService.checkScopeAdminOnly(actorUserId, scopeId, scopeType);
        } else {
            accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);
        }
    }

    private void requireMutationAuthority(Long actorUserId, PermissionGroupEntity group,
                                          List<Long> permissionIds) {
        Long scopeId = group.getTeamId() != null ? group.getTeamId() : group.getOrganizationId();
        String scopeType = group.getTeamId() != null ? "TEAM" : "ORGANIZATION";
        requireMutationAuthority(actorUserId, scopeId, scopeType, permissionIds);
    }

    private boolean containsF0914Permission(List<Long> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) return false;
        return permissionRepository.findByIdIn(permissionIds).stream()
                .map(PermissionEntity::getName)
                .anyMatch(F0914_SENSITIVE_PERMISSIONS::contains);
    }

    private List<Long> permissionIdsForGroup(Long groupId) {
        return permissionGroupPermissionRepository.findByGroupId(groupId).stream()
                .map(PermissionGroupPermissionEntity::getPermissionId).toList();
    }

    private List<Long> union(List<Long> first, List<Long> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).distinct().toList();
    }

    private void validateAssignmentTarget(Long userId, Long scopeId, String scopeType,
                                          List<Long> requestedGroupIds,
                                          List<PermissionGroupEntity> lockedGroups) {
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            throw new BusinessException(RoleErrorCode.ROLE_006);
        }
        String effectiveRole = accessControlService.resolveEffectiveRoleName(userId, scopeId, scopeType);
        if (effectiveRole == null) {
            throw new BusinessException(RoleErrorCode.ROLE_006);
        }
        for (Long groupId : requestedGroupIds) {
            PermissionGroupEntity group = lockedGroups.stream()
                    .filter(candidate -> groupId.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_006));
            if (group.getTargetRole() == null || !effectiveRole.equals(group.getTargetRole().name())) {
                throw new BusinessException(RoleErrorCode.ROLE_006);
            }
        }
    }

    private void evictAfterCommit(List<Long> userIds, PermissionGroupEntity group) {
        evictAfterCommit(userIds, group.getTeamId() != null ? "TEAM" : "ORGANIZATION",
                group.getTeamId() != null ? group.getTeamId() : group.getOrganizationId());
    }

    private void evictAfterCommit(List<Long> userIds, String scopeType, Long scopeId) {
        Runnable eviction = () -> evictRolePermissions(userIds, scopeType, scopeId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { eviction.run(); }
            });
        } else {
            eviction.run();
        }
    }

    private void evictRolePermissions(List<Long> userIds, String scopeType, Long scopeId) {
        /* cache manager failure is handled per key so later users are still attempted. */
        userIds.stream().distinct().forEach(userId -> {
            String key = userId + ":" + scopeType + ":" + scopeId;
            org.springframework.cache.Cache cache = null;
            try {
                cache = cacheManager.getCache("role-permissions");
                if (cache != null) {
                    cache.evict(key);
                }
            } catch (RuntimeException ex) {
                if (cacheErrorHandler != null) {
                    cacheErrorHandler.handleCacheEvictError(ex, cache, key);
                }
            }
        });
    }

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
