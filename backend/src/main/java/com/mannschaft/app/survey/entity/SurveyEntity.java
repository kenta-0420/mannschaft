package com.mannschaft.app.survey.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * アンケートエンティティ。アンケート・投票の基本情報を管理する。
 */
@Entity
@Table(name = "surveys")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SurveyEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SurveyStatus status = SurveyStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAnonymous = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowMultipleSubmissions = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ResultsVisibility resultsVisibility = ResultsVisibility.AFTER_RESPONSE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DistributionMode distributionMode = DistributionMode.ALL;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoPostToTimeline = false;

    @Column(length = 50)
    private String seriesId;

    @Column(columnDefinition = "JSON")
    private String remindBeforeHours;

    @Column(nullable = false)
    @Builder.Default
    private Integer manualRemindCount = 0;

    private LocalDateTime startsAt;

    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer responseCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer targetCount = 0;

    private Long createdBy;

    private LocalDateTime publishedAt;

    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * アンケートを公開する。
     */
    public void publish() {
        this.status = SurveyStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * アンケートを締め切る。
     */
    public void close() {
        this.status = SurveyStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
    }

    /**
     * アンケートをアーカイブする。
     */
    public void archive() {
        this.status = SurveyStatus.ARCHIVED;
    }

    /**
     * タイトルを変更する。
     *
     * @param title 新しいタイトル
     */
    public void changeTitle(String title) {
        this.title = title;
    }

    /**
     * 説明を変更する。
     *
     * @param description 新しい説明
     */
    public void changeDescription(String description) {
        this.description = description;
    }

    /**
     * アンケート設定を更新する。
     *
     * @param isAnonymous              匿名かどうか
     * @param allowMultipleSubmissions  複数回答許可
     * @param resultsVisibility        結果公開設定
     * @param autoPostToTimeline       タイムライン自動投稿
     */
    public void updateSettings(Boolean isAnonymous, Boolean allowMultipleSubmissions,
                               ResultsVisibility resultsVisibility, Boolean autoPostToTimeline) {
        this.isAnonymous = isAnonymous;
        this.allowMultipleSubmissions = allowMultipleSubmissions;
        this.resultsVisibility = resultsVisibility;
        this.autoPostToTimeline = autoPostToTimeline;
    }

    /**
     * 期間を更新する。
     *
     * @param startsAt  開始日時
     * @param expiresAt 終了日時
     */
    public void updatePeriod(LocalDateTime startsAt, LocalDateTime expiresAt) {
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 回答数をインクリメントする。
     */
    public void incrementResponseCount() {
        this.responseCount++;
    }

    /**
     * 対象者数を設定する。
     *
     * @param count 対象者数
     */
    public void updateTargetCount(int count) {
        this.targetCount = count;
    }

    /**
     * 手動リマインド回数をインクリメントする。
     */
    public void incrementManualRemindCount() {
        this.manualRemindCount++;
    }

    /**
     * 公開可能かどうかを判定する。
     *
     * @return DRAFT ステータスの場合 true
     */
    public boolean isPublishable() {
        return this.status == SurveyStatus.DRAFT;
    }

    /**
     * 締め切り可能かどうかを判定する。
     *
     * @return PUBLISHED ステータスの場合 true
     */
    public boolean isClosable() {
        return this.status == SurveyStatus.PUBLISHED;
    }

    /**
     * 回答可能かどうかを判定する。
     *
     * @return PUBLISHED かつ期限内の場合 true
     */
    public boolean isAcceptingResponses() {
        if (this.status != SurveyStatus.PUBLISHED) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (this.startsAt != null && now.isBefore(this.startsAt)) {
            return false;
        }
        return this.expiresAt == null || !now.isAfter(this.expiresAt);
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
