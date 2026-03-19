package com.mannschaft.app.auth.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ユーザーマスターエンティティ。認証・プロフィール情報を管理する。
 */
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String lastName;

    @Column(nullable = false, length = 50)
    private String firstName;

    @Column(length = 50)
    private String lastNameKana;

    @Column(length = 50)
    private String firstNameKana;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Column(length = 50)
    private String nickname2;

    @Column(nullable = false)
    private Boolean isSearchable;

    @Column(length = 500)
    private String avatarUrl;

    @Column(length = 20)
    private String phoneNumber;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserStatus status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime reminderSentAt;

    private LocalDateTime archivedAt;

    private LocalDateTime deletedAt;

    /**
     * ユーザーステータス
     */
    public enum UserStatus {
        PENDING_VERIFICATION,
        ACTIVE,
        FROZEN,
        ARCHIVED
    }

    /**
     * ユーザーを有効化する。
     */
    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * ユーザーを凍結する。
     */
    public void freeze() {
        this.status = UserStatus.FROZEN;
    }

    /**
     * ユーザーの凍結を解除する。
     */
    public void unfreeze() {
        this.status = UserStatus.ACTIVE;
    }

    /**
     * ユーザーをアーカイブする。
     */
    public void archive() {
        this.status = UserStatus.ARCHIVED;
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * ユーザーのアーカイブを解除する。
     */
    public void unarchive() {
        this.status = UserStatus.ACTIVE;
        this.archivedAt = null;
    }

    /**
     * 退会リクエストを処理する（論理削除）。
     */
    public void requestDeletion() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 退会リクエストを取り消す。
     */
    public void cancelDeletion() {
        this.deletedAt = null;
    }

    /**
     * 最終ログイン日時を更新する。
     */
    public void updateLastLoginAt() {
        this.lastLoginAt = LocalDateTime.now();
    }
}
