package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * チーム TZ 対応・日跨ぎ予約枠（CMP-260822-1730）の受け入れ条件を、
 * 実装前のコード実体へ結び付ける red テスト。
 *
 * <p>本クラスは第一陣では本番コードを変更しないため、まだ存在しないフィールド・
 * migration・UI契約を反射およびソース契約として検証する。実装後は、同じ受け入れ条件を
 * 実DB/API/ブラウザ経路のテストへ置き換える前提である。</p>
 */
@DisplayName("チームTZ対応・日跨ぎ予約枠 AC red テスト")
class OvernightReservationTeamTimezoneAcceptanceTest {

    @Test
    @DisplayName("AC-1: 同日内テンプレートのセル数は従来どおり")
    void ac01_sameDayTemplateCellCountRemainsUnchanged() {
        ReservationSlotTemplateEntity template = ReservationSlotTemplateEntity.builder()
                .teamId(1L)
                .dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 0))
                .build();

        assertThat(template.cellCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-2: TeamEntity に IANA TZ が保存される")
    void ac02_teamStoresIanaTimezone() throws Exception {
        Field timezone = TeamEntity.class.getDeclaredField("timezone");

        assertThat(timezone.getType()).isEqualTo(String.class);
        Column column = timezone.getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.nullable()).isFalse();
    }

    @Test
    @DisplayName("AC-3: TZ は IANA 名に限定し、チーム更新経路でだけ変更できる")
    void ac03_timezoneValidationAndTeamScopedUpdate() throws Exception {
        Field timezone = findField("src/main/java/com/mannschaft/app/team/dto/UpdateTeamRequest.java", "timezone");
        assertThat(timezone).as("UpdateTeamRequest.timezone").isNotNull();
        assertThat(source("src/main/java/com/mannschaft/app/team/dto/UpdateTeamRequest.java"))
                .contains("ValidTimezone");

        String service = source("src/main/java/com/mannschaft/app/team/service/TeamService.java");
        assertThat(service).contains("timezone");
        assertThat(service).contains("teamId");
    }

    @Test
    @DisplayName("AC-4: 営業時間とテンプレートが終了翌日を保持する")
    void ac04_businessHoursAndTemplatesExposeEndsNextDay() throws Exception {
        assertHasField("src/main/java/com/mannschaft/app/reservation/dto/BusinessHourEntry.java", "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/entity/ReservationBusinessHourEntity.java",
                "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/dto/CreateSlotTemplateRequest.java",
                "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/entity/ReservationSlotTemplateEntity.java",
                "endsNextDay");
    }

    @Test
    @DisplayName("AC-5: endsNextDay 省略時は false")
    void ac05_endsNextDayDefaultsToFalse() throws Exception {
        for (String file : List.of(
                "src/main/java/com/mannschaft/app/reservation/dto/BusinessHourEntry.java",
                "src/main/java/com/mannschaft/app/reservation/entity/ReservationBusinessHourEntity.java",
                "src/main/java/com/mannschaft/app/reservation/dto/CreateSlotTemplateRequest.java",
                "src/main/java/com/mannschaft/app/reservation/entity/ReservationSlotTemplateEntity.java")) {
            String text = source(file);
            assertThat(text).as(file).contains("endsNextDay");
            assertThat(text).as(file).contains("false");
        }
    }

    @Test
    @DisplayName("AC-6: 明示的な日跨ぎは許可し、同値・24時間超は拒否する")
    void ac06_explicitOvernightRangeValidation() throws Exception {
        Method validator = Stream.of(
                        Class.forName("com.mannschaft.app.reservation.service.SlotTimeValidator")
                                .getDeclaredMethods())
                .filter(method -> method.getName().equals("validateTimeRange"))
                .filter(method -> method.getParameterCount() == 3)
                .findFirst()
                .orElseThrow(() -> new AssertionError("endsNextDay を受け取る validator が未実装"));
        validator.setAccessible(true);

        assertThatCode(() -> invoke(validator, LocalTime.of(18, 0), LocalTime.of(4, 0), true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> invoke(validator, LocalTime.of(18, 0), LocalTime.of(18, 0), true))
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("AC-7: 日跨ぎ枠の終了日は翌日になる")
    void ac07_slotHasEndDate() throws Exception {
        assertHasField("src/main/java/com/mannschaft/app/reservation/entity/ReservationSlotEntity.java", "endDate");
        assertThat(source("src/main/java/com/mannschaft/app/reservation/service/ReservationSlotGenerationService.java"))
                .contains("endDate");
    }

    @Test
    @DisplayName("AC-8: horizon と曜日は UTC 日付ではなくチーム TZ の現地日付で決まる")
    void ac08_generationUsesTeamTimezoneForLocalDate() {
        String generation = source("src/main/java/com/mannschaft/app/reservation/service/ReservationSlotGenerationService.java");
        assertThat(generation).contains("teamTimezone");
        assertThat(generation).contains("LocalDate");
        assertThat(generation).contains("ZoneId");
    }

    @Test
    @DisplayName("AC-9: 日跨ぎの包含・境界・重複判定は実日時区間で行う")
    void ac09_overlapUsesHalfOpenInstantIntervals() {
        String service = source("src/main/java/com/mannschaft/app/reservation/service/ReservationUnavailabilityChecker.java");
        String repository = source("src/main/java/com/mannschaft/app/reservation/repository/ReservationSlotRepository.java");
        assertThat(service + repository).contains("endDate");
        assertThat(service + repository).contains("Instant");
    }

    @Test
    @DisplayName("AC-10: 単発・繰返しブロックの日跨ぎが予約可否へ反映される")
    void ac10_blockedTimesCarryOvernightSemantics() throws Exception {
        assertHasField("src/main/java/com/mannschaft/app/reservation/dto/BlockedTimeRequest.java", "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/entity/ReservationBlockedTimeEntity.java",
                "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/dto/CreateRecurringBlockedTimeRequest.java",
                "endsNextDay");
        assertHasField("src/main/java/com/mannschaft/app/reservation/entity/ReservationRecurringBlockedTimeEntity.java",
                "endsNextDay");
    }

    @Test
    @DisplayName("AC-11: 期限・失効・通知・待機リストはチーム TZ から Instant 化する")
    void ac11_deadlineAndNotificationComparisonsUseTeamTimezone() {
        String sources = String.join("\n",
                source("src/main/java/com/mannschaft/app/reservation/service/ReservationService.java"),
                source("src/main/java/com/mannschaft/app/reservation/service/ReservationPendingExpireService.java"),
                source("src/main/java/com/mannschaft/app/reservation/service/ReservationReminderService.java"),
                source("src/main/java/com/mannschaft/app/reservation/service/ReservationWaitlistService.java"));
        assertThat(sources).contains("TeamTimezone");
        assertThat(sources).contains("Instant");
    }

    @Test
    @DisplayName("AC-12: チーム TZ の DST gap/overlap 規約が決定的に検証される")
    void ac12_dstPolicyIsExplicit() {
        Path resolver = Path.of("src/main/java/com/mannschaft/app/common/timezone/TeamTimezoneResolver.java");
        assertThat(Files.exists(resolver)).isTrue();
        String source = read(resolver);
        assertThat(source).contains("gap");
        assertThat(source).contains("overlap");
        assertThat(source).contains("ZoneOffsetTransition");
    }

    @Test
    @DisplayName("AC-13: 既存同日枠と既存日跨ぎ相当データを移行する")
    void ac13_migrationBackfillsEndDateAndTeamTimezone() {
        String migrations = migrationSources();
        assertThat(migrations).contains("timezone");
        assertThat(migrations).contains("end_date");
        assertThat(migrations).contains("slot_date");
    }

    @Test
    @DisplayName("AC-14: 枠生成の途中失敗は原子性を守り、再実行で自己修復する")
    void ac14_generationIsRetryableAndSelfHealing() {
        String generation = source("src/main/java/com/mannschaft/app/reservation/service/ReservationSlotGenerationService.java");
        assertThat(generation).contains("delete");
        assertThat(generation).contains("REQUIRES_NEW");
        assertThat(generation).contains("endDate");
    }

    @Test
    @DisplayName("AC-15: 複数チーム処理は TZ を一括取得し N+1 を増やさない")
    void ac15_generationUsesBulkTimezoneLookup() {
        String batch = source("src/main/java/com/mannschaft/app/reservation/service/ReservationSlotGenerationBatchService.java");
        assertThat(batch).contains("resolveZones");
        assertThat(batch).doesNotContain("resolveZone(teamId)");
    }

    @Test
    @DisplayName("AC-16: 設定 UI と予約画面が TZ・終了翌日・翌日セルを扱う")
    void ac16_frontendExposesTimezoneAndNextDayCell() {
        String frontend = frontendSources();
        assertThat(frontend).contains("endsNextDay");
        assertThat(frontend).contains("timezone");
        assertThat(frontend).contains("endDate");
    }

    private static void assertHasField(String relativePath, String fieldName) throws Exception {
        assertThat(findField(relativePath, fieldName)).as(relativePath + "." + fieldName).isNotNull();
    }

    private static Field findField(String relativePath, String fieldName) throws Exception {
        String className = relativePath
                .replace("src/main/java/", "")
                .replace(".java", "")
                .replace('/', '.');
        Class<?> type = Class.forName(className);
        try {
            return type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException missing) {
            return null;
        }
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new AssertionError("validator rejected the invocation", exception.getCause());
        }
    }

    private static String source(String relativePath) {
        return read(Path.of(relativePath));
    }

    private static String frontendSources() {
        Path root = Path.of("..", "frontend", "app");
        return walkText(root, ".vue", ".ts");
    }

    private static String migrationSources() {
        return walkText(Path.of("src/main/resources/db/migration"), ".sql");
    }

    private static String walkText(Path root, String... suffixes) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> Stream.of(suffixes).anyMatch(suffix -> path.toString().endsWith(suffix)))
                    .sorted(Comparator.naturalOrder())
                    .map(OvernightReservationTeamTimezoneAcceptanceTest::read)
                    .reduce("", (all, next) -> all + "\n" + next);
        } catch (java.io.IOException exception) {
            throw new AssertionError("テスト対象ソースを読めません: " + root, exception);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new AssertionError("テスト対象ファイルを読めません: " + path, exception);
        }
    }
}
