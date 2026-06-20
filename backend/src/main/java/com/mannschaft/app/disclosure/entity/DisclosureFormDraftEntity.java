package com.mannschaft.app.disclosure.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.disclosure.DraftStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 重要事項説明書 ドラフトエンティティ。
 * ADMIN が出力前に途中保存できる入力データを保持する。
 * F09.14 設計書 §3 disclosure_form_drafts テーブル定義に対応。
 */
@Entity
@Table(name = "disclosure_form_drafts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class DisclosureFormDraftEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /** disclosure_form_templates.id への FK。ON DELETE RESTRICT。 */
    @Column(nullable = false)
    private Long templateId;

    /** ドラフト作成時の様式バージョン（バージョン更新差分検知用）。 */
    @Column(nullable = false, length = 20)
    private String templateVersionSnapshot;

    @Column(nullable = false, length = 200)
    private String title;

    /** dwelling_units.id（住戸単位の重説書の場合のみ）。ON DELETE SET NULL。 */
    private Long targetDwellingUnitId;

    /** 入力済みデータ JSON（form_schema 対応、最大 1MB）。 */
    @Column(columnDefinition = "JSON", nullable = false)
    private String formData;

    /** 引用済み履歴パッケージ ID 配列 JSON。 */
    @Column(columnDefinition = "JSON")
    private String referencedPackageIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DraftStatus status = DraftStatus.DRAFT;

    @Column(nullable = false)
    private Long createdBy;

    private Long updatedBy;

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
     * ステータスを変更する。
     */
    public void changeStatus(DraftStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 入力データ JSON を更新する。
     */
    public void updateFormData(String formData) {
        this.formData = formData;
    }

    /**
     * 引用パッケージ ID 配列を更新する。
     */
    public void updateReferencedPackageIds(String referencedPackageIds) {
        this.referencedPackageIds = referencedPackageIds;
    }

    /**
     * タイトルを更新する。
     */
    public void rename(String title) {
        this.title = title;
    }

    /**
     * 更新者を記録する。
     */
    public void recordUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    /**
     * 対象居室を割り当てる。
     */
    public void assignDwellingUnit(Long dwellingUnitId) {
        this.targetDwellingUnitId = dwellingUnitId;
    }
}
