package com.mannschaft.app.budget.entity;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.common.BaseEntity;
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
 * 予算費目エンティティ。
 */
@Entity
@Table(name = "budget_categories")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetCategoryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long fiscalYearId;

    private Long parentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BudgetCategoryType categoryType;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * カテゴリ内容を更新する。
     * 管理対象（managed）エンティティを直接ミューテートすることで、
     * 主キー id を保持したまま UPDATE 文が発行されることを保証する。
     * （toBuilder().build() による再構築は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpdate(String name, Integer sortOrder, String description) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.description = description;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
