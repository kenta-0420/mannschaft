package com.mannschaft.app.line.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ユーザーLINE連携エンティティ。
 */
@Entity
@Table(name = "user_line_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserLineConnectionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String lineUserId;

    @Column(length = 100)
    private String displayName;

    @Column(length = 500)
    private String pictureUrl;

    @Column(length = 500)
    private String statusMessage;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(nullable = false)
    private LocalDateTime linkedAt;

    /**
     * プロフィール情報を更新する。
     */
    public void updateProfile(String displayName, String pictureUrl, String statusMessage) {
        this.displayName = displayName;
        this.pictureUrl = pictureUrl;
        this.statusMessage = statusMessage;
    }

    /**
     * 有効/無効を切り替える。
     */
    public void deactivate() {
        this.isActive = false;
    }
}
