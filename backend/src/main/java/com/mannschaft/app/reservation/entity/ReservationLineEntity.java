package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 予約ラインエンティティ。チームが提供する予約メニュー（ライン）を管理する。
 */
@Entity
@Table(name = "reservation_lines")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReservationLineEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Integer displayOrder = 1;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private Long defaultStaffUserId;

    private LocalDateTime deletedAt;

    /**
     * ラインを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * ラインを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * ライン名を変更する。
     *
     * @param name 新しいライン名
     */
    public void changeName(String name) {
        this.name = name;
    }

    /**
     * 説明文を変更する。
     *
     * @param description 新しい説明文
     */
    public void changeDescription(String description) {
        this.description = description;
    }

    /**
     * 表示順を変更する。
     *
     * @param displayOrder 新しい表示順
     */
    public void changeDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * デフォルト担当者を変更する。
     *
     * @param staffUserId 担当者ユーザーID
     */
    public void changeDefaultStaff(Long staffUserId) {
        this.defaultStaffUserId = staffUserId;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
