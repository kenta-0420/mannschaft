package com.mannschaft.app.property.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.property.VendorCategory;
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
 * 業者マスタエンティティ。
 * 施工業者・点検業者・コンサル等を管理する。同じ業者を複数工事で再利用可能。
 * F09.13 設計書 §3 vendors テーブル定義に対応。
 */
@Entity
@Table(name = "vendors")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class VendorEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 200)
    private String nameKana;

    /** 業者分類。null 許容（未分類業者を許可）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private VendorCategory category;

    @Column(length = 30)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(length = 500)
    private String website;

    @Column(length = 10)
    private String postalCode;

    @Column(length = 255)
    private String address;

    @Column(length = 100)
    private String representative;

    @Column(length = 100)
    private String contactPerson;

    @Column(length = 100)
    private String licenseNumber;

    private LocalDate licenseExpiry;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * 反社チェック状態。UNCHECKED / PASSED / FAILED / EXPIRED。
     * F08.8 Phase 4 でカンバンカード追加時に complianceCheckStatus スナップショット元として参照する。
     */
    @Column(name = "compliance_check_status", nullable = false, length = 20)
    @Builder.Default
    private String complianceCheckStatus = "UNCHECKED";

    /** 反社チェック実施日時。未実施時は null。 */
    @Column(name = "compliance_checked_at")
    private LocalDateTime complianceCheckedAt;

    @Column(nullable = false)
    private Long createdBy;

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
     * 業者を有効化する。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * 業者を取引停止（非表示）にする。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 基本情報を更新する。
     */
    public void updateBasicInfo(String name, String nameKana, VendorCategory category) {
        this.name = name;
        this.nameKana = nameKana;
        this.category = category;
    }

    /**
     * 連絡先情報を更新する。
     */
    public void updateContact(String phone, String email, String website,
                               String postalCode, String address) {
        this.phone = phone;
        this.email = email;
        this.website = website;
        this.postalCode = postalCode;
        this.address = address;
    }

    /**
     * 担当者情報を更新する。
     */
    public void updatePersonnel(String representative, String contactPerson) {
        this.representative = representative;
        this.contactPerson = contactPerson;
    }

    /**
     * 許可情報を更新する。
     */
    public void updateLicense(String licenseNumber, LocalDate licenseExpiry) {
        this.licenseNumber = licenseNumber;
        this.licenseExpiry = licenseExpiry;
    }

    /**
     * メモを更新する。
     */
    public void updateNote(String note) {
        this.note = note;
    }

    /**
     * 反社チェック状態を更新する（F08.8 Phase 4）。
     *
     * @param status UNCHECKED / PASSED / FAILED / EXPIRED
     * @param checkedAt チェック実施日時（UNCHECKED 時は null 可）
     */
    public void updateComplianceStatus(String status, LocalDateTime checkedAt) {
        this.complianceCheckStatus = status;
        this.complianceCheckedAt = checkedAt;
    }
}
