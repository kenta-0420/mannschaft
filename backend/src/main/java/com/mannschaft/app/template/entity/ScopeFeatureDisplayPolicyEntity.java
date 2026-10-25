package com.mannschaft.app.template.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.template.FeatureDisplayScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** スコープと一般機能ごとの独立したMEMBER/SUPPORTER表示設定。認可には使用しない。 */
@Entity
@Table(name = "scope_feature_display_policies", uniqueConstraints =
        @UniqueConstraint(name = "uq_sfdp_scope_feature", columnNames = {"scope_type", "scope_id", "feature_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScopeFeatureDisplayPolicyEntity extends UuidV7Entity {
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private FeatureDisplayScope scopeType;
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;
    @Column(name = "feature_key", nullable = false, length = 64)
    private String featureKey;
    @Builder.Default
    @Column(name = "member_visible", nullable = false)
    private boolean memberVisible = true;
    @Builder.Default
    @Column(name = "supporter_visible", nullable = false)
    private boolean supporterVisible = true;
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
