package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.payment.AdvanceSettlementStatus;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.repository.TeamPaymentAdvanceRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * F08.9 P7 第一波: 協会請求の立替/精算記録サービス（team_payment_advances・案3）。
 *
 * <p>協会→チーム請求を「チーム ADMIN 個人の Stripe Customer で立替課金」（案3・README §6.3）した事実を
 * {@code PENDING} で起票し（{@link #createAdvance}・{@link PaymentRequestService#pay} 内部から呼ぶ）、後に
 * チームから精算された事実を {@code SETTLED} に確定する（{@link #confirmSettlement}・F04.9 確認必須通知から）。</p>
 *
 * <p>ドメイン境界: 本サービスは payment ドメイン内に閉じる（team/user は論理参照・ID のみ）。team ADMIN 認可は
 * {@link AccessControlService} を経由してロール判定する（クロスドメイン Repository 参照をしない）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.5 / 02_api_design.md §7</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TeamPaymentAdvanceService {

    /** team ADMIN 判定の scopeType（{@link AccessControlService} 用）。 */
    private static final String SCOPE_TYPE_TEAM = "TEAM";

    /** 精算確認通知の通知種別。 */
    private static final String SETTLEMENT_NOTIFICATION_TYPE = "PAYMENT_ADVANCE_SETTLED";

    /** 精算確認通知の sourceType（F00 visibility マッパー非対応＝fail-soft で素通り）。 */
    private static final String SETTLEMENT_NOTIFICATION_SOURCE_TYPE = "PAYMENT_ADVANCE";

    private final TeamPaymentAdvanceRepository teamPaymentAdvanceRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    /** 第二波: 精算確認時に協会 ADMIN へ軽量通知（確認必須までは不要）。 */
    private final NotificationHelper notificationHelper;
    /** 第二波: 協会 ADMIN/DEPUTY_ADMIN の userId 群を解決（role ドメイン経由）。 */
    private final UserRoleRepository userRoleRepository;
    /** 第二波: 通知文言の 6 言語解決。 */
    private final MessageSource messageSource;

    /**
     * 協会請求支払い時に立替記録を {@code PENDING} で起票する（{@link PaymentRequestService#pay} 内部用）。
     *
     * <p><b>冪等（1請求＝1立替）:</b> 同一 {@code paymentRequestId} の立替が既にあれば再作成せず既存を返す
     * （DB の {@code uk_tpa_request} UNIQUE と相まって二重起票を防ぐ）。pay 側の status ゲートと二重防御。</p>
     *
     * @param organizationId   テナント（協会）。論理参照
     * @param teamId           立替の主体チーム（請求先）
     * @param payerUserId      立替えた ADMIN 個人
     * @param escrowTransactionId 連結する escrow 取引 ID
     * @param paymentRequestId 対象の協会請求 ID
     * @param advancedAmount   立替額（円整数・課金された請求額）
     * @param currency         通貨
     * @return 起票（または既存）の立替記録
     */
    public TeamPaymentAdvanceEntity createAdvance(Long organizationId, Long teamId, Long payerUserId,
                                                  UUID escrowTransactionId, UUID paymentRequestId,
                                                  int advancedAmount, String currency) {
        var existing = teamPaymentAdvanceRepository.findByPaymentRequestIdAndDeletedAtIsNull(paymentRequestId);
        if (existing.isPresent()) {
            log.info("立替記録は既に存在します（冪等・再作成しない）: advanceId={}, paymentRequestId={}",
                    existing.get().getId(), paymentRequestId);
            return existing.get();
        }
        TeamPaymentAdvanceEntity advance = TeamPaymentAdvanceEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .payerUserId(payerUserId)
                .escrowTransactionId(escrowTransactionId)
                .paymentRequestId(paymentRequestId)
                .advancedAmount(advancedAmount)
                .currency(currency != null ? currency : "JPY")
                .settlementStatus(AdvanceSettlementStatus.PENDING)
                .build();
        advance = teamPaymentAdvanceRepository.save(advance);
        log.info("立替記録を PENDING で起票: advanceId={}, teamId={}, payer={}, paymentRequestId={}, amount={}",
                advance.getId(), teamId, payerUserId, paymentRequestId, advancedAmount);
        return advance;
    }

    /**
     * チーム ADMIN が立替金の精算を確認する（PENDING → SETTLED・F04.9 確認必須通知から・02_api §7）。
     *
     * <p>認可: 当該チーム ADMIN（{@code team_payment_advances.team_id} の team ADMIN/DEPUTY_ADMIN）。
     * 無関係チームは {@link MembershipBillingErrorCode#PAYMENT_ADVANCE_NOT_FOUND}（404 秘匿・IDOR）。
     * 既に SETTLED なら {@link MembershipBillingErrorCode#PAYMENT_ADVANCE_ALREADY_SETTLED}（409・二重確認防止）。</p>
     *
     * @param teamId       チーム ID（URL パスのスコープ・立替の team_id と一致必須）
     * @param advanceId    立替記録 ID
     * @param actorUserId  確認操作者（チーム ADMIN）
     * @return SETTLED 化した立替記録
     */
    public TeamPaymentAdvanceEntity confirmSettlement(Long teamId, UUID advanceId, Long actorUserId) {
        TeamPaymentAdvanceEntity advance = teamPaymentAdvanceRepository.findByIdAndDeletedAtIsNull(advanceId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_ADVANCE_NOT_FOUND));

        // IDOR: URL の teamId と立替の team_id 不一致は存在秘匿で 404。
        if (!advance.getTeamId().equals(teamId)) {
            log.warn("立替精算確認: URL teamId と立替 team_id 不一致（404 秘匿）: advanceId={}, urlTeam={}, advTeam={}",
                    advanceId, teamId, advance.getTeamId());
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_ADVANCE_NOT_FOUND);
        }

        // 認可: 当該チーム ADMIN/DEPUTY_ADMIN のみ精算確認できる（03_security §1）。
        requireTeamAdmin(actorUserId, teamId);

        // 冪等/二重確認防止: 既に SETTLED は 409。
        if (advance.getSettlementStatus() == AdvanceSettlementStatus.SETTLED) {
            log.info("立替は既に精算済み（重複確認防止）: advanceId={}", advanceId);
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_ADVANCE_ALREADY_SETTLED);
        }

        advance.markAsSettled(actorUserId);
        teamPaymentAdvanceRepository.save(advance);

        recordSettledAudit(actorUserId, teamId, advance);
        notifyOrgAdminsOfSettlement(advance);
        log.info("立替精算を確認 SETTLED: advanceId={}, teamId={}, confirmedBy={}", advanceId, teamId, actorUserId);
        return advance;
    }

    /**
     * 精算確認の成立を協会(ORG) ADMIN へ軽量通知する（確認必須までは不要・既存 NotificationHelper 経路）。
     *
     * <p>協会 ADMIN 不在・通知失敗は補助チャネルとして握って継続する（精算確定そのものは確定済み）。
     * organization_id が無い立替（過去データ）はスキップする。</p>
     */
    private void notifyOrgAdminsOfSettlement(TeamPaymentAdvanceEntity advance) {
        Long orgId = advance.getOrganizationId();
        if (orgId == null) {
            return;
        }
        try {
            List<Long> orgAdmins = userRoleRepository.findAdminUserIdsByOrganizationId(orgId);
            if (orgAdmins == null || orgAdmins.isEmpty()) {
                log.debug("精算確認通知: 協会 ADMIN 不在のためスキップ orgId={}", orgId);
                return;
            }
            Locale locale = LocaleContextHolder.getLocale();
            String title = messageSource.getMessage(
                    "notification.payment_advance.settled.title", null, "立替金の精算が確認されました", locale);
            String body = messageSource.getMessage(
                    "notification.payment_advance.settled.body",
                    new Object[]{advance.getAdvancedAmount(), advance.getCurrency()},
                    advance.getAdvancedAmount() + " " + advance.getCurrency() + " の立替金が精算済みになりました。",
                    locale);
            String actionUrl = "/organizations/" + orgId + "/payment-requests";
            notificationHelper.notifyAll(
                    orgAdmins,
                    SETTLEMENT_NOTIFICATION_TYPE,
                    title,
                    body,
                    SETTLEMENT_NOTIFICATION_SOURCE_TYPE,
                    null,
                    NotificationScopeType.ORGANIZATION,
                    orgId,
                    actionUrl,
                    advance.getSettledConfirmedBy());
        } catch (RuntimeException e) {
            // 通知は補助チャネル。失敗しても精算確定は確定済みのため握って継続（症状は warn で記録）。
            log.warn("精算確認通知の送信失敗（継続）: advanceId={}, orgId={}, error={}",
                    advance.getId(), orgId, e.getMessage());
        }
    }

    /**
     * チームの立替/精算一覧を取得する（チーム ADMIN の閲覧画面の本体・Controller は第二波）。
     *
     * @param teamId      チーム ID
     * @param actorUserId 操作者（チーム ADMIN）
     * @return 立替/精算記録（新しい順）
     */
    @Transactional(readOnly = true)
    public List<TeamPaymentAdvanceEntity> findForTeam(Long teamId, Long actorUserId) {
        requireTeamAdmin(actorUserId, teamId);
        return teamPaymentAdvanceRepository.findByTeamIdAndDeletedAtIsNullOrderByAdvancedAtDesc(teamId);
    }

    /**
     * 操作者が当該チームの ADMIN/DEPUTY_ADMIN であることを要求する。違反時は権限なし（403）。
     *
     * <p>{@link AccessControlService#checkAdminOrAbove} の {@code COMMON_002}（403）を、本ドメインの
     * {@link MembershipBillingErrorCode#PAYMENT_REQUEST_NOT_FOR_THIS_TEAM}（403）へ正規化して返す
     * （払い手向け文言・IDOR 秘匿）。</p>
     */
    private void requireTeamAdmin(Long actorUserId, Long teamId) {
        try {
            accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TYPE_TEAM);
        } catch (BusinessException e) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM, e);
        }
    }

    private void recordSettledAudit(Long actorUserId, Long teamId, TeamPaymentAdvanceEntity advance) {
        String metadata = String.format("{\"advanceId\":\"%s\",\"paymentRequestId\":\"%s\"}",
                advance.getId(), advance.getPaymentRequestId());
        auditLogService.record(AuditEventType.PAYMENT_ADVANCE_SETTLED.name(), actorUserId, null,
                teamId, advance.getOrganizationId(), null, null, null, metadata);
    }
}
