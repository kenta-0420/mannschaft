package com.mannschaft.app.family.entity;

import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareRelationship;
import com.mannschaft.app.family.CareLinkStatus;
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
 * ユーザーケアリンクエンティティ。
 * ケア対象者と見守り者の関係を管理する。F03.12。
 */
@Entity
@Table(name = "user_care_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserCareLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long careRecipientUserId;

    @Column(nullable = false)
    private Long watcherUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CareCategory careCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private CareRelationship relationship = CareRelationship.PARENT;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrimary = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CareLinkStatus status = CareLinkStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CareLinkInvitedBy invitedBy;

    @Column(length = 64)
    private String invitationToken;

    private LocalDateTime invitationSentAt;
    private LocalDateTime confirmedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyOnRsvp = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyOnCheckin = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyOnCheckout = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyOnAbsentAlert = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean notifyOnDismissal = true;

    @Column(nullable = false)
    private Long createdBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    private LocalDateTime revokedAt;
    private Long revokedBy;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ケアリンクを承認してアクティブにする。
     */
    public void activate(LocalDateTime confirmedAt) {
        this.status = CareLinkStatus.ACTIVE;
        this.confirmedAt = confirmedAt;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ケアリンク招待を拒否する。
     */
    public void reject() {
        this.status = CareLinkStatus.REJECTED;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ケアリンクを解除する。
     */
    public void revoke(Long revokedByUserId) {
        this.status = CareLinkStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revokedBy = revokedByUserId;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 通知設定を更新する。null の場合は現在値を維持する。
     */
    public void updateNotifySettings(Boolean notifyOnRsvp, Boolean notifyOnCheckin,
                                     Boolean notifyOnCheckout, Boolean notifyOnAbsentAlert,
                                     Boolean notifyOnDismissal) {
        if (notifyOnRsvp != null) this.notifyOnRsvp = notifyOnRsvp;
        if (notifyOnCheckin != null) this.notifyOnCheckin = notifyOnCheckin;
        if (notifyOnCheckout != null) this.notifyOnCheckout = notifyOnCheckout;
        if (notifyOnAbsentAlert != null) this.notifyOnAbsentAlert = notifyOnAbsentAlert;
        if (notifyOnDismissal != null) this.notifyOnDismissal = notifyOnDismissal;
        this.updatedAt = LocalDateTime.now();
    }
}
