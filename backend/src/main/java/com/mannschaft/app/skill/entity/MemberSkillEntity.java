package com.mannschaft.app.skill.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.skill.SkillStatus;
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
 * メンバースキル・資格エンティティ。
 */
@Entity
@Table(name = "member_skills")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class MemberSkillEntity extends BaseEntity {

    private Long skillCategoryId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String issuer;

    @Column(length = 100)
    private String credentialNumber;

    private LocalDate acquiredOn;

    private LocalDate expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SkillStatus status = SkillStatus.PENDING_REVIEW;

    // NOTE: S3Key のような数字→大文字境界は Hibernate 物理命名で _s3_key にならず
    // certificates3key になる。DDL の certificate_s3_key と一致させるため name を明示。
    @Column(name = "certificate_s3_key", length = 500)
    private String certificateS3Key;

    private LocalDateTime verifiedAt;

    private Long verifiedBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * スキル情報を更新する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpdate(String name, String issuer, String credentialNumber,
                            LocalDate acquiredOn, LocalDate expiresAt) {
        this.name = name;
        this.issuer = issuer;
        this.credentialNumber = credentialNumber;
        this.acquiredOn = acquiredOn;
        this.expiresAt = expiresAt;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(SkillStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 資格を承認済みに変更する。
     */
    public void verify(Long verifierId) {
        this.status = SkillStatus.ACTIVE;
        this.verifiedAt = LocalDateTime.now();
        this.verifiedBy = verifierId;
    }

    /**
     * 資格を期限切れにする。
     */
    public void expire() {
        this.status = SkillStatus.EXPIRED;
    }
}
