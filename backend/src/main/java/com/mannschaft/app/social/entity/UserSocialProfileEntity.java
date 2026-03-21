package com.mannschaft.app.social.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ソーシャルプロフィールエンティティ。ユーザーの公開プロフィール情報を管理する。
 */
@Entity
@Table(name = "user_social_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserSocialProfileEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 30)
    private String handle;

    @Column(length = 50)
    private String displayName;

    @Column(length = 500)
    private String avatarUrl;

    @Column(length = 300)
    private String bio;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * プロフィール情報を更新する。
     *
     * @param displayName 表示名
     * @param bio         自己紹介
     * @param avatarUrl   アバターURL
     */
    public void updateProfile(String displayName, String bio, String avatarUrl) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (bio != null) {
            this.bio = bio;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl;
        }
    }

    /**
     * ハンドルを変更する。
     *
     * @param newHandle 新しいハンドル
     */
    public void changeHandle(String newHandle) {
        this.handle = newHandle;
    }

    /**
     * プロフィールを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * プロフィールを有効化する。
     */
    public void activate() {
        this.isActive = true;
    }
}
