package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
        // NOTE: 一意制約 uq_sba_scope_category_period は Flyway (V11.030) 側で
        //       「関数インデックス」として定義しており、Entity では宣言しない。
        //       MySQL 8.0 は FK のベースカラム (team_id / project_id) に対する STORED 生成カラムを
        //       許さない (Error 3192) ため、V11.030 は生成カラムを捨てて
        //         CREATE UNIQUE INDEX uq_sba_scope_category_period ON shift_budget_allocations (
        //             organization_id, (COALESCE(team_id,0)), (COALESCE(project_id,0)),
        //             budget_category_id, period_start, period_end,
        //             (COALESCE(deleted_at,'9999-12-31 00:00:00')))
        //       という関数インデックスで NULL-safe な一意性を実現している。
        //       JPA の @UniqueConstraint は式を表現できないため、ここで擬似的に宣言すると
        //       「実 DB に存在しない列」を Entity が持つことになり、Flyway で構築した環境
        //       （本番・staging）で Hibernate が Unknown column を投げて API が全滅する
        //       （2026-09-09 実機で確認: deleted_at_uq で allocations 系 API が全て 500）。
        //       アプリ層の重複防止は ShiftBudgetAllocationService.findLiveByScope の
        //       SELECT ... FOR UPDATE が担い、DB 側は上記関数インデックスが最終防衛線となる。
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
@SuperBuilder(toBuilder = true)
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

    // NOTE: かつてここに UNIQUE 用 STORED 生成カラム team_id_uq / project_id_uq / deleted_at_uq を
    //       宣言していたが、V11.030 は MySQL 8.0 の制約（FK ベースカラムに STORED 生成カラム不可、
    //       Error 3192）により生成カラムを作らず関数インデックスで代替している。
    //       実 DB に存在しない列を Entity が宣言していたため、Flyway 構築環境では
    //       SELECT に deleted_at_uq 等が載って Unknown column となり API が全滅していた。
    //       詳細は上の @Table のコメントおよび docs/features/F08.7_shift_budget_integration.md を参照。

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
