package com.mannschaft.app.proxyvote.entity;

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
 * 議案コメントエンティティ。投票前の疑問解消・討論記録として機能する。
 */
@Entity
@Table(name = "proxy_vote_motion_comments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyVoteMotionCommentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long motionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
