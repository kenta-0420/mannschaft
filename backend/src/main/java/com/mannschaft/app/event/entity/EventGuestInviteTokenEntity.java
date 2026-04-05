package com.mannschaft.app.event.entity;

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
 * イベントゲスト招待トークンエンティティ。ゲスト招待用トークンを管理する。
 */
@Entity
@Table(name = "event_guest_invite_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventGuestInviteTokenEntity extends BaseEntity {

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 36)
    private String token;

    @Column(length = 100)
    private String label;

    private Integer maxUses;

    @Column(nullable = false)
    @Builder.Default
    private Integer usedCount = 0;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    private Long createdBy;

    /**
     * トークンを無効化する。
     */
    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 使用回数をインクリメントする。
     */
    public void incrementUsedCount() {
        this.usedCount++;
    }

    /**
     * トークンが使用可能かどうかを判定する。
     *
     * @return 有効かつ使用回数上限未達かつ期限内の場合 true
     */
    public boolean isUsable() {
        if (!this.isActive) {
            return false;
        }
        if (this.maxUses != null && this.usedCount >= this.maxUses) {
            return false;
        }
        if (this.expiresAt != null && LocalDateTime.now().isAfter(this.expiresAt)) {
            return false;
        }
        return true;
    }
}
