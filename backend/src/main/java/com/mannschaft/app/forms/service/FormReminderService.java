package com.mannschaft.app.forms.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.forms.FormErrorCode;
import com.mannschaft.app.forms.FormScopes;
import com.mannschaft.app.forms.FormStatus;
import com.mannschaft.app.forms.dto.FormRemindResponse;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import com.mannschaft.app.forms.event.FormTemplateRemindEvent;
import com.mannschaft.app.forms.repository.FormSubmissionRepository;
import com.mannschaft.app.forms.repository.FormTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * フォームリマインダーサービス（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>未提出者リマインド（{@code .../remind}）と特定者向けリマインド（{@code .../remind-specific}）
 * の 2 種類を提供する。本サービスは「対象者の抽出」と「イベント発火」のみを担当し、
 * 実際のプッシュ通知 / メール送信は notification ドメインのリスナーが {@link FormTemplateRemindEvent}
 * を受信して実装する（モジュラーモノリス原則）。</p>
 *
 * <p>未提出者の判定:</p>
 * <ul>
 *   <li>本サービスでは「スコープ内の全 MEMBER」リストを直接取得しない（ドメイン越境を避ける）。
 *       Phase 11 第四陣 4-B 時点では、Service の引数 {@code candidateUserIds}（通常は Controller 経由で
 *       team / organization 側 Service から取得）を入力とし、その中から submission_status &gt; DRAFT のユーザーを除外する。</li>
 *   <li>特定者向けの場合は引数で渡されたユーザー全員に発火する（既提出者除外なし）。</li>
 * </ul>
 *
 * @since 2026-05-17 (F05.7 Phase 11 第四陣 4-B)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FormReminderService {

    private final FormTemplateRepository templateRepository;
    private final FormSubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;

    /**
     * 全未提出者リマインドを送信する。
     *
     * <p>Controller 層で提供された候補ユーザー（スコープ内 MEMBER 一覧）から、
     * 既に提出済み（status &gt; DRAFT）のユーザーを除外して残りに対しイベントを発火する。</p>
     *
     * @param scopeType         スコープ種別
     * @param scopeId           スコープ ID
     * @param templateId        テンプレート ID
     * @param candidateUserIds  候補ユーザー ID リスト（Controller 経由で team/org メンバーから取得）
     * @param currentUserId     実行者ユーザー ID
     * @return リマインド実行結果
     */
    public FormRemindResponse remindAllUnsubmitted(
            String scopeType, Long scopeId, Long templateId,
            List<Long> candidateUserIds, Long currentUserId) {
        FormTemplateEntity template = templateRepository
                .findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
        // 認可根治戦役 Wave3-B4: リマインド送信は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, FormScopes.canonical(scopeType));

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.INVALID_TEMPLATE_STATUS);
        }

        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return new FormRemindResponse(0, 0);
        }

        // 既に提出済みのユーザー ID を取得して除外する
        Set<Long> submittedUserIds = Set.copyOf(
                submissionRepository.findSubmittedUserIds(templateId, candidateUserIds));
        List<Long> unsubmitted = candidateUserIds.stream()
                .filter(uid -> !submittedUserIds.contains(uid))
                .toList();

        publishAndAudit(scopeType, scopeId, templateId,
                FormTemplateRemindEvent.RemindKind.ALL_UNSUBMITTED,
                unsubmitted, null, currentUserId);

        log.info("フォームリマインド送信(ALL_UNSUBMITTED): templateId={}, candidate={}, unsubmitted={}",
                templateId, candidateUserIds.size(), unsubmitted.size());
        return new FormRemindResponse(unsubmitted.size(), unsubmitted.size());
    }

    /**
     * 特定者向けリマインドを送信する。
     *
     * <p>引数で渡されたユーザー全員に対してイベントを発火する。既提出者の除外は行わない
     * （ADMIN の意思で特定ユーザーへ送信するため）。</p>
     *
     * @param scopeType     スコープ種別
     * @param scopeId       スコープ ID
     * @param templateId    テンプレート ID
     * @param userIds       リマインド対象ユーザー ID リスト
     * @param customMessage 任意のカスタムメッセージ
     * @param currentUserId 実行者ユーザー ID
     * @return リマインド実行結果
     */
    public FormRemindResponse remindSpecificUsers(
            String scopeType, Long scopeId, Long templateId,
            List<Long> userIds, String customMessage, Long currentUserId) {
        FormTemplateEntity template = templateRepository
                .findByIdAndScopeTypeAndScopeId(templateId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(FormErrorCode.TEMPLATE_NOT_FOUND));
        // 認可根治戦役 Wave3-B4: リマインド送信は ADMIN/DEPUTY_ADMIN 以上のみ許可する
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, FormScopes.canonical(scopeType));

        if (template.getStatus() != FormStatus.PUBLISHED) {
            throw new BusinessException(FormErrorCode.INVALID_TEMPLATE_STATUS);
        }

        if (userIds == null || userIds.isEmpty()) {
            throw new BusinessException(FormErrorCode.REMIND_NO_TARGET);
        }

        publishAndAudit(scopeType, scopeId, templateId,
                FormTemplateRemindEvent.RemindKind.SPECIFIC_USERS,
                userIds, customMessage, currentUserId);

        log.info("フォームリマインド送信(SPECIFIC_USERS): templateId={}, target={}",
                templateId, userIds.size());
        return new FormRemindResponse(userIds.size(), userIds.size());
    }

    private void publishAndAudit(
            String scopeType, Long scopeId, Long templateId,
            FormTemplateRemindEvent.RemindKind kind,
            List<Long> targetUserIds, String customMessage, Long currentUserId) {
        LocalDateTime now = LocalDateTime.now();
        FormTemplateRemindEvent event = new FormTemplateRemindEvent(
                templateId, scopeType, scopeId, kind, targetUserIds,
                customMessage, currentUserId, now);
        eventPublisher.publishEvent(event);

        Long teamId = "teams".equalsIgnoreCase(scopeType) ? scopeId : null;
        Long orgId = "organizations".equalsIgnoreCase(scopeType) ? scopeId : null;
        String metadata = String.format(
                "{\"templateId\":%d,\"kind\":\"%s\",\"targetCount\":%d}",
                templateId, kind.name(), targetUserIds.size());
        auditLogService.record(
                AuditEventType.FORM_TEMPLATE_REMIND.name(),
                currentUserId, null, teamId, orgId, null, null, null, metadata);
    }
}
