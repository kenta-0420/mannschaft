package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationSettingsEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F04.9 確認通知設定サービス。
 *
 * <p>スコープ（チーム・組織）ごとのリマインド設定・アラート閾値を管理する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationSettingsService {

    private final ConfirmableNotificationSettingsRepository settingsRepository;

    /**
     * スコープの確認通知設定を取得する。存在しない場合はデフォルト値でレコードを作成して返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知設定エンティティ（既存または新規作成）
     */
    @Transactional
    public ConfirmableNotificationSettingsEntity getOrCreate(ScopeType scopeType, Long scopeId) {
        return settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> {
                    // 設定が存在しない場合はデフォルト値でレコードを新規作成
                    ConfirmableNotificationSettingsEntity defaultSettings =
                            ConfirmableNotificationSettingsEntity.builder()
                                    .scopeType(scopeType)
                                    .scopeId(scopeId)
                                    // defaultFirstReminderMinutes: NULL = リマインドなし（デフォルト）
                                    // defaultSecondReminderMinutes: NULL = リマインドなし（デフォルト）
                                    // senderAlertThresholdPercent: 80%（Entity の @Builder.Default で設定済み）
                                    .build();
                    ConfirmableNotificationSettingsEntity saved = settingsRepository.save(defaultSettings);
                    log.info("確認通知設定を新規作成: scopeType={}, scopeId={}", scopeType, scopeId);
                    return saved;
                });
    }

    /**
     * スコープの確認通知設定を更新する。
     *
     * @param scopeType                    スコープ種別
     * @param scopeId                      スコープID
     * @param firstReminderMinutes         1回目リマインド送信タイミング（分）。NULL でリマインドなし
     * @param secondReminderMinutes        2回目リマインド送信タイミング（分）。NULL でリマインドなし
     * @param senderAlertThresholdPercent  送信者アラート閾値（確認率%）
     * @return 更新後の確認通知設定エンティティ
     */
    @Transactional
    public ConfirmableNotificationSettingsEntity update(
            ScopeType scopeType,
            Long scopeId,
            Integer firstReminderMinutes,
            Integer secondReminderMinutes,
            Integer senderAlertThresholdPercent) {

        // 設定が存在しない場合はデフォルト値で作成してから更新する
        ConfirmableNotificationSettingsEntity settings = getOrCreate(scopeType, scopeId);

        ConfirmableNotificationSettingsEntity updated = settings.toBuilder()
                .defaultFirstReminderMinutes(firstReminderMinutes)
                .defaultSecondReminderMinutes(secondReminderMinutes)
                .senderAlertThresholdPercent(
                        senderAlertThresholdPercent != null ? senderAlertThresholdPercent : 80)
                .build();

        ConfirmableNotificationSettingsEntity saved = settingsRepository.save(updated);
        log.info("確認通知設定を更新: scopeType={}, scopeId={}, firstReminder={}, secondReminder={}, alertThreshold={}",
                scopeType, scopeId, firstReminderMinutes, secondReminderMinutes, senderAlertThresholdPercent);
        return saved;
    }
}
