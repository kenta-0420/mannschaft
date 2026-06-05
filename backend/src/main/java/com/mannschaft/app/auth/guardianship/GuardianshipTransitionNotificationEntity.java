package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F08.9 P3c-3 自立移行通知の送信記録（重複送信防止・02_api_design §2.3）。
 *
 * <p>自立移行の保険として日次で走る 2 つのバッチ（進学予告／封印時未設定メール）の送信を
 * 記録し、同一（受信者×子×封印境界日×種別）で 1 回だけ送ることを保証する。
 * 各バッチは送信前に {@code existsBy...} で既送信を確認し、送信後に本レコードを保存する。</p>
 *
 * <p>設計原則:</p>
 * <ul>
 *   <li>原則1: クロスドメイン FK なし（{@code recipientUserId} / {@code childUserId} は論理参照）。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 * </ul>
 *
 * <p>UNIQUE 制約 {@code uk_gtn_dedup(notification_kind, recipient_user_id, child_user_id, seal_date)}
 * が二重防御として効く（並行実行や時刻境界で {@code existsBy} 後に挿入が競合しても DB が弾く）。</p>
 */
@Entity
@Table(name = "guardianship_transition_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(callSuper = true)
public class GuardianshipTransitionNotificationEntity extends UuidV7Entity {

    /** 通知種別（進学予告／封印時未設定メール）。冪等キーの一部。 */
    @Column(name = "notification_kind", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private GuardianshipTransitionNotificationKind notificationKind;

    /** 通知の宛先ユーザーID（進学予告＝保護者／封印時メール＝子本人）。論理参照・FK なし。 */
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    /** 対象の子ユーザーID。論理参照・FK なし。 */
    @Column(name = "child_user_id", nullable = false)
    private Long childUserId;

    /** 封印境界日（{@link GuardianshipAgePolicy#sealDate}）。冪等キーの一部。 */
    @Column(name = "seal_date", nullable = false)
    private LocalDate sealDate;

    /** 送信記録作成日時（UTC）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
