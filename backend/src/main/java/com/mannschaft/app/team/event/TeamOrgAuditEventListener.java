package com.mannschaft.app.team.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.organization.event.OrganizationInviteTokenCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * TEAM / ORGANIZATION 系監査ログイベントリスナー。
 *
 * <p>チーム・組織の作成・削除・メンバー操作・招待トークン作成を監査ログへ記録する。
 * 既存の {@link com.mannschaft.app.auth.event.AuditLogEventListener} は変更しない。</p>
 *
 * <p>F10.3 監査ログ Phase 2 — TEAM/ORGANIZATION 系イベント15種の実装。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamOrgAuditEventListener {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // TEAM 系
    // ─────────────────────────────────────────────

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamCreated(TeamCreatedEvent event) {
        auditLogService.record(
                AuditEventType.TEAM_CREATED.name(),
                event.getUserId(),
                null,
                event.getTeamId(),
                null,
                null, null, null,
                toJson(Map.of("team_name", event.getTeamName()))
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamDeleted(TeamDeletedEvent event) {
        auditLogService.record(
                AuditEventType.TEAM_DELETED.name(),
                event.getUserId(),
                null,
                event.getTeamId(),
                null,
                null, null, null, null
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamMemberAudit(TeamMemberAuditEvent event) {
        AuditEventType type = switch (event.getSubType()) {
            case INVITED      -> AuditEventType.TEAM_MEMBER_INVITED;
            case JOINED       -> AuditEventType.TEAM_MEMBER_JOINED;
            case ROLE_CHANGED -> AuditEventType.TEAM_MEMBER_ROLE_CHANGED;
            case REMOVED      -> AuditEventType.TEAM_MEMBER_REMOVED;
            case BLOCKED      -> AuditEventType.TEAM_MEMBER_BLOCKED;
            case UNBLOCKED    -> AuditEventType.TEAM_MEMBER_UNBLOCKED;
        };
        auditLogService.record(
                type.name(),
                event.getUserId(),
                event.getTargetUserId(),
                event.getTeamId(),
                null,
                null, null, null, null
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamInviteTokenCreated(TeamInviteTokenCreatedEvent event) {
        auditLogService.record(
                AuditEventType.TEAM_INVITE_TOKEN_CREATED.name(),
                event.getUserId(),
                null,
                event.getTeamId(),
                null,
                null, null, null,
                toJson(Map.of("token_id", event.getTokenId()))
        );
    }

    // ─────────────────────────────────────────────
    // ORGANIZATION 系
    // ─────────────────────────────────────────────

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationCreated(OrganizationCreatedEvent event) {
        auditLogService.record(
                AuditEventType.ORGANIZATION_CREATED.name(),
                event.getUserId(),
                null,
                null,
                event.getOrganizationId(),
                null, null, null,
                toJson(Map.of("org_name", event.getOrganizationName()))
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationDeleted(OrganizationDeletedEvent event) {
        auditLogService.record(
                AuditEventType.ORGANIZATION_DELETED.name(),
                event.getUserId(),
                null,
                null,
                event.getOrganizationId(),
                null, null, null, null
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationMemberAudit(OrganizationMemberAuditEvent event) {
        AuditEventType type = switch (event.getSubType()) {
            case JOINED       -> AuditEventType.ORGANIZATION_MEMBER_JOINED;
            case ROLE_CHANGED -> AuditEventType.ORGANIZATION_MEMBER_ROLE_CHANGED;
            case REMOVED      -> AuditEventType.ORGANIZATION_MEMBER_REMOVED;
            case BLOCKED      -> AuditEventType.ORGANIZATION_MEMBER_BLOCKED;
            case UNBLOCKED    -> AuditEventType.ORGANIZATION_MEMBER_UNBLOCKED;
        };
        auditLogService.record(
                type.name(),
                event.getUserId(),
                event.getTargetUserId(),
                null,
                event.getOrganizationId(),
                null, null, null, null
        );
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとチーム・組織の作成削除とメンバー操作の監査記録が欠落する。イベントは再生されないため停止期間の証跡は恒久的に失われる")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationInviteTokenCreated(OrganizationInviteTokenCreatedEvent event) {
        auditLogService.record(
                AuditEventType.ORGANIZATION_INVITE_TOKEN_CREATED.name(),
                event.getUserId(),
                null,
                null,
                event.getOrganizationId(),
                null, null, null,
                toJson(Map.of("token_id", event.getTokenId()))
        );
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("監査ログ metadata JSON化失敗: {}", e.getMessage());
            return null;
        }
    }
}
