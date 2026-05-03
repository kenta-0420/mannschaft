package com.mannschaft.app.proxy.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 代理入力の本人同意書エンティティ（F14.1）。
 * スマートフォン・PCを持たない住民の代わりに管理員等が代理入力する際の同意記録。
 * 本人同意・職務分掌・監査ログを必須とし、最長1年の有効期限を持つ。
 */
@Entity
@Table(name = "proxy_input_consents")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyInputConsentEntity extends BaseEntity {

    /** 代理される本人のユーザーID。 */
    @Column(nullable = false)
    private Long subjectUserId;

    /** 代理者のユーザーID（管理員・副理事・支援者等）。 */
    @Column(nullable = false)
    private Long proxyUserId;

    /** 同意の有効範囲となる組合ID。 */
    @Column(nullable = false)
    private Long organizationId;

    /** 同意取得方法。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ConsentMethod consentMethod;

    /** 同意書スキャンPDFのS3オブジェクトキー（presigned URLは閲覧時に都度発行・5分TTL）。 */
    @Column(length = 512)
    private String scannedDocumentS3Key;

    /** GUARDIAN_BY_COURT時の後見登記事項証明書S3キー。 */
    @Column(length = 512)
    private String guardianCertificateS3Key;

    /** WITNESSED_ORAL時の立会人ユーザーID（ADMIN必須）。 */
    private Long witnessUserId;

    /** 有効開始日。 */
    @Column(nullable = false)
    private LocalDate effectiveFrom;

    /** 有効期限（最長1年・更新要）。 */
    @Column(nullable = false)
    private LocalDate effectiveUntil;

    /** 撤回日時。NULLの場合は有効（または未承認）。 */
    private LocalDateTime revokedAt;

    /** 撤回方法。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RevokeMethod revokeMethod;

    /** 紙の撤回届を代行入力したADMINのユーザーID。 */
    private Long revokeWitnessedByUserId;

    /** 撤回理由。 */
    @Column(length = 255)
    private String revokeReason;

    /** 承認したADMINのユーザーID（proxyUserIdとは異なる必要がある＝自己承認禁止）。 */
    private Long approvedByUserId;

    /** 承認日時。NULLの場合は承認待ち（PENDING_APPROVAL）。 */
    private LocalDateTime approvedAt;

    /** 論理削除日時。 */
    private LocalDateTime deletedAt;

    /** この同意書で許可された機能スコープ一覧。 */
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    @jakarta.persistence.JoinColumn(name = "proxy_input_consent_id")
    @Builder.Default
    private List<ProxyInputConsentScopeEntity> scopes = new ArrayList<>();

    /**
     * 同意書を生成するファクトリーメソッド。
     */
    public static ProxyInputConsentEntity create(
            Long subjectUserId,
            Long proxyUserId,
            Long organizationId,
            ConsentMethod consentMethod,
            String scannedDocumentS3Key,
            String guardianCertificateS3Key,
            Long witnessUserId,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil) {
        return ProxyInputConsentEntity.builder()
                .subjectUserId(subjectUserId)
                .proxyUserId(proxyUserId)
                .organizationId(organizationId)
                .consentMethod(consentMethod)
                .scannedDocumentS3Key(scannedDocumentS3Key)
                .guardianCertificateS3Key(guardianCertificateS3Key)
                .witnessUserId(witnessUserId)
                .effectiveFrom(effectiveFrom)
                .effectiveUntil(effectiveUntil)
                .build();
    }

    /**
     * 同意書を承認する。自己承認はService層で禁止すること。
     */
    public void approve(Long approvedByUserId) {
        this.approvedByUserId = approvedByUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 同意書を撤回する。
     */
    public void revoke(RevokeMethod revokeMethod, Long revokeWitnessedByUserId, String revokeReason) {
        this.revokedAt = LocalDateTime.now();
        this.revokeMethod = revokeMethod;
        this.revokeWitnessedByUserId = revokeWitnessedByUserId;
        this.revokeReason = revokeReason;
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 有効な同意書かどうかを判定する。
     * DB側でも検証するが、Service層の二重チェックとして使用する。
     */
    public boolean isActive() {
        LocalDate today = LocalDate.now();
        return approvedAt != null
                && revokedAt == null
                && !effectiveFrom.isAfter(today)
                && !effectiveUntil.isBefore(today);
    }

    /**
     * 同意取得方法
     */
    public enum ConsentMethod {
        /** 紙の同意書に署名・捺印 */
        PAPER_SIGNED,
        /** ADMIN立会のもとでの口頭同意 */
        WITNESSED_ORAL,
        /** 電子署名 */
        DIGITAL_SIGNATURE,
        /** 法定後見人による同意（後見登記事項証明書必須） */
        GUARDIAN_BY_COURT
    }

    /**
     * 撤回方法
     */
    public enum RevokeMethod {
        /** 本人がAPIから撤回 */
        API_BY_SUBJECT,
        /** 紙の撤回届をADMINが代行入力 */
        PAPER_BY_SUBJECT,
        /** ライフイベント（DECEASED/RELOCATED）による自動失効 */
        AUTO_BY_LIFE_EVENT,
        /** 任期終了による自動失効 */
        AUTO_BY_TENURE_END
    }
}
