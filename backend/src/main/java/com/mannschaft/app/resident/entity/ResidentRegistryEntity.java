package com.mannschaft.app.resident.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * 氏名・連絡先はAES-256-GCMで暗号化して保存する。
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

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String lastName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String firstName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String lastNameKana;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String firstNameKana;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String phone;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "TEXT")
    private String emergencyContact;

    @Column(length = 64)
    private String lastNameHash;

    @Column(length = 64)
    private String firstNameHash;

    @Column(nullable = false)
    @Builder.Default
    private Integer encryptionKeyVersion = 1;

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
     * ブラインドインデックスを更新する。
     */
    public void updateHashes(String lastNameHash, String firstNameHash) {
        this.lastNameHash = lastNameHash;
        this.firstNameHash = firstNameHash;
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
