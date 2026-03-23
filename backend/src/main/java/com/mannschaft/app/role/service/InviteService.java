package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 招待トークンサービス。トークン作成・プレビュー・参加・失効を管理する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class InviteService {

    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final TeamBlockRepository teamBlockRepository;
    private final OrganizationBlockRepository organizationBlockRepository;

    /**
     * 招待トークンを作成する。
     */
    @Transactional
    public ApiResponse<InviteTokenResponse> createInviteToken(Long scopeId, String scopeType,
                                                               CreateInviteTokenRequest req, Long createdBy) {
        RoleEntity role = roleRepository.findById(req.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 有効期限の計算
        LocalDateTime expiresAt = resolveExpiresAt(req.getExpiresIn());

        InviteTokenEntity.InviteTokenEntityBuilder builder = InviteTokenEntity.builder()
                .token(UUID.randomUUID().toString())
                .roleId(req.getRoleId())
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .maxUses(req.getMaxUses())
                .usedCount(0);
        setScopeFieldOnInvite(builder, scopeId, scopeType);
        InviteTokenEntity token = builder.build();
        inviteTokenRepository.save(token);

        log.info("招待トークン作成完了: scopeType={}, scopeId={}, roleId={}", scopeType, scopeId, req.getRoleId());
        return ApiResponse.of(toResponse(token, role.getName()));
    }

    /**
     * スコープ内の有効な招待トークン一覧を取得する。
     */
    public List<InviteTokenResponse> getInviteTokens(Long scopeId, String scopeType) {
        List<InviteTokenEntity> tokens;
        if ("TEAM".equals(scopeType)) {
            tokens = inviteTokenRepository.findByTeamIdAndRevokedAtIsNull(scopeId);
        } else {
            tokens = inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(scopeId);
        }
        return tokens.stream()
                .map(token -> {
                    String roleName = roleRepository.findById(token.getRoleId())
                            .map(RoleEntity::getName).orElse(null);
                    return toResponse(token, roleName);
                })
                .toList();
    }

    /**
     * 招待トークンを失効させる。
     */
    @Transactional
    public void revokeInviteToken(Long tokenId) {
        InviteTokenEntity token = inviteTokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
        token.revoke();
        log.info("招待トークン失効完了: tokenId={}", tokenId);
    }

    /**
     * 招待トークンをプレビューする。未認証ユーザーにも表示可能。
     */
    public ApiResponse<InvitePreviewResponse> previewInvite(String tokenStr) {
        InviteTokenEntity token = inviteTokenRepository.findByToken(tokenStr)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        String scopeType = resolveScopeType(token);
        Long scopeId = resolveScopeId(token);
        String targetName = resolveTargetName(scopeId, scopeType);
        String roleName = roleRepository.findById(token.getRoleId())
                .map(RoleEntity::getName).orElse(null);

        return ApiResponse.of(new InvitePreviewResponse(
                targetName, scopeType, roleName, token.isValid()));
    }

    /**
     * 招待トークンを使用してスコープに参加する。
     * FOR UPDATEでロック取得し、ブロック・重複・有効性をチェック。
     */
    @Transactional
    public void joinByInvite(String tokenStr, Long userId) {
        // FOR UPDATEでロック取得（同時参加の排他制御）
        InviteTokenEntity token = inviteTokenRepository.findByTokenForUpdate(tokenStr)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        // 有効性チェック
        if (!token.isValid()) {
            if (token.getMaxUses() != null && token.getUsedCount() >= token.getMaxUses()) {
                throw new BusinessException(RoleErrorCode.ROLE_003);
            }
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        String scopeType = resolveScopeType(token);
        Long scopeId = resolveScopeId(token);

        // ブロックチェック
        checkNotBlocked(userId, scopeId, scopeType);

        // 重複参加チェック
        boolean alreadyJoined = "TEAM".equals(scopeType)
                ? userRoleRepository.existsByUserIdAndTeamId(userId, scopeId)
                : userRoleRepository.findByUserIdAndOrganizationId(userId, scopeId).isPresent();
        if (alreadyJoined) {
            throw new BusinessException(TeamErrorCode.TEAM_003);
        }

        // ロール割当
        UserRoleEntity.UserRoleEntityBuilder roleBuilder = UserRoleEntity.builder()
                .userId(userId)
                .roleId(token.getRoleId());
        if ("TEAM".equals(scopeType)) {
            roleBuilder.teamId(scopeId);
        } else {
            roleBuilder.organizationId(scopeId);
        }
        userRoleRepository.save(roleBuilder.build());

        // 使用回数をインクリメント
        token.incrementUsedCount();

        log.info("招待トークンによる参加完了: userId={}, scopeType={}, scopeId={}",
                userId, scopeType, scopeId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * expiresIn文字列からLocalDateTimeを計算する。
     */
    private LocalDateTime resolveExpiresAt(String expiresIn) {
        if (expiresIn == null) {
            return null;
        }
        return switch (expiresIn) {
            case "1d" -> LocalDateTime.now().plusDays(1);
            case "7d" -> LocalDateTime.now().plusDays(7);
            case "30d" -> LocalDateTime.now().plusDays(30);
            case "90d" -> LocalDateTime.now().plusDays(90);
            default -> null;
        };
    }

    /**
     * トークンからスコープタイプを判定する。
     */
    private String resolveScopeType(InviteTokenEntity token) {
        if (token.getTeamId() != null) {
            return "TEAM";
        }
        return "ORGANIZATION";
    }

    /**
     * トークンからスコープIDを取得する。
     */
    private Long resolveScopeId(InviteTokenEntity token) {
        if (token.getTeamId() != null) {
            return token.getTeamId();
        }
        return token.getOrganizationId();
    }

    /**
     * スコープの名前を解決する。
     */
    private String resolveTargetName(Long scopeId, String scopeType) {
        return switch (scopeType) {
            case "ORGANIZATION" -> organizationRepository.findById(scopeId)
                    .map(OrganizationEntity::getName).orElse(null);
            case "TEAM" -> teamRepository.findById(scopeId)
                    .map(TeamEntity::getName).orElse(null);
            default -> null;
        };
    }

    /**
     * ブロックされていないかチェックする。
     */
    private void checkNotBlocked(Long userId, Long scopeId, String scopeType) {
        boolean blocked = switch (scopeType) {
            case "TEAM" -> teamBlockRepository.existsByTeamIdAndUserId(scopeId, userId);
            case "ORGANIZATION" -> organizationBlockRepository.existsByOrganizationIdAndUserId(scopeId, userId);
            default -> false;
        };
        if (blocked) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }
    }

    /**
     * InviteTokenEntityビルダーにスコープフィールドをセットする。
     */
    private void setScopeFieldOnInvite(InviteTokenEntity.InviteTokenEntityBuilder builder,
                                        Long scopeId, String scopeType) {
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
    }

    private InviteTokenResponse toResponse(InviteTokenEntity token, String roleName) {
        return new InviteTokenResponse(
                token.getId(), token.getToken(), roleName,
                token.getExpiresAt(), token.getMaxUses(), token.getUsedCount(),
                token.getRevokedAt(), token.getCreatedAt());
    }
}
