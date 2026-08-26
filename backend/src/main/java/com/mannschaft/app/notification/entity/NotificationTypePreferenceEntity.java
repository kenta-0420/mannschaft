package com.mannschaft.app.notification.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 通知種別設定エンティティ。通知種別単位の受信設定を管理する。
 *
 * <p><b>ハイブリッド方式（2026-06-21 改訂・F04.3 §3）</b>:</p>
 * <ul>
 *   <li>{@code channelOverride = false}（既定・単一モード）: {@code isEnabled} で受信可否を決め、
 *       配信チャネルは優先度自動配信（{@code notification_settings.priority_auto_delivery}）に従う。</li>
 *   <li>{@code channelOverride = true}（Dual モード）: {@code inAppEnabled} / {@code pushEnabled} で
 *       チャネルを直接制御する（手動優先・自動配信は適用しない）。</li>
 * </ul>
 */
@Entity
@Table(name = "notification_type_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class NotificationTypePreferenceEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(length = 50)
    private String notificationType;

    /** 単一モード（{@code channelOverride=false}）での受信可否。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /** Dual（チャネル個別）モードに展開しているか。false=単一モード、true=Dual モード。 */
    @Column(name = "channel_override", nullable = false)
    @Builder.Default
    private Boolean channelOverride = false;

    /** Dual モード時のアプリ内（WebSocket）配信の可否。 */
    @Column(name = "in_app_enabled", nullable = false)
    @Builder.Default
    private Boolean inAppEnabled = true;

    /** Dual モード時のプッシュ（PWA Push）配信の可否。 */
    @Column(name = "push_enabled", nullable = false)
    @Builder.Default
    private Boolean pushEnabled = true;

    /**
     * 単一モードの有効/無効を更新する。
     *
     * @param enabled 有効にする場合 true
     */
    public void updateEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    /**
     * ハイブリッド設定（単一/Dual）を一括更新する。
     *
     * <p>{@code toBuilder=true} の再構築による id 欠落バグ
     * （{@code project_tobuilder_update_corruption_campaign}）を避けるため、
     * フィールドを直接ミューテートして更新する。</p>
     *
     * @param channelOverride Dual モードか
     * @param isEnabled       単一モードの受信可否
     * @param inAppEnabled    Dual モードのアプリ内配信可否
     * @param pushEnabled     Dual モードのプッシュ配信可否
     */
    public void updateHybrid(boolean channelOverride, boolean isEnabled,
                             boolean inAppEnabled, boolean pushEnabled) {
        this.channelOverride = channelOverride;
        this.isEnabled = isEnabled;
        this.inAppEnabled = inAppEnabled;
        this.pushEnabled = pushEnabled;
    }
}
