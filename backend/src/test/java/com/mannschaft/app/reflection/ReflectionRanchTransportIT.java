package com.mannschaft.app.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.reflection.service.ReflectionRanchOutboxDeliveryService;
import com.mannschaft.app.reflection.service.ReflectionRanchOutboxAdminService;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.RecallSessionOperationFacade;
import com.mannschaft.app.reflection.service.ReflectionRanchTransportWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQL/Guardで耐久受付の原子性を検証する。fixtureの資格をHTTPの時点認可証拠へ流用しない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReflectionRanchTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private ReflectionThemeRepository themes;
    @Autowired private ReflectionEntryRepository entries;
    @Autowired private RecallSessionOperationFacade operations;
    @Autowired private ReflectionRanchTransportWriter transport;
    @Autowired private UserRewardDeliveryGuard deliveryGuard;
    @Autowired private UserOperationGuard active;
    @Autowired private ReflectionRanchOutboxDeliveryService outboxDelivery;
    @Autowired private ReflectionRanchOutboxAdminService outboxAdmin;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    private Long owner;
    private UUID themeId;

    @BeforeEach void setup() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@transport-fixture.invalid")
                .lastName("配送").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        themeId=themes.saveAndFlush(ReflectionThemeEntity.builder().userId(owner).title("配送fixture").build()).getId();
    }

    @Test void immutableCompletedFactCreatesOneWitnessAndOutbox() {
        var completed=completed(LocalDate.of(2026,10,3));
        var fact=fact(completed,UuidV7.generate());
        assertThat(receive(fact)).isTrue();
        assertThat(receive(fact(completed,UuidV7.generate()))).isFalse();
        assertThat(count("reflection_ranch_witnesses")).isEqualTo(1);
        assertThat(count("reflection_ranch_outboxes")).isEqualTo(1);
        byte[] canonicalKey=jdbc.queryForObject("SELECT canonical_key FROM reflection_ranch_outboxes WHERE id=?",
                byte[].class,bytes(fact.eventId()));
        assertThat(new String(canonicalKey,java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "PERSONAL_RECALL_COMPLETE|UUID|"+completed.entryId()+"|"+owner+"|"+completed.rewardWeek());
        String encoded=jdbc.queryForObject("SELECT payload_json FROM reflection_ranch_outboxes WHERE id=?",
                String.class,bytes(fact.eventId()));
        assertThat(encoded).doesNotContain("原文fixture","回答fixture","body_hash","originalSnapshot");
        assertThat(mapper.convertValue(read(encoded),ReflectionRecallRewardPayload.class)).isEqualTo(fact);
    }

    @Test void outboxFailureRollsBackWitnessAndKeepsNativeCompletion() {
        var first=completed(LocalDate.of(2026,10,3));
        UUID eventId=UuidV7.generate();
        assertThat(receive(fact(first,eventId))).isTrue();
        var second=completed(LocalDate.of(2026,10,4));
        // 別entryの新witness保存後に既存event PKへ衝突させ、同じ源TX全体を失敗させる。
        assertThatThrownBy(()->receive(fact(second,eventId))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("reflection_ranch_witnesses")).isEqualTo(1);
        assertThat(count("reflection_ranch_outboxes")).isEqualTo(1);
        assertThat(operations.get(owner,second.id()).status()).isEqualTo(RecallSessionStatus.COMPLETED);
    }

    @AfterEach void cleanupOnlyOwnFixtures() {
        if(owner==null) return;
        jdbc.update("DELETE FROM reflection_ranch_outboxes WHERE recipient_user_id=?",owner);
        jdbc.update("DELETE FROM reflection_ranch_witnesses WHERE recipient_user_id=?",owner);
        jdbc.update("DELETE FROM reflection_ranch_admin_commands WHERE actor_user_id=?",owner);
        jdbc.update("DELETE FROM reflection_recall_commands WHERE user_id=?",owner);
        jdbc.update("DELETE FROM recall_attempts WHERE user_id=?",owner);
        jdbc.update("DELETE FROM reflection_recall_sessions WHERE user_id=?",owner);
        jdbc.update("DELETE FROM reflection_entries WHERE user_id=?",owner);
        if(themeId!=null) themes.deleteById(themeId);
        users.deleteById(owner);
    }

    @Test void reflectionLeaseDeferralAndAdminReplayKeepSourceOwnedState() {
        var completed=completed(LocalDate.of(2026,10,3));var fact=fact(completed,UuidV7.generate());
        assertThat(receive(fact)).isTrue();var now=deliveryNow();
        var first=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,2)).getFirst();
        assertThat(first.envelope()).isEqualTo(fact.toEnvelope());
        var defer=new SourceOutboxDeferRequest(first.eventId(),first.leaseToken(),now.plusSeconds(1),now.plusSeconds(5),10);
        assertThat(outboxDelivery.defer(defer)).isTrue();assertThat(outboxDelivery.defer(defer)).isFalse();
        assertThat(jdbc.update("UPDATE reflection_ranch_outboxes SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=? AND status='RETRY'",bytes(first.eventId()))).isEqualTo(1);
        var second=outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(5),10,30,2)).getFirst();
        assertThat(second.attemptCount()).isEqualTo(1);
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(first.eventId(),first.leaseToken(),now.plusSeconds(6),terminal))).isFalse();
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(second.eventId(),second.leaseToken(),now.plusSeconds(6),terminal))).isTrue();
        var key=UUID.randomUUID();var reason=new SourceOutboxAdminRetryRequest("OPERATOR_RETRY");
        var ack=active.withActiveUser(owner,() -> outboxAdmin.retry(owner,second.eventId(),key,reason,now.plusSeconds(7)));
        assertThat(ack.disposition()).isEqualTo(SourceOutboxAdminRetryAck.Disposition.ALREADY_TERMINAL);
        jdbc.update("DELETE FROM reflection_ranch_outboxes WHERE id=?",bytes(second.eventId()));
        SourceOutboxAdminRetryAck replayAck=active.withActiveUser(owner,() -> outboxAdmin.retry(owner,second.eventId(),key,reason,now.plusSeconds(8)));
        assertThat(replayAck).isEqualTo(ack);
        assertThat(count("reflection_ranch_outboxes")).isZero();
        // actor fixtureは源命令の原子性用。SYSTEM_ADMIN HTTP認可・実purgeの証拠ではない。
    }

    @Test void reflectionPoisonAndExpiredBudgetDoNotInventNewEvents() {
        var first=completed(LocalDate.of(2026,10,3));var bad=fact(first,UuidV7.generate());assertThat(receive(bad)).isTrue();
        var second=completed(LocalDate.of(2026,10,4));var valid=fact(second,UuidV7.generate());assertThat(receive(valid)).isTrue();
        jdbc.update("UPDATE reflection_ranch_outboxes SET payload_json='{}' WHERE id=?",bytes(bad.eventId()));
        var now=deliveryNow();var leased=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,1));
        assertThat(leased).hasSize(1);assertThat(leased.getFirst().eventId()).isEqualTo(valid.eventId());
        assertThat(outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(29),10,30,1))).isEmpty();
        // 自己の現leaseだけを期限切れにし、native発生時刻には触れない。
        var current=leased.getFirst();
        assertThat(jdbc.update("UPDATE reflection_ranch_outboxes SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=? AND status='LEASED' AND lease_token=?",bytes(current.eventId()),bytes(current.leaseToken()))).isEqualTo(1);
        assertThat(outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(30),10,30,1))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reflection_ranch_outboxes WHERE recipient_user_id=? AND status='DEAD_LETTER'",Integer.class,owner)).isEqualTo(2);
        assertThat(count("reflection_ranch_outboxes")).isEqualTo(2);
        assertThat(operations.get(owner,first.id()).status()).isEqualTo(RecallSessionStatus.COMPLETED);
        assertThat(operations.get(owner,second.id()).status()).isEqualTo(RecallSessionStatus.COMPLETED);
    }
    private java.time.Instant deliveryNow() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,index) -> rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant()); }
    private boolean receive(ReflectionRecallRewardPayload fact) {
        return deliveryGuard.withLockedDeliveryUser(owner,state->transport.accept(fact));
    }
    private RecallSessionResponse completed(LocalDate date) {
        UUID entry=entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(themeId)
                .targetDate(date).structuredContent("{\"free_note\":\"原文fixture\"}")
                .visibility(ReflectionVisibility.PRIVATE).build()).getId();
        var started=operations.start(owner,entry,UUID.randomUUID(),mapper.createObjectNode());
        var body=mapper.valueToTree(Map.of("version",started.version(),"answers",List.of(Map.of(
                "promptId",started.prompts().getFirst().id().toString(),"state","ANSWERED","text","回答fixture")),
                "selfRating","REMEMBERED"));
        return operations.complete(owner,started.id(),UUID.randomUUID(),body);
    }
    private ReflectionRecallRewardPayload fact(RecallSessionResponse response,UUID eventId) {
        return new ReflectionRecallRewardPayload(eventId,1,RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.IdType.UUID,response.entryId().toString(),RanchRewardEnvelope.ScopeType.PERSONAL,
                null,null,RanchRewardEnvelope.ActorKind.USER,owner,null,owner,owner,response.completedAt(),
                RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,new RanchRewardEnvelope.PersonalRecall(
                        response.id(),response.prompts().size(),response.rewardWeek(),true));
    }
    private int count(String table) {
        if(!List.of("reflection_ranch_witnesses","reflection_ranch_outboxes").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE recipient_user_id=?",Integer.class,owner);
    }
    private com.fasterxml.jackson.databind.JsonNode read(String encoded) {
        try{return mapper.readTree(encoded);}catch(Exception ignored){throw new AssertionError("技術factの読取失敗");}
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    /** native JPAで確定した不変事実を接続別zoneでも保存・読取する。共有pool/設定は変更しない。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UTC","Pacific/Honolulu"})
    void nativeEventAndLeaseRoundTripIgnoreJdbcSessionZone(String zone) throws Exception {
        var completed=completed(LocalDate.of(2026,10,3));var fact=fact(completed,UuidV7.generate());
        assertThat(operations.get(owner,completed.id()).completedAt()).isEqualTo(fact.occurredAt());
        String encoded=mapper.writeValueAsString(fact);
        var config=new com.zaxxer.hikari.HikariConfig();
        // own Testcontainers資格だけを使い、出力しない。
        config.setJdbcUrl(MYSQL.getJdbcUrl());config.setUsername(MYSQL.getUsername());config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);
        config.addDataSourceProperty("connectionTimeZone",zone);
        config.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
        config.addDataSourceProperty("preserveInstants","true");
        try(var probe=new com.zaxxer.hikari.HikariDataSource(config)) {
            var probeJdbc=new JdbcTemplate(probe);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(probe));
            // JPA完了資格は上で照合。ここは源所有JDBC insert/read境界で、配送authの証明ではない。
            var transportRows=new com.mannschaft.app.reflection.repository.ReflectionRanchTransportRepository(probeJdbc);
            Boolean accepted=tx.execute(status->transportRows.insertQualified(fact,encoded,java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
            assertThat(accepted).as("実native事実の耐久受付 zone=%s",zone).isTrue();
            var repository=new com.mannschaft.app.reflection.repository.ReflectionRanchOutboxRepository(probeJdbc);
            tx.executeWithoutResult(status -> {
                java.time.Instant dbNow=probeJdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant());
                var candidate=repository.candidates(dbNow,100).stream().filter(row->row.eventId().equals(fact.eventId())).findFirst().orElseThrow();
                assertThat(candidate.occurredAt()).as("保存payloadと実readerの不変Instant").isEqualTo(fact.occurredAt());
                UUID token=com.mannschaft.app.common.UuidV7.generate();
                java.util.Optional<java.time.Instant> expiry=repository.lease(fact.eventId(),token,dbNow.plusSeconds(30),dbNow);
                assertThat(expiry).isPresent();
                byte[] event=java.nio.ByteBuffer.allocate(16).putLong(fact.eventId().getMostSignificantBits()).putLong(fact.eventId().getLeastSignificantBits()).array();
                byte[] leaseToken=java.nio.ByteBuffer.allocate(16).putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array();
                java.time.Instant stored=probeJdbc.queryForObject("SELECT lease_expires_at FROM reflection_ranch_outboxes WHERE id=? AND lease_token=? AND status='LEASED'",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant(),event,leaseToken);
                assertThat(expiry.orElseThrow()).as("公表期限は同じ源TXの実DB期限").isEqualTo(stored);
            });
        }
    }
}
