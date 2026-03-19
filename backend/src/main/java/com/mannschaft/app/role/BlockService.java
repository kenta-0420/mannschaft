package com.mannschaft.app.role;

import com.mannschaft.app.auth.UserEntity;
import com.mannschaft.app.auth.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrganizationBlockEntity;
import com.mannschaft.app.organization.OrganizationBlockRepository;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.dto.BlockResponse;
import com.mannschaft.app.team.TeamBlockEntity;
import com.mannschaft.app.team.TeamBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * ユーザーをブロックする。上位ロールのユーザーはブロック不可。ブロック時は自動除名。
     */
    @Transactional
    public ApiResponse<BlockResponse> blockUser(Long scopeId, String scopeType,
                                                 BlockRequest req, Long blockedBy) {
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

        log.info("ユーザーブロック完了: scopeType={}, scopeId={}, userId={}, blockedBy={}",
                scopeType, scopeId, req.getUserId(), blockedBy);
        return ApiResponse.of(response);
    }

    /**
     * ユーザーのブロックを解除する。
     */
    @Transactional
    public void unblockUser(Long scopeId, String scopeType, Long userId) {
        if ("TEAM".equals(scopeType)) {
            teamBlockRepository.findByTeamIdAndUserId(scopeId, userId)
                    .ifPresent(teamBlockRepository::delete);
        } else {
            organizationBlockRepository.findByOrganizationIdAndUserId(scopeId, userId)
                    .ifPresent(organizationBlockRepository::delete);
        }
        log.info("ユーザーブロック解除完了: scopeType={}, scopeId={}, userId={}", scopeType, scopeId, userId);
    }

    /**
     * スコープ内のブロック一覧を取得する。
     */
    public List<BlockResponse> getBlocks(Long scopeId, String scopeType) {
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
