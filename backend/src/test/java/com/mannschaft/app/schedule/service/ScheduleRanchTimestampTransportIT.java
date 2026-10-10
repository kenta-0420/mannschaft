package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRanchTransportRepository;
import com.mannschaft.app.schedule.repository.ScheduleRanchOutboxRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 実nativeのJPA UTC資格を、同じ受信実装の異なるJDBC zoneで照合する。共有pool/設定は変更しない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleRanchTimestampTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired UserRepository users;
    @Autowired ScheduleRepository schedules;
    @Autowired ScheduleAttendanceRepository responses;
    @Autowired ScheduleRanchTransportRepository rows;
    @Autowired UserOperationGuard guard;
    @Autowired ScheduleRanchNativeWriter nativeWriter;
    @Autowired ObjectMapper mapper;
    private Long owner, scheduleId, responseId;
    @BeforeEach void committedOwnFixture() {
        owner = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@source-zone.invalid")
                .firstName("検証").lastName("本人").displayName("検証").locale("ja").timezone("UTC")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false).build()).getId();
        scheduleId = schedules.saveAndFlush(ScheduleEntity.builder().userId(owner).title("検証予定")
                .startAt(LocalDateTime.of(2026,10,10,0,0)).eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED).attendanceRequired(true).build()).getId();
        responseId = responses.saveAndFlush(ScheduleAttendanceEntity.builder().scheduleId(scheduleId)
                .userId(owner).status(AttendanceStatus.UNDECIDED).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if (owner == null) return;
        rows.deleteForUser(owner);
        if (responseId != null) responses.deleteById(responseId);
        if (scheduleId != null) schedules.deleteById(scheduleId);
        users.deleteById(owner);
    }
    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Honolulu"})
    void nativeQualifiedInstantIsAcceptedWithoutJdbcZoneShift(String zone) {
        var saved = guard.withActiveUser(owner, () -> nativeWriter.respond(scheduleId, owner,
                new AttendanceRequest("ATTENDING", null, null)));
        assertThat(saved.capture()).isNotNull();
        try (var probe = ownZoneProbe(zone)) {
            var jdbc = new JdbcTemplate(probe);
            // 本物の源writer/repositoryと標準TXを使う日時境界試験。Spring proxy/配送authの証明ではない。
            var receiver = new ScheduleRanchTransportWriter(new ScheduleRanchTransportRepository(jdbc), jdbc, mapper, Clock.systemUTC());
            var tx = new TransactionTemplate(new DataSourceTransactionManager(probe));
            Boolean accepted = tx.execute(status -> receiver.accept(saved.capture()));
            assertThat(accepted).as("同一native資格InstantをJDBC zone=%sでも拒否しない", zone).isTrue();
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_ranch_outboxes WHERE recipient_user_id=?", Long.class, owner);
            assertThat(count).isEqualTo(1L);
            // nativeのJPA保存瞬間と、異なるzoneでINSERT・再読取した配送候補を照合する。
            var candidates = tx.execute(status -> new ScheduleRanchOutboxRepository(jdbc)
                    .candidates(Instant.now().plusSeconds(60), 100));
            assertThat(candidates).isNotNull();
            var ownCandidates = candidates.stream()
                    .filter(candidate -> candidate.eventId().equals(saved.capture().payload().eventId())).toList();
            assertThat(ownCandidates).hasSize(1);
            assertThat(ownCandidates.getFirst().recipient()).isEqualTo(owner.longValue());
            assertThat(ownCandidates.getFirst().occurredAt()).isEqualTo(saved.capture().payload().occurredAt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Honolulu"})
    void dbClockLeasePublishesStoredExpiryAndSubsecondDeferDoesNotSpendAttempt(String zone) {
        var saved = guard.withActiveUser(owner, () -> nativeWriter.respond(scheduleId, owner,
                new AttendanceRequest("ATTENDING", null, null)));
        assertThat(saved.capture()).isNotNull();
        try (var probe = ownZoneProbe(zone)) {
            var jdbc = new JdbcTemplate(probe);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(probe));
            var receiver = new ScheduleRanchTransportWriter(new ScheduleRanchTransportRepository(jdbc), jdbc, mapper, Clock.systemUTC());
            Boolean accepted = tx.execute(status -> receiver.accept(saved.capture()));
            assertThat(accepted).isTrue();
            var outboxes = new ScheduleRanchOutboxRepository(jdbc);
            var source = new ScheduleRanchOutboxDeliveryService(outboxes, mapper);
            Instant futureJavaTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                    .plus(Duration.ofDays(3650));
            var leased = tx.execute(status -> {
                Long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                var result = source.lease(new SourceOutboxLeaseRequest(futureJavaTime, 100, 300, 3));
                assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
                return result;
            });
            assertThat(leased).isNotNull();
            var ownLease = leased.stream()
                    .filter(event -> event.eventId().equals(saved.capture().payload().eventId())).toList();
            assertThat(ownLease).hasSize(1);
            var event = ownLease.getFirst();
            var stored = jdbc.query("SELECT lease_expires_at FROM schedule_ranch_outboxes WHERE id=?",
                    (rs, index) -> rs.getTimestamp(1, JdbcUtcCalendar.fresh()).toInstant(), uuidBytes(event.eventId()));
            assertThat(stored).containsExactly(event.leaseExpiresAt());
            assertThat(event.leaseExpiresAt()).isBefore(futureJavaTime);
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,lease_expires_at) "
                    +"FROM schedule_ranch_outboxes WHERE id=?", Long.class, uuidBytes(event.eventId()))).isEqualTo(300_000_000L);
            var defer = new SourceOutboxDeferRequest(event.eventId(), event.leaseToken(), futureJavaTime,
                    futureJavaTime.plusNanos(1000), 1);
            Boolean deferred = tx.execute(status -> source.defer(defer));
            assertThat(deferred).isTrue();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM schedule_ranch_outboxes WHERE id=?",
                    Integer.class, uuidBytes(event.eventId()))).isZero();
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) "
                    +"FROM schedule_ranch_outboxes WHERE id=?", Long.class, uuidBytes(event.eventId()))).isEqualTo(1_000_000L);
            Boolean repeatedDefer = tx.execute(status -> source.defer(defer));
            assertThat(repeatedDefer).isFalse();
        }
    }

    private HikariDataSource ownZoneProbe(String zone) {
        var config = new HikariConfig();
        // この資格情報はown Testcontainers fixtureだけ。出力や実環境設定の読取は行わない。
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.addDataSourceProperty("connectionTimeZone", zone);
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        config.addDataSourceProperty("preserveInstants", "true");
        return new HikariDataSource(config);
    }

    private static byte[] uuidBytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }
}
