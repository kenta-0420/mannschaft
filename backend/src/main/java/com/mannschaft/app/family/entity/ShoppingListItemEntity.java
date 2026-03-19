package com.mannschaft.app.family.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * お買い物リストアイテムエンティティ。個別の購入アイテムを表す。
 */
@Entity
@Table(name = "shopping_list_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShoppingListItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 50)
    private String quantity;

    @Column(length = 500)
    private String note;

    private Long assignedTo;

    @Column(nullable = false)
    private Boolean isChecked;

    private Long checkedBy;

    private LocalDateTime checkedAt;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.isChecked == null) { this.isChecked = false; }
        if (this.sortOrder == null) { this.sortOrder = 0; }
    }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = LocalDateTime.now(); }

    public void update(String name, String quantity, String note, Long assignedTo, Integer sortOrder) {
        this.name = name; this.quantity = quantity; this.note = note;
        this.assignedTo = assignedTo; this.sortOrder = sortOrder;
    }

    public void toggleCheck(Long userId) {
        if (Boolean.TRUE.equals(this.isChecked)) {
            this.isChecked = false; this.checkedBy = null; this.checkedAt = null;
        } else {
            this.isChecked = true; this.checkedBy = userId; this.checkedAt = LocalDateTime.now();
        }
    }

    public void uncheckItem() {
        this.isChecked = false; this.checkedBy = null; this.checkedAt = null;
    }

    public void clearAssignment() { this.assignedTo = null; }
}
