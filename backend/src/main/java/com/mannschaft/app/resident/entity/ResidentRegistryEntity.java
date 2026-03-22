package com.mannschaft.app.resident.entity;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 居住者台帳エンティティ。
 */
@Entity
@Table(name = "resident_registry")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ResidentRegistryEntity extends BaseEntity {

    @Column(nullable = false)
    private Long dwellingUnitId;

    private Long userId;

    @Column(nullable = false, length = 20)
    private String residentType;

    @Column(nullable = false, length = 50)
    private String lastName;

    @Column(nullable = false, length = 50)
    private String firstName;

    @Column(length = 100)
    private String lastNameKana;

    @Column(length = 100)
    private String firstNameKana;

    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(length = 200)
    private String emergencyContact;

    @Column(nullable = false)
    private LocalDate moveInDate;

    private LocalDate moveOutDate;

    private BigDecimal ownershipRatio;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    private Long verifiedBy;

    private LocalDateTime verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime deletedAt;

    /**
     * 居住者情報を更新する。
     */
    public void update(String residentType, String lastName, String firstName,
                       String lastNameKana, String firstNameKana,
                       String phone, String email, String emergencyContact,
                       LocalDate moveInDate, BigDecimal ownershipRatio,
                       Boolean isPrimary, String notes) {
        this.residentType = residentType;
        this.lastName = lastName;
        this.firstName = firstName;
        this.lastNameKana = lastNameKana;
        this.firstNameKana = firstNameKana;
        this.phone = phone;
        this.email = email;
        this.emergencyContact = emergencyContact;
        this.moveInDate = moveInDate;
        this.ownershipRatio = ownershipRatio;
        this.isPrimary = isPrimary;
        this.notes = notes;
    }

    /**
     * 管理者確認済みにする。
     */
    public void verify(Long verifierId) {
        this.isVerified = true;
        this.verifiedBy = verifierId;
        this.verifiedAt = LocalDateTime.now();
    }

    /**
     * 退去処理を行う。
     */
    public void moveOut(LocalDate moveOutDate) {
        this.moveOutDate = moveOutDate;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
