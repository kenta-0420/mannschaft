package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 村ニュースレター号 詳細 レスポンス DTO（F17.1 ②-4・設計書 §8.1）。
 *
 * <p>凍結ダイジェスト（全 digest_* + TOP3 トピック）＋ 村長コメント ＋ 付与タグ ＋ 楽観ロック版番号 を
 * 返す。{@code version} はコメント保存・タグ付け・公開範囲切替の楽観ロック（設計書 §4.4）に用いる。</p>
 */
@Builder
public record NewsletterIssueDetailResponse(
        UUID id,
        UUID villageId,
        String title,
        VillageNewsletterFrequency frequency,
        VillageNewsletterIssueType issueType,
        VillageNewsletterIssueStatus status,
        VillageNewsletterVisibility visibility,
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        LocalDateTime aggregatedAt,
        LocalDateTime scheduledPublishAt,
        LocalDateTime publishedAt,
        Integer digestPostCount,
        Integer digestNewMemberCount,
        Integer digestFestivalCount,
        Integer digestMeetupCount,
        Integer digestRecruitCount,
        String digestTopic1Name,
        Integer digestTopic1Count,
        String digestTopic2Name,
        Integer digestTopic2Count,
        String digestTopic3Name,
        Integer digestTopic3Count,
        String headmanComment,
        Long commentUpdatedBy,
        LocalDateTime commentUpdatedAt,
        List<NewsletterTagResponse> tags,
        Long version
) {}
