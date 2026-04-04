package com.mannschaft.app.supporter.entity;

import com.mannschaft.app.supporter.SupporterApplicationStatus;
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
 * サポーター申請エンティティ。チーム・組織へのサポーター申請を管理する。
 * 自動承認OFFの場合のみ作成される。承認後は user_roles に SUPPORTER ロールが追加される。
 */
@Entity
@Table(name = "supporter_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SupporterApplicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** スコープ種別: TEAM または ORGANIZATION */
    @Column(nullable = false, length = 20)
    private String scopeType;

    /** スコープID: チームID または 組織ID */
    @Column(nullable = false)
    private Long scopeId;

    /** 申請者ユーザーID */
    @Column(nullable = false)
    private Long userId;

    /** 申請メッセージ（任意） */
    @Column(columnDefinition = "TEXT")
    private String message;

    /** 申請ステータス */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupporterApplicationStatus status;

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
     * ステータスを変更する。
     */
    public void updateStatus(SupporterApplicationStatus newStatus) {
        this.status = newStatus;
    }
}
