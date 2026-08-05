package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.common.qr.BrandedQrImageWriter;
import org.springframework.beans.factory.annotation.Value;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.event.TeamInviteTokenCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationInviteTokenCreatedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
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

    @Value("${app.base-url}")
    private String baseUrl;

    private static final int QR_DEFAULT_SIZE = 300;
    private static final int QR_MIN_SIZE = 64;
    private static final int QR_MAX_SIZE = 1024;

    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final TeamBlockRepository teamBlockRepository;
    private final OrganizationBlockRepository organizationBlockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MyScopeFolderService myScopeFolderService;
    private final MyScopeFolderRepository myScopeFolderRepository;
    private final MembershipService membershipService;
    private final BrandedQrImageWriter brandedQrImageWriter;
    private final AccessControlService accessControlService;

    /**
     * 招待トークンを作成する。
     */
    @Transactional
    public ApiResponse<InviteTokenResponse> createInviteToken(Long scopeId, String scopeType,
                                                               CreateInviteTokenRequest req, Long createdBy) {
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみ招待トークンを発行できる。
        accessControlService.checkAdminOrAbove(createdBy, scopeId, scopeType);

        RoleEntity role = roleRepository.findById(req.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 有効期限の計算
        LocalDateTime expiresAt = resolveExpiresAt(req.getExpiresIn());

        var inviteBuilder = InviteTokenEntity.builder()
                .token(UUID.randomUUID().toString())
                .roleId(req.getRoleId())
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .maxUses(req.getMaxUses())
                .usedCount(0);
        if ("TEAM".equals(scopeType)) {
            inviteBuilder.teamId(scopeId);
        } else {
            inviteBuilder.organizationId(scopeId);
        }
        InviteTokenEntity token = inviteBuilder.build();
        inviteTokenRepository.save(token);

        // 監査ログ用イベント発行
        if ("TEAM".equals(scopeType)) {
            eventPublisher.publishEvent(new TeamInviteTokenCreatedEvent(createdBy, scopeId, token.getId()));
        } else {
            eventPublisher.publishEvent(new OrganizationInviteTokenCreatedEvent(createdBy, scopeId, token.getId()));
        }

        log.info("招待トークン作成完了: scopeType={}, scopeId={}, roleId={}", scopeType, scopeId, req.getRoleId());
        return ApiResponse.of(toResponse(token, role.getName()));
    }

    /**
     * スコープ内の有効な招待トークン一覧を取得する。
     */
    public List<InviteTokenResponse> getInviteTokens(Long scopeId, String scopeType, Long actorUserId) {
        // 束1 権限昇格根治: 招待トークン一覧は当該スコープの ADMIN/DEPUTY_ADMIN のみ閲覧できる。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);

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
    public void revokeInviteToken(Long tokenId, Long actorUserId) {
        InviteTokenEntity token = inviteTokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        // 束1 BOLA 根治: トークンが属するスコープ（team/org）を entity から導出し、
        // そのスコープの ADMIN/DEPUTY_ADMIN のみ失効できる（別スコープ ADMIN の tokenId 越境失効を遮断）。
        accessControlService.checkAdminOrAbove(actorUserId, resolveScopeId(token), resolveScopeType(token));

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
     * 招待トークンを使用してスコープに参加する（後方互換版）。
     * F15.3 で folderId 受領版が追加されたが、folderId=null と等価。
     */
    public void joinByInvite(String tokenStr, Long userId) {
        joinByInvite(tokenStr, userId, null);
    }

    /**
     * 招待トークンを使用してスコープに参加する（F15.3 §5.1.1: folderId 受領版）。
     * FOR UPDATEでロック取得し、ブロック・重複・有効性をチェック。
     *
     * <p>参加成功後、{@code folderId} 指定時はそのフォルダへ配置 (assignedVia=INVITE)、
     * 未指定時は「未分類」フォルダへ自動配置 (assignedVia=DEFAULT) する。</p>
     *
     * <p>{@code folderId} の scope_type と招待 scope が不一致の場合、参加自体は完了するが
     * フォルダ配置で {@link ScopeFolderErrorCode#SCOPE_FOLDER_TYPE_MISMATCH} が発生する。
     * 厳密にはチェックを参加前に行うことで「参加せずエラー返却」も可能だが、設計書 §5.1.1 は
     * 「参加→配置失敗時にエラー」の振る舞いを許容するため、現実装は本流に従う。</p>
     */
    @Transactional
    // TODO: RoleドメインとOrganizationドメイン・Teamドメイン・ScopeFolderドメインをまたいでいる。
    //       将来はInviteJoinedEventで分離予定
    public void joinByInvite(String tokenStr, Long userId, Long folderId) {
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
        var roleBuilder = UserRoleEntity.builder()
                .userId(userId)
                .roleId(token.getRoleId());
        if ("TEAM".equals(scopeType)) {
            roleBuilder.teamId(scopeId);
        } else {
            roleBuilder.organizationId(scopeId);
        }
        userRoleRepository.save(roleBuilder.build());

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる。
        // 認可（AccessControlService.isMember）は memberships を真実の源とするため、
        // user_roles だけでは招待参加者が当該スコープから 403 で締め出される構造的欠陥を防ぐ。
        // 招待トークンが配布するのは常に権限ロール（user_roles）であり SUPPORTER は配布しないため、
        // membership の role_kind は MEMBER 固定とする（在籍有無のみを表す）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType("TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION);
        membershipReq.setScopeId(scopeId);
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setInvitedBy(token.getCreatedBy());
        membershipReq.setSource("INVITE_TOKEN");
        membershipService.join(membershipReq);

        // 使用回数をインクリメント
        token.incrementUsedCount();

        // 監査ログ用イベント発行（招待者 = トークン作成者、参加者 = userId）
        if ("TEAM".equals(scopeType)) {
            eventPublisher.publishEvent(new com.mannschaft.app.team.event.TeamMemberAuditEvent(
                    token.getCreatedBy(), userId, scopeId,
                    com.mannschaft.app.team.event.TeamMemberAuditEvent.SubType.INVITED));
        } else {
            eventPublisher.publishEvent(new com.mannschaft.app.organization.event.OrganizationMemberAuditEvent(
                    token.getCreatedBy(), userId, scopeId,
                    com.mannschaft.app.organization.event.OrganizationMemberAuditEvent.SubType.JOINED));
        }

        log.info("招待トークンによる参加完了: userId={}, scopeType={}, scopeId={}",
                userId, scopeType, scopeId);

        // F15.3 §5.1.1: マイスコープフォルダ配置
        assignToFolder(userId, scopeType, scopeId, folderId);
    }

    /**
     * 招待参加後のフォルダ配置を行う（F15.3 §5.1.1）。
     *
     * <p>{@code folderId} 指定時は当該フォルダへ (INVITE)、未指定時は未分類フォルダへ (DEFAULT) 配置。
     * フォルダの scope_type が招待 scope と不一致なら {@code SCOPE_FOLDER_TYPE_MISMATCH} を投げる。</p>
     */
    private void assignToFolder(Long userId, String scopeType, Long scopeId, Long folderId) {
        com.mannschaft.app.scopefolder.entity.enums.ScopeType folderScope = "TEAM".equals(scopeType)
                ? com.mannschaft.app.scopefolder.entity.enums.ScopeType.TEAM
                : com.mannschaft.app.scopefolder.entity.enums.ScopeType.ORGANIZATION;

        if (folderId != null) {
            // フォルダの存在と scope_type 整合チェック（IDOR 含む）
            MyScopeFolderEntity folder = myScopeFolderRepository
                    .findByIdAndUserIdAndDeletedAtIsNull(folderId, userId)
                    .orElseThrow(() -> new com.mannschaft.app.common.BusinessException(
                            ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND));
            if (folder.getScopeType() != folderScope) {
                throw new com.mannschaft.app.common.BusinessException(
                        ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH);
            }
            myScopeFolderService.addItemWithAssignedVia(userId, folderId, scopeId, AssignedVia.INVITE);
            log.info("招待時フォルダ配置(INVITE): userId={}, folderId={}, scopeId={}", userId, folderId, scopeId);
        } else {
            MyScopeFolderEntity defaultFolder =
                    myScopeFolderService.findOrCreateDefaultInternal(userId, folderScope);
            myScopeFolderService.addItemWithAssignedVia(
                    userId, defaultFolder.getId(), scopeId, AssignedVia.DEFAULT);
            log.info("招待時フォルダ配置(DEFAULT): userId={}, defaultFolderId={}, scopeId={}",
                    userId, defaultFolder.getId(), scopeId);
        }
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
     * 招待QRコード画像（PNG）を生成して返す。
     * {@link BrandedQrImageWriter} で中央ブランドバッジ入りQR（ECL=H）としてフロントエンドの招待URLをエンコードする。
     *
     * @param tokenStr トークン文字列
     * @param size     QR画像サイズ（px）。null の場合はデフォルト300
     * @return PNG バイト配列
     */
    public byte[] generateInviteQrCode(String tokenStr, Integer size) {
        inviteTokenRepository.findByToken(tokenStr)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        int qrSize = (size != null) ? size : QR_DEFAULT_SIZE;
        if (qrSize < QR_MIN_SIZE || qrSize > QR_MAX_SIZE) {
            throw new BusinessException(RoleErrorCode.ROLE_008);
        }

        String inviteUrl = baseUrl + "/invite/" + tokenStr;

        return brandedQrImageWriter.writePng(inviteUrl, qrSize);
    }

    /**
     * 招待QRコードをBase64エンコードされたPNG文字列として生成する。
     * PDFテンプレートへの埋め込み用。
     *
     * @param tokenStr トークン文字列（UUIDv4）
     * @param size     QRコードサイズ（px）
     * @return Base64エンコード済みPNG文字列（data URI プレフィックスなし）
     */
    public String generateInviteQrCodeAsBase64(String tokenStr, int size) {
        byte[] qrBytes = generateInviteQrCode(tokenStr, size);
        return Base64.getEncoder().encodeToString(qrBytes);
    }

    /**
     * 招待トークンをIDとチームIDで取得する（IDORチェック用）。
     *
     * @param tokenId 招待トークンID
     * @param teamId  チームID
     * @return 招待トークンEntity
     * @throws BusinessException ROLE_002（存在しないまたはチームに属さない）
     */
    public InviteTokenEntity findByIdAndTeamId(Long tokenId, Long teamId) {
        return inviteTokenRepository.findByIdAndTeamId(tokenId, teamId)
            .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
    }

    private InviteTokenResponse toResponse(InviteTokenEntity token, String roleName) {
        return new InviteTokenResponse(
                token.getId(), token.getToken(), roleName,
                token.getExpiresAt(), token.getMaxUses(), token.getUsedCount(),
                token.getRevokedAt(), token.getCreatedAt());
    }
}
