package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.dto.CreateOperationalCampaignRequest;
import com.mannschaft.app.advertising.dto.OperationalCampaignResponse;
import com.mannschaft.app.advertising.entity.AdCampaignEntity.CampaignStatus;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdRateCardRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 運用型（CPM/CPC × 日予算）キャンペーン CRUD・状態遷移サービス（F09.19.1）。
 *
 * <p><b>試練（テスト先行）段階の骨格クラス</b>。全メソッドは {@link UnsupportedOperationException} を
 * 投げる。出陣（実装）で以下を満たすこと（正本 F09.19 §6.5 / §16 F09.19.1）:</p>
 * <ul>
 *   <li>作成時に unit_price_snapshot を rate_card.unit_price から確定・凍結（AC-1.1）</li>
 *   <li>状態機械: DRAFT→submit→PENDING_REVIEW→approve→ACTIVE→pause→PAUSED→resume→ACTIVE、
 *       ACTIVE/PAUSED→end→ENDED（終端）。遷移条件外は 409 / AD_027（AC-1.2 / 1.5）</li>
 *   <li>編集: DRAFT は全フィールド可（rateCardId 変更で snapshot 再確定）。PAUSED は
 *       name/dailyBudget/endDate のみ可・snapshot 不変。他状態の PUT は 409 / AD_027（AC-1.4）</li>
 *   <li>バリデーション: AD_028（min_daily_budget 未満）/ AD_030（期間不正）/ AD_031（期間外カード）（AC-1.5 / 1.10）</li>
 *   <li>認可: 呼び出し元 Controller の checkAdminOrAbove に加え、scope の広告主アカウント
 *       （ACTIVE・未削除）存在検証。他 scope のキャンペーンは 403（存在有無を問わず）（AC-1.6）</li>
 *   <li>reject 時 reject_reason 永続化・再 submit で NULL クリア（AC-1.11）</li>
 *   <li>submit/approve/reject/pause/resume/end で OPERATIONAL_CAMPAIGN_* 監査ログ（AC-1.9）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OperationalAdCampaignService {

    /** 運用型監査ログイベント種別（AC-1.9 の 6 イベント）。 */
    public static final String AUDIT_SUBMITTED = "OPERATIONAL_CAMPAIGN_SUBMITTED";
    public static final String AUDIT_APPROVED = "OPERATIONAL_CAMPAIGN_APPROVED";
    public static final String AUDIT_REJECTED = "OPERATIONAL_CAMPAIGN_REJECTED";
    public static final String AUDIT_PAUSED = "OPERATIONAL_CAMPAIGN_PAUSED";
    public static final String AUDIT_RESUMED = "OPERATIONAL_CAMPAIGN_RESUMED";
    public static final String AUDIT_ENDED = "OPERATIONAL_CAMPAIGN_ENDED";

    private final AdCampaignRepository adCampaignRepository;
    private final AdRateCardRepository adRateCardRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    /** キャンペーン作成（DRAFT・snapshot 確定）。 */
    public OperationalCampaignResponse create(ScopeType scopeType, Long scopeId, Long userId,
                                              CreateOperationalCampaignRequest request) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** 一覧（status フィルタ・created_at DESC・PagedResponse 正準）。 */
    public PagedResponse<OperationalCampaignResponse> list(ScopeType scopeType, Long scopeId,
                                                           CampaignStatus status, int page, int size) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** 詳細。 */
    public OperationalCampaignResponse get(ScopeType scopeType, Long scopeId, Long campaignId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** 編集（DRAFT / PAUSED のみ。PUT 全フィールド送信）。 */
    public OperationalCampaignResponse update(ScopeType scopeType, Long scopeId, Long campaignId,
                                              CreateOperationalCampaignRequest request) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** DRAFT → PENDING_REVIEW（reject_reason NULL クリア + 監査ログ）。 */
    public OperationalCampaignResponse submit(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** ACTIVE → PAUSED。 */
    public OperationalCampaignResponse pause(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** PAUSED → ACTIVE。 */
    public OperationalCampaignResponse resume(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** ACTIVE / PAUSED → ENDED（終端・不可逆）。 */
    public OperationalCampaignResponse end(ScopeType scopeType, Long scopeId, Long campaignId, Long userId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    // ─── SYSTEM_ADMIN 審査（§6.1 /system-admin/ad-campaigns-operational） ───

    /** 審査キュー一覧（status フィルタ既定 PENDING_REVIEW）。 */
    public PagedResponse<OperationalCampaignResponse> listForReview(CampaignStatus status, int page, int size) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** PENDING_REVIEW → ACTIVE（監査ログ OPERATIONAL_CAMPAIGN_APPROVED）。 */
    public OperationalCampaignResponse approve(Long campaignId, Long adminUserId) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }

    /** PENDING_REVIEW → DRAFT（理由必須 1〜500 文字。reject_reason 永続化 + 監査ログ）。 */
    public OperationalCampaignResponse reject(Long campaignId, Long adminUserId, String reason) {
        throw new UnsupportedOperationException("F09.19.1 出陣で実装");
    }
}
