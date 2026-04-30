package com.mannschaft.app.proxy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 同意書ごとの許可機能スコープエンティティ（F14.1）。
 * 代理入力を許可する機能の範囲を同意書単位で管理する。
 */
@Entity
@Table(name = "proxy_input_consent_scopes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProxyInputConsentScopeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK→proxy_input_consents.id */
    @Column(nullable = false)
    private Long proxyInputConsentId;

    /** 許可された機能スコープ。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private FeatureScope featureScope;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * スコープエンティティのファクトリーメソッド。
     */
    public static ProxyInputConsentScopeEntity create(Long proxyInputConsentId, FeatureScope featureScope) {
        return ProxyInputConsentScopeEntity.builder()
                .proxyInputConsentId(proxyInputConsentId)
                .featureScope(featureScope)
                .build();
    }

    /**
     * 代理入力を許可する機能スコープ
     */
    public enum FeatureScope {
        /** アンケート（F05.4） */
        SURVEY,
        /** 出欠回答（F03.1 / F03.8） */
        SCHEDULE_ATTENDANCE,
        /** シフト希望（F03.5）: consent_method=PAPER_SIGNED必須 */
        SHIFT_REQUEST,
        /** お知らせ既読確認（F02.6 / F04.9）: 既読率には含めない */
        ANNOUNCEMENT_READ,
        /** 駐車場申請（F09.3） */
        PARKING_APPLICATION,
        /** 回覧（F05.2） */
        CIRCULAR,
        /** 家族・見守り者の閲覧権限（SUPPORTER用） */
        SUPPORTER_VIEW
    }
}
