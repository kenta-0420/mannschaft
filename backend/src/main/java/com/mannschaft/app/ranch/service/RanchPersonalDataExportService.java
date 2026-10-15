package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** GDPR collector専用。Ranch所有表を本人user_id条件で読み、不変JSONへ集約する。 */
@Service
@RequiredArgsConstructor
public class RanchPersonalDataExportService {
    /** 技術的なhash、署名材料、binary同一性keyを輸出しない固定列一覧。 */
    private static final Map<String, String> EXPORT_COLUMNS = Map.ofEntries(
            Map.entry("ranch_owners", "id,created_at,updated_at,user_id,status,balance,view_mode,render_style,motion_mode,is_sound_enabled,sound_volume,version"),
            Map.entry("ranch_dinosaurs", "id,created_at,updated_at,owner_id,user_id,habitat,species_key,variant_key,species_catalog_version,assignment_method,selection_confirmed_at,assignment_rule_version,assignment_result_id,egg_started_at,egg_ready_at,egg_rule_snapshot,hatched_at,name,named_at,stage,xp,growth_rule_snapshot,affinity,affinity_rule_snapshot,version"),
            Map.entry("ranch_participation_periods", "id,created_at,updated_at,owner_id,user_id,starts_at,ends_at"),
            Map.entry("ranch_care_week_budgets", "id,created_at,updated_at,owner_id,user_id,week_starts_on,rule_id,rule_snapshot,weekly_cap_xp,awarded_xp,version"),
            Map.entry("ranch_affinity_units", "id,created_at,updated_at,owner_id,user_id,dinosaur_id,earned_on,kind,gain"),
            Map.entry("ranch_point_ledger", "id,created_at,updated_at,owner_id,user_id,decision_id,command_id,entry_kind,delta_points,balance_after,delta_xp,dinosaur_id,rule_snapshot,occurred_at"),
            Map.entry("ranch_commands", "id,created_at,updated_at,owner_id,user_id,command_type,result_json,completed_at"),
            Map.entry("ranch_room_placements", "id,created_at,updated_at,owner_id,user_id,slot_key,inventory_id,version"),
            Map.entry("ranch_collectible_inventory", "id,created_at,updated_at,owner_id,user_id,collectible_key,acquisition_kind,legacy_badge_id,legacy_award_period,sku_key,price_version,awarded_at,is_revoked"),
            Map.entry("ranch_week_budgets", "id,created_at,updated_at,owner_id,user_id,week_starts_on,policy_id,rule_snapshot,global_cap,awarded_total,source_counts,version"),
            Map.entry("ranch_reward_decisions", "id,created_at,updated_at,owner_id,user_id,event_id,source_type,reward_week,policy_id,status,requested_points,awarded_points,occurred_at,decided_at")
    );
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public String exportUser(Long userId) {
        Objects.requireNonNull(userId);
        if (userId <= 0) throw new IllegalArgumentException("本人IDが不正です");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("owners", rows("ranch_owners", userId));
        data.put("dinosaurs", rows("ranch_dinosaurs", userId));
        data.put("participationPeriods", rows("ranch_participation_periods", userId));
        data.put("careWeekBudgets", rows("ranch_care_week_budgets", userId));
        data.put("affinityUnits", rows("ranch_affinity_units", userId));
        data.put("pointLedger", rows("ranch_point_ledger", userId));
        data.put("commands", rows("ranch_commands", userId));
        data.put("roomPlacements", rows("ranch_room_placements", userId));
        data.put("collectibleInventory", rows("ranch_collectible_inventory", userId));
        data.put("rewardWeekBudgets", rows("ranch_week_budgets", userId));
        data.put("rewardDecisions", rows("ranch_reward_decisions", userId));
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("牧場データのexport JSONを生成できません", exception);
        }
    }

    private List<Map<String, Object>> rows(String table, Long userId) {
        // table/columnsはこのクラス内の固定11値だけ。client由来のSQL識別子は受け取らない。
        String columns = Objects.requireNonNull(EXPORT_COLUMNS.get(table));
        return jdbc.query("SELECT " + columns + " FROM " + table + " WHERE user_id = ? ORDER BY id",
                (rs, rowNumber) -> row(rs), userId);
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int index = 1; index <= meta.getColumnCount(); index++) {
            String name = meta.getColumnLabel(index);
            Object value = rs.getObject(index);
            result.put(name, exportedValue(name, value));
        }
        return result;
    }

    private Object exportedValue(String column, Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof byte[] bytes) {
            if (bytes.length == 16 && (column.equals("id") || column.endsWith("_id"))) {
                ByteBuffer binary = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                return new UUID(binary.getLong(), binary.getLong()).toString();
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }
}
