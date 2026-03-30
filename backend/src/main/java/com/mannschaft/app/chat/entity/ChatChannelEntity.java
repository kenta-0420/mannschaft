package com.mannschaft.app.chat.entity;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * チャットチャンネルエンティティ。チーム・組織・DM等のチャットルームを管理する。
 */
@Entity
@Table(name = "chat_channels")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChatChannelEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType channelType;

    private Long teamId;

    private Long organizationId;

    @Column(length = 100)
    private String name;

    @Column(length = 500)
    private String iconKey;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPrivate = false;

    private Long createdBy;

    private LocalDateTime lastMessageAt;

    @Column(length = 100)
    private String lastMessagePreview;

    @Column(length = 30)
    private String sourceType;

    private Long sourceId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * 最終メッセージ情報を更新する。
     *
     * @param messageAt      メッセージ送信日時
     * @param messagePreview メッセージプレビュー（先頭100文字）
     */
    public void updateLastMessage(LocalDateTime messageAt, String messagePreview) {
        this.lastMessageAt = messageAt;
        this.lastMessagePreview = messagePreview;
    }

    /**
     * チャンネルをアーカイブする。
     */
    public void archive() {
        this.isArchived = true;
    }

    /**
     * チャンネルのアーカイブを解除する。
     */
    public void unarchive() {
        this.isArchived = false;
    }

    /**
     * チャンネル情報を更新する。
     *
     * @param name        チャンネル名
     * @param description 説明
     * @param iconKey     アイコンキー
     */
    public void updateInfo(String name, String description, String iconKey) {
        this.name = name;
        this.description = description;
        this.iconKey = iconKey;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * DMチャンネルかどうかを判定する。
     *
     * @return DM または GROUP_DM の場合 true
     */
    public boolean isDm() {
        return this.channelType == ChannelType.DM || this.channelType == ChannelType.GROUP_DM;
    }

    /**
     * DMチャンネルをグループDMに変換する。
     * DM または GROUP_DM のみ変換可能。
     */
    public void convertToGroupDm() {
        this.channelType = ChannelType.GROUP_DM;
    }
}
