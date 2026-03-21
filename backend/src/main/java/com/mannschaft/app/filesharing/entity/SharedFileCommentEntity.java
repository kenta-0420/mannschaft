package com.mannschaft.app.filesharing.entity;

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
 * ファイルコメントエンティティ。ファイルに対するコメントを管理する。
 */
@Entity
@Table(name = "shared_file_comments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SharedFileCommentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long fileId;

    private Long userId;

    @Column(nullable = false, length = 2000)
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
}
