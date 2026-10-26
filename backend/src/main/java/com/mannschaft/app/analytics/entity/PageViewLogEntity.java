package com.mannschaft.app.analytics.entity;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ページビュー生ログエンティティ（F10.8 アクセス解析）。
 *
 * <p>FE ビーコンが 1 閲覧ごとに 1 行を非同期 INSERT する高頻度書き込みテーブル。
 * スケール手本は {@code audit_logs}（F10.3 / V64.001）の月次レンジパーティション。</p>
 *
 * <h2>主キーの罠（重要）</h2>
 * <p>DB DDL は複合 PK {@code (id, viewed_at)}（月次レンジパーティションのキーは全 UNIQUE
 * インデックスに含める必要があるため）だが、<b>JPA は {@code UuidV7Entity} の単一
 * {@code @Id UUID id} のままとする</b>。{@code @IdClass} / {@code @EmbeddedId} は使わない。
 * これは {@code auth.entity.AuditLogEntity} が単一 {@code @Id Long id} で DB 複合 PK
 * {@code (id, created_at)} を扱う前例と同一（複合 ID を導入するとむしろ壊れる）。</p>
 *
 * <h2>クロスドメイン FK 禁止</h2>
 * <p>{@code user_id} / {@code scope_id} / {@code content_id} は {@code users} / {@code teams}
 * / {@code organizations} 等へ FK を張らず、参照整合性はアプリ層で保証する（CLAUDE.md 原則 1）。</p>
 */
@Entity
@Table(name = "page_view_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PageViewLogEntity extends UuidV7Entity {

    /** スコープ種別（{@code TEAM} / {@code ORGANIZATION}）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PageViewScopeType scopeType;

    /** チーム/組織 ID（FK なし・アプリ層で整合性保証）。 */
    @Column(nullable = false)
    private Long scopeId;

    /** 閲覧対象種別（{@code ARTICLE} / {@code ACTIVITY} / {@code PAGE} / {@code TEAM}）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PageViewContentType contentType;

    /** 閲覧対象 ID。ID を持たない種別（{@code PAGE} 等）は 0 固定。 */
    @Column(nullable = false)
    private Long contentId;

    /** アプリ内相対パス。 */
    @Column(nullable = false, length = 512)
    private String url;

    /** 表示タイトル。 */
    @Column(nullable = false, length = 255)
    private String title;

    /** ログイン利用者 ID。NULL = ゲスト（未ログイン）。FK なし。 */
    @Column
    private Long userId;

    /** 匿名 cookie の UUID（個人特定不能）。 */
    @Column(nullable = false, length = 36)
    private String visitorId;

    /** 閲覧日時（月次パーティションキー）。 */
    @Column(nullable = false)
    private LocalDateTime viewedAt;

    /** レコード作成日時。 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.viewedAt == null) {
            this.viewedAt = now;
        }
        if (this.createdAt == null) {
            this.createdAt = now;
        }
    }
}
