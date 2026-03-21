package com.mannschaft.app.schedule.entity;

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
 * iCal購読URLトークンエンティティ。
 */
@Entity
@Table(name = "user_ical_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserIcalTokenEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private LocalDateTime lastPolledAt;

    /**
     * トークンを再生成する。
     *
     * @param newToken 新しいトークン
     */
    public void regenerateToken(String newToken) {
        this.token = newToken;
    }

    /**
     * トークンを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * ポーリング日時を記録する。
     */
    public void recordPoll() {
        this.lastPolledAt = LocalDateTime.now();
    }
}
