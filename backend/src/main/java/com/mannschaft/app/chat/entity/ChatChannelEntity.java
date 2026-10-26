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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * チャットチャンネルエンティティ。チーム・組織・DM・村ロビー等のチャットルームを管理する。
 *
 * <p>F17.1 Phase 1: 村ロビー対応のため {@code village_id} を追加。
 * {@code channelType=VILLAGE_LOBBY} の場合に村の UUIDv7 を保持する（FK は張らない／原則1）。</p>
 */
@Entity
@Table(name = "chat_channels")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ChatChannelEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChannelType channelType;

    private Long teamId;

    private Long organizationId;

    /**
     * 村 ID（F17.1 Phase 1）。
     * {@code channelType=VILLAGE_LOBBY} のとき必須。FK は張らない（クロスドメイン・原則1）。
     */
    @Column(name = "village_id", columnDefinition = "BINARY(16)")
    private UUID villageId;

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

    @Column(name = "is_inquiry_channel", nullable = false)
    @Builder.Default
    private Boolean isInquiryChannel = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer activeThreadCount = 0;

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
     * アクティブスレッド数をインクリメントする。
     */
    public void incrementActiveThreadCount() {
        this.activeThreadCount++;
    }

    /**
     * アクティブスレッド数をデクリメントする。0 以下にはならない。
     */
    public void decrementActiveThreadCount() {
        if (this.activeThreadCount > 0) {
            this.activeThreadCount--;
        }
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

    /**
     * F10.7: 問い合わせチャンネルフラグを更新する。
     *
     * @param isInquiryChannel 問い合わせチャンネルに設定する場合 true
     */
    public void updateInquiryChannel(Boolean isInquiryChannel) {
        this.isInquiryChannel = isInquiryChannel != null ? isInquiryChannel : false;
    }
}
