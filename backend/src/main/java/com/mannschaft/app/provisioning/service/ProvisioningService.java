package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.ProvisioningErrorCode;
import com.mannschaft.app.provisioning.dto.ProvisioningInvitationResponse;
import com.mannschaft.app.provisioning.dto.ProvisioningOrganizationCreateRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningTeamCreateRequest;
import com.mannschaft.app.provisioning.entity.ProvisioningInvitationEntity;
import com.mannschaft.app.provisioning.event.ProvisioningInvitationIssuedEvent;
import com.mannschaft.app.provisioning.repository.ProvisioningInvitationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 柱②-2: 販促プロビジョニングサービス（SYSTEM_ADMIN 側: 作成・一覧・再送・取消）。
 *
 * <p>正本: .claude/campaigns/2026-09-01-org-governance.md 柱②。
 * SYSTEM_ADMIN が組織/チームを PROVISIONED 状態で事前作成し、管理予定者のメールへ
 * ADMIN 招待を送る。承諾は {@link ProvisioningAcceptanceService} が担う。</p>
 *
 * <h2>認可は二層</h2>
 * <p>Controller の {@code SecurityConfig}（{@code /api/v1/system-admin/**} は
 * {@code hasRole("SYSTEM_ADMIN")}）に加え、本 Service 自身も
 * {@link AccessControlService#checkSystemAdmin(Long)} で細粒度認可を行う（AC2）。</p>
 *
 * <h2>作成者は ADMIN にならない</h2>
 * <p>作成直後は PROVISIONED であり、誰も ADMIN/membership を持たない
 * （招待承諾で初めて ADMIN が生まれる）。{@code OrganizationService#createOrganization} /
 * {@code TeamService#createTeam} とは異なり、{@code AdminRoleMutationLockService} や
 * {@code MembershipService.join} は呼ばない。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ProvisioningService {

    /** 招待の有効期限（発行から7日）。 */
    private static final int INVITATION_TTL_DAYS = 7;

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final ProvisioningInvitationRepository invitationRepository;
    private final AccessControlService accessControlService;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;

    /** slug 生成ロジック（既存の一意 slug 採番）を共有ヘルパとして流用する。 */
    private final OrganizationService organizationService;
    private final TeamService teamService;

    private final SecretTokenVault secretTokenVault;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 組織を PROVISIONED 状態で事前作成し、管理予定者へ ADMIN 招待メールを送る。
     */
    @Transactional
    public ProvisioningInvitationResponse createOrganization(
            Long actorUserId, ProvisioningOrganizationCreateRequest request) {
        accessControlService.checkSystemAdmin(actorUserId); // AC2: Service単体でも403
        validateInviteEmail(request.inviteEmail()); // AC13: 不正/空は400

        String slug = organizationService.createUniqueSlug(request.name());
        OrganizationEntity org = OrganizationEntity.builder()
                .name(request.name())
                .slug(slug)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PRIVATE) // AC3: PRIVATE強制
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .lifecycleStatus(OrganizationEntity.LifecycleStatus.PROVISIONED) // AC3: PROVISIONED強制
                .build();
        organizationRepository.save(org);

        ProvisioningInvitationEntity invitation = issueInvitation(
                actorUserId, request.inviteEmail(), org.getId(), null, org.getName(), true);

        log.info("柱②-2 販促プロビジョニング組織作成完了: orgId={}, issuedBy={}", org.getId(), actorUserId);
        return toResponse(invitation);
    }

    /**
     * チームを PROVISIONED 状態で事前作成し、管理予定者へ ADMIN 招待メールを送る。
     */
    @Transactional
    public ProvisioningInvitationResponse createTeam(Long actorUserId, ProvisioningTeamCreateRequest request) {
        accessControlService.checkSystemAdmin(actorUserId); // AC2
        validateInviteEmail(request.inviteEmail()); // AC13

        String slug = teamService.createUniqueSlug(request.name());
        TeamEntity team = TeamEntity.builder()
                .name(request.name())
                .slug(slug)
                // Team.Visibility に PRIVATE 相当は無いため、既存4値のうち最も制限的な
                // MEMBERS_AND_ABOVE を採用する（承諾までメンバーが存在しないため実質非公開）。
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(false)
                .lifecycleStatus(TeamEntity.LifecycleStatus.PROVISIONED) // AC3相当
                .build();
        teamRepository.save(team);

        ProvisioningInvitationEntity invitation = issueInvitation(
                actorUserId, request.inviteEmail(), null, team.getId(), team.getName(), true);

        log.info("柱②-2 販促プロビジョニングチーム作成完了: teamId={}, issuedBy={}", team.getId(), actorUserId);
        return toResponse(invitation);
    }

    /**
     * 招待の一覧を返す（0 件なら空配列・AC14）。
     */
    public List<ProvisioningInvitationResponse> list(Long actorUserId) {
        accessControlService.checkSystemAdmin(actorUserId); // AC2
        return invitationRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 招待を再送する（旧行を CANCELLED にし、新しいトークン・新しい行を発行する・AC8）。
     */
    @Transactional
    public ProvisioningInvitationResponse resend(Long actorUserId, UUID invitationId) {
        accessControlService.checkSystemAdmin(actorUserId); // AC2
        ProvisioningInvitationEntity old = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));

        Instant now = Instant.now();
        old.setStatus(STATUS_CANCELLED);
        old.setResolvedAt(now);
        invitationRepository.save(old);

        String scopeName = resolveScopeName(old);
        ProvisioningInvitationEntity newInvitation = issueInvitation(
                actorUserId, old.getInviteEmail(), old.getOrganizationId(), old.getTeamId(), scopeName, false);

        auditLogService.record(
                AuditEventType.PROVISIONING_INVITATION_RESENT.name(),
                actorUserId, null,
                old.getTeamId(), old.getOrganizationId(),
                null, null, null,
                "{\"oldInvitationId\":\"" + old.getId() + "\",\"newInvitationId\":\"" + newInvitation.getId() + "\"}");

        log.info("柱②-2 販促プロビジョニング招待再送: oldId={}, newId={}", old.getId(), newInvitation.getId());
        return toResponse(newInvitation);
    }

    /**
     * 招待を取消す（{@code status=CANCELLED}・AC8）。
     */
    @Transactional
    public void cancel(Long actorUserId, UUID invitationId) {
        accessControlService.checkSystemAdmin(actorUserId); // AC2
        ProvisioningInvitationEntity invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new BusinessException(ProvisioningErrorCode.PROV_001));

        invitation.setStatus(STATUS_CANCELLED);
        invitation.setResolvedAt(Instant.now());
        invitationRepository.save(invitation);

        auditLogService.record(
                AuditEventType.PROVISIONING_INVITATION_CANCELLED.name(),
                actorUserId, null,
                invitation.getTeamId(), invitation.getOrganizationId(),
                null, null, null,
                "{\"invitationId\":\"" + invitation.getId() + "\"}");

        log.info("柱②-2 販促プロビジョニング招待取消: invitationId={}", invitation.getId());
    }

    // ─────────────────────────────────────────────────────────────

    private ProvisioningInvitationEntity issueInvitation(
            Long actorUserId, String inviteEmail, Long organizationId, Long teamId, String scopeName,
            boolean logScopeCreated) {
        IssuedToken issued = secretTokenVault.issueBase64Url();
        ProvisioningInvitationEntity invitation = ProvisioningInvitationEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .inviteEmail(inviteEmail)
                .tokenHash(issued.hash())
                .status(STATUS_PENDING)
                .expiresAt(Instant.now().plus(INVITATION_TTL_DAYS, ChronoUnit.DAYS))
                .issuedBy(actorUserId)
                .build();
        invitation = invitationRepository.save(invitation);

        // AC15: 作成（初回のみ）/招待発行の監査記録。
        if (logScopeCreated) {
            auditLogService.record(
                    AuditEventType.PROVISIONING_SCOPE_CREATED.name(),
                    actorUserId, null,
                    teamId, organizationId,
                    null, null, null,
                    "{\"invitationId\":\"" + invitation.getId() + "\",\"inviteEmail\":\"" + inviteEmail + "\"}");
        }
        auditLogService.record(
                AuditEventType.PROVISIONING_INVITATION_SENT.name(),
                actorUserId, null,
                teamId, organizationId,
                null, null, null,
                "{\"invitationId\":\"" + invitation.getId() + "\"}");

        // 通知は業務TX内では publishEvent のみ（AFTER_COMMIT リスナーで実送信）。
        eventPublisher.publishEvent(new ProvisioningInvitationIssuedEvent(
                inviteEmail, issued.plaintext(), scopeName, actorUserId));

        return invitation;
    }

    private String resolveScopeName(ProvisioningInvitationEntity invitation) {
        if (invitation.getTeamId() != null) {
            return teamRepository.findById(invitation.getTeamId()).map(TeamEntity::getName).orElse(null);
        }
        if (invitation.getOrganizationId() != null) {
            return organizationRepository.findById(invitation.getOrganizationId())
                    .map(OrganizationEntity::getName).orElse(null);
        }
        return null;
    }

    private void validateInviteEmail(String inviteEmail) {
        if (inviteEmail == null || inviteEmail.isBlank() || !EMAIL_PATTERN.matcher(inviteEmail).matches()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    private ProvisioningInvitationResponse toResponse(ProvisioningInvitationEntity invitation) {
        return new ProvisioningInvitationResponse(
                invitation.getId(),
                invitation.getTeamId(),
                invitation.getOrganizationId(),
                invitation.getInviteEmail(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getIssuedBy());
    }
}
