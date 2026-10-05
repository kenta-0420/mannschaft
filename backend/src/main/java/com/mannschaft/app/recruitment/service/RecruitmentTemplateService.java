package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateCreateRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateResponse;
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
 * <p>テンプレートの CRUD と deepCopyPolicyIfNeeded を提供する。
 * ListingService は deepCopyPolicyIfNeeded を呼ぶが、循環依存を避けるため
 * このサービスは ListingService に依存しない。</p>
 *
 * <p>設計書参照:</p>
 * <ul>
 *   <li>§3.3 recruitment_templates テーブル</li>
 *   <li>§5.1.2 テンプレートから募集枠を作成する</li>
 *   <li>§13 認可: 閲覧はメンバー以上、作成/更新/削除は管理者以上</li>
 * </ul>
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
    public Page<RecruitmentTemplateResponse> listByScope(
            RecruitmentScopeType scopeType, Long scopeId, Long userId, Pageable pageable) {
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
        return templateRepository.findActiveByScopeTypeAndScopeId(scopeType, scopeId, pageable)
                .map(RecruitmentTemplateResponse::from);
    }

    /**
     * テンプレートの認可入力（認可ファサードが 403 / 404 を分けるための最小情報）。
     *
     * @param scopeType テンプレートのスコープ種別
     * @param scopeId   テンプレートのスコープ ID
     */
    public record TemplateAccessScope(RecruitmentScopeType scopeType, Long scopeId) {
    }

    /**
     * 認可の前に、テンプレートのスコープを素の読み取りで解決する（readOnly。アーカイブ済み・不在は {@code TEMPLATE_NOT_FOUND}(404)）。
     *
     * @param templateId テンプレート ID
     * @return テンプレートの認可入力
     */
    public TemplateAccessScope resolveTemplateScope(Long templateId) {
        RecruitmentTemplateEntity template = findOrThrow(templateId);
        return new TemplateAccessScope(template.getScopeType(), template.getScopeId());
    }

    /**
     * テンプレート単件取得（論理削除除外。<b>tx 本体</b>）。
     *
     * <p>認可（メンバー以上）は {@link RecruitmentListingFacade#getTemplate} が tx の外で済ませる。
     * 本メソッドは認可の後にテンプレートを読み直し、アーカイブ済みなら {@code TEMPLATE_NOT_FOUND}(404)。</p>
     */
    public RecruitmentTemplateResponse getTemplate(Long templateId) {
        return RecruitmentTemplateResponse.from(findOrThrow(templateId));
    }

    // ===========================================
    // 更新系
    // ===========================================

    /**
     * テンプレート作成。
     * 操作権限: 管理者以上。
     */
    @Transactional
    public RecruitmentTemplateResponse create(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            RecruitmentTemplateCreateRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        RecruitmentVisibility defaultVisibility = request.getDefaultVisibility() != null
                ? request.getDefaultVisibility()
                : RecruitmentVisibility.SCOPE_ONLY;

        // バリデーション: min_capacity <= capacity
        if (request.getDefaultMinCapacity() != null && request.getDefaultCapacity() != null
                && request.getDefaultMinCapacity() > request.getDefaultCapacity()) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_CAPACITY);
        }
        // バリデーション: 決済有効化時は料金必須
        if (Boolean.TRUE.equals(request.getDefaultPaymentEnabled()) && request.getDefaultPrice() == null) {
            throw new BusinessException(RecruitmentErrorCode.PRICE_REQUIRED);
        }

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
                .defaultPaymentEnabled(Boolean.TRUE.equals(request.getDefaultPaymentEnabled()))
                .defaultPrice(request.getDefaultPrice())
                .defaultVisibility(defaultVisibility)
                .defaultLocation(request.getDefaultLocation())
                .defaultReservationLineId(request.getDefaultReservationLineId())
                .defaultImageUrl(request.getDefaultImageUrl())
                .defaultCancellationPolicyId(request.getDefaultCancellationPolicyId())
                .createdBy(userId)
                .build();

        RecruitmentTemplateEntity saved = templateRepository.save(entity);
        log.info("F03.11 テンプレート作成: id={}, scope={}/{}", saved.getId(), scopeType, scopeId);
        return RecruitmentTemplateResponse.from(saved);
    }

    /**
     * テンプレート更新。null のフィールドは変更しない（部分更新。<b>tx 本体</b>）。
     *
     * <p>認可（管理者以上）は {@link RecruitmentListingFacade#updateTemplate} が tx の外で済ませる。
     * 本メソッドは認可の後にテンプレートを読み直し、アーカイブ済みなら {@code TEMPLATE_NOT_FOUND}(404)・DB 不変。
     * テンプレートのスコープ列は不変という前提。</p>
     */
    @Transactional
    public RecruitmentTemplateResponse update(Long templateId, RecruitmentTemplateUpdateRequest request) {
        RecruitmentTemplateEntity template = findOrThrow(templateId);

        // 更新後の値で再検証
        int effectiveCapacity = request.getDefaultCapacity() != null
                ? request.getDefaultCapacity()
                : template.getDefaultCapacity();
        int effectiveMinCapacity = request.getDefaultMinCapacity() != null
                ? request.getDefaultMinCapacity()
                : template.getDefaultMinCapacity();
        if (effectiveMinCapacity > effectiveCapacity) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_CAPACITY);
        }
        boolean effectivePaymentEnabled = request.getDefaultPaymentEnabled() != null
                ? request.getDefaultPaymentEnabled()
                : template.getDefaultPaymentEnabled();
        Integer effectivePrice = request.getDefaultPrice() != null
                ? request.getDefaultPrice()
                : template.getDefaultPrice();
        if (effectivePaymentEnabled && effectivePrice == null) {
            throw new BusinessException(RecruitmentErrorCode.PRICE_REQUIRED);
        }

        template.update(
                request.getTemplateName(),
                request.getTitle(),
                request.getDescription(),
                request.getCategoryId(),
                request.getSubcategoryId(),
                request.getParticipationType(),
                request.getDefaultCapacity(),
                request.getDefaultMinCapacity(),
                request.getDefaultDurationMinutes(),
                request.getDefaultApplicationDeadlineHours(),
                request.getDefaultAutoCancelHours(),
                request.getDefaultPaymentEnabled(),
                request.getDefaultPrice(),
                request.getDefaultVisibility(),
                request.getDefaultLocation(),
                request.getDefaultReservationLineId(),
                request.getDefaultImageUrl(),
                request.getDefaultCancellationPolicyId()
        );

        RecruitmentTemplateEntity saved = templateRepository.save(template);
        log.info("F03.11 テンプレート更新: id={}", templateId);
        return RecruitmentTemplateResponse.from(saved);
    }

    /**
     * テンプレート論理削除（アーカイブ。<b>tx 本体</b>）。
     *
     * <p>認可（管理者以上）は {@link RecruitmentListingFacade#archiveTemplate} が tx の外で済ませる。
     * 本メソッドは認可の後にテンプレートを読み直し、アーカイブ済みなら {@code TEMPLATE_NOT_FOUND}(404)・DB 不変。</p>
     */
    @Transactional
    public void archive(Long templateId) {
        RecruitmentTemplateEntity template = findOrThrow(templateId);

        template.archive();
        templateRepository.save(template);
        log.info("F03.11 テンプレート論理削除: id={}", templateId);
    }

    // ===========================================
    // 内部ユーティリティ（ListingService から呼ばれる）
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

    // ===========================================
    // 内部ヘルパー
    // ===========================================

    private RecruitmentTemplateEntity findOrThrow(Long templateId) {
        return templateRepository.findActiveById(templateId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));
    }
}
