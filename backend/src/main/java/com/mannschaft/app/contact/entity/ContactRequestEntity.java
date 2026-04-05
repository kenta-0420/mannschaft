package com.mannschaft.app.contact.entity;

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
 * 連絡先追加申請エンティティ。ユーザー間の連絡先申請を管理する。
 */
@Entity
@Table(name = "contact_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ContactRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申請した側のユーザーID */
    @Column(nullable = false)
    private Long requesterId;

    /** 申請された側のユーザーID */
    @Column(nullable = false)
    private Long targetId;

    /** 申請ステータス: PENDING / ACCEPTED / REJECTED / CANCELLED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    /** 申請起点: HANDLE_SEARCH / TEAM_SEARCH / ORG_SEARCH / INVITE_URL / AUTO_TEAM / AUTO_ORG */
    @Column(length = 30)
    private String sourceType;

    /** チーム・組織・招待トークンのID */
    private Long sourceId;

    /** 申請時の一言メッセージ */
    @Column(length = 200)
    private String message;

    /** 応答日時（承認/拒否） */
    private LocalDateTime respondedAt;

    /** 申請の有効期限。NULL=無期限 */
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 申請を承認する。
     */
    public void accept() {
        this.status = "ACCEPTED";
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 申請を拒否する。
     */
    public void reject() {
        this.status = "REJECTED";
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 申請をキャンセルする。
     */
    public void cancel() {
        this.status = "CANCELLED";
    }

    /**
     * PENDING かどうかを返す。
     */
    public boolean isPending() {
        return "PENDING".equals(this.status);
    }

    /**
     * ACCEPTED かどうかを返す。
     */
    public boolean isAccepted() {
        return "ACCEPTED".equals(this.status);
    }
}
