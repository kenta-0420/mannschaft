package com.mannschaft.app.knowledgebase.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ナレッジベースページエンティティ。
 */
@Entity
@Table(name = "kb_pages")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class KbPageEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private KbPageEntity parent;

    @Column(nullable = false, length = 1000)
    private String path;

    @Column(nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String body;

    @Column(length = 50)
    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PageAccessLevel accessLevel = PageAccessLevel.ALL_MEMBERS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private PageStatus status = PageStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(nullable = false)
    private Long createdBy;

    private Long lastEditedBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 閲覧数を1増加させる。
     */
    public void incrementViewCount() {
        this.viewCount = this.viewCount + 1;
    }

    /**
     * ページ内容を更新する（toBuilder → UPDATE 化バグを防ぐドメインメソッド）。
     * 呼び出し前に旧値をローカル変数へ捕捉し、比較・監査ログに使用すること。
     */
    public void applyUpdate(String title, String body, String icon,
                            PageAccessLevel accessLevel, Long lastEditedBy) {
        if (title != null) this.title = title;
        if (body != null) this.body = body;
        if (icon != null) this.icon = icon;
        if (accessLevel != null) this.accessLevel = accessLevel;
        if (lastEditedBy != null) this.lastEditedBy = lastEditedBy;
    }

    /**
     * ページのパスと深さを更新する（移動操作用）。
     */
    public void applyMove(KbPageEntity newParent, String newPath, int newDepth) {
        this.parent = newParent;
        this.path = newPath;
        this.depth = newDepth;
    }

    /**
     * ページのパスを設定する（作成直後のID確定後 path 更新用）。
     */
    public void updatePath(String path) {
        this.path = path;
    }

    /**
     * ページのステータスを変更する（公開・アーカイブ用）。
     */
    public void applyStatus(PageStatus newStatus) {
        this.status = newStatus;
    }
}
