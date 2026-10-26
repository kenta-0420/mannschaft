package com.mannschaft.app.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * F20.1 課金・エンタイトルメント基盤: プラン→機能の展開表（{@code plan_features}）。
 *
 * <p>マスタ例外（自然キー複合）。{@code plan_key} への FK は同一ドメイン内のため CASCADE 可
 * （{@code plans} が親・DDL 側で {@code ON DELETE CASCADE}）。{@code feature_key} への FK は
 * 張らない（{@code feature_catalog} 側の運用入替を妨げない・整合はシスアド CRUD のアプリ層検証・
 * 設計書 01 §2.3）。</p>
 *
 * <p>このフェーズでは Entity/Repo 骨格のみ（Service/Controller は別部隊）。</p>
 *
 * <p>設計書: docs/features/F20.1_entitlement_billing/01_data_model.md §2.3</p>
 */
@Entity
@Table(name = "plan_features")
@IdClass(PlanFeatureId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
public class PlanFeatureEntity {

    @Id
    @Column(name = "plan_key", nullable = false, length = 32)
    private String planKey;

    @Id
    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
