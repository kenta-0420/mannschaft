package com.mannschaft.app.disclosure.entity;

import com.mannschaft.app.disclosure.DisclosureOutputFormat;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 重要事項説明書 出力履歴エンティティ。
 * 生成された PDF/Excel/Word の永続記録。
 * F09.14 設計書 §3 disclosure_exports テーブル定義に対応。
 *
 * <p>BaseEntity を継承せず、{@code created_at} のみ持つ独立エンティティ
 * （{@code updated_at} 不要、出力後は不変。{@code deleted_at} は論理削除用）。
 * F09.13 {@link com.mannschaft.app.property.entity.PropertyWorkDocumentEntity} と同パターン。</p>
 */
@Entity
@Table(name = "disclosure_exports")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DisclosureExportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /** 元ドラフト ID。ON DELETE SET NULL。 */
    private Long draftId;

    /** 出力時の様式 ID。ON DELETE RESTRICT。 */
    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 50)
    private String templateCodeSnapshot;

    @Column(nullable = false, length = 20)
    private String templateVersionSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DisclosureOutputFormat outputFormat;

    /** 生成物の保存先 shared_files.id。ON DELETE RESTRICT（履歴保持優先）。 */
    @Column(nullable = false)
    private Long sharedFileId;

    /** dwelling_units.id（住戸単位の場合のみ）。ON DELETE SET NULL。 */
    private Long targetDwellingUnitId;

    @Column(nullable = false)
    private Long requesterUserId;

    /** 提出先メモ（例: 「○○仲介株式会社 山田様」）。 */
    @Column(length = 500)
    private String recipientNote;

    /** 引用された property_work_packages.id 配列 JSON。 */
    @Column(columnDefinition = "JSON")
    private String referencedPackageIds;

    /** 引用された dwelling_units.id 配列 JSON。 */
    @Column(columnDefinition = "JSON")
    private String referencedDwellingUnitIds;

    /** 出力時の入力データスナップショット JSON（事後検証・GDPR マスキング対象）。 */
    @Column(columnDefinition = "JSON", nullable = false)
    private String dataSnapshot;

    /**
     * 出力ファイル（PDF/Excel/Word）の SHA-256（改ざん検出用）。
     * <p>Phase 3-A で pdfSha256 から汎用化。Word 出力（Phase 3-B 完了）も本カラムを共通利用する。
     * Phase 3-D で `circulation_document_id` を介した F05.2 電子印鑑連携時、本ハッシュと
     * F05.3 seal_stamp_logs の証跡ログとの照合により改ざん検出を多層化する設計（§6.3）。
     * F05.3 連携の実装は Phase 4 以降。</p>
     */
    @Column(name = "output_sha256", length = 64)
    private String outputSha256;

    /** Phase 3 — 電子印鑑承認回覧 ID。 */
    private Long circulationDocumentId;

    /** 自動削除予定日（デフォルト created_at + 90日）。 */
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 自動削除予定日を延長する（最大 7年、F12.3 GDPR 整合）。
     */
    public void extendExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    /**
     * 電子印鑑承認回覧を関連付ける（Phase 3）。
     */
    public void linkCirculationDocument(Long circulationDocumentId) {
        this.circulationDocumentId = circulationDocumentId;
    }

    /**
     * 個人情報マスキング適用後の data_snapshot に置換する（GDPR 削除対応）。
     */
    public void maskDataSnapshot(String maskedJson) {
        this.dataSnapshot = maskedJson;
    }
}
