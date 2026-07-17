package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 号×タグの中間テーブル（F17.1 ②-1・案Y の pull 層）。
 *
 * <p>両側 UUIDv7＝Long の壁を越えない（設計書 §4.7.1）。{@code issue_id}／{@code tag_id} とも
 * 村ドメイン内のため同一ドメイン CASCADE で参照整合を保つ。</p>
 *
 * <h2>主キーについて（設計書 §4.7.1 からの相違）</h2>
 * <p>設計書は複合主キー {@code (issue_id, tag_id)} を提示するが、本プロジェクトは原則6を
 * ArchUnit 番人（{@code EntityUuidV7ConventionArchTest} D-2b）で機械的に強制しており、
 * <b>新規 {@code @Entity} は例外なく {@link UuidV7Entity} 継承（UUIDv7 サロゲート PK）</b>を要求する。
 * 既存の中間表（{@code BlogPostTagEntity} 等）は enforcement 導入前の凍結免除であって新規は対象外。
 * したがって本中間表は UUIDv7 サロゲート PK を持ち、リンクの一意性は {@code UNIQUE (issue_id, tag_id)}
 * で担保する（両側 UUID＝Long 壁は越えない点は設計意図どおり）。</p>
 */
@Entity
@Table(name = "village_newsletter_issue_tags")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageNewsletterIssueTagEntity extends UuidV7Entity {

    /** FK → village_newsletter_issues.id（同一ドメイン CASCADE）。 */
    @Column(name = "issue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID issueId;

    /** FK → village_newsletter_tags.id（同一ドメイン CASCADE）。 */
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
}
