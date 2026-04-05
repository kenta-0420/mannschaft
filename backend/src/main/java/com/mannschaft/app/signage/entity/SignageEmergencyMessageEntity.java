package com.mannschaft.app.signage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * デジタルサイネージ 緊急メッセージエンティティ。
 * ON DELETE CASCADE により、親画面削除時に物理削除される。
 * created_at のみ持ち updated_at は持たないため BaseEntity を継承しない。
 */
@Entity
@Table(name = "signage_emergency_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SignageEmergencyMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long screenId;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String backgroundColor = "#FF0000";

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String textColor = "#FFFFFF";

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    @Column(nullable = false)
    private Long sentBy;

    private LocalDateTime dismissedAt;

    private Long dismissedBy;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 緊急メッセージを表示状態にする。
     */
    public void activate() {
        this.isActive = true;
    }

    /**
     * 緊急メッセージを解除する。
     */
    public void dismiss(Long dismisserId) {
        this.isActive = false;
        this.dismissedAt = LocalDateTime.now();
        this.dismissedBy = dismisserId;
    }
}
