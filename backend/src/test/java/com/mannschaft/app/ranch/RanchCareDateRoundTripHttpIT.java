package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 無料careのcommit後にUTC暦日のDATEを読み戻し、本人stateと週枠・日次親密度を保つ契約。
 * 共通MySQL singletonを継承し、実機のdriver UTC指定と固定Clockに必要な限定contextだけを閉じる。
 * テスト全体を@Transactionalで包まず、POSTとGETは別の業務TXで実DBを観測する。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mannschaft.ranch.development-fixtures=true",
        "spring.datasource.hikari.data-source-properties.serverTimezone=UTC"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchCareDateRoundTripHttpIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchCareWeekBudgetRepository budgets;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchPurgeService purge;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @TestBean(name = "utcClock", methodName = "fixedUtcClock")
    private Clock clock;
    private Long me;

    static Clock fixedUtcClock() {
        // UTC日曜/JST月曜。期待値もこのClockから算出し、実時計の日跨ぎに依存しない。
        return Clock.fixed(Instant.parse("2026-10-11T23:59:58Z"), ZoneOffset.UTC);
    }

    @BeforeEach
    void createSyntheticHatchedDinosaur() {
        RanchTestFixture.operationalControl(controls);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Instant eggStart = clock.instant().minusSeconds(604801);
        enrollment.enroll(me, UUID.randomUUID(), eggStart, PROJECTION);
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(dinosaur, "selectionConfirmedAt", eggStart.plusSeconds(1));
        dinosaur.hatch("試験", eggStart.plusSeconds(604800));
        dinosaurs.saveAndFlush(dinosaur);
    }

    @AfterEach
    void removeOnlyOwnedSyntheticRows() {
        if (me == null) return;
        purge.purgeUser(me);
        for (String table : List.of("ranch_owners", "ranch_dinosaurs", "ranch_commands",
                "ranch_point_ledger", "ranch_care_week_budgets", "ranch_affinity_units",
                "ranch_room_placements", "ranch_collectible_inventory",
                "ranch_participation_periods", "ranch_reward_decisions", "ranch_week_budgets")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                    Long.class, me)).as("本人fixture残存行: %s", table).isZero();
        }
        users.deleteById(me);
    }

    @Test
    void 無料care保存後のDATEと本人GETがUTC週を保ち再操作でも親密度は一日一回() throws Exception {
        assertRuntimeDateConditions();
        LocalDate today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.careBudget.weekStartsOn").value(monday.toString()))
                .andExpect(jsonPath("$.data.careBudget.awardedXp").value("0"));

        feed("0");
        // SoftAssertionsでraw DATEと独立GETの両方を観測し、書込ずれと500を切り分ける。
        SoftAssertions softly = new SoftAssertions();
        assertDates(softly, monday, today);
        var firstBudget = budgets.findByUserIdAndWeekStartsOn(me, monday).orElseThrow();
        softly.assertThat(firstBudget.getWeekStartsOn()).as("JPA読戻しのUTC週DATE").isEqualTo(monday);
        assertState(softly, read(), monday, "20", "80", "1");
        softly.assertAll();

        feed("1");
        assertDates(softly, monday, today);
        var secondBudget = budgets.findByUserIdAndWeekStartsOn(me, monday).orElseThrow();
        softly.assertThat(secondBudget.getId()).as("再careでも同じ週枠").isEqualTo(firstBudget.getId());
        softly.assertThat(secondBudget.getAwardedXp()).isEqualTo(40);
        softly.assertThat(secondBudget.getVersion()).isEqualTo(2);
        softly.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ranch_care_week_budgets WHERE user_id = ?", Long.class, me)).isEqualTo(1);
        softly.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ranch_affinity_units WHERE user_id = ? AND kind = 'FEED'",
                Long.class, me)).as("UTC同日にFEED親密度は一回だけ").isEqualTo(1);
        softly.assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isEqualTo(1);
        assertState(softly, read(), monday, "40", "60", "2");
        softly.assertAll();
    }

    private void feed(String version) throws Exception {
        var response = mvc.perform(post("/api/v1/me/ranch/feeding").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"" + version + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.gainedXp").value("20"))
                .andExpect(jsonPath("$.data.costPoints").value("0"))
                .andExpect(jsonPath("$.data.balanceAfter").value("0")).andReturn();
        String completedAt = json.readTree(response.getResponse().getContentAsString())
                .path("data").path("completedAt").asText();
        assertThat(Instant.parse(completedAt)).as("固定ClockがHTTP careへ届く").isEqualTo(clock.instant());
    }

    private MvcResult read() throws Exception {
        return mvc.perform(get("/api/v1/me/ranch").with(user(me.toString()))).andReturn();
    }

    private void assertDates(SoftAssertions softly, LocalDate monday, LocalDate today) {
        // Stringとして読むことでjava.sql.Date/getDateのTZ変換を期待値側へ持ち込まない。
        softly.assertThat(jdbc.queryForObject(
                "SELECT CAST(week_starts_on AS CHAR) FROM ranch_care_week_budgets WHERE user_id = ?",
                String.class, me)).as("実MySQL DATEのUTC月曜").isEqualTo(monday.toString());
        softly.assertThat(jdbc.queryForObject(
                "SELECT CAST(earned_on AS CHAR) FROM ranch_affinity_units WHERE user_id = ? AND kind = 'FEED'",
                String.class, me)).as("実MySQL DATEのUTC親密度日").isEqualTo(today.toString());
    }

    private void assertState(SoftAssertions softly, MvcResult response, LocalDate monday,
                             String awarded, String remaining, String version) throws Exception {
        softly.assertThat(response.getResponse().getStatus()).as("care commit後の独立GET").isEqualTo(200);
        JsonNode state = json.readTree(response.getResponse().getContentAsString()).path("data");
        softly.assertThat(state.path("careBudget").path("weekStartsOn").asText()).isEqualTo(monday.toString());
        softly.assertThat(state.path("careBudget").path("awardedXp").asText()).isEqualTo(awarded);
        softly.assertThat(state.path("careBudget").path("remainingXp").asText()).isEqualTo(remaining);
        softly.assertThat(state.path("owner").path("version").asText()).isEqualTo(version);
        softly.assertThat(state.path("owner").path("balance").asText()).isEqualTo("0");
    }

    private void assertRuntimeDateConditions() throws Exception {
        assertThat(TimeZone.getDefault().toZoneId()).as("製品TimeZoneConfigのJVM既定")
                .isEqualTo(UserZoneLocalDateTimeParser.SERVER_ZONE);
        assertThat(jdbc.queryForObject("SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())", String.class))
                .as("MySQL sessionの実効UTC offset").isEqualTo("00:00:00");
        try (Connection connection = dataSource.getConnection()) {
            // Connector/JはruntimeOnlyのため、製品依存をcompile依存へ変更せず実効設定を読む。
            Class<?> connectionType = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            Object driver = connection.unwrap(connectionType);
            Object properties = connectionType.getMethod("getPropertySet").invoke(driver);
            Object timezone = Class.forName("com.mysql.cj.conf.PropertySet")
                    .getMethod("getStringProperty", String.class).invoke(properties, "connectionTimeZone");
            Object value = Class.forName("com.mysql.cj.conf.RuntimeProperty").getMethod("getValue").invoke(timezone);
            assertThat(value).as("実効Connector/J connectionTimeZone").isEqualTo("UTC");
            Object session = connectionType.getMethod("getSession").invoke(driver);
            Object server = Class.forName("com.mysql.cj.Session").getMethod("getServerSession").invoke(session);
            TimeZone driverDefault = (TimeZone) Class.forName("com.mysql.cj.protocol.ServerSession")
                    .getMethod("getDefaultTimeZone").invoke(server);
            System.out.printf("Ranch DATE条件: driver=%s JVM=%s connectionTimeZone=%s driverDefault=%s DBoffset=UTC%n",
                    connection.getMetaData().getDriverVersion(), TimeZone.getDefault().getID(), value,
                    driverDefault.getID());
        }
    }
}
