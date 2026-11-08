package com.mannschaft.app.role.service;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.provisioning.service.ProvisioningGate;
import com.mannschaft.app.common.qr.BrandedQrImageWriter;
import org.springframework.beans.factory.annotation.Value;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.BetaInviteScope;
import com.mannschaft.app.role.dto.InviteTokenIssuedResponse;
import com.mannschaft.app.role.dto.InviteTokenSummary;
import com.mannschaft.app.role.dto.SharedInviteTokenCommand;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 招待トークンサービス。トークン作成・プレビュー・失効を管理する。
 *
 * <p>招待の承諾・辞退は {@link InviteAcceptanceFacade} へ移した（CMP-260826-1457。承諾・辞退を本クラスの
 * 単一トランザクションで行うと user ロック、ブロック判定、memberships、フォルダ、監査にトランザクションが
 * 越境していたため）。</p>
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
    private final BrandedQrImageWriter brandedQrImageWriter;
    private final AccessControlService accessControlService;
    private final ProvisioningGate provisioningGate;
    private final InviteScopeStateService scopeStates;
    private final SharedInviteTokenIssuer sharedTokenIssuer;
    private final TeamInviteQueryService teamInviteQueryService;
    private final OrganizationInviteQueryService organizationInviteQueryService;
    private final SecretTokenVault vault;

    /**
     * 招待トークンを作成する。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ApiResponse<InviteTokenIssuedResponse> createInviteToken(Long scopeId, String scopeType,
                                                               CreateInviteTokenRequest req, Long createdBy) {
        // 束1 権限昇格根治: 当該スコープの ADMIN/DEPUTY_ADMIN のみ招待トークンを発行できる。
        accessControlService.checkAdminOrAbove(createdBy, scopeId, scopeType);

        // 柱②-3 販促プロビジョニングゲート（AC11）: PROVISIONED（承諾前の事前作成状態）スコープへの
        // 招待発行を遮断する。
        provisioningGate.requireActive(scopeId, scopeType);
        var scope = scopeStates.requireAvailable(scopeType, scopeId);

        RoleEntity role = roleRepository.findById(req.getRoleId())
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));

        // 有効期限の計算
        LocalDateTime expiresAt = resolveExpiresAt(req.getExpiresIn());

        // role専属writerから戻る時点でcommit済み。親共有lockはuser/token lock解放後だけ取得する。
        InviteTokenIssuedResponse token = sharedTokenIssuer.issue(new SharedInviteTokenCommand(scopeType, scopeId,
                role.getId(), role.getName(), createdBy,
                expiresAt == null ? null : expiresAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant(),
                req.getMaxUses(), scope.closureGeneration()));
        boolean stillCurrent;
        try {
            stillCurrent = scopeStates.recheckIssued(scopeType, scopeId, scope.closureGeneration());
        } catch (RuntimeException failure) {
            try {
                sharedTokenIssuer.revokeForCompensation(token.id());
            } catch (RuntimeException compensationFailure) {
                failure.addSuppressed(compensationFailure);
            }
            throw failure;
        }
        if (!stillCurrent) {
            sharedTokenIssuer.revokeForCompensation(token.id());
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        log.info("招待トークン作成完了: scopeType={}, scopeId={}, roleId={}", scopeType, scopeId, req.getRoleId());
        return ApiResponse.of(token);
    }

    /**
     * スコープ内の有効な招待トークン一覧を取得する。
     */
    public List<InviteTokenSummary> getInviteTokens(Long scopeId, String scopeType, Long actorUserId) {
        // 束1 権限昇格根治: 招待トークン一覧は当該スコープの ADMIN/DEPUTY_ADMIN のみ閲覧できる。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, scopeType);

        var scope = scopeStates.read(scopeType, scopeId);
        if (scope.isEmpty() || !scope.orElseThrow().available()) {
            return List.of();
        }
        long currentGeneration = scope.orElseThrow().closureGeneration();

        List<InviteTokenEntity> tokens;
        if ("TEAM".equals(scopeType)) {
            tokens = inviteTokenRepository.findByTeamIdAndRevokedAtIsNull(scopeId);
        } else {
            tokens = inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(scopeId);
        }
        return tokens.stream()
                .filter(token -> token.getScopeClosureGeneration() == currentGeneration)
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
        InviteTokenEntity token = inviteTokenRepository.lockFreshById(tokenId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        // 束1 BOLA 根治: トークンが属するスコープ（team/org）を entity から導出し、
        // そのスコープの ADMIN/DEPUTY_ADMIN のみ失効できる（別スコープ ADMIN の tokenId 越境失効を遮断）。
        accessControlService.checkAdminOrAbove(actorUserId, resolveScopeId(token), resolveScopeType(token));

        token.revoke();
        log.info("招待トークン失効完了: tokenId={}", tokenId);
    }

    /**
     * ベータ招待として使える（存在し有効な）トークンの招待先を返す。平文で受け取り、ハッシュで照合する。
     * admin ドメインが role の Repository を直接参照しないための入口。
     */
    public java.util.Optional<BetaInviteScope> findValidInviteScope(String tokenStr) {
        return inviteTokenRepository.findByTokenHash(vault.hash(tokenStr))
                .filter(InviteTokenEntity::isValid)
                .map(token -> new BetaInviteScope(token.getTeamId(), token.getOrganizationId()));
    }

    /**
     * 招待トークンをプレビューする。未認証ユーザーにも表示可能。
     */
    public ApiResponse<InvitePreviewResponse> previewInvite(String tokenStr) {
        InviteTokenEntity token = inviteTokenRepository.findByTokenHash(vault.hash(tokenStr))
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        String scopeType = resolveScopeType(token);
        Long scopeId = resolveScopeId(token);
        String targetName = switch (scopeType) {
            // team / organization の Repository には触れず、各ドメインの読み取り窓口へ委譲する
            case "ORGANIZATION" -> organizationInviteQueryService.findName(scopeId);
            case "TEAM" -> teamInviteQueryService.findName(scopeId);
            default -> null;
        };
        String roleName = roleRepository.findById(token.getRoleId())
                .map(RoleEntity::getName).orElse(null);

        return ApiResponse.of(new InvitePreviewResponse(
                targetName, scopeType, roleName, token.isValid()
                        && scopeStates.isCurrent(scopeType, scopeId, token.getScopeClosureGeneration())));
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
     * 招待QRコード画像（PNG）を生成して返す。
     * {@link BrandedQrImageWriter} で中央ブランドバッジ入りQR（ECL=H）としてフロントエンドの招待URLをエンコードする。
     *
     * @param tokenStr トークン文字列
     * @param size     QR画像サイズ（px）。null の場合はデフォルト300
     * @return PNG バイト配列
     */
    public byte[] generateInviteQrCode(String tokenStr, Integer size) {
        inviteTokenRepository.findByTokenHash(vault.hash(tokenStr))
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
     * 平文の招待トークンから、指定チームに属する招待を取得する（IDORチェック用）。
     *
     * @param plaintextToken 平文の招待トークン
     * @param teamId         チームID
     * @return 招待トークンEntity
     * @throws BusinessException ROLE_002（存在しない、またはチームに属さない。両者は区別しない）
     */
    public InviteTokenEntity findByPlaintextAndTeamId(String plaintextToken, Long teamId) {
        return inviteTokenRepository.findByTokenHash(vault.hash(plaintextToken))
            .filter(token -> teamId.equals(token.getTeamId()))
            .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
    }

    private InviteTokenSummary toResponse(InviteTokenEntity token, String roleName) {
        return new InviteTokenSummary(
                token.getId(), roleName,
                token.getExpiresAt(), token.getMaxUses(), token.getUsedCount(),
                token.getRevokedAt(), token.getCreatedAt());
    }
}
