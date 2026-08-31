package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.event.ConfirmableNotificationCreatedEvent;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知コアサービス（ファサード）。
 *
 * <p>確認通知の送信・確認・キャンセル・詳細取得などの業務ロジックを提供する公開エントリポイント。
 * 実装はリファクタリング第9弾で以下の 3 クラスに分割されており、本クラスはファサードとして
 * 既存呼び出し元（Controller / 他ドメイン Service）の public シグネチャを完全に維持する。</p>
 *
 * <ul>
 *   <li>本クラス: 送信処理（{@code send}）— 通知作成・受信者バッチ登録・課金・イベント発行</li>
 *   <li>{@link ConfirmableNotificationConfirmService}: 確認・キャンセル・リマインド再送</li>
 *   <li>{@link ConfirmableNotificationQueryService}: 詳細・一覧の参照（読込専用）</li>
 * </ul>
 *
 * <p><b>リマインド分数の3段フォールバック解決ロジック</b>:
 * <ol>
 *   <li>通知個別設定（引数の firstReminderMinutes）</li>
 *   <li>スコープ設定（settings.getDefaultFirstReminderMinutes()）</li>
 *   <li>システムデフォルト（1回目: 180分 / 2回目: 120分）</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationService {

    /** リマインド分数のシステムデフォルト値（1回目） */
    private static final int DEFAULT_FIRST_REMINDER_MINUTES = 180;

    /** リマインド分数のシステムデフォルト値（2回目） */
    private static final int DEFAULT_SECOND_REMINDER_MINUTES = 120;

    /** 受信者リストの最大件数 */
    private static final int MAX_RECIPIENT_COUNT = 500;

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationSettingsService settingsService;
    private final UserRepository userRepository;
    private final NotificationHelper notificationHelper;
    private final ApplicationEventPublisher eventPublisher;
    /** F09.13 通知クレジット消費（確認通知は課金対象） */
    private final NotificationCreditService notificationCreditService;

    /** リファクタリング第9弾で分離された確認系処理（委譲先） */
    private final ConfirmableNotificationConfirmService confirmService;

    /** リファクタリング第9弾で分離された参照系処理（委譲先） */
    private final ConfirmableNotificationQueryService queryService;

    /**
     * 確認通知を送信する。
     *
     * <p>受信者ごとに確認トークン（UUID）を付与し、リマインド分数を3段フォールバックで解決してから
     * {@code confirmable_notification_recipients} に batch INSERT する。
     * 最後に {@link ConfirmableNotificationCreatedEvent} を発行してF04.3通知基盤に引き渡す。</p>
     *
     * <p><b>F04.9 Phase D（未確認者一覧の可視化）</b>:
     * {@code unconfirmedVisibility} が NULL の場合、スコープ設定の
     * {@code defaultUnconfirmedVisibility} を採用する（2段フォールバック）。
     * 解決された値はエンティティにスナップショットされ、後の設定変更による影響を受けない。</p>
     *
     * @param scopeType          スコープ種別
     * @param scopeId            スコープID
     * @param title              通知タイトル
     * @param body               通知本文（任意）
     * @param priority           優先度
     * @param deadlineAt         確認期限（任意）
     * @param firstReminderMinutes  個別1回目リマインド設定（任意）
     * @param secondReminderMinutes 個別2回目リマインド設定（任意）
     * @param actionUrl          アクションURL（任意）
     * @param templateId         使用テンプレートID（任意）
     * @param unconfirmedVisibility 未確認者リスト公開範囲（任意）。NULL時はスコープ設定の default を使用
     * @param createdByUserId    作成者ユーザーID
     * @param recipientUserIds   受信者ユーザーIDリスト（最大500件）
     * @return 作成された確認通知エンティティ
     */
    /**
     * 旧シグネチャ互換のオーバーロード。{@code unconfirmedVisibility} を省略する呼び出し向け。
     * F04.9 Phase D 導入前の呼び出し元（CommitteeDistributionService 等）の互換性のため。
     * 内部で {@code unconfirmedVisibility=null} を渡し、スコープ設定の default に解決させる。
     */
    @Transactional
    public ConfirmableNotificationEntity send(
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            ConfirmableNotificationPriority priority,
            LocalDateTime deadlineAt,
            Integer firstReminderMinutes,
            Integer secondReminderMinutes,
            String actionUrl,
            Long templateId,
            Long createdByUserId,
            List<Long> recipientUserIds) {
        return send(scopeType, scopeId, title, body, priority, deadlineAt,
                firstReminderMinutes, secondReminderMinutes, actionUrl, templateId,
                null, createdByUserId, recipientUserIds);
    }

    @Transactional
    public ConfirmableNotificationEntity send(
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            ConfirmableNotificationPriority priority,
            LocalDateTime deadlineAt,
            Integer firstReminderMinutes,
            Integer secondReminderMinutes,
            String actionUrl,
            Long templateId,
            UnconfirmedVisibility unconfirmedVisibility,
            Long createdByUserId,
            List<Long> recipientUserIds) {
        return send(scopeType, scopeId, title, body, priority, deadlineAt,
                firstReminderMinutes, secondReminderMinutes, actionUrl, templateId,
                unconfirmedVisibility, null, null, createdByUserId, recipientUserIds);
    }

    /**
     * F22.1 市: 発生元（{@code source_type}/{@code source_id}）を明示して確認通知を送信する
     * オーバーロード（01_data_model §5 / 02_api_design §6.1）。
     *
     * <p>市の最終認証では {@code sourceType="MARKET_FINALIZE"}, {@code sourceId=recruitment_listings.id}
     * を渡す。確認応答後のリスナ（{@code MarketFinalizeConfirmedListener}）が source で札を引いて
     * {@code FULL→COMPLETED} 遷移を行う。</p>
     *
     * @param sourceType 発生元種別（例: {@code MARKET_FINALIZE}）
     * @param sourceId   発生元レコードID（例: 札ID）
     * @return 作成された確認通知エンティティ
     */
    @Transactional
    public ConfirmableNotificationEntity sendFromSource(
            String sourceType,
            Long sourceId,
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            ConfirmableNotificationPriority priority,
            LocalDateTime deadlineAt,
            String actionUrl,
            Long createdByUserId,
            List<Long> recipientUserIds) {
        return send(scopeType, scopeId, title, body, priority, deadlineAt,
                null, null, actionUrl, null, null, sourceType, sourceId,
                createdByUserId, recipientUserIds);
    }

    @Transactional
    public ConfirmableNotificationEntity send(
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            ConfirmableNotificationPriority priority,
            LocalDateTime deadlineAt,
            Integer firstReminderMinutes,
            Integer secondReminderMinutes,
            String actionUrl,
            Long templateId,
            UnconfirmedVisibility unconfirmedVisibility,
            String sourceType,
            Long sourceId,
            Long createdByUserId,
            List<Long> recipientUserIds) {

        // 受信者数の上限チェック
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SEND_FAILED);
        }
        if (recipientUserIds.size() > MAX_RECIPIENT_COUNT) {
            log.warn("受信者数が上限を超えています: count={}, max={}", recipientUserIds.size(), MAX_RECIPIENT_COUNT);
            throw new BusinessException(ConfirmableNotificationErrorCode.SEND_FAILED);
        }

        // スコープ設定を取得（存在しない場合はデフォルト値で作成）
        ConfirmableNotificationSettingsEntity settings =
                settingsService.getOrCreate(scopeType, scopeId);

        // -----------------------------------------------------------------------
        // リマインド分数の3段フォールバック解決
        // 1. 通知個別設定（引数の firstReminderMinutes）
        // 2. スコープ設定（settings.getDefaultFirstReminderMinutes()）
        // 3. システムデフォルト（1回目: 180分 / 2回目: 120分）
        // -----------------------------------------------------------------------
        int resolvedFirstReminder = firstReminderMinutes != null ? firstReminderMinutes
                : (settings.getDefaultFirstReminderMinutes() != null
                        ? settings.getDefaultFirstReminderMinutes()
                        : DEFAULT_FIRST_REMINDER_MINUTES);
        int resolvedSecondReminder = secondReminderMinutes != null ? secondReminderMinutes
                : (settings.getDefaultSecondReminderMinutes() != null
                        ? settings.getDefaultSecondReminderMinutes()
                        : DEFAULT_SECOND_REMINDER_MINUTES);

        // -----------------------------------------------------------------------
        // 未確認者リスト公開範囲の2段フォールバック解決（F04.9 Phase D）
        // 1. リクエスト引数（unconfirmedVisibility）
        // 2. スコープ設定（settings.getDefaultUnconfirmedVisibility()）— 未設定時 CREATOR_AND_ADMIN
        // -----------------------------------------------------------------------
        UnconfirmedVisibility resolvedVisibility = unconfirmedVisibility != null
                ? unconfirmedVisibility
                : (settings.getDefaultUnconfirmedVisibility() != null
                        ? settings.getDefaultUnconfirmedVisibility()
                        : UnconfirmedVisibility.CREATOR_AND_ADMIN);

        // 作成者エンティティの取得
        UserEntity createdByUser = userRepository.findById(createdByUserId).orElse(null);

        // 確認通知エンティティ作成
        ConfirmableNotificationEntity.ConfirmableNotificationEntityBuilder builder =
                ConfirmableNotificationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(title)
                .body(body)
                .priority(priority != null ? priority : ConfirmableNotificationPriority.NORMAL)
                .deadlineAt(deadlineAt)
                .firstReminderMinutes(firstReminderMinutes)
                .secondReminderMinutes(secondReminderMinutes)
                .actionUrl(actionUrl)
                .templateId(templateId)
                .unconfirmedVisibility(resolvedVisibility)
                .createdBy(createdByUser)
                .totalRecipientCount(recipientUserIds.size());

        // F22.1 市: 発生元（source_type/source_id）が指定された場合のみ上書きする。
        // 未指定時は @Builder.Default の 'EMERGENCY_CLOSURE' / null を維持（既存呼び出し互換）。
        if (sourceType != null) {
            builder.sourceType(sourceType);
        }
        if (sourceId != null) {
            builder.sourceId(sourceId);
        }

        ConfirmableNotificationEntity notification = builder.build();
        ConfirmableNotificationEntity savedNotification = notificationRepository.save(notification);

        // 受信者エンティティをバッチ作成（saveAll = batch INSERT）
        List<ConfirmableNotificationRecipientEntity> recipients = recipientUserIds.stream()
                .map(userId -> {
                    UserEntity user = userRepository.getReferenceById(userId);
                    return ConfirmableNotificationRecipientEntity.builder()
                            .confirmableNotification(savedNotification)
                            .user(user)
                            // 各受信者に一意の確認トークンを付与
                            .confirmToken(UUID.randomUUID().toString())
                            .resolvedFirstReminderMinutes(resolvedFirstReminder)
                            .resolvedSecondReminderMinutes(resolvedSecondReminder)
                            .build();
                })
                .collect(Collectors.toList());

        recipientRepository.saveAll(recipients);

        log.info("確認通知送信: notificationId={}, scopeType={}, scopeId={}, recipientCount={}",
                savedNotification.getId(), scopeType, scopeId, recipientUserIds.size());

        // F09.13: 確認通知は課金対象（組織スコープのみ）
        // チームスコープの場合は組織IDが不明なためスキップ（将来はteam→org解決を追加）
        if (ScopeType.ORGANIZATION == scopeType) {
            // PLATFORM は組織課金スコープを持たない個人宛・システム確認通知にも使う。
            if (scopeType != ScopeType.PLATFORM) {
                notificationCreditService.consume(scopeId, recipientUserIds.size(), NotificationSourceType.CONFIRMABLE);
            }
        }

        // F04.3 通知基盤へのアプリ内通知（送信者には通知しない）
        NotificationPriority notifPriority = toNotificationPriority(savedNotification.getPriority());
        NotificationScopeType notifScopeType = toNotificationScopeType(scopeType);
        notificationHelper.notifyAll(
                recipientUserIds,
                "CONFIRMABLE_NOTIFICATION",
                notifPriority,
                title,
                body != null ? body : "",
                "CONFIRMABLE_NOTIFICATION",
                savedNotification.getId(),
                notifScopeType,
                scopeId,
                actionUrl,
                createdByUserId);

        // ConfirmableNotificationCreatedEvent を発行
        eventPublisher.publishEvent(new ConfirmableNotificationCreatedEvent(
                savedNotification.getId(),
                scopeType,
                scopeId,
                recipientUserIds));

        return savedNotification;
    }

    /**
     * 認証済みユーザーがアプリ内から確認通知を確認する。
     *
     * <p>実装は {@link ConfirmableNotificationConfirmService#confirm(Long, Long)} に委譲。</p>
     *
     * @param notificationId 確認通知ID
     * @param userId         確認するユーザーID
     */
    @Transactional
    public void confirm(Long notificationId, Long userId) {
        confirmService.confirm(notificationId, userId);
    }

    /**
     * トークンURL経由で確認通知を確認する（認証不要）。
     *
     * <p>実装は {@link ConfirmableNotificationConfirmService#confirmByToken(String)} に委譲。</p>
     *
     * @param confirmToken 確認トークン（UUID文字列）
     */
    @Transactional
    public void confirmByToken(String confirmToken) {
        confirmService.confirmByToken(confirmToken);
    }

    /**
     * 確認通知をキャンセルする（ADMIN操作）。
     *
     * <p>実装は {@link ConfirmableNotificationConfirmService#cancel(Long, Long)} に委譲。</p>
     *
     * @param notificationId    確認通知ID
     * @param cancelledByUserId キャンセル実行者のユーザーID
     */
    @Transactional
    public void cancel(Long notificationId, Long cancelledByUserId) {
        confirmService.cancel(notificationId, cancelledByUserId);
    }

    /**
     * 手動リマインドを再送する（ADMIN操作）。
     *
     * <p>ACTIVE 状態の通知に対して、未確認の受信者全員にリマインドを再送する。
     * 実装は {@link ConfirmableNotificationConfirmService#resendReminder(Long)} に委譲。</p>
     *
     * @param notificationId 確認通知ID
     */
    @Transactional
    public void resendReminder(Long notificationId) {
        confirmService.resendReminder(notificationId);
    }

    /**
     * 確認通知の詳細を取得する。
     *
     * <p>実装は {@link ConfirmableNotificationQueryService#getDetail(Long)} に委譲。</p>
     *
     * @param notificationId 確認通知ID
     * @return 確認通知エンティティ
     */
    @Transactional(readOnly = true)
    public ConfirmableNotificationEntity getDetail(Long notificationId) {
        return queryService.getDetail(notificationId);
    }

    /**
     * 確認通知の受信者一覧を取得する（ADMIN+ 用・全件）。
     *
     * <p>呼び出し側で ADMIN+ 権限チェック済みであること。
     * F04.9 Phase D の MEMBER 視点アクセスは
     * {@link #getRecipientsForMember(Long, Long)} を使用すること。
     * 実装は {@link ConfirmableNotificationQueryService#getRecipients(Long)} に委譲。</p>
     *
     * @param notificationId 確認通知ID
     * @return 受信者エンティティリスト（除外者・確認済みも含む全件）
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> getRecipients(Long notificationId) {
        return queryService.getRecipients(notificationId);
    }

    /**
     * MEMBER 視点で確認通知の未確認者一覧を取得する（F04.9 Phase D）。
     *
     * <p>実装は {@link ConfirmableNotificationQueryService#getRecipientsForMember(Long, Long)} に委譲。</p>
     *
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @return 未確認受信者エンティティリスト（マスク前）
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> getRecipientsForMember(
            Long notificationId, Long requesterUserId) {
        return queryService.getRecipientsForMember(notificationId, requesterUserId);
    }

    /**
     * スコープ内の確認通知一覧を取得する（作成日時降順）。
     *
     * <p>実装は {@link ConfirmableNotificationQueryService#listByScope(ScopeType, Long)} に委譲。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知エンティティリスト
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationEntity> listByScope(ScopeType scopeType, Long scopeId) {
        return queryService.listByScope(scopeType, scopeId);
    }

    /**
     * ユーザーの未確認通知一覧を取得する（受信者視点）。
     *
     * <p>実装は {@link ConfirmableNotificationQueryService#listPending(Long)} に委譲。</p>
     *
     * @param userId ユーザーID
     * @return 未確認受信者エンティティリスト
     */
    @Transactional(readOnly = true)
    public List<ConfirmableNotificationRecipientEntity> listPending(Long userId) {
        return queryService.listPending(userId);
    }

    // =========================================================================
    // プライベートヘルパーメソッド（send 内部で使用）
    // =========================================================================

    /**
     * 確認通知の優先度を F04.3 通知基盤の優先度に変換する。
     */
    private NotificationPriority toNotificationPriority(ConfirmableNotificationPriority priority) {
        return switch (priority) {
            case URGENT -> NotificationPriority.URGENT;
            case HIGH -> NotificationPriority.HIGH;
            case NORMAL -> NotificationPriority.NORMAL;
        };
    }

    /**
     * ScopeType を NotificationScopeType に変換する。
     */
    private NotificationScopeType toNotificationScopeType(ScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PLATFORM -> NotificationScopeType.SYSTEM;
            case COMMITTEE -> NotificationScopeType.COMMITTEE;
        };
    }
}
