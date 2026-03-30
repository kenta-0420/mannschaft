package com.mannschaft.app.signage.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * デジタルサイネージ 画面エンティティ。
 */
@Entity
@Table(name = "signage_screens")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SignageScreenEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SignageLayout layout = SignageLayout.LANDSCAPE;

    @Column(nullable = false)
    @Builder.Default
    private Integer defaultSlideDuration = 10;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SignageTransitionEffect transitionEffect = SignageTransitionEffect.FADE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isClockShown = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isWeatherShown = false;

    @Column(length = 200)
    private String weatherLocation;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String backgroundColor = "#000000";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 画面を無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 画面を有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
