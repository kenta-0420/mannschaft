package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationBlockEntity;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.dto.BlockResponse;
import com.mannschaft.app.team.entity.TeamBlockEntity;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * ブロックサービス。チーム・組織レベルでのユーザーブロック/解除を管理する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class BlockService {

    private final TeamBlockRepository teamBlockRepository;
    private final OrganizationBlockRepository organizationBlockRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;

    /**
     * ユーザーをブロックする。上位ロールのユーザーはブロック不可。ブロック時は自動除名。
     */
    @Transactional
    public ApiResponse<BlockResponse> blockUser(Long scopeId, String scopeType,
                                                 BlockRequest req, Long blockedBy) {
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみブロックできる。
        accessControlService.checkAdminOrAbove(blockedBy, scopeId, scopeType);

        // 上位ロール不可チェック
        checkCanBlock(scopeId, scopeType, req.getUserId(), blockedBy);

        BlockResponse response;
        if ("TEAM".equals(scopeType)) {
            TeamBlockEntity block = TeamBlockEntity.builder()
                    .teamId(scopeId)
                    .userId(req.getUserId())
                    .blockedBy(blockedBy)
                    .reason(req.getReason())
                    .build();
            teamBlockRepository.save(block);
            response = toBlockResponse(block.getId(), req.getUserId(), blockedBy,
                    req.getReason(), block.getCreatedAt());
        } else {
            OrganizationBlockEntity block = OrganizationBlockEntity.builder()
                    .organizationId(scopeId)
                    .userId(req.getUserId())
                    .blockedBy(blockedBy)
                    .reason(req.getReason())
                    .build();
            organizationBlockRepository.save(block);
            response = toBlockResponse(block.getId(), req.getUserId(), blockedBy,
                    req.getReason(), block.getCreatedAt());
        }

        // 自動除名（UserRoleを削除）
        findUserRole(req.getUserId(), scopeId, scopeType)
                .ifPresent(userRoleRepository::delete);

        // 監査ログ用イベント発行
        if ("TEAM".equals(scopeType)) {
            eventPublisher.publishEvent(new TeamMemberAuditEvent(
                    blockedBy, req.getUserId(), scopeId, TeamMemberAuditEvent.SubType.BLOCKED));
        } else {
            eventPublisher.publishEvent(new OrganizationMemberAuditEvent(
                    blockedBy, req.getUserId(), scopeId, OrganizationMemberAuditEvent.SubType.BLOCKED));
        }

        log.info("ユーザーブロック完了: scopeType={}, scopeId={}, userId={}, blockedBy={}",
                scopeType, scopeId, req.getUserId(), blockedBy);
        return ApiResponse.of(response);
    }

    /**
     * ユーザーのブロックを解除する。
     */
    @Transactional
    public void unblockUser(Long scopeId, String scopeType, Long userId, Long unblockedBy) {
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみブロック解除できる。
        accessControlService.checkAdminOrAbove(unblockedBy, scopeId, scopeType);

        if ("TEAM".equals(scopeType)) {
            teamBlockRepository.findByTeamIdAndUserId(scopeId, userId)
                    .ifPresent(teamBlockRepository::delete);
            // 監査ログ用イベント発行（TEAM のみ対応）
            eventPublisher.publishEvent(new TeamMemberAuditEvent(
                    unblockedBy, userId, scopeId, TeamMemberAuditEvent.SubType.UNBLOCKED));
        } else {
            organizationBlockRepository.findByOrganizationIdAndUserId(scopeId, userId)
                    .ifPresent(organizationBlockRepository::delete);
            // 監査ログ用イベント発行
            eventPublisher.publishEvent(new OrganizationMemberAuditEvent(
                    unblockedBy, userId, scopeId, OrganizationMemberAuditEvent.SubType.UNBLOCKED));
        }
        log.info("ユーザーブロック解除完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
    }

    /**
     * スコープ内のブロック一覧を取得する。
     */
    public List<BlockResponse> getBlocks(Long scopeId, String scopeType, Long actorUserId) {
        // 束1 権限昇格根治: ブロック一覧は当該スコープの ADMIN/DEPUTY_ADMIN のみ閲覧できる。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);

        if ("TEAM".equals(scopeType)) {
            return teamBlockRepository.findByTeamId(scopeId).stream()
                    .map(b -> toBlockResponse(b.getId(), b.getUserId(), b.getBlockedBy(),
                            b.getReason(), b.getCreatedAt()))
                    .toList();
        } else {
            return organizationBlockRepository.findByOrganizationId(scopeId).stream()
                    .map(b -> toBlockResponse(b.getId(), b.getUserId(), b.getBlockedBy(),
                            b.getReason(), b.getCreatedAt()))
                    .toList();
        }
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * ブロック実行者よりも上位ロールのユーザーをブロックできないようチェック。
     */
    private void checkCanBlock(Long scopeId, String scopeType, Long targetUserId, Long blockedBy) {
        // ブロック対象のロール階層レベルを取得
        int targetLevel = getHierarchyLevel(targetUserId, scopeId, scopeType);
        int blockerLevel = getHierarchyLevel(blockedBy, scopeId, scopeType);

        // 階層レベルが低い値ほど上位ロール
        if (targetLevel <= blockerLevel) {
            throw new BusinessException(RoleErrorCode.ROLE_005);
        }
    }

    /**
     * ユーザーのスコープ内でのロール階層レベルを取得する。
     */
    private int getHierarchyLevel(Long userId, Long scopeId, String scopeType) {
        return findUserRole(userId, scopeId, scopeType)
                .map(ur -> roleRepository.findById(ur.getRoleId()).orElse(null))
                .map(RoleEntity::getPriority)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * スコープタイプに応じてユーザーロールを検索する。
     */
    private Optional<UserRoleEntity> findUserRole(Long userId, Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            return userRoleRepository.findByUserIdAndTeamId(userId, scopeId);
        }
        return userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId);
    }

    private BlockResponse toBlockResponse(Long id, Long userId, Long blockedBy,
                                          String reason, java.time.LocalDateTime createdAt) {
        String displayName = userRepository.findById(userId)
                .map(UserEntity::getDisplayName).orElse(null);
        String blockedByName = userRepository.findById(blockedBy)
                .map(UserEntity::getDisplayName).orElse(null);
        return new BlockResponse(id, userId, displayName, blockedByName, reason, createdAt);
    }
}
