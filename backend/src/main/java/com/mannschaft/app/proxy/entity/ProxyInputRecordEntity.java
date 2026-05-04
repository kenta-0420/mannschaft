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
 * 代理入力の実行ログエンティティ（F14.1）。
 * 代理入力1件ごとに必ず1レコードを作成する。集計分離専用の最小列構成。
 * 人間可読ログはaudit_logsに一本化し、本テーブルは重複保持しない。
 * 追記専用（更新・削除不可）。物理削除は保管期限経過後バッチで実施。
 */
@Entity
@Table(name = "proxy_input_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ProxyInputRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 根拠となる同意書ID（FK→proxy_input_consents.id）。 */
    @Column(nullable = false, updatable = false)
    private Long proxyInputConsentId;

    /** 本人ユーザーID（集計用冗長保持）。 */
    @Column(nullable = false, updatable = false)
    private Long subjectUserId;

    /** 代理者ユーザーID。 */
    @Column(nullable = false, updatable = false)
    private Long proxyUserId;

    /** 操作対象機能スコープ（FeatureScopeのname値と連動）。 */
    @Column(nullable = false, length = 64, updatable = false)
    private String featureScope;

    /** 操作対象エンティティ種別（例: SURVEY_RESPONSE）。 */
    @Column(nullable = false, length = 64, updatable = false)
    private String targetEntityType;

    /** 操作対象レコードID。 */
    @Column(nullable = false, updatable = false)
    private Long targetEntityId;

    /** 入力元（紙・電話・対面）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private InputSource inputSource;

    /** 紙原本の保管場所（紛争時の証跡として必須）。 */
    @Column(nullable = false, length = 255, updatable = false)
    private String originalStorageLocation;

    /** 同期作成されたaudit_logs.idへの参照。 */
    @Column(updatable = false)
    private Long auditLogId;

    /** 保管期限満了日（createdAt + 5年で自動計算、DBのSTORED生成列）。 */
    @Column(name = "retention_expires_at", insertable = false, updatable = false)
    private java.time.LocalDate retentionExpiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 代理入力記録のファクトリーメソッド。
     */
    public static ProxyInputRecordEntity create(
            Long proxyInputConsentId,
            Long subjectUserId,
            Long proxyUserId,
            String featureScope,
            String targetEntityType,
            Long targetEntityId,
            InputSource inputSource,
            String originalStorageLocation) {
        return ProxyInputRecordEntity.builder()
                .proxyInputConsentId(proxyInputConsentId)
                .subjectUserId(subjectUserId)
                .proxyUserId(proxyUserId)
                .featureScope(featureScope)
                .targetEntityType(targetEntityType)
                .targetEntityId(targetEntityId)
                .inputSource(inputSource)
                .originalStorageLocation(originalStorageLocation)
                .build();
    }

    /**
     * audit_log_idを設定する（同一トランザクション内でaudit_logs保存後に呼び出す）。
     */
    public void setAuditLogId(Long auditLogId) {
        this.auditLogId = auditLogId;
    }

    /**
     * 代理入力の入力元
     */
    public enum InputSource {
        /** 紙の申込書・アンケート用紙から転記 */
        PAPER_FORM,
        /** 電話ヒアリングによる聴取 */
        PHONE_INTERVIEW,
        /** 対面での直接聴取 */
        IN_PERSON
    }
}
