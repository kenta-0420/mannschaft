package com.mannschaft.app.digest;

import com.mannschaft.app.digest.dto.DigestConfigResponse;
import com.mannschaft.app.digest.dto.DigestDetailResponse;
import com.mannschaft.app.digest.dto.DigestSummaryResponse;
import com.mannschaft.app.digest.dto.DigestTriggeredByResponse;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import java.time.LocalDateTime;
import java.time.LocalTime;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class DigestMapperImpl implements DigestMapper {

    @Override
    public DigestConfigResponse toConfigResponse(TimelineDigestConfigEntity config) {
        if ( config == null ) {
            return null;
        }

        Long id = null;
        LocalTime scheduleTime = null;
        Integer scheduleDayOfWeek = null;
        LocalDateTime lastExecutedAt = null;
        String timezone = null;
        Boolean includeReactions = null;
        Boolean includePolls = null;
        Boolean includeDiffFromPrevious = null;
        Boolean autoPublish = null;
        String stylePresets = null;
        Integer minPostsThreshold = null;
        Integer maxPostsPerDigest = null;
        Integer contentMaxChars = null;
        String language = null;
        String customPromptSuffix = null;
        String autoTagIds = null;
        Boolean isEnabled = null;

        id = config.getId();
        scheduleTime = config.getScheduleTime();
        scheduleDayOfWeek = config.getScheduleDayOfWeek();
        lastExecutedAt = config.getLastExecutedAt();
        timezone = config.getTimezone();
        includeReactions = config.getIncludeReactions();
        includePolls = config.getIncludePolls();
        includeDiffFromPrevious = config.getIncludeDiffFromPrevious();
        autoPublish = config.getAutoPublish();
        stylePresets = config.getStylePresets();
        minPostsThreshold = config.getMinPostsThreshold();
        maxPostsPerDigest = config.getMaxPostsPerDigest();
        contentMaxChars = config.getContentMaxChars();
        language = config.getLanguage();
        customPromptSuffix = config.getCustomPromptSuffix();
        autoTagIds = config.getAutoTagIds();
        isEnabled = config.getIsEnabled();

        String scheduleType = config.getScheduleType() != null ? config.getScheduleType().name() : null;
        String digestStyle = config.getDigestStyle() != null ? config.getDigestStyle().name() : null;

        DigestConfigResponse digestConfigResponse = new DigestConfigResponse( id, scheduleType, scheduleTime, scheduleDayOfWeek, lastExecutedAt, timezone, digestStyle, includeReactions, includePolls, includeDiffFromPrevious, autoPublish, stylePresets, minPostsThreshold, maxPostsPerDigest, contentMaxChars, language, customPromptSuffix, autoTagIds, isEnabled );

        return digestConfigResponse;
    }

    @Override
    public DigestSummaryResponse toSummaryResponse(TimelineDigestEntity digest) {
        if ( digest == null ) {
            return null;
        }

        Long id = null;
        LocalDateTime periodStart = null;
        LocalDateTime periodEnd = null;
        Integer postCount = null;
        String generatedTitle = null;
        Long blogPostId = null;
        LocalDateTime createdAt = null;

        id = digest.getId();
        periodStart = digest.getPeriodStart();
        periodEnd = digest.getPeriodEnd();
        postCount = digest.getPostCount();
        generatedTitle = digest.getGeneratedTitle();
        blogPostId = digest.getBlogPostId();
        createdAt = digest.getCreatedAt();

        String digestStyle = digest.getDigestStyle() != null ? digest.getDigestStyle().name() : null;
        String status = digest.getStatus() != null ? digest.getStatus().name() : null;

        DigestSummaryResponse digestSummaryResponse = new DigestSummaryResponse( id, periodStart, periodEnd, postCount, digestStyle, generatedTitle, status, blogPostId, createdAt );

        return digestSummaryResponse;
    }

    @Override
    public DigestDetailResponse toDetailResponse(TimelineDigestEntity digest) {
        if ( digest == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        LocalDateTime periodStart = null;
        LocalDateTime periodEnd = null;
        Integer postCount = null;
        String generatedTitle = null;
        String generatedBody = null;
        String generatedExcerpt = null;
        String aiModel = null;
        Integer aiInputTokens = null;
        Integer aiOutputTokens = null;
        Long blogPostId = null;
        LocalDateTime createdAt = null;

        id = digest.getId();
        scopeId = digest.getScopeId();
        periodStart = digest.getPeriodStart();
        periodEnd = digest.getPeriodEnd();
        postCount = digest.getPostCount();
        generatedTitle = digest.getGeneratedTitle();
        generatedBody = digest.getGeneratedBody();
        generatedExcerpt = digest.getGeneratedExcerpt();
        aiModel = digest.getAiModel();
        aiInputTokens = digest.getAiInputTokens();
        aiOutputTokens = digest.getAiOutputTokens();
        blogPostId = digest.getBlogPostId();
        createdAt = digest.getCreatedAt();

        String scopeType = digest.getScopeType() != null ? digest.getScopeType().name() : null;
        String digestStyle = digest.getDigestStyle() != null ? digest.getDigestStyle().name() : null;
        String status = digest.getStatus() != null ? digest.getStatus().name() : null;
        DigestTriggeredByResponse triggeredBy = null;

        DigestDetailResponse digestDetailResponse = new DigestDetailResponse( id, scopeType, scopeId, periodStart, periodEnd, postCount, digestStyle, generatedTitle, generatedBody, generatedExcerpt, aiModel, aiInputTokens, aiOutputTokens, status, blogPostId, triggeredBy, createdAt );

        return digestDetailResponse;
    }
}
