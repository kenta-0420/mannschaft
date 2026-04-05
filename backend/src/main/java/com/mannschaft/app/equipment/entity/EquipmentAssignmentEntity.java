package com.mannschaft.app.equipment.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 備品貸出・返却履歴エンティティ。
 */
@Entity
@Table(name = "equipment_assignments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EquipmentAssignmentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long equipmentItemId;

    @Column(nullable = false)
    private Long assignedToUserId;

    private Long assignedByUserId;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime assignedAt = LocalDateTime.now();

    private LocalDate expectedReturnAt;

    private LocalDateTime returnedAt;

    private Long returnedByUserId;

    @Column(length = 300)
    private String note;

    private LocalDateTime lastOverdueNotifiedAt;

    /**
     * 返却処理を行う。
     *
     * @param returnedByUserId 返却操作者のユーザーID
     * @param note             返却時の備考
     */
    public void markReturned(Long returnedByUserId, String note) {
        this.returnedAt = LocalDateTime.now();
        this.returnedByUserId = returnedByUserId;
        if (note != null) {
            this.note = note;
        }
    }

    /**
     * 返却済みかどうかを判定する。
     *
     * @return 返却済みの場合 true
     */
    public boolean isReturned() {
        return this.returnedAt != null;
    }
}
