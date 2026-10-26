package com.mannschaft.app.property.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物件履歴パッケージエンティティ（集約ルート）。
 * 1件の工事/事故/点検イベントを表す。
 * F09.13 設計書 §3 property_work_packages テーブル定義に対応。
 *
 * 単一情報源:
 * - 金額は {@code budgetTransactionId} 経由で F08.6 BudgetTransaction を参照
 * - 文書は {@link PropertyWorkDocumentEntity} 経由で F05.5 SharedFile を参照
 * - 事故は {@code incidentId} 経由で F07.6 Incident を参照
 */
@Entity
@Table(name = "property_work_packages")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PropertyWorkPackageEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    /** 居室ID（F09.1 dwelling_units.id）。共用部の場合 NULL。 */
    private Long dwellingUnitId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkType workType;

    /** 工事カテゴリ（自由文字列、例: 「外壁塗装」「給水管」）。 */
    @Column(length = 50)
    private String category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** 事故起点の場合の F07.6 Incident.id。 */
    private Long incidentId;

    /** 事故/発覚日。INCIDENT/DISASTER 時必須（アプリ層で検証）。 */
    private LocalDate incidentDate;

    /** 事故の経緯（重要事項説明書 §11 の事故告知用）。 */
    @Column(columnDefinition = "TEXT")
    private String incidentNarrative;

    private LocalDate plannedStartDate;

    private LocalDate plannedEndDate;

    private LocalDate actualStartDate;

    private LocalDate actualEndDate;

    /** 業者ID（vendors.id）。業者削除時 SET NULL（vendor_name_snapshot で表示維持）。 */
    private Long vendorId;

    /** 業者名スナップショット（vendor 削除後も保持）。 */
    @Column(length = 150)
    private String vendorNameSnapshot;

    private Long estimatedAmount;

    private Long contractAmount;

    private Long actualAmount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "JPY";

    /** F08.6 BudgetTransaction.id。金額の単一情報源。 */
    private Long budgetTransactionId;

    /** F04.1 TimelinePost.id（自動投稿）。 */
    private Long timelinePostId;

    private LocalDate warrantyUntil;

    /** 重説書（F09.14）への自動引用可否。デフォルト TRUE。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDisclosable = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WorkPackageVisibility visibility = WorkPackageVisibility.ADMINS_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WorkPackageStatus status = WorkPackageStatus.PLANNED;

    /** 添付文書数（非正規化、一覧表示用）。サービス層で更新。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer attachmentCount = 0;

    /** コメント数（タイムライン経由）。 */
    @Column(nullable = false)
    @Builder.Default
    private Integer commentCount = 0;

    /** 自由タグ配列（JSON）。例: ["大規模修繕","国交省ガイドライン準拠"]。 */
    @Column(columnDefinition = "JSON")
    private String tags;

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
    public void changeStatus(WorkPackageStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 可視性を変更する。
     */
    public void changeVisibility(WorkPackageVisibility newVisibility) {
        this.visibility = newVisibility;
    }

    /**
     * 重説書引用可否を切り替える。
     */
    public void setDisclosable(boolean disclosable) {
        this.isDisclosable = disclosable;
    }

    /**
     * 業者を割り当てる（snapshot も同時更新）。
     */
    public void assignVendor(Long vendorId, String vendorNameSnapshot) {
        this.vendorId = vendorId;
        this.vendorNameSnapshot = vendorNameSnapshot;
    }

    /**
     * 業者名 snapshot のみを更新する（GDPR 削除時のマスク用）。
     */
    public void maskVendorSnapshot(String maskedName) {
        this.vendorNameSnapshot = maskedName;
    }

    /**
     * F08.6 BudgetTransaction を関連付ける。
     */
    public void linkBudgetTransaction(Long budgetTransactionId) {
        this.budgetTransactionId = budgetTransactionId;
    }

    /**
     * F04.1 TimelinePost を関連付ける。
     */
    public void linkTimelinePost(Long timelinePostId) {
        this.timelinePostId = timelinePostId;
    }

    /**
     * 添付文書数を増加させる。
     */
    public void incrementAttachmentCount() {
        this.attachmentCount = this.attachmentCount + 1;
    }

    /**
     * 添付文書数を減少させる（0 未満にはしない）。
     */
    public void decrementAttachmentCount() {
        if (this.attachmentCount > 0) {
            this.attachmentCount = this.attachmentCount - 1;
        }
    }

    /**
     * コメント数を更新する（F04.1 TimelinePost のリプライ件数を集約）。
     */
    public void updateCommentCount(int count) {
        this.commentCount = count;
    }

    /**
     * 基本情報を更新する。
     */
    public void updateBasicInfo(String title, String description, String category) {
        this.title = title;
        this.description = description;
        this.category = category;
    }

    /**
     * 計画日程を更新する。
     */
    public void updatePlannedDates(LocalDate plannedStartDate, LocalDate plannedEndDate) {
        this.plannedStartDate = plannedStartDate;
        this.plannedEndDate = plannedEndDate;
    }

    /**
     * 実績日程を更新する。
     */
    public void updateActualDates(LocalDate actualStartDate, LocalDate actualEndDate) {
        this.actualStartDate = actualStartDate;
        this.actualEndDate = actualEndDate;
    }

    /**
     * 金額情報を更新する。
     */
    public void updateAmounts(Long estimatedAmount, Long contractAmount, Long actualAmount) {
        this.estimatedAmount = estimatedAmount;
        this.contractAmount = contractAmount;
        this.actualAmount = actualAmount;
    }

    /**
     * 更新者を記録する。
     */
    public void recordUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    /**
     * タグ JSON 文字列を更新する。
     */
    public void updateTags(String tags) {
        this.tags = tags;
    }

    /**
     * 保証期限を更新する。
     */
    public void updateWarrantyUntil(LocalDate warrantyUntil) {
        this.warrantyUntil = warrantyUntil;
    }

    /**
     * 事故情報を更新する（INCIDENT/DISASTER 用）。
     */
    public void updateIncidentInfo(Long incidentId, LocalDate incidentDate, String incidentNarrative) {
        this.incidentId = incidentId;
        this.incidentDate = incidentDate;
        this.incidentNarrative = incidentNarrative;
    }
}
