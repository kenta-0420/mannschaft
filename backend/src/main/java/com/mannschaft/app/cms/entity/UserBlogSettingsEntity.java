package com.mannschaft.app.cms.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * ユーザーブログ設定エンティティ。セルフレビュー機能の設定を管理する。
 */
@Entity
@Table(name = "user_blog_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class UserBlogSettingsEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean selfReviewEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private LocalTime selfReviewStart = LocalTime.of(23, 0);

    @Column(nullable = false)
    @Builder.Default
    private LocalTime selfReviewEnd = LocalTime.of(6, 0);

    /**
     * セルフレビュー設定を更新する。
     */
    public void update(boolean enabled, LocalTime start, LocalTime end) {
        this.selfReviewEnabled = enabled;
        this.selfReviewStart = start;
        this.selfReviewEnd = end;
    }
}
