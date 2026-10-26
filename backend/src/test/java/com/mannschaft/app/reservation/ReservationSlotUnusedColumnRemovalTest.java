package com.mannschaft.app.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>予約スロットの未使用足場 3 本（{@code recurrence_rule} / {@code parent_slot_id} /
 * {@code is_exception}）が Entity・Repository・API レスポンスから完全に撤去されていること</b>を
 * 恒久的に番人化するテスト。
 *
 * <h2>背景</h2>
 * <p>この 3 本は F03.4 初期 DDL（V3.061）で「将来の繰り返し枠」を見越して置かれたが、
 * 展開ロジック・日次バッチ・書き込み経路のいずれも実装されないまま残置され、
 * 代替として {@code reservation_slot_templates}（週間テンプレート＋日次バッチ生成）が稼働している。
 * F03.4.2 §3.3 のクリーンアップ裁可により列ごと撤去した。</p>
 *
 * <p>再導入されると「値は入るが何も起きない」休眠足場が API 契約に再び現れるため、
 * Entity フィールド・Repository メソッド・レスポンス DTO の 3 面から復活を検出する。</p>
 */
@DisplayName("予約スロット 未使用列 3 本 撤去番人テスト")
class ReservationSlotUnusedColumnRemovalTest {

    /** LocalDate/LocalTime を含む DTO を実 API と同じ規則でシリアライズするため JSR-310 モジュールを登録する。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    @DisplayName("ReservationSlotEntity に recurrenceRule/parentSlotId/isException フィールドが存在しない")
    void エンティティから未使用3フィールドが撤去されている() {
        var fieldNames = Arrays.stream(ReservationSlotEntity.class.getDeclaredFields())
                .map(Field::getName)
                .toList();

        assertThat(fieldNames)
                .as("休眠足場の 3 フィールドは撤去済みであること")
                .doesNotContain("recurrenceRule", "parentSlotId", "isException");
    }

    @Test
    @DisplayName("ReservationSlotEntity に isRecurring() が存在しない")
    void エンティティからisRecurringが撤去されている() {
        var methodNames = Arrays.stream(ReservationSlotEntity.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .as("recurrenceRule 依存の isRecurring() は撤去済みであること")
                .doesNotContain("isRecurring", "getRecurrenceRule", "getParentSlotId", "getIsException");
    }

    @Test
    @DisplayName("ReservationSlotRepository に findByParentSlotIdOrderBySlotDateAsc が存在しない")
    void リポジトリから未使用finderが撤去されている() {
        var methodNames = Arrays.stream(ReservationSlotRepository.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .as("呼び出し元ゼロの parent_slot_id finder は撤去済みであること")
                .doesNotContain("findByParentSlotIdOrderBySlotDateAsc");
    }

    @Test
    @DisplayName("ReservationSlotResponse に recurrence フィールドと RecurrenceDto が存在しない")
    void レスポンスDTOからrecurrenceが撤去されている() {
        var fieldNames = Arrays.stream(ReservationSlotResponse.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fieldNames)
                .as("recurrence フィールドは撤去済みであること")
                .doesNotContain("recurrence");

        var nestedNames = Arrays.stream(ReservationSlotResponse.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .toList();
        assertThat(nestedNames)
                .as("RecurrenceDto は撤去済みであること")
                .doesNotContain("RecurrenceDto");
    }

    @Test
    @DisplayName("SlotStatusDto の要素に isException が含まれない")
    void ステータスDTOからisExceptionが撤去されている() {
        var componentNames = Arrays.stream(ReservationSlotResponse.SlotStatusDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames)
                .as("isException は撤去済みであること")
                .doesNotContain("isException");
    }

    @Test
    @DisplayName("API レスポンス JSON に recurrence / isException が出力されない")
    void レスポンスJSONに未使用フィールドが出力されない() throws Exception {
        ReservationSlotResponse response = ReservationSlotResponse.builder()
                .id(1L)
                .teamId(100L)
                .basic(new ReservationSlotResponse.SlotBasicDto(
                        "体験レッスン", LocalDate.of(2026, 8, 4), LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .status(new ReservationSlotResponse.SlotStatusDto(
                        "AVAILABLE", 0, 1, null, null))
                .build();

        String json = OBJECT_MAPPER.writeValueAsString(response);

        assertThat(json)
                .as("休眠足場は API 契約に現れないこと")
                .doesNotContain("recurrence")
                .doesNotContain("parentSlotId")
                .doesNotContain("isException");
    }
}
