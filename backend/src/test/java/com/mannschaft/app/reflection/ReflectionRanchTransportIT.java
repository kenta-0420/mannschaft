package com.mannschaft.app.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
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
}
