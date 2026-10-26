package com.mannschaft.app.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * AC-4: {@code fields_json} の {@code field_type} を {@link FieldType} へ変換する経路
 * （{@code ActivityTemplateService#importPreset} が使う変換ロジックと同一手順）が
 * {@code DATETIME} を例外なく変換できることを検証する。
 *
 * <p>V9.089 が {@code system_activity_template_presets.fields_json} に投入する
 * 「開催日時」フィールドは {@code field_type: 'DATETIME'} を持つ。
 * {@link FieldType} に {@code DATETIME} が無い状態では
 * {@code FieldType.valueOf("DATETIME")} が {@link IllegalArgumentException} を投げ、
 * プリセットインポート（{@code ActivityTemplateService#importPreset}）が失敗する。</p>
 */
@DisplayName("プリセット fields_json → FieldType 変換（DATETIME）テスト")
class ActivityPresetFieldsJsonDatetimeConversionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** V9.089 の議事録プリセット fields_json（meeting_date が DATETIME）。 */
    private static final String MINUTES_FIELDS_JSON = "["
            + "{\"field_key\":\"meeting_date\",\"field_label\":\"開催日時\",\"field_type\":\"DATETIME\","
            + "\"is_required\":true,\"is_aggregatable\":false,\"sort_order\":1},"
            + "{\"field_key\":\"location\",\"field_label\":\"開催場所\",\"field_type\":\"TEXT\","
            + "\"is_required\":false,\"is_aggregatable\":false,\"sort_order\":2},"
            + "{\"field_key\":\"next_meeting\",\"field_label\":\"次回開催予定\",\"field_type\":\"DATE\","
            + "\"is_required\":false,\"is_aggregatable\":false,\"sort_order\":7}"
            + "]";

    @Test
    @DisplayName("AC-4: field_type=DATETIME を含むfields_jsonがFieldTypeへ例外なく変換される")
    void fieldsJsonのDATETIMEがFieldTypeへ変換される() throws Exception {
        List<Map<String, Object>> fields = objectMapper.readValue(
                MINUTES_FIELDS_JSON, new TypeReference<>() {});

        assertThatCode(() -> {
            for (Map<String, Object> field : fields) {
                FieldType.valueOf((String) field.get("field_type"));
            }
        }).doesNotThrowAnyException();

        FieldType meetingDateType = FieldType.valueOf((String) fields.get(0).get("field_type"));
        assertThat(meetingDateType).isEqualTo(FieldType.DATETIME);
    }
}
