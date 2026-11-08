package com.mannschaft.app.village.migration;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.service.VillageParticipationLimits;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260827-1808 番人: V255（村の参加枠）の SQL に書いた数値が、Java の唯一の定義
 * {@link VillageParticipationLimits} と一致することを照合する。
 *
 * <p>Flyway の SQL は Java 定数を参照できない。天井や既定値を変えるとき、片方だけ直す事故を防ぐ。</p>
 */
@DisplayName("村の参加枠: V255 の SQL と Java 定数の一致")
class VillageParticipationLimitsMigrationConsistencyTest {

    private static final String MIGRATION_SUFFIX = "__enforce_village_membership_slots.sql";
    private static final Pattern CHECK_CEILING = Pattern.compile(
            "ck_vm_active_user_slot\\s+CHECK\\s*\\(.*?user_slot\\s+BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SLOT_COLUMN_TYPE = Pattern.compile(
            "ADD\\s+COLUMN\\s+(user_slot|active_user_slot)\\s+(TINYINT|SMALLINT|MEDIUMINT|INT|BIGINT)(\\s+UNSIGNED)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERFLOW_THRESHOLD = Pattern.compile(
            "CREATE\\s+TEMPORARY\\s+TABLE\\s+cmp1808_overflow_fix.*?WHERE\\s+rn\\s*>\\s*(\\d+)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static String sql;

    @BeforeAll
    static void readMigration() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            List<Path> candidates = files.filter(p -> p.getFileName().toString().endsWith(MIGRATION_SUFFIX)).toList();
            assertThat(candidates).as("参加枠の移行はちょうど1本").hasSize(1);
            sql = Files.readString(candidates.getFirst(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("CHECK の範囲が 1〜DB_SLOT_CEILING と一致する")
    void checkCeilingMatchesConstant() {
        Matcher matcher = CHECK_CEILING.matcher(sql);
        assertThat(matcher.find()).as("ck_vm_active_user_slot の BETWEEN を見つける").isTrue();
        assertThat(Integer.parseInt(matcher.group(1))).isEqualTo(VillageParticipationLimits.MIN_HARD_LIMIT);
        assertThat(Integer.parseInt(matcher.group(2))).isEqualTo(VillageParticipationLimits.DB_SLOT_CEILING);
    }

    @Test
    @DisplayName("枠番号の列の型が天井を表せる（DB の型と Entity の Short の両方）")
    void slotColumnTypesCoverCeiling() {
        Matcher matcher = SLOT_COLUMN_TYPE.matcher(sql);
        int found = 0;
        while (matcher.find()) {
            found++;
            long max = maxOf(matcher.group(2), matcher.group(3) != null);
            assertThat(max).as(matcher.group(1) + " の型の最大値").isGreaterThanOrEqualTo(VillageParticipationLimits.DB_SLOT_CEILING);
        }
        assertThat(found).as("user_slot と active_user_slot の両方を検査した").isEqualTo(2);
        assertThat(fieldType("userSlot")).isEqualTo(Short.class);
        assertThat((long) Short.MAX_VALUE).isGreaterThanOrEqualTo(VillageParticipationLimits.DB_SLOT_CEILING);
    }

    @Test
    @DisplayName("移行時の超過是正のしきい値が既定の上限 DEFAULT_HARD_LIMIT と一致し、天井以下である")
    void overflowThresholdMatchesDefaultLimit() {
        Matcher matcher = OVERFLOW_THRESHOLD.matcher(sql);
        assertThat(matcher.find()).as("cmp1808_overflow_fix の rn しきい値を見つける").isTrue();
        assertThat(Integer.parseInt(matcher.group(1))).isEqualTo(VillageParticipationLimits.DEFAULT_HARD_LIMIT);
        assertThat(VillageParticipationLimits.DEFAULT_HARD_LIMIT).isBetween(
                VillageParticipationLimits.MIN_HARD_LIMIT, VillageParticipationLimits.DB_SLOT_CEILING);
    }

    @Test
    @DisplayName("E2E seed の既定上限と天井が Java の定数と一致する（JS は Java 定数を読めないため照合する）")
    void seedConstantsMatchJavaConstants() throws IOException {
        String seed = Files.readString(Path.of("scripts/seed-e2e-data.js"), StandardCharsets.UTF_8);
        assertThat(seedConstant(seed, "VILLAGE_DEFAULT_PARTICIPATION_HARD_LIMIT"))
                .isEqualTo(VillageParticipationLimits.DEFAULT_HARD_LIMIT);
        assertThat(seedConstant(seed, "VILLAGE_DB_SLOT_CEILING"))
                .isEqualTo(VillageParticipationLimits.DB_SLOT_CEILING);
        assertThat(seed).as("seed の現在の上限は BE の設定と同じ環境変数から読む")
                .contains("process.env.MANNSCHAFT_VILLAGE_PARTICIPATION_HARD_LIMIT");
    }

    private static int seedConstant(String seed, String name) {
        Matcher matcher = Pattern.compile("const\\s+" + name + "\\s*=\\s*(\\d+)\\s*;").matcher(seed);
        assertThat(matcher.find()).as("seed に定数 " + name + " がある").isTrue();
        int value = Integer.parseInt(matcher.group(1));
        assertThat(matcher.find()).as("seed の定数 " + name + " は1か所だけ").isFalse();
        return value;
    }

    private static long maxOf(String type, boolean unsigned) {
        int bits = switch (type.toUpperCase()) {
            case "TINYINT" -> Byte.SIZE;
            case "SMALLINT" -> Short.SIZE;
            case "MEDIUMINT" -> Short.SIZE + Byte.SIZE;
            case "INT" -> Integer.SIZE;
            default -> Long.SIZE - 1;
        };
        return unsigned ? (1L << bits) - 1 : (1L << (bits - 1)) - 1;
    }

    private static Class<?> fieldType(String name) {
        try {
            return VillageMembershipEntity.class.getDeclaredField(name).getType();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(name + " が無い", e);
        }
    }
}
