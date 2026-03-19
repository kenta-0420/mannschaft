package com.mannschaft.app.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当番ローテーションエンティティ。曜日/週単位でメンバーを自動ローテーションする定義。
 */
@Entity
@Table(name = "duty_rotations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DutyRotationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String dutyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RotationType rotationType;

    @Column(nullable = false, columnDefinition = "JSON")
    private String memberOrder;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(length = 10)
    private String icon;

    @Column(nullable = false)
    private Boolean isEnabled;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.isEnabled == null) {
            this.isEnabled = true;
        }
        if (this.rotationType == null) {
            this.rotationType = RotationType.DAILY;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 当番情報を更新する。
     *
     * @param dutyName     当番名
     * @param rotationType ローテーション種別
     * @param memberOrder  メンバー順序JSON
     * @param startDate    開始日
     * @param icon         アイコン
     * @param isEnabled    有効フラグ
     */
    public void update(String dutyName, RotationType rotationType, String memberOrder,
                       LocalDate startDate, String icon, Boolean isEnabled) {
        this.dutyName = dutyName;
        this.rotationType = rotationType;
        this.memberOrder = memberOrder;
        this.startDate = startDate;
        this.icon = icon;
        this.isEnabled = isEnabled;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * メンバー順序を更新する。
     *
     * @param memberOrder 新しいメンバー順序JSON
     */
    public void updateMemberOrder(String memberOrder) {
        this.memberOrder = memberOrder;
    }

    /**
     * 無効化する。
     */
    public void disable() {
        this.isEnabled = false;
    }
}
