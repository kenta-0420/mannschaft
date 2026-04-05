package com.mannschaft.app.cms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * ブログ記事タグ中間テーブルエンティティ。
 */
@Entity
@Table(name = "blog_post_tags")
@IdClass(BlogPostTagEntity.BlogPostTagId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class BlogPostTagEntity {

    @Id
    @Column(nullable = false)
    private Long blogPostId;

    @Id
    @Column(nullable = false)
    private Long blogTagId;

    /**
     * 複合主キークラス。
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlogPostTagId implements Serializable {
        private Long blogPostId;
        private Long blogTagId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlogPostTagId that = (BlogPostTagId) o;
            return Objects.equals(blogPostId, that.blogPostId)
                    && Objects.equals(blogTagId, that.blogTagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(blogPostId, blogTagId);
        }
    }
}
