package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.ProvisioningErrorCode;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationAcceptResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationPreviewResponse;
import com.mannschaft.app.provisioning.entity.ProvisioningInvitationEntity;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 柱②-2: 販促プロビジョニング招待の下見（preview）/ 承諾（accept）サービス。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。
 * 承諾は要ログイン・verified email と invite_email の一致（NFC 正規化 + lowercase・
 * {@link ProvisioningEmailNormalizer}）を必須とし、二重承諾防止は
 * {@link ProvisioningInvitationRepository#findByTokenHashForUpdate} の悲観ロックで担保する。
 * 同一 TX 内で ADMIN role + membership 付与 → {@code activate()} → status=ACCEPTED → 監査 →
 * 通知 outbox まで完結させる（途中失敗時はロールバックする・AC12）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ProvisioningAcceptanceService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";

    private final ProvisioningInvitationRepository invitationRepository;
    private final SecretTokenVault secretTokenVault;
    private final ProvisioningEmailNormalizer emailNormalizer;

    /**
     * ログインユーザーの検証済みメールアドレス（{@code status=ACTIVE} = 認証メール確認済み）の照合に使う。
     * D-1/D-5 に従い {@link UserService#findVerifiedEmail} 経由の軽量参照に限定する
     * （role→user の表示名参照と同様、{@code UserEntity}/{@code UserRepository} は漏らさない）。
     */
    private final UserService userService;

    private final OrganizationService organizationService;
    private final TeamService teamService;
    private final AdminRoleMutationLockService adminRoleMutationLockService;
    private final MembershipService membershipService;
    private final AuditLogService auditLogService;

    /**
     * トークンの下見（承諾前確認画面用）。存在しない/期限切れ/取消済みは一律 PROV_001（404）。
     *
     * @param tokenPlaintext 平文トークン（POST ボディで受け取ったもの）
     * @return 下見応答
     */
    public ProvisioningInvitationPreviewResponse preview(String tokenPlaintext) {
        String tokenHash = secretTokenVault.hash(tokenPlaintext);
        ProvisioningInvitationEntity invitation = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));

        if (!STATUS_PENDING.equals(invitation.getStatus())) {
            throw new BusinessException(ProvisioningErrorCode.PROV_001);
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ProvisioningErrorCode.PROV_001);
        }

        String scopeName = resolveScopeName(invitation);
        return new ProvisioningInvitationPreviewResponse(
                invitation.getTeamId(), invitation.getOrganizationId(), scopeName,
                invitation.getInviteEmail(), invitation.getExpiresAt());
    }

    /**
     * トークンを承諾する。ADMIN role + membership 付与 → activate() → ACCEPTED → 監査 → 通知 outbox
     * を同一 TX で行う。
     *
     * @param tokenPlaintext 平文トークン
     * @param actorUserId    実行ユーザー ID（要ログイン）
     * @return 承諾結果
     */
    @Transactional
    public ProvisioningInvitationAcceptResponse accept(String tokenPlaintext, Long actorUserId) {
        String tokenHash = secretTokenVault.hash(tokenPlaintext);
        // AC6: 悲観ロックで同一トークンへの並行承諾を直列化する。
        ProvisioningInvitationEntity invitation = invitationRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));

        if (STATUS_ACCEPTED.equals(invitation.getStatus())) {
            // AC9: ACCEPTED済みは承諾者本人のみ冪等成功応答。別ユーザーは存在秘匿のため404へ畳む。
            if (actorUserId.equals(invitation.getAcceptedBy())) {
                return toAcceptResponse(invitation);
            }
            throw new BusinessException(ProvisioningErrorCode.PROV_010);
        }
        if (!STATUS_PENDING.equals(invitation.getStatus())) {
            // AC8: CANCELLED/EXPIRED（明示的な状態遷移済み）は409。
            throw new BusinessException(ProvisioningErrorCode.PROV_003);
        }
        if (invitation.getExpiresAt().isBefore(Instant.now())) {
            // AC7: TTL経過（境界=ちょうどは有効。isBefore は厳密未満のみ真）。
            throw new BusinessException(ProvisioningErrorCode.PROV_002);
        }

        // AC4: verified email（status=ACTIVE = 認証メール確認済み）と invite_email の一致
        // （NFC正規化+lowercase+trim）。未検証（PENDING_VERIFICATION等）は不一致と同様に403へ畳む。
        UserService.VerifiedEmail actor = userService.findVerifiedEmail(actorUserId)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));
        String normalizedInvite = emailNormalizer.normalize(invitation.getInviteEmail());
        String normalizedActor = emailNormalizer.normalize(actor.email());
        if (!actor.verified() || !normalizedInvite.equals(normalizedActor)) {
            throw new BusinessException(ProvisioningErrorCode.PROV_006);
        }

        // ロック順序を users → roles.ADMIN → user_roles に固定する（既存作成経路と同型）。
        Long adminRoleId = adminRoleMutationLockService.lockAdminRoleIdForCreation(actorUserId)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));

        String scopeType;
        String scopeName;
        if (invitation.getTeamId() != null) {
            scopeType = "TEAM";
            scopeName = teamService.activateProvisionedTeam(invitation.getTeamId())
                    .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));
        } else {
            scopeType = "ORGANIZATION";
            scopeName = organizationService.activateProvisionedOrganization(invitation.getOrganizationId())
                    .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));
        }

        // ADMIN role 付与（OrganizationService#createOrganization L109-120 と同型）。
        adminRoleMutationLockService.grantAdminRole(
                actorUserId, adminRoleId, invitation.getTeamId(), invitation.getOrganizationId());

        // membership 付与（認可はmembershipsを真実の源とするため必須。role_kind=MEMBER）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(actorUserId);
        membershipReq.setScopeType(ScopeType.valueOf(scopeType));
        membershipReq.setScopeId(invitation.getTeamId() != null ? invitation.getTeamId() : invitation.getOrganizationId());
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setSource("PROVISIONING_ACCEPT");
        membershipService.join(membershipReq);

        Instant now = Instant.now();
        invitation.setStatus(STATUS_ACCEPTED);
        invitation.setAcceptedAt(now);
        invitation.setAcceptedBy(actorUserId);
        invitation.setResolvedAt(now);
        invitationRepository.save(invitation);

        // AC15: 承諾の監査記録。
        auditLogService.record(
                AuditEventType.PROVISIONING_INVITATION_ACCEPTED.name(),
                actorUserId, invitation.getIssuedBy(),
                invitation.getTeamId(), invitation.getOrganizationId(),
                null, null, null,
                "{\"invitationId\":\"" + invitation.getId() + "\"}");

        log.info("柱②-2 販促プロビジョニング招待承諾完了: invitationId={}, actorUserId={}, scopeType={}",
                invitation.getId(), actorUserId, scopeType);

        return new ProvisioningInvitationAcceptResponse(
                invitation.getTeamId(), invitation.getOrganizationId(), scopeName, STATUS_ACCEPTED);
    }

    // ─────────────────────────────────────────────────────────────

    private ProvisioningInvitationAcceptResponse toAcceptResponse(ProvisioningInvitationEntity invitation) {
        return new ProvisioningInvitationAcceptResponse(
                invitation.getTeamId(), invitation.getOrganizationId(), resolveScopeName(invitation), STATUS_ACCEPTED);
    }

    private String resolveScopeName(ProvisioningInvitationEntity invitation) {
        if (invitation.getTeamId() != null) {
            return teamService.findNameById(invitation.getTeamId()).orElse(null);
        }
        if (invitation.getOrganizationId() != null) {
            return organizationService.findNameById(invitation.getOrganizationId()).orElse(null);
        }
        return null;
    }
}
