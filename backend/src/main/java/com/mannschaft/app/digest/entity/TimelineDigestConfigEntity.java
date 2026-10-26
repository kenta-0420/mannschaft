package com.mannschaft.app.digest.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStyle;
import com.mannschaft.app.digest.ScheduleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * タイムラインダイジェスト自動生成設定エンティティ。
 * チーム/組織ごとに1つの有効な設定を持てる。
 */
@Entity
@Table(name = "timeline_digest_configs")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class TimelineDigestConfigEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DigestScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleType scheduleType;

    private LocalTime scheduleTime;

    private Integer scheduleDayOfWeek;

    private LocalDateTime lastExecutedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DigestStyle digestStyle;

    @Column(nullable = false)
    private Boolean autoPublish;

    @Column(columnDefinition = "JSON")
    private String stylePresets;

    @Column(nullable = false)
    private Boolean includeReactions;

    @Column(nullable = false)
    private Boolean includePolls;

    @Column(nullable = false)
    private Boolean includeDiffFromPrevious;

    @Column(nullable = false)
    private Integer minPostsThreshold;

    @Column(nullable = false)
    private Integer maxPostsPerDigest;

    @Column(nullable = false, length = 50)
    private String timezone;

    @Column(nullable = false)
    private Integer contentMaxChars;

    @Column(nullable = false, length = 10)
    private String language;

    @Column(columnDefinition = "TEXT")
    private String customPromptSuffix;

    @Column(columnDefinition = "JSON")
    private String autoTagIds;

    @Column(nullable = false)
    private Boolean isEnabled;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * ダイジェスト設定を更新する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpdate(ScheduleType scheduleType, LocalTime scheduleTime,
                            Integer scheduleDayOfWeek, DigestStyle digestStyle, Boolean autoPublish,
                            String stylePresets, Boolean includeReactions, Boolean includePolls,
                            Boolean includeDiffFromPrevious, Integer minPostsThreshold,
                            Integer maxPostsPerDigest, String timezone, Integer contentMaxChars,
                            String language, String customPromptSuffix, String autoTagIds) {
        this.scheduleType = scheduleType;
        this.scheduleTime = scheduleTime;
        this.scheduleDayOfWeek = scheduleDayOfWeek;
        this.digestStyle = digestStyle;
        this.autoPublish = autoPublish;
        this.stylePresets = stylePresets;
        this.includeReactions = includeReactions;
        this.includePolls = includePolls;
        this.includeDiffFromPrevious = includeDiffFromPrevious;
        this.minPostsThreshold = minPostsThreshold;
        this.maxPostsPerDigest = maxPostsPerDigest;
        this.timezone = timezone;
        this.contentMaxChars = contentMaxChars;
        this.language = language;
        this.customPromptSuffix = customPromptSuffix;
        this.autoTagIds = autoTagIds;
    }

    /**
     * ダイジェスト設定を論理削除（無効化）する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     */
    public void deactivateAndDelete() {
        this.deletedAt = LocalDateTime.now();
        this.isEnabled = false;
    }
}
