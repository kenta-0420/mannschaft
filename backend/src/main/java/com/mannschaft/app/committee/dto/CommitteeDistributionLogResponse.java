package com.mannschaft.app.committee.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * F04.10 委員会伝達処理ログ レスポンス DTO。
 */
@Slf4j
@Getter
@Builder
public class CommitteeDistributionLogResponse {

    private Long id;
    private Long committeeId;
    private String contentType;
    private Long contentId;
    private String customTitle;
    private String customBody;
    private DistributionScope targetScope;
    private boolean announcementEnabled;
    private ConfirmationMode confirmationMode;
    private Long confirmableNotificationId;
    /** announcement_feeds の ID リスト（JSON 文字列からパース済み） */
    private List<Long> announcementFeedIds;
    private Long createdBy;
    private LocalDateTime createdAt;

    /**
     * エンティティから DTO を生成するファクトリメソッド。
     *
     * @param entity       伝達処理ログエンティティ
     * @param objectMapper JSON 変換用 ObjectMapper
     * @return レスポンス DTO
     */
    public static CommitteeDistributionLogResponse of(
            CommitteeDistributionLogEntity entity,
            ObjectMapper objectMapper) {

        List<Long> feedIds = parseAnnouncementFeedIds(entity.getAnnouncementFeedIds(), objectMapper);

        return CommitteeDistributionLogResponse.builder()
                .id(entity.getId())
                .committeeId(entity.getCommitteeId())
                .contentType(entity.getContentType())
                .contentId(entity.getContentId())
                .customTitle(entity.getCustomTitle())
                .customBody(entity.getCustomBody())
                .targetScope(entity.getTargetScope())
                .announcementEnabled(entity.isAnnouncementEnabled())
                .confirmationMode(entity.getConfirmationMode())
                .confirmableNotificationId(entity.getConfirmableNotificationId())
                .announcementFeedIds(feedIds)
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * JSON 文字列を List&lt;Long&gt; に変換する。
     * 変換失敗時は空リストを返す（ログ出力あり）。
     */
    private static List<Long> parseAnnouncementFeedIds(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("announcementFeedIds の JSON パースに失敗しました: json={}", json, e);
            return Collections.emptyList();
        }
    }
}
