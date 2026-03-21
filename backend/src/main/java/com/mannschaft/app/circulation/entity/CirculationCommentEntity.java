package com.mannschaft.app.circulation.entity;

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
 * 回覧コメントエンティティ。回覧文書に対するコメントを管理する。
 */
@Entity
@Table(name = "circulation_comments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CirculationCommentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String body;

    private LocalDateTime deletedAt;

    /**
     * コメント本文を更新する。
     *
     * @param body 新しいコメント本文
     */
    public void updateBody(String body) {
        this.body = body;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 指定ユーザーが所有者かどうかを判定する。
     *
     * @param userId ユーザーID
     * @return 所有者の場合 true
     */
    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
