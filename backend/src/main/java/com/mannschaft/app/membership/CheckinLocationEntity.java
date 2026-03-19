package com.mannschaft.app.membership;

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

import java.time.LocalDateTime;

/**
 * セルフチェックイン拠点エンティティ。店舗の受付やジムの入口等にQRポップとして掲示する。
 */
@Entity
@Table(name = "checkin_locations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CheckinLocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 36)
    private String locationCode;

    @Column(nullable = false, length = 64)
    private String locationSecret;

    @Column(nullable = false)
    private Boolean isActive;

    @Column(nullable = false)
    private Boolean autoCompleteReservation;

    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.autoCompleteReservation == null) {
            this.autoCompleteReservation = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 拠点情報を更新する。
     *
     * @param name                     拠点名
     * @param isActive                 有効フラグ
     * @param autoCompleteReservation   予約自動完了フラグ
     */
    public void update(String name, Boolean isActive, Boolean autoCompleteReservation) {
        this.name = name;
        this.isActive = isActive;
        this.autoCompleteReservation = autoCompleteReservation;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
