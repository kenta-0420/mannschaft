package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.common.BaseEntity;
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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 掲示板返信エンティティ。スレッドへの返信情報を管理する。
 */
@Entity
@Table(name = "bulletin_replies")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BulletinReplyEntity extends BaseEntity {

    @Column(nullable = false)
    private Long threadId;

    private Long parentId;

    /**
     * ネストの深さ（設計書 F05.1 §5）。
     * 0 = スレッド直下、1 = 返信の返信、… 最大4（= 5階層目）。6階層目（depth 5）の作成は 400 で弾く。
     */
    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer depth = 0;

    private Long authorId;

    /**
     * 投稿主体種別（F17.1 Phase 1）。
     * USER（個人投稿）/ TEAM（チーム代表）/ ORGANIZATION（組織代表）。
     * デフォルトは USER。
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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEdited = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer replyCount = 0;

    private LocalDateTime deletedAt;

    /**
     * 返信本文を更新する。
     *
     * @param body 新しい本文
     */
    public void updateBody(String body) {
        this.body = body;
        this.isEdited = true;
    }

    /**
     * 子返信カウントをインクリメントする。
     */
    public void incrementReplyCount() {
        this.replyCount++;
    }

    /**
     * 子返信カウントをデクリメントする。
     */
    public void decrementReplyCount() {
        if (this.replyCount > 0) {
            this.replyCount--;
        }
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
