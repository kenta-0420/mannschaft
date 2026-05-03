package com.mannschaft.app.corkboard.entity;

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
 * コルクボードカードエンティティ。ボード上の各カード（参照・メモ・URL・見出し）を管理する。
 */
@Entity
@Table(name = "corkboard_cards")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CorkboardCardEntity extends BaseEntity {

    @Column(nullable = false)
    private Long corkboardId;

    @Column(nullable = false, length = 20)
    private String cardType;

    @Column(length = 30)
    private String referenceType;

    private Long referenceId;

    @Column(columnDefinition = "TEXT")
    private String contentSnapshot;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 2000)
    private String url;

    @Column(length = 200)
    private String ogTitle;

    @Column(length = 500)
    private String ogImageUrl;

    @Column(length = 500)
    private String ogDescription;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String colorLabel = "NONE";

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String cardSize = "MEDIUM";

    @Column(nullable = false)
    @Builder.Default
    private Integer positionX = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer positionY = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer zIndex = 0;

    @Column(columnDefinition = "TEXT")
    private String userNote;

    private LocalDateTime autoArchiveAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(name = "pinned_at")
    private LocalDateTime pinnedAt;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * カード情報を更新する。
     */
    public void update(String title, String body, String url, String colorLabel, String cardSize,
                       Integer positionX, Integer positionY, Integer zIndex, String userNote,
                       LocalDateTime autoArchiveAt) {
        this.title = title;
        this.body = body;
        this.url = url;
        this.colorLabel = colorLabel;
        this.cardSize = cardSize;
        this.positionX = positionX;
        this.positionY = positionY;
        this.zIndex = zIndex;
        this.userNote = userNote;
        this.autoArchiveAt = autoArchiveAt;
    }

    /**
     * カード位置を更新する。
     */
    public void updatePosition(Integer positionX, Integer positionY, Integer zIndex) {
        this.positionX = positionX;
        this.positionY = positionY;
        this.zIndex = zIndex;
    }

    /**
     * アーカイブ状態を変更する。
     */
    public void archive(boolean archived) {
        this.isArchived = archived;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
