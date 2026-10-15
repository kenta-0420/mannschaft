package com.mannschaft.app.chat.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * チャットメッセージエンティティ。チャンネル内のメッセージ・スレッド返信を管理する。
 *
 * <p>F17.1 Phase 1: 投稿主体切替のため {@code posted_as_subject_type} / {@code posted_as_subject_id}
 * を追加。村ロビーやチャネル内でチーム/組織代表として発言する際に使用。デフォルトは USER。</p>
 */
@PersonalData(category = "chatMessages")
@Entity
@Table(name = "chat_messages")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ChatMessageEntity extends BaseEntity {

    @Column(nullable = false)
    private Long channelId;

    private Long senderId;

    /**
     * 投稿主体種別（F17.1 Phase 1）。
     * USER（個人投稿）/ TEAM（チーム代表）/ ORGANIZATION（組織代表）。デフォルトは USER。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "posted_as_subject_type", nullable = false, length = 20)
    @Builder.Default
    private com.mannschaft.app.village.entity.enums.VillageSubjectType postedAsSubjectType =
            com.mannschaft.app.village.entity.enums.VillageSubjectType.USER;

    /**
     * 投稿主体 ID（F17.1 Phase 1）。USER 以外の場合のみ値を持つ。FK は張らない（原則1）。
     */
    @Column(name = "posted_as_subject_id")
    private Long postedAsSubjectId;

    private Long parentId;

    /**
     * メッセージ種別（F04.12）。TEXT（通常本文）/ INVITE_CARD（チーム/組織への招待カード）。
     * 将来拡張のため VARCHAR + アプリ層 enum バリデーション（MySQL ENUM にはしない）。デフォルトは TEXT。
     */
    @Column(name = "message_type", nullable = false, length = 20)
    @ColumnDefault("'TEXT'")
    @Builder.Default
    private String messageType = "TEXT";

    /**
     * 招待カードが参照する招待トークン ID（F04.12）。{@code messageType == INVITE_CARD} のときのみ値を持つ。
     * invite_tokens は role ドメインのためクロスドメイン FK は張らない（原則1）。通常メッセージは NULL。
     */
    @Column(name = "invite_token_id")
    private Long inviteTokenId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private Long forwardedFromId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEdited = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    private LocalDateTime scheduledAt;

    /** 予約送信バッチが配信を実行した日時。NULL の場合は未配信（または通常送信メッセージ）。 */
    private LocalDateTime scheduledSentAt;

    private Long rootId;

    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer reactionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    private LocalDateTime deletedAt;

    /**
     * メッセージ本文を編集する。
     *
     * @param newBody 新しいメッセージ本文
     */
    public void editBody(String newBody) {
        this.body = newBody;
        this.isEdited = true;
    }

    /**
     * メッセージをピン留めする。
     */
    public void pin() {
        this.isPinned = true;
    }

    /**
     * メッセージのピン留めを解除する。
     */
    public void unpin() {
        this.isPinned = false;
    }

    /**
     * リアクション数をインクリメントする。
     */
    public void incrementReactionCount() {
        this.reactionCount++;
    }

    /**
     * リアクション数をデクリメントする。
     */
    public void decrementReactionCount() {
        if (this.reactionCount > 0) {
            this.reactionCount--;
        }
    }

    /**
     * 返信数をインクリメントする。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * 返信数をデクリメントする。0 以下にはならない。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * トップレベルメッセージ（depth == 0）かどうかを判定する。
     *
     * @return depth が 0 の場合 true
     */
    public boolean isRootMessage() {
        return this.depth == 0;
    }

    /**
     * スレッド返信かどうかを判定する。
     *
     * @return parentId が設定されている場合 true
     */
    public boolean isReply() {
        return this.parentId != null;
    }

    /**
     * 転送メッセージかどうかを判定する。
     *
     * @return forwardedFromId が設定されている場合 true
     */
    public boolean isForwarded() {
        return this.forwardedFromId != null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 予約送信バッチによる配信完了をマークする。
     *
     * @param sentAt 配信実行日時
     */
    public void markScheduledSent(LocalDateTime sentAt) {
        this.scheduledSentAt = sentAt;
    }
}
