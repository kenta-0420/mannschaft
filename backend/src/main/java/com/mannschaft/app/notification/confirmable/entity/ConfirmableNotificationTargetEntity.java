package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * CMP-260920-1040 F04.9 確認通知の送信時点の宛先ターゲット（軍議第8版確定稿 §3.1）。
 *
 * <p>送信時点の宛先指定を凍結して保存する（ワーカーの再開・監査用）。
 * クロスドメイン FK は張らない（{@code confirmable_notification_id} は同一ドメイン内参照）。</p>
 */
@Entity
@Table(name = "confirmable_notification_targets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ConfirmableNotificationTargetEntity extends UuidV7Entity {

    @Column(name = "confirmable_notification_id", nullable = false)
    private Long confirmableNotificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ConfirmableTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /**
     * CI是正（CMP-260920-1040）: 引数なし {@code LocalDateTime.now()} を使う {@code @PrePersist} の
     * 代わりに Hibernate の {@link CreationTimestamp}（JVM既定ゾーン基準）を使う
     * （docs/architecture/datetime_policy_utc_instant_vs_wallclock.md 是正・番人
     * {@code DateTimeAndZoneGuardTest} 新規クラス違反の根治）。
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
