package com.mannschaft.app.bulletin.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BulletinReplyEntity extends BaseEntity {

    @Column(nullable = false)
    private Long threadId;

    private Long parentId;

    private Long authorId;

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
