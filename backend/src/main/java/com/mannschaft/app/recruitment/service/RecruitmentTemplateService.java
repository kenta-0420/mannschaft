package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateCreateRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateUpdateRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.11 募集型予約 Phase 3: テンプレートサービス。
 *
 * 設計書参照:
 * - §3.x recruitment_templates テーブル
 * - §5.1.2 テンプレートから募集枠を作成する
 * - §13 認可: 閲覧はメンバー以上、作成/更新/削除は管理者以上
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentTemplateService {

    private final RecruitmentTemplateRepository templateRepository;
    private final RecruitmentCancellationPolicyRepository policyRepository;
    private final RecruitmentCancellationPolicyTierRepository tierRepository;
    private final AccessControlService accessControlService;

    // ===========================================
    // 取得系
    // ===========================================

    /**
     * テンプレート一覧取得（スコープ内アクティブのみ）。
     * 閲覧権限: メンバー以上。
     */
    public Page<RecruitmentTemplateEntity> list(
            RecruitmentScopeType scopeType, Long scopeId, Long userId, Pageable pageable) {
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
        return templateRepository.findActiveByScopeTypeAndScopeId(scopeType, scopeId, pageable);
    }

    /**
     * テンプレート単件取得（論理削除除外）。
     * 閲覧権限: メンバー以上。
     */
    public RecruitmentTemplateEntity get(Long templateId, Long userId) {
        RecruitmentTemplateEntity template = templateRepository.findActiveById(templateId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));
        accessControlService.checkMembership(userId, template.getScopeId(), template.getScopeType().name());
        return template;
    }

    // ===========================================
    // 更新系
    // ===========================================

    /**
     * テンプレート作成。
     * 操作権限: 管理者以上。
     */
    @Transactional
    public RecruitmentTemplateEntity create(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            RecruitmentTemplateCreateRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        RecruitmentTemplateEntity entity = RecruitmentTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(request.getCategoryId())
                .subcategoryId(request.getSubcategoryId())
                .templateName(request.getTemplateName())
                .title(request.getTitle())
                .description(request.getDescription())
                .participationType(request.getParticipationType())
                .defaultCapacity(request.getDefaultCapacity())
                .defaultMinCapacity(request.getDefaultMinCapacity())
                .defaultDurationMinutes(request.getDefaultDurationMinutes())
                .defaultApplicationDeadlineHours(request.getDefaultApplicationDeadlineHours())
                .defaultAutoCancelHours(request.getDefaultAutoCancelHours())
                .defaultPaymentEnabled(request.getDefaultPaymentEnabled())
                .defaultPrice(request.getDefaultPrice())
                .defaultVisibility(request.getDefaultVisibility())
                .defaultLocation(request.getDefaultLocation())
                .defaultReservationLineId(request.getDefaultReservationLineId())
                .defaultImageUrl(request.getDefaultImageUrl())
                .defaultCancellationPolicyId(request.getDefaultCancellationPolicyId())
                .createdBy(userId)
                .build();

        RecruitmentTemplateEntity saved = templateRepository.save(entity);
        log.info("F03.11 テンプレート作成: id={}, scope={}/{}", saved.getId(), scopeType, scopeId);
        return saved;
    }

    /**
     * テンプレート更新。null のフィールドは変更しない（部分更新）。
     * 操作権限: 管理者以上。
     */
    @Transactional
    public RecruitmentTemplateEntity update(Long templateId, Long userId,
            RecruitmentTemplateUpdateRequest request) {
        RecruitmentTemplateEntity template = templateRepository.findActiveById(templateId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, template.getScopeId(), template.getScopeType().name());

        RecruitmentTemplateEntity updated = template.toBuilder()
                .templateName(request.getTemplateName() != null
                        ? request.getTemplateName() : template.getTemplateName())
                .categoryId(request.getCategoryId() != null
                        ? request.getCategoryId() : template.getCategoryId())
                .subcategoryId(request.getSubcategoryId() != null
                        ? request.getSubcategoryId() : template.getSubcategoryId())
                .title(request.getTitle() != null
                        ? request.getTitle() : template.getTitle())
                .description(request.getDescription() != null
                        ? request.getDescription() : template.getDescription())
                .participationType(request.getParticipationType() != null
                        ? request.getParticipationType() : template.getParticipationType())
                .defaultCapacity(request.getDefaultCapacity() != null
                        ? request.getDefaultCapacity() : template.getDefaultCapacity())
                .defaultMinCapacity(request.getDefaultMinCapacity() != null
                        ? request.getDefaultMinCapacity() : template.getDefaultMinCapacity())
                .defaultDurationMinutes(request.getDefaultDurationMinutes() != null
                        ? request.getDefaultDurationMinutes() : template.getDefaultDurationMinutes())
                .defaultApplicationDeadlineHours(request.getDefaultApplicationDeadlineHours() != null
                        ? request.getDefaultApplicationDeadlineHours() : template.getDefaultApplicationDeadlineHours())
                .defaultAutoCancelHours(request.getDefaultAutoCancelHours() != null
                        ? request.getDefaultAutoCancelHours() : template.getDefaultAutoCancelHours())
                .defaultPaymentEnabled(request.getDefaultPaymentEnabled() != null
                        ? request.getDefaultPaymentEnabled() : template.getDefaultPaymentEnabled())
                .defaultPrice(request.getDefaultPrice() != null
                        ? request.getDefaultPrice() : template.getDefaultPrice())
                .defaultVisibility(request.getDefaultVisibility() != null
                        ? request.getDefaultVisibility() : template.getDefaultVisibility())
                .defaultLocation(request.getDefaultLocation() != null
                        ? request.getDefaultLocation() : template.getDefaultLocation())
                .defaultReservationLineId(request.getDefaultReservationLineId() != null
                        ? request.getDefaultReservationLineId() : template.getDefaultReservationLineId())
                .defaultImageUrl(request.getDefaultImageUrl() != null
                        ? request.getDefaultImageUrl() : template.getDefaultImageUrl())
                .defaultCancellationPolicyId(request.getDefaultCancellationPolicyId() != null
                        ? request.getDefaultCancellationPolicyId() : template.getDefaultCancellationPolicyId())
                .build();

        RecruitmentTemplateEntity saved = templateRepository.save(updated);
        log.info("F03.11 テンプレート更新: id={}", templateId);
        return saved;
    }

    /**
     * テンプレート論理削除（アーカイブ）。
     * 操作権限: 管理者以上。
     */
    @Transactional
    public void archive(Long templateId, Long userId) {
        RecruitmentTemplateEntity template = templateRepository.findActiveById(templateId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));
        accessControlService.checkAdminOrAbove(userId, template.getScopeId(), template.getScopeType().name());

        template.softDelete();
        templateRepository.save(template);
        log.info("F03.11 テンプレート論理削除: id={}", templateId);
    }

    // ===========================================
    // 内部ユーティリティ
    // ===========================================

    /**
     * テンプレートにキャンセルポリシーが設定されている場合、ポリシーと段階を DEEP COPY して返す。
     * スナップショット方式: 元のポリシーは変更しない。
     * キャンセルポリシーが未設定の場合は null を返す。
     * ListingService.createFromTemplate() から呼ばれる内部メソッド。
     */
    @Transactional
    public RecruitmentCancellationPolicyEntity deepCopyPolicyIfNeeded(
            RecruitmentTemplateEntity template, Long userId) {
        if (template.getDefaultCancellationPolicyId() == null) {
            return null;
        }

        RecruitmentCancellationPolicyEntity original = policyRepository
                .findById(template.getDefaultCancellationPolicyId())
                .orElse(null);
        if (original == null) {
            log.warn("F03.11 テンプレートのキャンセルポリシーが見つかりません: policyId={}",
                    template.getDefaultCancellationPolicyId());
            return null;
        }

        // ポリシー本体を DEEP COPY（isTemplatePolicy=false のスナップショットとして作成）
        RecruitmentCancellationPolicyEntity copied = RecruitmentCancellationPolicyEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .policyName(original.getPolicyName())
                .freeUntilHoursBefore(original.getFreeUntilHoursBefore())
                .isTemplatePolicy(false)
                .createdBy(userId)
                .build();
        RecruitmentCancellationPolicyEntity savedPolicy = policyRepository.save(copied);

        // 段階（Tier）を全件コピー
        List<RecruitmentCancellationPolicyTierEntity> tiers =
                tierRepository.findByPolicyIdOrderByTierOrderAsc(original.getId());
        for (RecruitmentCancellationPolicyTierEntity tier : tiers) {
            RecruitmentCancellationPolicyTierEntity copiedTier =
                    RecruitmentCancellationPolicyTierEntity.builder()
                            .policyId(savedPolicy.getId())
                            .tierOrder(tier.getTierOrder())
                            .appliesAtOrBeforeHours(tier.getAppliesAtOrBeforeHours())
                            .feeType(tier.getFeeType())
                            .feeValue(tier.getFeeValue())
                            .build();
            tierRepository.save(copiedTier);
        }

        log.info("F03.11 キャンセルポリシー DEEP COPY: original={}, copied={}",
                original.getId(), savedPolicy.getId());
        return savedPolicy;
    }
}
