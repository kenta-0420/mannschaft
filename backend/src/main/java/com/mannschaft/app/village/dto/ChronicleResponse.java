package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageChronicleEntity;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 村史レスポンス。
 *
 * <p>TOP3 トピックは {@code topics} リストに名称と件数を詰めて返す。
 * 集計対象が 3 件未満の場合は実在する件数のみを格納する。</p>
 */
@Builder
public record ChronicleResponse(
        UUID id,
        UUID villageId,
        LocalDate yearMonth,
        LocalDateTime generatedAt,
        Integer postCount,
        Integer newMemberCount,
        List<TopicItem> topics) {

    /**
     * TOP トピック 1 件分の表現。
     */
    @Builder
    public record TopicItem(String name, Integer count) {}

    /**
     * Entity から DTO を生成する。
     */
    public static ChronicleResponse of(VillageChronicleEntity entity) {
        List<TopicItem> topics = new java.util.ArrayList<>(3);
        if (entity.getTopic1Name() != null && !entity.getTopic1Name().isBlank()) {
            topics.add(new TopicItem(entity.getTopic1Name(), entity.getTopic1Count()));
        }
        if (entity.getTopic2Name() != null && !entity.getTopic2Name().isBlank()) {
            topics.add(new TopicItem(entity.getTopic2Name(), entity.getTopic2Count()));
        }
        if (entity.getTopic3Name() != null && !entity.getTopic3Name().isBlank()) {
            topics.add(new TopicItem(entity.getTopic3Name(), entity.getTopic3Count()));
        }
        return ChronicleResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .yearMonth(entity.getYearMonth())
                .generatedAt(entity.getGeneratedAt())
                .postCount(entity.getPostCount())
                .newMemberCount(entity.getNewMemberCount())
                .topics(List.copyOf(topics))
                .build();
    }
}
