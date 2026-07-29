package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.config.JacksonConfig;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 予約タスク payload_json の <b>往復（write → read）実証テスト</b>。
 *
 * <p>検証対象の本番経路:</p>
 * <ol>
 *   <li>{@link ScheduleScheduledTaskService#registerTasks} が
 *       {@code @Primary} な共通 {@link ObjectMapper} で {@link CreateSurveyRequest} を
 *       {@code payload_json} へ直列化する（リクエストスレッド＝ユーザー TZ がセット済み）</li>
 *   <li>{@link ScheduleScheduledTaskBatchService#materializeOne} が
 *       <b>同じ</b> 共通 {@link ObjectMapper} で {@code payload_json} を
 *       {@link CreateSurveyRequest} に復元する（バッチスレッド＝
 *       {@link TimezoneContextHolder} は未セット）</li>
 * </ol>
 *
 * <p><b>なぜ「実物の共通 ObjectMapper」でなければならないか</b>:
 * {@link JacksonConfig} は {@code LocalDateTimeTimezoneSerializer}（オフセット付き出力）を
 * 登録しているが、対になる Deserializer は登録していない。
 * {@code new ObjectMapper().findAndRegisterModules()} で組んだ mapper では
 * この非対称性が再現せず、テストが偽の緑になる
 * （既存の {@code ScheduleScheduledTaskBatchServiceTest} がまさにその状態）。
 * よってここでは Spring Boot の {@code JacksonAutoConfiguration} +
 * {@link JacksonConfig} を実際に起動して {@code @Primary} Bean そのものを取り出す。</p>
 *
 * <p><b>TZ 汚染対策</b>: JVM 既定 TZ を Asia/Tokyo（{@code TimeZoneConfig} と同じ）に固定し、
 * {@code tearDown} で {@link TimezoneContextHolder#clear()} と JVM 既定 TZ の復元を行う
 * （{@code LocalDateTimeTimezoneSerializerTest} の作法を踏襲）。</p>
 */
@DisplayName("予約タスク payload_json 往復（共通ObjectMapper実物）実証")
class ScheduledTaskPayloadRoundTripProbeTest {

    /** 予約アンケートの回答開始日時（JST 壁時計）。 */
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 1, 9, 0, 0);

    /** 予約アンケートの締切日時（JST 壁時計）。 */
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 8, 8, 18, 30, 0);

    private ObjectMapper objectMapper;
    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        // JVM 既定 TZ を Asia/Tokyo に固定（本番は TimeZoneConfig が @PostConstruct で同じことをする）
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        objectMapper = productionObjectMapper();
    }

    @AfterEach
    void tearDown() {
        // テスト間のスレッドローカル汚染を防止
        TimezoneContextHolder.clear();
        // JVM 既定 TZ を元に戻す
        TimeZone.setDefault(originalTimeZone);
    }

    /**
     * 本番と同一の {@code @Primary} {@link ObjectMapper} Bean を取り出す。
     *
     * <p>Spring Boot の {@code JacksonAutoConfiguration} が組み立てた
     * {@code Jackson2ObjectMapperBuilder} を {@link JacksonConfig} に食わせるため、
     * 実行時に出来上がるインスタンスは本番と同じ構成になる。DB には一切触らない。</p>
     */
    private static ObjectMapper productionObjectMapper() {
        AtomicReference<ObjectMapper> ref = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> ref.set(context.getBean(ObjectMapper.class)));
        ObjectMapper mapper = ref.get();
        assertThat(mapper)
                .as("JacksonConfig の @Primary ObjectMapper Bean が取得できていない")
                .isNotNull();
        return mapper;
    }

    /**
     * 本番 FE が実際に組み立てるのと同形の予約アンケート payload を作る。
     * {@code startsAt} / {@code expiresAt} だけを可変にする。
     */
    private static CreateSurveyRequest surveyRequest(LocalDateTime startsAt, LocalDateTime expiresAt) {
        return new CreateSurveyRequest(
                "練習日程アンケート",   // title
                null,                   // description
                false,                  // isAnonymous
                false,                  // allowMultipleSubmissions
                "AFTER_CLOSE",          // resultsVisibility
                "ALL",                  // distributionMode
                null,                   // unrespondedVisibility
                null,                   // autoPostToTimeline
                null,                   // seriesId
                List.of(),              // remindBeforeHours
                startsAt,               // startsAt
                expiresAt,              // expiresAt
                List.of(),              // questions
                null,                   // targetUserIds
                null,                   // resultViewerUserIds
                null,                   // includeSupporters
                null);                  // teamBreakdownEnabled
    }

    /**
     * 「リクエストスレッドで書き、バッチスレッドで読む」本番の非対称性を再現する。
     *
     * @param userZone 書き込み時のユーザー TZ（{@code null} なら未セット＝ UTC 扱い）
     * @return 直列化された payload_json 文字列
     */
    private String writeAsUser(ZoneId userZone, CreateSurveyRequest request) throws Exception {
        try {
            if (userZone != null) {
                TimezoneContextHolder.set(userZone);
            } else {
                TimezoneContextHolder.clear();
            }
            return objectMapper.writeValueAsString(request);
        } finally {
            TimezoneContextHolder.clear();
        }
    }

    @Nested
    @DisplayName("共通 ObjectMapper の配線（診断）")
    class MapperWiring {

        @Test
        @DisplayName("書き込みは LocalDateTime をオフセット付き文字列で出力する")
        void 書き込みはオフセット付き() throws Exception {
            String json = writeAsUser(ZoneId.of("Asia/Tokyo"), surveyRequest(STARTS_AT, EXPIRES_AT));

            assertThat(json)
                    .as("LocalDateTimeTimezoneSerializer が ISO_OFFSET_DATE_TIME で書いている")
                    .contains("\"startsAt\":\"2026-08-01T09:00:00+09:00\"")
                    .contains("\"expiresAt\":\"2026-08-08T18:30:00+09:00\"");
        }
    }

    @Nested
    @DisplayName("startsAt / expiresAt 非 null の予約アンケート payload の往復")
    class SurveyPayloadRoundTrip {

        @Test
        @DisplayName("ユーザーTZ=Asia/Tokyo で書いた payload をバッチスレッド（TZ未セット）で読み戻せる")
        void 東京ユーザーの往復() throws Exception {
            CreateSurveyRequest original = surveyRequest(STARTS_AT, EXPIRES_AT);
            String payloadJson = writeAsUser(ZoneId.of("Asia/Tokyo"), original);

            // バッチスレッド相当: TimezoneContextHolder 未セット
            CreateSurveyRequest restored =
                    objectMapper.readValue(payloadJson, CreateSurveyRequest.class);

            assertThat(restored.getStartsAt())
                    .as("payload_json: %s", payloadJson)
                    .isEqualTo(STARTS_AT);
            assertThat(restored.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @Test
        @DisplayName("ユーザーTZ=America/Los_Angeles で書いた payload をバッチスレッド（TZ未セット）で読み戻せる")
        void ロサンゼルスユーザーの往復() throws Exception {
            CreateSurveyRequest original = surveyRequest(STARTS_AT, EXPIRES_AT);
            String payloadJson = writeAsUser(ZoneId.of("America/Los_Angeles"), original);

            CreateSurveyRequest restored =
                    objectMapper.readValue(payloadJson, CreateSurveyRequest.class);

            assertThat(restored.getStartsAt())
                    .as("payload_json: %s", payloadJson)
                    .isEqualTo(STARTS_AT);
            assertThat(restored.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @Test
        @DisplayName("ユーザーTZ未セット（UTC 出力）で書いた payload も同じ瞬間を指したまま読み戻せる")
        void TZ未セットで書いた場合の往復() throws Exception {
            CreateSurveyRequest original = surveyRequest(STARTS_AT, EXPIRES_AT);
            String payloadJson = writeAsUser(null, original);

            CreateSurveyRequest restored =
                    objectMapper.readValue(payloadJson, CreateSurveyRequest.class);

            assertThat(restored.getStartsAt())
                    .as("payload_json: %s", payloadJson)
                    .isEqualTo(STARTS_AT);
            assertThat(restored.getExpiresAt()).isEqualTo(EXPIRES_AT);
        }

        @Test
        @DisplayName("読み戻しを実際に別スレッド（ThreadLocal 未継承）で行っても往復できる")
        void 別スレッドでの読み戻し() throws Exception {
            CreateSurveyRequest original = surveyRequest(STARTS_AT, EXPIRES_AT);
            String payloadJson = writeAsUser(ZoneId.of("Asia/Tokyo"), original);

            AtomicReference<Object> result = new AtomicReference<>();
            Thread batchThread = new Thread(() -> {
                try {
                    result.set(objectMapper.readValue(payloadJson, CreateSurveyRequest.class));
                } catch (Exception e) {
                    result.set(e);
                }
            }, "probe-batch-thread");
            batchThread.start();
            batchThread.join();

            assertThat(result.get())
                    .as("バッチスレッドでの readValue が例外になった: %s", result.get())
                    .isInstanceOf(CreateSurveyRequest.class);
            assertThat(((CreateSurveyRequest) result.get()).getStartsAt()).isEqualTo(STARTS_AT);
        }
    }

    @Nested
    @DisplayName("予約出欠募集 payload（AttendancePayload）の往復")
    class AttendancePayloadRoundTrip {

        /** 出欠回答期限（JST 壁時計）。 */
        private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 8, 5, 23, 59, 0);

        @Test
        @DisplayName("attendanceDeadline 非 null の payload を読み戻せる")
        void 出欠payloadの往復() throws Exception {
            ScheduleScheduledTaskService.AttendancePayload original =
                    new ScheduleScheduledTaskService.AttendancePayload(DEADLINE, "OPTIONAL", "MEMBER");

            String payloadJson;
            try {
                TimezoneContextHolder.set(ZoneId.of("Asia/Tokyo"));
                payloadJson = objectMapper.writeValueAsString(original);
            } finally {
                TimezoneContextHolder.clear();
            }

            ScheduleScheduledTaskService.AttendancePayload restored = objectMapper.readValue(
                    payloadJson, ScheduleScheduledTaskService.AttendancePayload.class);

            assertThat(restored.attendanceDeadline())
                    .as("payload_json: %s", payloadJson)
                    .isEqualTo(DEADLINE);
        }
    }
}
