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

/**
 * ブログタグエンティティ。
 */
@Entity
@Table(name = "blog_tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BlogTagEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#6B7280";

    @Column(nullable = false)
    @Builder.Default
    private Integer postCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * タグ情報を更新する。
     */
    public void update(String name, String color, Integer sortOrder) {
        this.name = name;
        this.color = color;
        this.sortOrder = sortOrder;
    }

    /**
     * 記事数をインクリメントする。
     */
    public void incrementPostCount() {
        this.postCount++;
    }

    /**
     * 記事数をデクリメントする。
     */
    public void decrementPostCount() {
        if (this.postCount > 0) {
            this.postCount--;
        }
    }
}
