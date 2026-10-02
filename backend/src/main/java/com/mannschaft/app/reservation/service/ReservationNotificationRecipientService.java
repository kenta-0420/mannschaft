package com.mannschaft.app.reservation.service;

import com.mannschaft.app.billing.EntitlementQueryService;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateNotificationRecipientRequest;
import com.mannschaft.app.reservation.dto.NotificationRecipientListResponse;
import com.mannschaft.app.reservation.dto.NotificationRecipientResponse;
import com.mannschaft.app.reservation.dto.UpdateNotificationRecipientRequest;
import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import com.mannschaft.app.reservation.repository.ReservationNotificationRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 予約通知メール宛先サービス（機能D）。
 *
 * <p>チーム単位の宛先 CRUD と<b>フリーミアム件数ゲート（BE 強制）</b>を担う。
 * 件数は<b>有効・無効を問わず全登録行</b>で数える（{@code countByTeamId}）。</p>
 *
 * <ul>
 *   <li>{@code count >= MAX_RECIPIENT_LIMIT(10)} → {@code RESERVATION_028}（上限超過・400）</li>
 *   <li>{@code count >= FREE_RECIPIENT_LIMIT(3)} かつ {@code !hasPaidPlan} → {@code RESERVATION_029}（有料必須・402）</li>
 *   <li>同一チームで {@code email} 重複 → {@code RESERVATION_030}（409・DB {@code UNIQUE(team_id,email)} が最終防御）</li>
 * </ul>
 *
 * <p><b>登録時ゲート方針</b>: 有料→無料ダウングレードでも登録済み宛先は剥奪しない
 * （既に登録済みの4件目以降もそのまま全件送出される）。件数ゲートは追加時のみ効く。</p>
 *
 * <p>F20.1: 4 件目以降ゲート（RESERVATION_029）の有料判定は
 * {@link EntitlementQueryService#isEntitled}（feature_key=
 * {@code reservation.notification_recipients_extended}）に置換済み。
 * 既存有料チームは後方互換ブリッジ（team_subscriptions ACTIVE → FULL 契約 → plan_features 全キー）で
 * extended entitlement を保持するため機能は失われない。エラーコード・HTTP ステータス（402）は不変。</p>
 *
 * <p>{@code TeamPlanService.hasPaidPlan} は一覧表示の {@code hasPaidPlan} フラグ（表示互換）でのみ使用。
 * billing / payment ドメインのサービス呼び出しは越境規制外（service 参照は許容・D-3）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationNotificationRecipientService {

    /** 無料プランで登録できる宛先の上限件数。 */
    public static final int FREE_RECIPIENT_LIMIT = 3;

    /** 有料プランでの宛先の最大登録件数。 */
    public static final int MAX_RECIPIENT_LIMIT = 10;

    private final ReservationNotificationRecipientRepository recipientRepository;
    private final TeamPlanService teamPlanService;
    private final EntitlementQueryService entitlementQueryService;

    /**
     * チームの宛先一覧＋フリーミアム状態を返す。
     *
     * @param teamId チームID
     * @return 宛先一覧（有効・無効を含む）＋件数・上限・有料状態
     */
    public NotificationRecipientListResponse listRecipients(Long teamId) {
        List<ReservationNotificationRecipientEntity> recipients =
                recipientRepository.findByTeamIdOrderByCreatedAtAsc(teamId);
        int totalCount = recipients.size();
        int enabledCount = (int) recipients.stream()
                .filter(ReservationNotificationRecipientEntity::getIsEnabled)
                .count();
        boolean hasPaidPlan = teamPlanService.hasPaidPlan(teamId);

        return NotificationRecipientListResponse.builder()
                .recipients(recipients.stream().map(this::toResponse).toList())
                .enabledCount(enabledCount)
                .totalCount(totalCount)
                .freeLimit(FREE_RECIPIENT_LIMIT)
                .maxLimit(MAX_RECIPIENT_LIMIT)
                .hasPaidPlan(hasPaidPlan)
                .build();
    }

    /**
     * 宛先を追加する（フリーミアム件数ゲート・重複 409）。
     *
     * <p>ゲート判定順（設計 §4.D）: 上限10件（028）→ 無料3件超（029）→ email 重複（030）。
     * {@code isEnabled} は {@code null → true} に正規化する。</p>
     *
     * @param teamId    チームID
     * @param request   作成リクエスト
     * @param createdBy 登録者 user_id
     * @return 作成された宛先
     */
    @Transactional
    public NotificationRecipientResponse addRecipient(
            Long teamId, CreateNotificationRecipientRequest request, Long createdBy) {
        long count = recipientRepository.countByTeamId(teamId);

        // (1) 上限 10 件超過 → 400（有料でも 10 件超は不可）。
        if (count >= MAX_RECIPIENT_LIMIT) {
            throw new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_LIMIT_EXCEEDED);
        }
        // (2) 無料プランで 4 件目以降 → 402（F20.1: 有料判定を isEntitled に置換・エラーコードは不変）。
        if (count >= FREE_RECIPIENT_LIMIT && !entitlementQueryService.isEntitled(
                EntitlementScopeKind.TEAM, teamId, FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED)) {
            throw new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED);
        }
        // (3) 同一チームで email 重複 → 409（アプリ層の事前チェック）。
        if (recipientRepository.existsByTeamIdAndEmail(teamId, request.getEmail())) {
            throw new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_DUPLICATE);
        }

        // isEnabled は final DTO では既定を表現できないため Service 層で null→true 正規化する。
        boolean enabled = request.getIsEnabled() == null || request.getIsEnabled();

        ReservationNotificationRecipientEntity entity = ReservationNotificationRecipientEntity.builder()
                .teamId(teamId)
                .email(request.getEmail())
                .label(request.getLabel())
                .isEnabled(enabled)
                .createdBy(createdBy)
                .build();

        try {
            ReservationNotificationRecipientEntity saved = recipientRepository.saveAndFlush(entity);
            log.info("予約通知メール宛先を追加: teamId={}, recipientId={}, enabled={}",
                    teamId, saved.getId(), enabled);
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE(team_id, email) 違反 = 競合登録の最終防御（並行 POST 等）。
            throw new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_DUPLICATE, ex);
        }
    }

    /**
     * 宛先を部分更新する（{@code label} / {@code isEnabled}）。
     *
     * @param teamId      チームID
     * @param recipientId 宛先ID
     * @param request     更新リクエスト
     * @return 更新後の宛先
     */
    @Transactional
    public NotificationRecipientResponse updateRecipient(
            Long teamId, UUID recipientId, UpdateNotificationRecipientRequest request) {
        ReservationNotificationRecipientEntity entity = recipientRepository
                .findByIdAndTeamId(recipientId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_NOT_FOUND));

        entity.updateRecipient(request.getLabel(), request.getIsEnabled());
        ReservationNotificationRecipientEntity saved = recipientRepository.save(entity);
        log.info("予約通知メール宛先を更新: teamId={}, recipientId={}, enabled={}",
                teamId, saved.getId(), saved.getIsEnabled());
        return toResponse(saved);
    }

    /**
     * 宛先を物理削除する（件数ゲートのカウントも減る＝空いた枠に再登録可能）。
     *
     * @param teamId      チームID
     * @param recipientId 宛先ID
     */
    @Transactional
    public void deleteRecipient(Long teamId, UUID recipientId) {
        ReservationNotificationRecipientEntity entity = recipientRepository
                .findByIdAndTeamId(recipientId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOTIFY_RECIPIENT_NOT_FOUND));
        recipientRepository.delete(entity);
        log.info("予約通知メール宛先を削除: teamId={}, recipientId={}", teamId, recipientId);
    }

    private NotificationRecipientResponse toResponse(ReservationNotificationRecipientEntity entity) {
        return NotificationRecipientResponse.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .label(entity.getLabel())
                .isEnabled(entity.getIsEnabled())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
