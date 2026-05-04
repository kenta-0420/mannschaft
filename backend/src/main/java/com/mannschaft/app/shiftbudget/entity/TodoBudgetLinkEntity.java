package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * F08.7 TODO/プロジェクト 予算紐付エンティティ（Phase 9-γ）。
 *
 * <p>TODO（プロジェクト・タスク）に予算枠を紐付け、進捗とともに消化を可視化するための
 * リンクテーブル。設計書 F08.7 (v1.2) §5.4 / §4.3 に準拠。</p>
 *
 * <p><strong>排他ルール</strong>:</p>
 * <ul>
 *   <li>{@code projectId} と {@code todoId} は排他（DB レベル {@code chk_tbl_target_xor} と
 *       Service 層検証の二重防御）</li>
 *   <li>{@code linkAmount} と {@code linkPercentage} は排他（両方 NULL = 割当全額紐付）</li>
 * </ul>
 *
 * <p>論理削除なし（紐付の取り消しは物理 DELETE で表現）。</p>
 */
@Entity
@Table(
        name = "todo_budget_links",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_tbl_project_alloc",
                        columnNames = {"project_id", "allocation_id"}
                ),
                @UniqueConstraint(
                        name = "uq_tbl_todo_alloc",
                        columnNames = {"todo_id", "allocation_id"}
                )
        },
        indexes = {
                @Index(name = "idx_tbl_project", columnList = "project_id"),
                @Index(name = "idx_tbl_todo", columnList = "todo_id"),
                @Index(name = "idx_tbl_allocation", columnList = "allocation_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TodoBudgetLinkEntity extends BaseEntity {

    /**
     * 紐付対象プロジェクトID。{@code todoId} と排他。
     * <p>FK → projects (ON DELETE CASCADE)。プロジェクト削除時に紐付も消える。</p>
     */
    @Column(name = "project_id")
    private Long projectId;

    /**
     * 紐付対象 TODO ID。{@code projectId} と排他。
     * <p>FK → todos (ON DELETE CASCADE)。TODO 削除時に紐付も消える。</p>
     */
    @Column(name = "todo_id")
    private Long todoId;

    /**
     * 紐付先割当ID。FK → shift_budget_allocations (ON DELETE RESTRICT)。
     * 紐付が残っている割当は削除不可（経理整合性保護）。
     */
    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /**
     * 紐付上限金額（円）。NULL かつ {@code linkPercentage} NULL = 割当全額紐付。
     * <p>{@code linkPercentage} と排他。</p>
     */
    @Column(name = "link_amount", precision = 12, scale = 0)
    private BigDecimal linkAmount;

    /**
     * 割合（0.00〜100.00）。NULL かつ {@code linkAmount} NULL = 割当全額紐付。
     * <p>{@code linkAmount} と排他。</p>
     */
    @Column(name = "link_percentage", precision = 5, scale = 2)
    private BigDecimal linkPercentage;

    /**
     * ISO 4217 通貨コード。Phase 9 では JPY 固定。多通貨拡張用の事前配置。
     */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** FK → users。ON DELETE RESTRICT。監査履歴保持のため作成者を残す */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
