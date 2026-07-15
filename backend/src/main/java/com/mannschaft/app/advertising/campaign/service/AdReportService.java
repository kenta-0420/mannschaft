package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.campaign.dto.AdReportCreatedResponse;
import com.mannschaft.app.advertising.campaign.dto.AdUserReportAdminResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateAdReportRequest;
import com.mannschaft.app.advertising.campaign.entity.AdCampaignModerationLog;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdUserReport;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignModerationLogRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.repository.AdUserReportRepository;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F09.19.9 通報サービス（メッセージ型/運用型両対応・新規実装）。
 *
 * <p>正本 {@code docs/features/F09.19_ad_slot_serving.md} §12 / §16 F09.19.9。</p>
 *
 * <p>責務:
 * <ul>
 *   <li>通報作成（XOR 検証 + 対象存在検証）</li>
 *   <li>自動停止（メッセージ型 → BLOCKED / 運用型 → 状態ガード付き PAUSED + report_suspended_at）</li>
 *   <li>SYSTEM_ADMIN 通報一覧（status / reasonCode フィルタ + ページング）と状態遷移</li>
 * </ul>
 * カウントは通報行数ベース（重複排除しない = V67.011 の仕様）で、
 * {@code NEW / REVIEWING} のみを対象とする（RESOLVED / DISMISSED は除外）。
 * {@code @Transactional} は advertising ドメイン内に閉じる。運用型の unsuspend（解除）は
 * 状態機械を所有する {@code OperationalAdCampaignService} 側に置く（§6.1）。</p>
 *
 * <p>SYSTEM_ADMIN 通知は監査ログ（{@link #AUDIT_OPERATIONAL_SUSPENDED_AUTO} /
 * {@link #AUDIT_CAMPAIGN_AUTO_BLOCKED}）と通報一覧画面（autoSuspendCandidate ハイライト）で担保する。
 * SYSTEM_ADMIN 個々への push 通知は notification ドメインへの越境（原則 5 違反）となるため本弾では発火しない。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdReportService {

    /** 自動停止の通報件数閾値。 */
    static final int AUTO_STOP_THRESHOLD = 3;

    /** 自動停止カウント対象の通報状態（未処理のみ）。 */
    static final Set<AdReportStatus> ACTIVE_REPORT_STATUSES =
            Set.of(AdReportStatus.NEW, AdReportStatus.REVIEWING);

    /** 監査ログイベント種別。 */
    public static final String AUDIT_OPERATIONAL_SUSPENDED_AUTO = "OPERATIONAL_CAMPAIGN_SUSPENDED_AUTO";
    public static final String AUDIT_CAMPAIGN_AUTO_BLOCKED = "CAMPAIGN_AUTO_BLOCKED";

    private static final int MAX_PAGE_SIZE = 100;
    private static final ObjectMapper AUDIT_METADATA_MAPPER = new ObjectMapper();

    private final AdUserReportRepository reportRepository;
    private final AdMessagingCampaignRepository messagingCampaignRepository;
    private final AdCampaignRepository operationalCampaignRepository;
    private final AdCampaignModerationLogRepository moderationLogRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    // ═══════════════════════════════════════════════════════════════════════
    // 通報作成（受信者・認証必須）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 通報を作成する。{@code campaignId}（メッセージ型）と {@code operationalCampaignId}（運用型）は XOR。
     *
     * <p>両方指定・両方 null は AD_032（400）。不存在対象は 404
     * （メッセージ型 = AD_CAMPAIGN_NOT_FOUND / 運用型 = AD_021）。作成後に自動停止判定を行う。</p>
     */
    @Transactional
    public AdReportCreatedResponse createReport(Long userId, CreateAdReportRequest request) {
        boolean hasMessaging = request.campaignId() != null;
        boolean hasOperational = request.operationalCampaignId() != null;
        // XOR: 両方指定 or 両方 null は不正
        if (hasMessaging == hasOperational) {
            throw new BusinessException(AdvertisingErrorCode.AD_032);
        }

        // 対象存在検証（帰属検証。不存在は 404）
        if (hasMessaging) {
            messagingCampaignRepository.findById(request.campaignId())
                    .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));
        } else {
            operationalCampaignRepository.findById(request.operationalCampaignId())
                    .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_021));
        }

        AdUserReport report = AdUserReport.builder()
                .campaignId(request.campaignId())
                .operationalCampaignId(request.operationalCampaignId())
                .reporterUserId(userId)
                .channelType(request.channelType())
                .reasonCode(request.reasonCode())
                .comment(request.comment())
                .status(AdReportStatus.NEW)
                .build();
        // カウントに本行を含めるため flush してから閾値判定する
        AdUserReport saved = reportRepository.saveAndFlush(report);

        if (hasMessaging) {
            maybeAutoBlockMessaging(request.campaignId());
        } else {
            maybeAutoSuspendOperational(request.operationalCampaignId());
        }
        return AdReportCreatedResponse.from(saved);
    }

    /** メッセージ型: 未処理通報 3 件到達で BLOCKED（既に BLOCKED なら冪等スキップ）。 */
    private void maybeAutoBlockMessaging(UUID campaignId) {
        long count = reportRepository.countByCampaignIdAndStatusIn(campaignId, ACTIVE_REPORT_STATUSES);
        if (count < AUTO_STOP_THRESHOLD) {
            return;
        }
        AdMessagingCampaign campaign = messagingCampaignRepository.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getStatus() == AdCampaignStatus.BLOCKED) {
            return;
        }
        campaign.setStatus(AdCampaignStatus.BLOCKED);
        campaign.setModerationStatus(AdModerationStatus.BLOCKED);
        campaign.setBlockedReason("ユーザー通報 " + count + " 件による自動ブロック（F09.19.9）");
        messagingCampaignRepository.save(campaign);

        moderationLogRepository.save(AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(null) // 自動処理のため NULL
                .action(AdModerationAction.BLOCKED)
                .reason("ユーザー通報 " + count + " 件による自動ブロック")
                .build());

        auditLogService.record(AUDIT_CAMPAIGN_AUTO_BLOCKED, null, null, null,
                auditOrgIdForMessaging(campaign), null, null, null,
                autoStopMetadata(campaignId.toString(), count));
    }

    /**
     * 運用型: 未処理通報 3 件到達で自動停止（状態ガード付き）。既に停止中（report_suspended_at 非 NULL）なら冪等スキップ。
     *
     * <p>ACTIVE のみ PAUSED へ遷移し {@code report_auto_paused=TRUE}。ENDED / DRAFT / PENDING_REVIEW /
     * 広告主 PAUSED は status 不変（{@link AdCampaignEntity#applyReportAutoStop}）。</p>
     */
    private void maybeAutoSuspendOperational(Long operationalCampaignId) {
        long count = reportRepository.countByOperationalCampaignIdAndStatusIn(
                operationalCampaignId, ACTIVE_REPORT_STATUSES);
        if (count < AUTO_STOP_THRESHOLD) {
            return;
        }
        AdCampaignEntity campaign = operationalCampaignRepository.findById(operationalCampaignId).orElse(null);
        if (campaign == null || campaign.getReportSuspendedAt() != null) {
            return; // 冪等: 既に自動停止済み
        }
        campaign.applyReportAutoStop(LocalDateTime.now(clock));
        operationalCampaignRepository.save(campaign);

        auditLogService.record(AUDIT_OPERATIONAL_SUSPENDED_AUTO, null, null, null,
                auditOrgIdForOperational(campaign), null, null, null,
                autoStopMetadata(String.valueOf(operationalCampaignId), count));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SYSTEM_ADMIN 通報一覧・状態遷移
    // ═══════════════════════════════════════════════════════════════════════

    /** 通報一覧（status / reasonCode 任意フィルタ + ページング。created_at DESC）。 */
    public PagedResponse<AdUserReportAdminResponse> listReports(
            AdReportStatus status, AdReportReasonCode reasonCode, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize); // 並び順は @Query の ORDER BY で担保
        Page<AdUserReport> result = reportRepository.searchForAdmin(status, reasonCode, pageable);
        return PagedResponse.of(
                result.getContent().stream().map(this::toAdminResponse).toList(),
                new PagedResponse.PageMeta(result.getTotalElements(), safePage, safeSize, result.getTotalPages()));
    }

    /** 通報の状態遷移（NEW→REVIEWING→RESOLVED/DISMISSED）。不正遷移は AD_027（409）。不存在は AD_035（404）。 */
    @Transactional
    public AdUserReportAdminResponse updateStatus(UUID reportId, AdReportStatus newStatus) {
        AdUserReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_035));
        if (!isAllowedTransition(report.getStatus(), newStatus)) {
            throw new BusinessException(AdvertisingErrorCode.AD_027);
        }
        report.setStatus(newStatus);
        AdUserReport saved = reportRepository.save(report);
        return toAdminResponse(saved);
    }

    /** 許可遷移: NEW→REVIEWING、REVIEWING→RESOLVED/DISMISSED のみ。 */
    private boolean isAllowedTransition(AdReportStatus from, AdReportStatus to) {
        return switch (from) {
            case NEW -> to == AdReportStatus.REVIEWING;
            case REVIEWING -> to == AdReportStatus.RESOLVED || to == AdReportStatus.DISMISSED;
            case RESOLVED, DISMISSED -> false;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═══════════════════════════════════════════════════════════════════════

    private AdUserReportAdminResponse toAdminResponse(AdUserReport r) {
        return new AdUserReportAdminResponse(
                r.getId(),
                r.getCampaignId(),
                r.getOperationalCampaignId(),
                r.getReporterUserId(),
                r.getReasonCode(),
                r.getComment(),
                r.getStatus(),
                isAutoSuspendCandidate(r),
                r.getCreatedAt());
    }

    /** 同一キャンペーンの未処理通報が閾値以上か（FE ハイライト用）。 */
    private boolean isAutoSuspendCandidate(AdUserReport r) {
        long count = r.getCampaignId() != null
                ? reportRepository.countByCampaignIdAndStatusIn(r.getCampaignId(), ACTIVE_REPORT_STATUSES)
                : reportRepository.countByOperationalCampaignIdAndStatusIn(
                        r.getOperationalCampaignId(), ACTIVE_REPORT_STATUSES);
        return count >= AUTO_STOP_THRESHOLD;
    }

    /** メッセージ型の監査ログ organization_id（scope が ORGANIZATION のときのみ）。 */
    private Long auditOrgIdForMessaging(AdMessagingCampaign campaign) {
        return campaign.getScopeType() == ScopeType.ORGANIZATION ? campaign.getScopeId() : null;
    }

    /** 運用型の監査ログ organization_id を広告主アカウントの scope から解決する。 */
    private Long auditOrgIdForOperational(AdCampaignEntity campaign) {
        AdvertiserAccountEntity account = advertiserAccountRepository
                .findById(campaign.getAdvertiserAccountId()).orElse(null);
        if (account == null || account.getScopeType() != ScopeType.ORGANIZATION) {
            return null;
        }
        return account.getScopeId();
    }

    /** 自動停止の監査ログ metadata（{@code {"campaignId":..,"reportCount":..}}）。 */
    private String autoStopMetadata(String campaignId, long reportCount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("campaignId", campaignId);
        meta.put("reportCount", reportCount);
        try {
            return AUDIT_METADATA_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{\"campaignId\":\"" + campaignId + "\",\"reportCount\":" + reportCount + "}";
        }
    }
}
