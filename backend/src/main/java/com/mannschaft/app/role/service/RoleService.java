package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * ロール・権限管理の中核サービス。
 * ロール割当・変更・除名・退会・有効権限解決を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class RoleService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionGroupRepository permissionGroupRepository;
    private final PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    private final UserPermissionGroupRepository userPermissionGroupRepository;

    /**
     * ユーザーにロールを割り当てる。
     */
    @Transactional
    public void assignRole(Long scopeId, String scopeType, Long targetUserId, Long roleId, Long grantedBy) {
        // ロール存在確認
        roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 既存ロール存在チェック → 上書き
        findUserRole(targetUserId, scopeId, scopeType)
                .ifPresent(existing -> userRoleRepository.delete(existing));

        UserRoleEntity.UserRoleEntityBuilder builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(roleId)
                .grantedBy(grantedBy);
        setScopeField(builder, scopeId, scopeType);
        userRoleRepository.save(builder.build());

        log.info("ロール割当完了: scopeType={}, scopeId={}, userId={}, roleId={}, grantedBy={}",
                scopeType, scopeId, targetUserId, roleId, grantedBy);
    }

    /**
     * ユーザーのロールを変更する。最後のADMIN保護チェック付き。
     */
    @Transactional
    public void changeRole(Long scopeId, String scopeType, Long targetUserId,
                           RoleChangeRequest req, Long changedBy) {
        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        RoleEntity currentRole = roleRepository.findById(current.getRoleId()).orElse(null);
        if (currentRole != null && "ADMIN".equals(currentRole.getName())) {
            long adminCount = countByRoleInScope(scopeId, scopeType, current.getRoleId());
            if (adminCount <= 1) {
                throw new BusinessException(RoleErrorCode.ROLE_004);
            }
        }

        // 新ロール存在確認
        roleRepository.findById(req.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 既存を削除して新規作成
        userRoleRepository.delete(current);
        UserRoleEntity.UserRoleEntityBuilder builder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(req.getRoleId());
        setScopeField(builder, scopeId, scopeType);
        userRoleRepository.save(builder.build());

        log.info("ロール変更完了: scopeType={}, scopeId={}, userId={}, newRoleId={}, changedBy={}",
                scopeType, scopeId, targetUserId, req.getRoleId(), changedBy);
    }

    /**
     * メンバーを除名する。最後のADMIN保護チェック付き。
     */
    @Transactional
    public void removeMember(Long scopeId, String scopeType, Long targetUserId) {
        UserRoleEntity current = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        checkLastAdmin(scopeId, scopeType, current);

        userRoleRepository.delete(current);
        log.info("メンバー除名完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, targetUserId);
    }

    /**
     * ユーザーが自主退会する。最後のADMIN保護チェック付き。
     */
    @Transactional
    public void leaveScope(Long userId, Long scopeId, String scopeType) {
        UserRoleEntity current = findUserRole(userId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 最後のADMIN保護
        checkLastAdmin(scopeId, scopeType, current);

        userRoleRepository.delete(current);
        log.info("スコープ退会完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
    }

    /**
     * ユーザーの有効権限リストを解決する。
     * ロール由来 + 権限グループ由来の統合リスト。
     */
    public List<String> resolveEffectivePermissions(Long userId, Long scopeId, String scopeType) {
        // 1. ロール由来の権限
        List<String> rolePermissions = findUserRole(userId, scopeId, scopeType)
                .map(ur -> rolePermissionRepository.findByRoleId(ur.getRoleId()))
                .orElse(List.of())
                .stream()
                .map(rp -> permissionRepository.findById(rp.getPermissionId()).orElse(null))
                .filter(p -> p != null)
                .map(PermissionEntity::getName)
                .toList();

        // 2. 権限グループ由来の権限
        List<PermissionGroupEntity> groups = findPermissionGroups(scopeId, scopeType);
        List<Long> groupIds = groups.stream().map(PermissionGroupEntity::getId).toList();

        List<String> groupPermissions = new ArrayList<>();
        if (!groupIds.isEmpty()) {
            List<UserPermissionGroupEntity> userGroups = userPermissionGroupRepository
                    .findByUserId(userId)
                    .stream()
                    .filter(ug -> groupIds.contains(ug.getGroupId()))
                    .toList();
            for (UserPermissionGroupEntity ug : userGroups) {
                permissionGroupPermissionRepository.findByGroupId(ug.getGroupId())
                        .stream()
                        .map(pgp -> permissionRepository.findById(pgp.getPermissionId()).orElse(null))
                        .filter(p -> p != null)
                        .map(PermissionEntity::getName)
                        .forEach(groupPermissions::add);
            }
        }

        // 3. 統合して重複排除
        return Stream.concat(rolePermissions.stream(), groupPermissions.stream())
                .distinct()
                .toList();
    }

    /**
     * ユーザーが特定の権限を持っているかチェックする。
     */
    public boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName) {
        return resolveEffectivePermissions(userId, scopeId, scopeType).contains(permissionName);
    }

    /**
     * オーナー（ADMIN）権限を譲渡する。
     * 現オーナーは MEMBER にダウングレードされ、対象ユーザーが ADMIN に昇格する。
     *
     * @param scopeId      スコープID（チームID or 組織ID）
     * @param scopeType    スコープ種別（TEAM or ORGANIZATION）
     * @param currentUserId 現オーナーのユーザーID
     * @param targetUserId  譲渡先ユーザーID
     */
    @Transactional
    public void transferOwnership(Long scopeId, String scopeType, Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new BusinessException(RoleErrorCode.ROLE_001);
        }

        // 現ユーザーが ADMIN であることを確認
        UserRoleEntity currentUserRole = findUserRole(currentUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        RoleEntity currentRole = roleRepository.findById(currentUserRole.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        if (!"ADMIN".equals(currentRole.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_001);
        }

        // 対象ユーザーがスコープに所属していることを確認
        UserRoleEntity targetUserRole = findUserRole(targetUserId, scopeId, scopeType)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // ADMIN ロールと MEMBER ロールを取得
        RoleEntity adminRole = currentRole;
        RoleEntity memberRole = roleRepository.findByName("MEMBER")
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 対象ユーザーを ADMIN に昇格
        userRoleRepository.delete(targetUserRole);
        UserRoleEntity.UserRoleEntityBuilder newAdminBuilder = UserRoleEntity.builder()
                .userId(targetUserId)
                .roleId(adminRole.getId())
                .grantedBy(currentUserId);
        setScopeField(newAdminBuilder, scopeId, scopeType);
        userRoleRepository.save(newAdminBuilder.build());

        // 現オーナーを MEMBER にダウングレード
        userRoleRepository.delete(currentUserRole);
        UserRoleEntity.UserRoleEntityBuilder demotedBuilder = UserRoleEntity.builder()
                .userId(currentUserId)
                .roleId(memberRole.getId());
        setScopeField(demotedBuilder, scopeId, scopeType);
        userRoleRepository.save(demotedBuilder.build());

        log.info("オーナー譲渡完了: scopeType={}, scopeId={}, from={}, to={}",
                scopeType, scopeId, currentUserId, targetUserId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * スコープタイプに応じてユーザーロールを検索する。
     */
    private Optional<UserRoleEntity> findUserRole(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.findByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId);
    }

    /**
     * スコープタイプに応じてビルダーのフィールドをセットする。
     */
    private void setScopeField(UserRoleEntity.UserRoleEntityBuilder builder, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
    }

    /**
     * スコープ内のロール数をカウントする。
     */
    private long countByRoleInScope(Long scopeId, String scopeType, Long roleId) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.countByTeamIdAndRoleId(scopeId, roleId);
        }
        return userRoleRepository.countByOrganizationIdAndRoleId(scopeId, roleId);
    }

    /**
     * スコープに応じてパーミッショングループを検索する。
     */
    private List<PermissionGroupEntity> findPermissionGroups(Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return permissionGroupRepository.findByTeamId(scopeId);
        }
        return permissionGroupRepository.findByOrganizationId(scopeId);
    }

    /**
     * 最後のADMINを除名・変更できないよう保護する。
     */
    private void checkLastAdmin(Long scopeId, String scopeType, UserRoleEntity current) {
        RoleEntity currentRole = roleRepository.findById(current.getRoleId()).orElse(null);
        if (currentRole != null && "ADMIN".equals(currentRole.getName())) {
            long adminCount = countByRoleInScope(scopeId, scopeType, current.getRoleId());
            if (adminCount <= 1) {
                throw new BusinessException(RoleErrorCode.ROLE_004);
            }
        }
    }
}
