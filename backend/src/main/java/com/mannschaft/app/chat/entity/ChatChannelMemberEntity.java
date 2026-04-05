package com.mannschaft.app.chat.entity;

import com.mannschaft.app.chat.ChannelMemberRole;
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

import java.time.LocalDateTime;

/**
 * チャンネルメンバーエンティティ。チャンネルへの参加状態・設定を管理する。
 */
@Entity
@Table(name = "chat_channel_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChatChannelMemberEntity extends BaseEntity {

    @Column(nullable = false)
    private Long channelId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ChannelMemberRole role = ChannelMemberRole.MEMBER;

    @Column(nullable = false)
    @Builder.Default
    private Integer unreadCount = 0;

    private LocalDateTime lastReadAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isMuted = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(length = 50)
    private String category;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();

    /**
     * 未読数をリセットする。
     */
    public void resetUnreadCount() {
        this.unreadCount = 0;
        this.lastReadAt = LocalDateTime.now();
    }

    /**
     * 未読数をインクリメントする。
     */
    public void incrementUnreadCount() {
        this.unreadCount++;
    }

    /**
     * ロールを変更する。
     *
     * @param newRole 新しいロール
     */
    public void changeRole(ChannelMemberRole newRole) {
        this.role = newRole;
    }

    /**
     * ミュート設定を切り替える。
     *
     * @param muted ミュートするかどうか
     */
    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    /**
     * ピン留め設定を切り替える。
     *
     * @param pinned ピン留めするかどうか
     */
    public void setPinned(boolean pinned) {
        this.isPinned = pinned;
    }

    /**
     * カテゴリを更新する。
     *
     * @param category カテゴリ名
     */
    public void updateCategory(String category) {
        this.category = category;
    }
}
