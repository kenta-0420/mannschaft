package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 範囲テンプレートのグループ3項目（個別グループ・範囲）と DB の JSON 列との相互変換（F01.2.1 §5.6・§8.6）。
 *
 * <p>{@code target_group_ids} は UUID 文字列の JSON 配列、{@code target_group_range} は
 * {@code {"from_group_id":..,"to_group_id":..}}（snake_case。端が無ければ null）で保存する。</p>
 */
public final class TemplateGroupItemsCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TemplateGroupItemsCodec() {
    }

    /** 個別グループ ID を JSON にする。null・空配列は「個別選択なし」として null。 */
    public static String groupIdsToJson(List<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return null;
        }
        return write(groupIds.stream().map(UUID::toString).toList());
    }

    /** 範囲を JSON にする。null は「範囲指定なし」として null。 */
    public static String rangeToJson(TargetGroupRange range) {
        if (range == null) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put("from_group_id", range.fromGroupId() == null ? null : range.fromGroupId().toString());
        map.put("to_group_id", range.toGroupId() == null ? null : range.toGroupId().toString());
        return write(map);
    }

    /** 個別グループ ID の JSON を読む。null・空は null。 */
    public static List<UUID> parseGroupIds(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> raw = MAPPER.readValue(json, new TypeReference<List<String>>() { });
            return raw.stream().map(UUID::fromString).toList();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("target_group_ids の JSON を読めません: " + json, e);
        }
    }

    /** 範囲の JSON を読む。null・空は null。 */
    public static TargetGroupRange parseRange(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new TargetGroupRange(uuidOrNull(node.get("from_group_id")), uuidOrNull(node.get("to_group_id")));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("target_group_range の JSON を読めません: " + json, e);
        }
    }

    /** 「チームを選ぶ」の target_team_ids（JSON 配列）を読む。null・空は null。 */
    public static List<Long> parseTeamIds(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Long>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("target_team_ids の JSON を読めません: " + json, e);
        }
    }

    private static UUID uuidOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("テンプレートのグループ項目を JSON に変換できません", e);
        }
    }
}
