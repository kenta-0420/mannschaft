package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 予約出欠募集リクエスト（{@link ScheduledAttendanceRequest}）のデシリアライズ契約テスト（Issue #2508 早馬）。
 *
 * <h2>回帰防止（欠陥A）</h2>
 * <p>FE（{@code ScheduleEventForm.vue}）は {@code scheduledAt} と {@code attendanceDeadline} の
 * <b>両方</b>を {@code buildOffsetDateTimeStr()} で「オフセット付き」文字列として送信する
 * （例: {@code "2026-08-01T09:00:00+09:00"}）。しかし {@code attendanceDeadline} だけが
 * {@code LocalDateTime} 宣言だったため、オフセット付き文字列を受け取れず
 * {@code InvalidFormatException}（→ HTTP 400）で落ちていた。</p>
 *
 * <p>本テストは本番と同一の Jackson 構成（{@link JacksonConfig#objectMapper}）で
 * 実際の JSON ボディをデシリアライズし、以下を恒久的に保証する。</p>
 * <ul>
 *   <li>AC-1: {@code +09:00} / {@code Z} いずれのオフセット付き文字列も受理される</li>
 *   <li>後方互換: オフセット無しの旧クライアント文字列も受理し、JST として解釈する</li>
 *   <li>AC-4: {@code attendanceDeadline} 省略時は null（既存挙動の非退行）</li>
 * </ul>
 */
@DisplayName("ScheduledAttendanceRequest デシリアライズ契約テスト（Issue #2508 欠陥A）")
class ScheduledAttendanceRequestDeserializationTest {

    /** 本番と同一構成の ObjectMapper（JacksonConfig の実コードを通す）。 */
    private final ObjectMapper objectMapper =
            new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());

    /**
     * FE が実際に送るボディ形状（{@code CreateScheduleRequest} に scheduledAttendance をネスト）を組み立てる。
     *
     * @param deadlineJson attendanceDeadline に入れる JSON リテラル（{@code null} 文字列も可）
     */
    private String createScheduleBody(String deadlineJson) {
        return """
                {
                  "title": "予約出欠テスト",
                  "startAt": "2026-08-05T10:00:00+09:00",
                  "endAt": "2026-08-05T12:00:00+09:00",
                  "allDay": false,
                  "eventType": "PRACTICE",
                  "attendanceRequired": true,
                  "scheduledAttendance": {
                    "scheduledAt": "2026-08-01T09:00:00+09:00",
                    "attendanceDeadline": %s,
                    "commentOption": "REQUIRED",
                    "minResponseRole": "ADMIN_ONLY"
                  }
                }
                """.formatted(deadlineJson);
    }

    @Test
    @DisplayName("AC-1: attendanceDeadline が +09:00 付き文字列でもデシリアライズできる")
    void attendanceDeadline_オフセット付きJST_受理される() throws Exception {
        String body = createScheduleBody("\"2026-08-02T18:00:00+09:00\"");

        CreateScheduleRequest req = objectMapper.readValue(body, CreateScheduleRequest.class);

        ScheduledAttendanceRequest attendance = req.getScheduledAttendance();
        assertThat(attendance).isNotNull();
        assertThat(attendance.getAttendanceDeadline())
                .as("+09:00 付きの回答締切がオフセットごと復元されること")
                .isEqualTo(OffsetDateTime.of(2026, 8, 2, 18, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("AC-1: attendanceDeadline が Z（UTC）付き文字列でもデシリアライズできる")
    void attendanceDeadline_オフセット付きUTC_受理される() throws Exception {
        String body = createScheduleBody("\"2026-08-02T09:00:00Z\"");

        CreateScheduleRequest req = objectMapper.readValue(body, CreateScheduleRequest.class);

        // 旧実装（LocalDateTime）は Z を黙って捨てて 09:00 のローカル時刻として扱っていた（9時間ズレ）。
        // OffsetDateTime 化により UTC 09:00 = JST 18:00 の「同じ瞬間」として保持される。
        assertThat(req.getScheduledAttendance().getAttendanceDeadline())
                .as("Z（UTC）付きの回答締切がオフセットを失わずに復元されること")
                .isEqualTo(OffsetDateTime.of(2026, 8, 2, 9, 0, 0, 0, ZoneOffset.UTC));
        assertThat(req.getScheduledAttendance().getAttendanceDeadline()
                .atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime())
                .as("UTC 09:00 は JST 18:00 と同じ瞬間であること")
                .isEqualTo(LocalDateTime.of(2026, 8, 2, 18, 0));
    }

    @Test
    @DisplayName("後方互換: オフセット無しの旧形式文字列も受理され JST として解釈される")
    void attendanceDeadline_オフセット無し_後方互換で受理される() throws Exception {
        String body = createScheduleBody("\"2026-08-02T18:00:00\"");

        assertThatCode(() -> objectMapper.readValue(body, CreateScheduleRequest.class))
                .as("オフセット無しの旧クライアント形式でも 400 にならないこと（後方互換）")
                .doesNotThrowAnyException();

        CreateScheduleRequest req = objectMapper.readValue(body, CreateScheduleRequest.class);
        assertThat(req.getScheduledAttendance().getAttendanceDeadline())
                .as("オフセット無しはサーバー既定 TZ（JST）として解釈されること")
                .isEqualTo(OffsetDateTime.of(2026, 8, 2, 18, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    @DisplayName("不正な日時文字列は握り潰さず例外にする")
    void attendanceDeadline_不正文字列_例外になる() {
        String body = createScheduleBody("\"not-a-datetime\"");

        assertThatCode(() -> objectMapper.readValue(body, CreateScheduleRequest.class))
                .as("解釈不能な入力は黙って null にせず失敗させること（対処療法の禁止）")
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("AC-4: attendanceDeadline 省略時は null（既存挙動の非退行）")
    void attendanceDeadline_省略_nullのまま() throws Exception {
        String body = createScheduleBody("null");

        CreateScheduleRequest req = objectMapper.readValue(body, CreateScheduleRequest.class);

        assertThat(req.getScheduledAttendance()).isNotNull();
        assertThat(req.getScheduledAttendance().getAttendanceDeadline())
                .as("締切未指定時は null のまま（既存挙動）")
                .isNull();
        assertThat(req.getScheduledAttendance().getScheduledAt())
                .as("scheduledAt は従来どおり受理されること")
                .isNotNull();
    }
}
