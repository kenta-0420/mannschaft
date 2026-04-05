package com.mannschaft.app.activity.entity;

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
 * 活動記録コメントエンティティ。
 */
@Entity
@Table(name = "activity_comments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActivityCommentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long activityResultId;

    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private LocalDateTime deletedAt;

    /**
     * コメントを更新する。
     */
    public void update(String body) {
        this.body = body;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
