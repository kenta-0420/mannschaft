package com.mannschaft.app.village.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 号×タグの中間テーブル（F17.1 ②-1・案Y の pull 層）。
 *
 * <p>両側 UUIDv7＝Long の壁を越えない（設計書 §4.7.1）。{@code issue_id}／{@code tag_id} とも
 * 村ドメイン内のため同一ドメイン CASCADE で参照整合を保つ。</p>
 */
@Entity
@Table(name = "village_newsletter_issue_tags")
@IdClass(VillageNewsletterIssueTagEntity.VillageNewsletterIssueTagId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class VillageNewsletterIssueTagEntity {

    /** FK → village_newsletter_issues.id（同一ドメイン CASCADE）。 */
    @Id
    @Column(name = "issue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID issueId;

    /** FK → village_newsletter_tags.id（同一ドメイン CASCADE）。 */
    @Id
    @Column(name = "tag_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tagId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /** 複合主キークラス。 */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VillageNewsletterIssueTagId implements Serializable {
        private UUID issueId;
        private UUID tagId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            VillageNewsletterIssueTagId that = (VillageNewsletterIssueTagId) o;
            return Objects.equals(issueId, that.issueId) && Objects.equals(tagId, that.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(issueId, tagId);
        }
    }
}
