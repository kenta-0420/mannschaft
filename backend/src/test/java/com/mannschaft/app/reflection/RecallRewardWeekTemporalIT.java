package com.mannschaft.app.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.RecallSessionOperationFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC33のnative SQL DATEと保存済みACKを、日曜対照/月曜境界で検査する。
 * driver UTCと局所Clockの専用contextだけを閉じる。JVM UTC/JSTは別プロセスで指定し、global TZを変更しない。
 * 実Facade/queue/transport/MySQLを使うが、source ACK・Ranch consumer付与の証明にはしない。
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.data-source-properties.serverTimezone=UTC",
        "ranch.source.transport.queue-capacity=16"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecallRewardWeekTemporalIT extends AbstractMySqlIntegrationTest {
    private static final Instant START = Instant.parse("2030-10-06T23:59:58Z");
    private static final Instant NEXT_WEEK = Instant.parse("2030-10-14T12:00:00Z");
    @Autowired private UserRepository users;
    @Autowired private ReflectionThemeRepository themes;
    @Autowired private ReflectionEntryRepository entries;
    @Autowired private RecallAttemptRepository attempts;
    @Autowired private RecallSessionOperationFacade operations;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @TestBean(name = "utcClock", methodName = "recallTemporalClock")
    private Clock clock;
    private Long owner;

    static Clock recallTemporalClock() { return new RecallTemporalClock(); }

    @BeforeEach
    void createOnlyOwnedNativeEntry() {
        time(START);
        owner = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@recall-week.invalid")
                .lastName("週境界").firstName("本人").displayName("試験本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }

    @AfterEach
    void removeOwnedNativeAndSourceRowsAndResetClock() {
        try {
            if (owner == null) return;
            jdbc.update("DELETE FROM reflection_ranch_outboxes WHERE recipient_user_id=?", owner);
            jdbc.update("DELETE FROM reflection_ranch_witnesses WHERE recipient_user_id=?", owner);
            jdbc.update("DELETE FROM reflection_recall_commands WHERE user_id=?", owner);
            jdbc.update("DELETE FROM recall_attempts WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_recall_sessions WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_entries WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_themes WHERE user_id=?", owner);
            users.deleteById(owner);
        } finally {
            time(START);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "SUNDAY_CONTROL,2030-10-06T23:59:59Z,2030-09-30",
            "MONDAY_BOUNDARY,2030-10-07T00:00:02Z,2030-10-07"
    })
    void physicalWeekAndNativeGetSurviveCompletionAndNextWeekReplay(
            String scenario, Instant completion, LocalDate expectedWeek) throws Exception {
        var theme = themes.saveAndFlush(ReflectionThemeEntity.builder()
                .userId(owner).title("UTC週境界fixture").build());
        var entry = entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(theme.getId())
                .targetDate(LocalDate.parse("2030-10-06"))
                .structuredContent("{\"free_note\":\"開始時の本人原文\"}")
                .visibility(ReflectionVisibility.PRIVATE).build());
        var started = operations.start(owner, entry.getId(), UUID.randomUUID(), mapper.createObjectNode());
        assertThat(started.status()).isEqualTo(RecallSessionStatus.STARTED);
        assertThat(started.startedAt()).isEqualTo(START);
        assertThat(started.rewardWeek()).isNull();
        assertThat(physicalWeek(started.id())).isNull();
        assertThat(started.completedAt()).isNull();
        assertThat(started.prompts()).hasSize(1);
        var body = mapper.valueToTree(Map.of("version", started.version(), "answers", List.of(Map.of(
                "promptId", started.prompts().getFirst().id().toString(),
                "state", "ANSWERED", "text", "本人の回答")), "selfRating", "REMEMBERED"));
        UUID key = UUID.randomUUID();
        time(completion);
        var completed = operations.complete(owner, started.id(), key, body);
        var fresh = operations.get(owner, started.id());
        var checks = new SoftAssertions();
        checks.assertThat(completed.status()).isEqualTo(RecallSessionStatus.COMPLETED);
        checks.assertThat(completed.rewardWeek()).as(scenario+" complete ACK").isEqualTo(expectedWeek);
        checks.assertThat(physicalWeek(started.id())).as(scenario+" SQL CAST DATE")
                .isEqualTo(expectedWeek.toString());
        checks.assertThat(fresh.rewardWeek()).as(scenario+" fresh native GET").isEqualTo(expectedWeek);
        checks.assertThat(fresh.completedAt()).isEqualTo(completion);
        checks.assertAll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(sourceCount(false)).isEqualTo(1);
            assertThat(sourceCount(true)).isEqualTo(1);
        });
        String savedPayload = jdbc.queryForObject(
                "SELECT payload_json FROM reflection_ranch_outboxes WHERE recipient_user_id=?",
                String.class, owner);
        var source = mapper.readValue(savedPayload, ReflectionRecallRewardPayload.class);
        assertThat(source.occurredAt()).isEqualTo(completion);
        assertThat(source.facts().completionWeek()).isEqualTo(expectedWeek);
        assertThat(source.facts().sessionId()).isEqualTo(started.id());

        time(NEXT_WEEK);
        var replay = operations.complete(owner, started.id(), key, body);
        JsonNode replayJson = mapper.valueToTree(replay);
        JsonNode completedJson = mapper.valueToTree(completed);
        assertThat(replayJson).isEqualTo(completedJson);
        var after = operations.get(owner, started.id());
        assertThat(after.startedAt()).isEqualTo(START);
        assertThat(after.completedAt()).isEqualTo(completion);
        assertThat(after.rewardWeek()).isEqualTo(expectedWeek);
        assertThat(physicalWeek(started.id())).isEqualTo(expectedWeek.toString());
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entry.getId())).hasSize(1);
        assertThat(sourceCount(false)).isEqualTo(1);
        assertThat(sourceCount(true)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM reflection_ranch_outboxes WHERE recipient_user_id=?",
                String.class, owner)).isEqualTo(savedPayload);
    }

    /** getDate/LocalDate JDBC変換を通さず、実SQL DATEの文字値を直接比較する。 */
    private String physicalWeek(UUID sessionId) {
        return jdbc.queryForObject("SELECT CAST(reward_week AS CHAR) FROM reflection_recall_sessions WHERE id=? AND user_id=?",
                String.class, ByteBuffer.allocate(16).putLong(sessionId.getMostSignificantBits())
                        .putLong(sessionId.getLeastSignificantBits()).array(), owner);
    }

    private int sourceCount(boolean outbox) {
        return jdbc.queryForObject(outbox
                        ? "SELECT COUNT(*) FROM reflection_ranch_outboxes WHERE recipient_user_id=?"
                        : "SELECT COUNT(*) FROM reflection_ranch_witnesses WHERE recipient_user_id=?",
                Integer.class, owner);
    }

    private void time(Instant value) { ((RecallTemporalClock) clock).current.set(value); }

    /** このtest contextだけの時計。共有JVM既定TZ・他contextのClockに触れない。 */
    private static final class RecallTemporalClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        private RecallTemporalClock() { this(new AtomicReference<>(START), ZoneOffset.UTC); }
        private RecallTemporalClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = java.util.Objects.requireNonNull(zone);
        }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) {
            return this.zone.equals(zone) ? this : new RecallTemporalClock(current, zone);
        }
        @Override public Instant instant() { return current.get(); }
    }
}
