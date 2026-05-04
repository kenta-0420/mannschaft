package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GeneratedColumn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算割当エンティティ。
 *
 * <p>シフト人件費を月単位で予算費目に割り当てる。F08.6 の {@code budget_allocations}
 * （年度×費目で 1 行）の細分化として位置づける。</p>
 *
 * <p>設計書 F08.7 (v1.2) §5.2 に準拠。</p>
 *
 * <p>マスター御裁可:</p>
 * <ul>
 *   <li>Q1 案A: {@code project_id} は V11.030 で NULLABLE 配置済（FK は Phase 9-γ V11.035）</li>
 *   <li>Q4: {@code consumed_amount} はアトミック増減のみ（{@code @Version} は allocation 自体の
 *       楽観ロック用に使うが、consumption 集計の競合制御には併用しない）</li>
 * </ul>
 */
@Entity
@Table(
        name = "shift_budget_allocations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_sba_scope_category_period",
                        columnNames = {
                                "organization_id", "team_id_uq", "project_id_uq",
                                "budget_category_id", "period_start", "period_end", "deleted_at_uq"
                        }
                )
        },
        indexes = {
                @Index(name = "idx_sba_org_period", columnList = "organization_id, period_start, period_end"),
                @Index(name = "idx_sba_team_period", columnList = "team_id, period_start, period_end"),
                @Index(name = "idx_sba_project", columnList = "project_id"),
                @Index(name = "idx_sba_fiscal", columnList = "fiscal_year_id"),
                @Index(name = "idx_sba_currency", columnList = "currency")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftBudgetAllocationEntity extends BaseEntity {

    /** 多テナント分離キー。FK → organizations。ON DELETE CASCADE */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** チーム単位の場合のみセット。NULL = 組織全体。FK → teams。ON DELETE CASCADE */
    @Column(name = "team_id")
    private Long teamId;

    /**
     * プロジェクト専用割当の場合のみセット。NULL = 通常の月×費目×team 割当。
     * <p>Phase 9-β（本マイグレーション V11.030 案A）で NULLABLE 配置。
     * Phase 9-γ V11.035 で FK 制約を追加予定（FK → projects ON DELETE RESTRICT）。</p>
     */
    @Column(name = "project_id")
    private Long projectId;

    /** FK → budget_fiscal_years。ON DELETE RESTRICT */
    @Column(name = "fiscal_year_id", nullable = false)
    private Long fiscalYearId;

    /** FK → budget_categories（通常は「人件費」配下）。ON DELETE RESTRICT */
    @Column(name = "budget_category_id", nullable = false)
    private Long budgetCategoryId;

    /** 適用開始日（通常は月初） */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** 適用終了日（通常は月末） */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 割当額（円） */
    @Column(name = "allocated_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal allocatedAmount;

    /** 消化額キャッシュ（PLANNED + CONFIRMED の合計）。アトミック増減で更新する */
    @Column(name = "consumed_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal consumedAmount;

    /** 確定済み消化額（CONFIRMED のみ）。Phase 9-δ 月次締めで更新 */
    @Column(name = "confirmed_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal confirmedAmount;

    /** ISO 4217 通貨コード。Phase 9 では JPY 固定。多通貨拡張用の事前配置 */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** 備考 */
    @Column(name = "note", length = 500)
    private String note;

    /** FK → users。ON DELETE RESTRICT */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 楽観ロック（allocation 自体の競合検出用） */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** 論理削除タイムスタンプ */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * UNIQUE 用 STORED 生成カラム。
     * <p>MySQL の UNIQUE は NULL を「異なる値」と扱うため、{@code team_id} / {@code project_id} /
     * {@code deleted_at} が NULL の場合に重複検知できない問題を回避するため、COALESCE で
     * 番兵値に変換した STORED 生成カラムを {@code @UniqueConstraint} に組み込む。</p>
     * <p>Hibernate の {@code ddl-auto} 経由でテスト DB に DDL 生成される際にも有効化されるよう、
     * Entity 側に {@code columnDefinition} で生成カラム定義を明示する。
     * 既存 V3.120 ({@code recruitment_participants.active_subject_key}) と同パターン。</p>
     */
    @GeneratedColumn("COALESCE(team_id, 0)")
    @Column(name = "team_id_uq", nullable = false, insertable = false, updatable = false)
    private Long teamIdUq;

    @GeneratedColumn("COALESCE(project_id, 0)")
    @Column(name = "project_id_uq", nullable = false, insertable = false, updatable = false)
    private Long projectIdUq;

    @GeneratedColumn("COALESCE(deleted_at, '9999-12-31 00:00:00')")
    @Column(name = "deleted_at_uq", nullable = false, insertable = false, updatable = false)
    private LocalDateTime deletedAtUq;

    /**
     * 割当額・備考を更新する。
     */
    public void updateAllocation(BigDecimal allocatedAmount, String note) {
        this.allocatedAmount = allocatedAmount;
        this.note = note;
    }

    /**
     * 論理削除を実行する。
     * <p>呼出側は事前に {@code shift_budget_consumptions} の残存状況を検証すること
     * （PLANNED/CONFIRMED が残っていれば 409 を返却）。設計書 §5.2 HAS_CONSUMPTIONS 制約参照。</p>
     */
    public void markDeleted() {
        this.deletedAt = LocalDateTime.now();
    }
}
