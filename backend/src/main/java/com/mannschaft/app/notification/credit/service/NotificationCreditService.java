package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.credit.dto.NotificationCreditBalanceResponse;
import com.mannschaft.app.notification.credit.dto.NotificationCreditPackageResponse;
import com.mannschaft.app.notification.credit.dto.NotificationCreditPurchaseResponse;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPackageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.entity.NotificationMonthlyUsageEntity;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPackageRepository;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.NotificationMonthlyUsageRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * F09.13 通知プリペイドクレジットサービス。
 *
 * <p>通知送信時のクレジット消費・残高照会・購入履歴を担当する。</p>
 *
 * <h2>消費ロジック</h2>
 * <ol>
 *   <li>月間無料枠（10,000通）を先に使い切る</li>
 *   <li>超過分はクレジット残高から差し引く</li>
 *   <li>残高不足時: 最初の不足で猶予期間（72時間）を開始し、その間は送信を許可する</li>
 *   <li>猶予期間を72時間超過した場合は {@link BusinessException} で送信ブロック</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationCreditService {

    /** 月間無料枠通数 */
    static final long FREE_MONTHLY_QUOTA = 10_000L;

    /** 無料枠のアラート閾値通数 */
    private static final long FREE_ALERT_THRESHOLD = 9_000L;

    /** 猶予期間（時間） */
    private static final long GRACE_PERIOD_HOURS = 72L;

    private final OrganizationNotificationBalanceRepository balanceRepository;
    private final NotificationCreditPurchaseRepository purchaseRepository;
    private final NotificationCreditPackageRepository packageRepository;
    private final NotificationMonthlyUsageRepository monthlyUsageRepository;
    private final UserRoleRepository userRoleRepository;
    // NotificationHelper → NotificationCreditService → NotificationHelper の循環を断つ。
    // sendFreeQuotaAlertAsync（@Async）でのみ使用するため @Lazy プロキシで遅延解決する。
    @Lazy
    @Autowired
    private NotificationHelper notificationHelper;
    /** Issue #2715 CMP-055 ロットC-1: 通知本文の i18n。locale 解決自体は notifyAllLocalized 内部の UserLocaleCache が担う。 */
    private final MessageSource messageSource;


    // ─────────────────────────────────────────────────────────
    // 消費（送信ゲート）
    // ─────────────────────────────────────────────────────────

    /**
     * 通知送信に伴いクレジットを消費する（送信ゲート）。
     *
     * <p>{@link OrganizationNotificationBalanceEntity} を PESSIMISTIC_WRITE でロックし、
     * 無料枠 → クレジット残高 → 猶予期間の順に消費する。</p>
     *
     * @param organizationId 組織ID
     * @param recipientCount 受信者数（＝消費通数）
     * @param sourceType     通知発生源
     * @throws BusinessException 猶予期間72時間超過の場合（CREDIT_INSUFFICIENT）
     */
    @Transactional
    public void consume(Long organizationId, int recipientCount, NotificationSourceType sourceType) {
        if (recipientCount <= 0) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate firstOfMonth = today.withDayOfMonth(1);

        // PESSIMISTIC_WRITE ロックで残高を取得（存在しなければ新規作成）
        OrganizationNotificationBalanceEntity balance = balanceRepository
                .findByOrganizationIdForUpdate(organizationId)
                .orElseGet(() -> {
                    OrganizationNotificationBalanceEntity newBalance =
                            OrganizationNotificationBalanceEntity.builder()
                                    .organizationId(organizationId)
                                    .freeUsedThisMonth(0L)
                                    .freeQuotaMonth(firstOfMonth)
                                    .alertSentThisMonth(false)
                                    .creditBalance(0L)
                                    .gracePeriodDebt(0L)
                                    .build();
                    return balanceRepository.save(newBalance);
                });

        // free_quota_month が今月でなければ無料枠をリセット（バッチ実行前の送信タイミング対策）
        if (!firstOfMonth.equals(balance.getFreeQuotaMonth())) {
            log.info("無料枠リセット（バッチ未実行補完）: organizationId={}, oldMonth={}, newMonth={}",
                    organizationId, balance.getFreeQuotaMonth(), firstOfMonth);
            // managed entity を直接ミューテート。toBuilder().build() は継承フィールド id を
            // 引き継がず id=null の新インスタンスになり、save が INSERT になって
            // organization_id 一意制約違反で 500 になるため使わない。
            balance.resetFreeQuotaForMonth(firstOfMonth);
            balance = balanceRepository.save(balance);
        }

        // ───────────────────────────────────────────────
        // 消費計算
        // ───────────────────────────────────────────────
        long freeRemaining = Math.max(0L, FREE_MONTHLY_QUOTA - balance.getFreeUsedThisMonth());
        long freeToUse = Math.min(recipientCount, freeRemaining);
        long creditNeeded = recipientCount - freeToUse;

        long graceUsed = 0L;
        long creditUsed = 0L;

        if (creditNeeded > 0) {
            if (balance.getCreditBalance() >= creditNeeded) {
                // クレジット残高で賄える
                balance.consumeCredit(creditNeeded);
                creditUsed = creditNeeded;
            } else {
                // 残高不足
                if (balance.getGracePeriodStartAt() == null) {
                    // 猶予期間開始
                    balance.startGracePeriod(creditNeeded);
                    graceUsed = creditNeeded;
                    log.warn("通知クレジット不足: 猶予期間開始 organizationId={}, debt={}",
                            organizationId, creditNeeded);
                } else {
                    // 猶予期間中かチェック
                    LocalDateTime gracePeriodEnd = balance.getGracePeriodStartAt()
                            .plusHours(GRACE_PERIOD_HOURS);
                    if (LocalDateTime.now().isBefore(gracePeriodEnd)) {
                        // 猶予期間内 → 引き続き送信許可
                        balance.addGraceDebt(creditNeeded);
                        graceUsed = creditNeeded;
                        log.warn("通知クレジット不足（猶予期間中）: organizationId={}, additionalDebt={}",
                                organizationId, creditNeeded);
                    } else {
                        // 猶予期間超過 → ブロック
                        log.error("通知クレジット不足: 猶予期間超過 organizationId={}", organizationId);
                        throw new BusinessException(NotificationCreditErrorCode.CREDIT_INSUFFICIENT);
                    }
                }
            }
        }

        // 無料枠消費量を更新
        balance.consumeFree(freeToUse);

        // 月次使用量をUPSERT
        upsertMonthlyUsage(organizationId, firstOfMonth, sourceType,
                recipientCount, freeToUse, creditUsed, graceUsed);

        // 9000通超過アラート判定（月1回のみ）
        if (balance.getFreeUsedThisMonth() >= FREE_ALERT_THRESHOLD
                && !Boolean.TRUE.equals(balance.getAlertSentThisMonth())) {
            balance.markAlertSentThisMonth();
            sendFreeQuotaAlertAsync(organizationId);
        }

        balanceRepository.save(balance);

        log.debug("通知クレジット消費: organizationId={}, recipients={}, free={}, credit={}, grace={}",
                organizationId, recipientCount, freeToUse, creditUsed, graceUsed);
    }

    // ─────────────────────────────────────────────────────────
    // クレジット加算（購入完了時）
    // ─────────────────────────────────────────────────────────

    /**
     * 購入完了時にクレジット残高へ通数を加算する。
     *
     * <p>{@link NotificationCreditCheckoutService#handlePurchaseCompleted} から呼ばれる。</p>
     *
     * @param purchaseId 購入ID
     */
    @Transactional
    public void addCredits(Long purchaseId) {
        NotificationCreditPurchaseEntity purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new BusinessException(NotificationCreditErrorCode.PURCHASE_NOT_FOUND));

        Long organizationId = purchase.getOrganizationId();
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);

        OrganizationNotificationBalanceEntity balance = balanceRepository
                .findByOrganizationIdForUpdate(organizationId)
                .orElseGet(() -> {
                    OrganizationNotificationBalanceEntity newBalance =
                            OrganizationNotificationBalanceEntity.builder()
                                    .organizationId(organizationId)
                                    .freeUsedThisMonth(0L)
                                    .freeQuotaMonth(firstOfMonth)
                                    .alertSentThisMonth(false)
                                    .creditBalance(0L)
                                    .gracePeriodDebt(0L)
                                    .build();
                    return balanceRepository.save(newBalance);
                });

        balance.addCredits(purchase.getCreditsGranted());
        balanceRepository.save(balance);

        log.info("通知クレジット加算: organizationId={}, purchaseId={}, credits={}",
                organizationId, purchaseId, purchase.getCreditsGranted());
    }

    // ─────────────────────────────────────────────────────────
    // 残高照会・一覧
    // ─────────────────────────────────────────────────────────

    /**
     * 組織の通知クレジット残高を取得する。
     *
     * @param organizationId 組織ID
     * @return 残高レスポンス
     */
    public NotificationCreditBalanceResponse getBalance(Long organizationId) {
        Optional<OrganizationNotificationBalanceEntity> balanceOpt =
                balanceRepository.findByOrganizationId(organizationId);

        if (balanceOpt.isEmpty()) {
            // 未使用の組織はゼロ残高で返す
            return new NotificationCreditBalanceResponse(0L, FREE_MONTHLY_QUOTA, 0L, false, null, 0L);
        }

        OrganizationNotificationBalanceEntity balance = balanceOpt.get();
        boolean inGracePeriod = balance.getGracePeriodStartAt() != null;
        LocalDateTime gracePeriodEndsAt = inGracePeriod
                ? balance.getGracePeriodStartAt().plusHours(GRACE_PERIOD_HOURS)
                : null;

        return new NotificationCreditBalanceResponse(
                balance.getFreeUsedThisMonth(),
                FREE_MONTHLY_QUOTA,
                balance.getCreditBalance(),
                inGracePeriod,
                gracePeriodEndsAt,
                balance.getGracePeriodDebt()
        );
    }

    /**
     * 組織の購入履歴一覧を取得する。
     *
     * @param organizationId 組織ID
     * @return 購入履歴レスポンスリスト
     */
    public List<NotificationCreditPurchaseResponse> listPurchases(Long organizationId) {
        return purchaseRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(purchase -> {
                    // パッケージ名はパッケージエンティティから取得（削除済みの場合はIDで代替）
                    String packageName = packageRepository.findById(purchase.getPackageId())
                            .map(NotificationCreditPackageEntity::getName)
                            .orElse("パッケージ#" + purchase.getPackageId());
                    return new NotificationCreditPurchaseResponse(
                            purchase.getId(),
                            packageName,
                            purchase.getCreditsGranted(),
                            purchase.getPriceJpy(),
                            purchase.getPaymentStatus(),
                            purchase.getPaidAt(),
                            purchase.getExpiresAt()
                    );
                })
                .toList();
    }

    /**
     * 販売中パッケージ一覧を取得する。
     *
     * @return パッケージレスポンスリスト
     */
    public List<NotificationCreditPackageResponse> listPackages() {
        return packageRepository.findAllByIsActiveTrueOrderByDisplayOrder().stream()
                .map(pkg -> new NotificationCreditPackageResponse(
                        pkg.getId(), pkg.getName(), pkg.getCredits(), pkg.getPriceJpy()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────
    // プライベートヘルパー
    // ─────────────────────────────────────────────────────────

    /**
     * 月次使用量をUPSERT（JPA save + UNIQUE KEY の INSERT OR UPDATE 相当）。
     */
    private void upsertMonthlyUsage(Long organizationId, LocalDate month,
                                    NotificationSourceType sourceType,
                                    long used, long free, long credit, long grace) {
        NotificationMonthlyUsageEntity usage = monthlyUsageRepository
                .findByOrganizationIdAndMonthAndSourceType(organizationId, month, sourceType)
                .orElseGet(() -> NotificationMonthlyUsageEntity.builder()
                        .organizationId(organizationId)
                        .month(month)
                        .sourceType(sourceType)
                        .build());
        usage.addUsage(used, free, credit, grace);
        monthlyUsageRepository.save(usage);
    }

    /**
     * 無料枠9000通超過アラートをADMINへ非同期送信する。
     *
     * @param organizationId 組織ID
     */
    @Async
    protected void sendFreeQuotaAlertAsync(Long organizationId) {
        try {
            List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_ALERT",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.freeQuotaAlert.title", null,
                                    "無料通知枠が残りわずかです", locale),
                            messageSource.getMessage(
                                    "notification.credit.freeQuotaAlert.body", null,
                                    "今月の無料通知枠（10,000通）の90%を使用しました。超過分はクレジットから消費されます。", locale))
            );
            log.info("無料枠アラート送信: organizationId={}", organizationId);
        } catch (Exception e) {
            log.error("無料枠アラート送信失敗: organizationId={}", organizationId, e);
        }
    }
}
