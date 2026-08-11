package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.PresetResponse;
import com.mannschaft.app.activity.service.SystemActivityPresetService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V9.089（議事録プリセットシード）が実際に投入する {@code system_activity_template_presets} 行を
 * {@link SystemActivityPresetService#listPresets()} が例外なく読める（=マッピング可能な enum 値である）
 * ことを検証する結合テスト。
 *
 * <p><b>背景</b>: V9.089 は {@code category} 列に {@code 'COMMITTEE'} を、
 * {@code fields_json} の {@code field_type} に {@code 'DATETIME'} を INSERT するが、
 * {@link PresetCategory} には {@code COMMITTEE} が、{@link FieldType} には {@code DATETIME} が
 * 存在しなかった。{@code category} は {@code @Enumerated(EnumType.STRING)} でマッピングされるため、
 * この1行が存在するだけで {@code listPresets()} が丸ごと {@link IllegalArgumentException} で
 * 落ちる（プリセット一覧 API 全体が 500 になる）。</p>
 *
 * <p><b>test profile の制約</b>: {@code application-test.yml} は {@code spring.flyway.enabled: false}
 * かつ {@code ddl-auto: create} であり、Flyway のシードは投入されない
 * （Entity 由来の DDL のみ）。そのため本テストは V9.089 の INSERT 文と同一の値を
 * {@link JdbcTemplate} で明示的に投入し、実際にマイグレーションが投入する行を再現する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("V9.089 議事録プリセットシード（COMMITTEE / DATETIME）結合テスト")
class SystemActivityPresetCommitteeSeedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private SystemActivityPresetService presetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** V9.089 が実際に INSERT する fields_json（meeting_date の field_type='DATETIME' を含む）。 */
    private static final String MINUTES_FIELDS_JSON = "["
            + "{\"field_key\":\"meeting_date\",\"field_label\":\"開催日時\",\"field_type\":\"DATETIME\","
            + "\"is_required\":true,\"is_aggregatable\":false,\"sort_order\":1},"
            + "{\"field_key\":\"location\",\"field_label\":\"開催場所\",\"field_type\":\"TEXT\","
            + "\"is_required\":false,\"is_aggregatable\":false,\"sort_order\":2},"
            + "{\"field_key\":\"agenda\",\"field_label\":\"議題\",\"field_type\":\"TEXTAREA\","
            + "\"is_required\":true,\"is_aggregatable\":false,\"sort_order\":3}"
            + "]";

    /** V9.089 の INSERT 文を再現して議事録プリセット行を投入する。 */
    private void seedMinutesPreset() {
        jdbcTemplate.update(
                "INSERT INTO system_activity_template_presets "
                        + "(category, name, description, icon, color, is_participant_required, "
                        + " default_visibility, fields_json, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                "COMMITTEE", "議事録", "委員会の議事録テンプレート", "📋", "#7C3AED",
                true, "MEMBERS_ONLY", MINUTES_FIELDS_JSON, true);
    }

    @Test
    @DisplayName("AC-3: category=COMMITTEE の行が存在してもlistPresetsが例外なく成功し議事録プリセットを含む")
    void listPresets_COMMITTEE行を含んでも例外なく成功する() {
        seedMinutesPreset();

        assertThatCode(() -> presetService.listPresets()).doesNotThrowAnyException();

        List<PresetResponse> presets = presetService.listPresets();
        assertThat(presets).extracting(PresetResponse::getName).contains("議事録");
        assertThat(presets).extracting(PresetResponse::getCategory).contains("COMMITTEE");
    }
}
