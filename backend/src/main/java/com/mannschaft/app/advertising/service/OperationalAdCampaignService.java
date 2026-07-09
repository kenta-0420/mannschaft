package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.PricingModel;
import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.entity.AdRateCardEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdRateCardRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 運用型（CPM/CPC × 日予算）キャンペーン CRUD・状態遷移サービス（F09.19.1）。
 *
 * <p>正本 F09.19 §6.5（CRUD 契約）・§6.1（審査）・§15（エラーコード）・§16 F09.19.1。</p>
 *
 * <p>認可（{@code checkAdminOrAbove} + 広告主アカウント検証 / SYSTEM_ADMIN 検証）は Controller 層で行い、
 * 本サービスは業務ロジック（状態機械・snapshot 確定・バリデーション）に専念する。
 * {@code campaign → scope} の帰属検証のみ本サービスでも二重に行い、越境は 403（COMMON_002）で拒否する
 * （IDOR 対策・存在有無を問わず）。{@code @Transactional} は advertising ドメイン内に閉じる。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OperationalAdCampaignService {

    /** 運用型監査ログイベント種別（AC-1.9 の 6 イベント）。 */
    public static final String AUDIT_SUBMITTED = "OPERATIONAL_CAMPAIGN_SUBMITTED";
    public static final String AUDIT_APPROVED = "OPERATIONAL_CAMPAIGN_APPROVED";
    public static final String AUDIT_REJECTED = "OPERATIONAL_CAMPAIGN_REJECTED";
    public static final String AUDIT_PAUSED = "OPERATIONAL_CAMPAIGN_PAUSED";
    public static final String AUDIT_RESUMED = "OPERATIONAL_CAMPAIGN_RESUMED";
    public static final String AUDIT_ENDED = "OPERATIONAL_CAMPAIGN_ENDED";

    private static final int MAX_PAGE_SIZE = 100;

    /** 監査ログ metadata（{@code {"campaignId":..,"reason":..}}）の JSON 化用。 */
    private static final ObjectMapper AUDIT_METADATA_MAPPER = new ObjectMapper();

    private final AdCampaignRepository adCampaignRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AdRateCardRepository adRateCardRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    // ═══════════════════════════════════════════════════════════════════════
    // 広告主向け CRUD
    // ═══════════════════════════════════════════════════════════════════════

    /** キャンペーン作成（DRAFT・snapshot 確定）。 */
    @Transactional
    public OperationalCampaignResponse create(ScopeType scopeType, Long scopeId, Long userId,
                                              CreateOperationalCampaignRequest request) {
        AdRateCardEntity card = validateAndResolveRateCard(
                request.pricingModel(), request.dailyBudget(),
                request.startDate(), request.endDate(), request.rateCardId());

        Long advertiserAccountId = resolveAccountId(scopeType, scopeId);

        AdCampaignEntity entity = AdCampaignEntity.builder()
                .advertiserAccountId(advertiserAccountId)
                .name(request.name())
                .status(CampaignStatus.DRAFT)
                .pricingModel(request.pricingModel())
                .dailyBudget(request.dailyBudget())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .rateCardId(request.rateCardId())
                .unitPriceSnapshot(card.getUnitPrice())
                .build();
        AdCampaignEntity saved = adCampaignRepository.save(entity);
        return toResponse(saved);
    }

    /** 一覧（status フィルタ・created_at DESC・PagedResponse 正準）。 */
    public PagedResponse<OperationalCampaignResponse> list(ScopeType scopeType, Long scopeId,
                                                           CampaignStatus status, int page, int size) {
        Long advertiserAccountId = resolveAccountId(scopeType, scopeId);
        Pageable pageable = createdAtDescPageable(page, size);
        Page<AdCampaignEntity> result = (status == null)
                ? adCampaignRepository.findByAdvertiserAccountId(advertiserAccountId, pageable)
                : adCampaignRepository.findByAdvertiserAccountIdAndStatus(advertiserAccountId, status, pageable);
        return toPaged(result, page, pageable.getPageSize());
    }

    /** 詳細。 */
    public OperationalCampaignResponse get(ScopeType scopeType, Long scopeId, Long campaignId) {
        return toResponse(findScoped(scopeType, scopeId, campaignId));
    }

    /** 編集（DRAFT / PAUSED のみ。PUT 全フィールド送信）。 */
    @Transactional
    public OperationalCampaignResponse update(ScopeType scopeType, Long scopeId, Long campaignId,
                                              CreateOperationalCampaignRequest request) {
        AdCampaignEntity campaign = findScoped(scopeType, scopeId, campaignId);
        switch (campaign.getStatus()) {
            case DRAFT -> {
                // DRAFT は全フィールド編集可。バリデーション（AD_030/031/028）は常に実施し、
                // unit_price_snapshot の再確定は「rateCardId が変更された場合のみ」行う
                // （正本 §6.5。同一カードのまま料金改定があっても申込時凍結価格を維持する）。
                AdRateCardEntity card = validateAndResolveRateCard(
                        request.pricingModel(), request.dailyBudget(),
                        request.startDate(), request.endDate(), request.rateCardId());
                boolean rateCardChanged = !Objects.equals(request.rateCardId(), campaign.getRateCardId());
                BigDecimal snapshot = rateCardChanged
                        ? card.getUnitPrice()
                        : campaign.getUnitPriceSnapshot();
                campaign.applyDraftEdit(request.name(), request.pricingModel(), request.dailyBudget(),
                        request.startDate(), request.endDate(), request.rateCardId(), snapshot);
            }
            case PAUSED -> {
                // PAUSED は name/dailyBudget/endDate のみ可。pricingModel/rateCardId/startDate の変更は AD_027。
                if (!Objects.equals(request.rateCardId(), campaign.getRateCardId())
                        || request.pricingModel() != campaign.getPricingModel()
                        || !Objects.equals(request.startDate(), campaign.getStartDate())) {
                    throw new BusinessException(AdvertisingErrorCode.AD_027);
                }
                validatePausedEdit(campaign, request.dailyBudget(), request.endDate());
                campaign.applyPausedEdit(request.name(), request.dailyBudget(), request.endDate());
            }
            default -> throw new BusinessException(AdvertisingErrorCode.AD_027);
        }
        return toResponse(campaign);
    }

    /** DRAFT → PENDING_REVIEW（reject_reason NULL クリア + 監査ログ）。 */
    @Transactional
    public OperationalCampaignResponse submit(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        AdCampaignEntity campaign = findScoped(scopeType, scopeId, campaignId);
        requireStatus(campaign, CampaignStatus.DRAFT);
        campaign.submitForReview();
        recordAudit(AUDIT_SUBMITTED, userId, auditOrgId(scopeType, scopeId),
                auditMetadata(campaign.getId(), null));
        return toResponse(campaign);
    }

    /** ACTIVE → PAUSED。 */
    @Transactional
    public OperationalCampaignResponse pause(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        AdCampaignEntity campaign = findScoped(scopeType, scopeId, campaignId);
        requireStatus(campaign, CampaignStatus.ACTIVE);
        campaign.pause();
        recordAudit(AUDIT_PAUSED, userId, auditOrgId(scopeType, scopeId),
                auditMetadata(campaign.getId(), null));
        return toResponse(campaign);
    }

    /** PAUSED → ACTIVE。通報自動停止中（report_suspended_at 非 NULL）は 403 / AD_033。 */
    @Transactional
    public OperationalCampaignResponse resume(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        AdCampaignEntity campaign = findScoped(scopeType, scopeId, campaignId);
        requireStatus(campaign, CampaignStatus.PAUSED);
        if (campaign.getReportSuspendedAt() != null) {
            throw new BusinessException(AdvertisingErrorCode.AD_033);
        }
        campaign.resume();
        recordAudit(AUDIT_RESUMED, userId, auditOrgId(scopeType, scopeId),
                auditMetadata(campaign.getId(), null));
        return toResponse(campaign);
    }

    /** ACTIVE / PAUSED → ENDED（終端・不可逆）。 */
    @Transactional
    public OperationalCampaignResponse end(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        AdCampaignEntity campaign = findScoped(scopeType, scopeId, campaignId);
        if (campaign.getStatus() != CampaignStatus.ACTIVE && campaign.getStatus() != CampaignStatus.PAUSED) {
            throw new BusinessException(AdvertisingErrorCode.AD_027);
        }
        campaign.end();
        recordAudit(AUDIT_ENDED, userId, auditOrgId(scopeType, scopeId),
                auditMetadata(campaign.getId(), null));
        return toResponse(campaign);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SYSTEM_ADMIN 審査（§6.1 /system-admin/ad-campaigns-operational）
    // ═══════════════════════════════════════════════════════════════════════

    /** 審査キュー一覧（status フィルタ既定 PENDING_REVIEW）。 */
    public PagedResponse<OperationalCampaignResponse> listForReview(CampaignStatus status, int page, int size) {
        Pageable pageable = createdAtDescPageable(page, size);
        Page<AdCampaignEntity> result = (status == null)
                ? adCampaignRepository.findAll(pageable)
                : adCampaignRepository.findByStatus(status, pageable);
        return toPaged(result, page, pageable.getPageSize());
    }

    /** PENDING_REVIEW → ACTIVE（監査ログ OPERATIONAL_CAMPAIGN_APPROVED）。 */
    @Transactional
    public OperationalCampaignResponse approve(Long campaignId, Long adminUserId) {
        AdCampaignEntity campaign = findById(campaignId);
        requireStatus(campaign, CampaignStatus.PENDING_REVIEW);
        campaign.approve();
        recordAudit(AUDIT_APPROVED, adminUserId, auditOrgIdFromCampaign(campaign),
                auditMetadata(campaign.getId(), null));
        return toResponse(campaign);
    }

    /** PENDING_REVIEW → DRAFT（理由必須 1〜500 文字。reject_reason 永続化 + 監査ログ）。 */
    @Transactional
    public OperationalCampaignResponse reject(Long campaignId, Long adminUserId, String reason) {
        AdCampaignEntity campaign = findById(campaignId);
        requireStatus(campaign, CampaignStatus.PENDING_REVIEW);
        campaign.reject(reason);
        recordAudit(AUDIT_REJECTED, adminUserId, auditOrgIdFromCampaign(campaign),
                auditMetadata(campaign.getId(), reason));
        return toResponse(campaign);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═══════════════════════════════════════════════════════════════════════

    /** scope に帰属するキャンペーンを取得する。不在・越境はいずれも 403（存在有無を問わず）。 */
    private AdCampaignEntity findScoped(ScopeType scopeType, Long scopeId, Long campaignId) {
        Long advertiserAccountId = resolveAccountId(scopeType, scopeId);
        AdCampaignEntity campaign = adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        if (!Objects.equals(campaign.getAdvertiserAccountId(), advertiserAccountId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return campaign;
    }

    /**
     * scope（org/team）に対応する広告主アカウント id を解決する。
     * Controller で ACTIVE 検証済みだが、不在は防御的に 403（COMMON_002）とする。
     */
    private Long resolveAccountId(ScopeType scopeType, Long scopeId) {
        return advertiserAccountRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002))
                .getId();
    }

    /** 監査ログの organization_id: scope が ORGANIZATION のときのみ scopeId、TEAM 等は null。 */
    private Long auditOrgId(ScopeType scopeType, Long scopeId) {
        return scopeType == ScopeType.ORGANIZATION ? scopeId : null;
    }

    /** SYSTEM_ADMIN 審査経路の監査ログ organization_id を、キャンペーンの広告主アカウントから解決する。 */
    private Long auditOrgIdFromCampaign(AdCampaignEntity campaign) {
        AdvertiserAccountEntity account = advertiserAccountRepository
                .findById(campaign.getAdvertiserAccountId())
                .orElse(null);
        if (account == null || account.getScopeType() != ScopeType.ORGANIZATION) {
            return null;
        }
        return account.getScopeId();
    }

    /** SYSTEM_ADMIN 審査用（scope 検証なし）。 */
    private AdCampaignEntity findById(Long campaignId) {
        return adCampaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_021));
    }

    private void requireStatus(AdCampaignEntity campaign, CampaignStatus expected) {
        if (campaign.getStatus() != expected) {
            throw new BusinessException(AdvertisingErrorCode.AD_027);
        }
    }

    /**
     * 作成・DRAFT 編集の共通バリデーション（AD_030 期間 / AD_031 カード / AD_028 予算）。
     *
     * @return 有効な料金カード（unit_price を snapshot に用いる）
     */
    private AdRateCardEntity validateAndResolveRateCard(PricingModel pricingModel, BigDecimal dailyBudget,
                                                        LocalDate startDate, LocalDate endDate, Long rateCardId) {
        LocalDate today = LocalDate.now(clock);

        // AD_030: 開始日は本日以降・終了日は開始日以降（null は無期限）
        if (startDate == null || startDate.isBefore(today)) {
            throw new BusinessException(AdvertisingErrorCode.AD_030);
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(AdvertisingErrorCode.AD_030);
        }

        // AD_031: 料金カードが存在し pricingModel 一致・申込日が effective 期間内
        AdRateCardEntity card = adRateCardRepository.findById(rateCardId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_031));
        if (card.getPricingModel() != pricingModel || !isEffectiveOn(card, today)) {
            throw new BusinessException(AdvertisingErrorCode.AD_031);
        }

        // AD_028: 日予算が料金カードの最低日予算以上
        if (dailyBudget == null || dailyBudget.compareTo(card.getMinDailyBudget()) < 0) {
            throw new BusinessException(AdvertisingErrorCode.AD_028);
        }
        return card;
    }

    /** PAUSED 編集の可変フィールド（dailyBudget / endDate）のバリデーション。 */
    private void validatePausedEdit(AdCampaignEntity campaign, BigDecimal dailyBudget, LocalDate endDate) {
        LocalDate today = LocalDate.now(clock);
        // AD_030: endDate 短縮で本日より前（または開始日より前）になる指定は不正
        if (endDate != null && (endDate.isBefore(today) || endDate.isBefore(campaign.getStartDate()))) {
            throw new BusinessException(AdvertisingErrorCode.AD_030);
        }
        // AD_028: 日予算は現行料金カードの最低日予算以上
        AdRateCardEntity card = adRateCardRepository.findById(campaign.getRateCardId())
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_031));
        if (dailyBudget == null || dailyBudget.compareTo(card.getMinDailyBudget()) < 0) {
            throw new BusinessException(AdvertisingErrorCode.AD_028);
        }
    }

    private boolean isEffectiveOn(AdRateCardEntity card, LocalDate date) {
        boolean startedInTime = !card.getEffectiveFrom().isAfter(date);
        boolean notExpired = card.getEffectiveUntil() == null || !card.getEffectiveUntil().isBefore(date);
        return startedInTime && notExpired;
    }

    private Pageable createdAtDescPageable(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private void recordAudit(String eventType, Long userId, Long organizationId, String metadata) {
        // fire-and-forget（AuditLogService.record は @Async・独立トランザクション）
        auditLogService.record(eventType, userId, null, null, organizationId, null, null, null, metadata);
    }

    /**
     * 監査ログ metadata JSON を構築する（正本 §6.5: reject は理由を記録）。
     * 全イベントで {@code campaignId} を、reject では {@code reason} も含める。
     */
    private String auditMetadata(Long campaignId, String reason) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("campaignId", campaignId);
        if (reason != null) {
            meta.put("reason", reason);
        }
        try {
            return AUDIT_METADATA_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            // 監査ログは fire-and-forget のため metadata 生成失敗でも主処理は止めない
            return "{\"campaignId\":" + campaignId + "}";
        }
    }

    private PagedResponse<OperationalCampaignResponse> toPaged(
            Page<AdCampaignEntity> page, int requestedPage, int size) {
        return PagedResponse.of(
                page.getContent().stream().map(this::toResponse).toList(),
                new PagedResponse.PageMeta(page.getTotalElements(), requestedPage, size, page.getTotalPages()));
    }

    private OperationalCampaignResponse toResponse(AdCampaignEntity c) {
        return new OperationalCampaignResponse(
                c.getId(),
                c.getName(),
                c.getStatus(),
                c.getPricingModel(),
                c.getDailyBudget(),
                c.getStartDate(),
                c.getEndDate(),
                c.getRateCardId(),
                c.getUnitPriceSnapshot(),
                c.getRejectReason(),
                c.getReportSuspendedAt(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
