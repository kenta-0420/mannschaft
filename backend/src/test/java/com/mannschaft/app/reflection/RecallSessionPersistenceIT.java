package com.mannschaft.app.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.RecallSessionCommandRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.RecallSessionOperationFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実Guard/Writer/MySQLの原子保存と再送。HTTP filter認可と報酬winnerの証明とは区別する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecallSessionPersistenceIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private ReflectionThemeRepository themes;
    @Autowired private ReflectionEntryRepository entries;
    @Autowired private RecallAttemptRepository attempts;
    @Autowired private RecallSessionCommandRepository commands;
    @Autowired private RecallSessionOperationFacade operations;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    private Long owner;
    private UUID entryId;

    @BeforeEach void setup() {
        owner = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@ar-fixture.invalid")
                .lastName("想起").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        var theme = themes.saveAndFlush(ReflectionThemeEntity.builder().userId(owner).title("想起fixture").build());
        entryId = entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(theme.getId())
                .targetDate(LocalDate.of(2026,10,3)).structuredContent("{\"free_note\":\"開始時の凍結原文\"}")
                .visibility(ReflectionVisibility.PRIVATE).build()).getId();
    }

    @Test void completionFreezesOriginalAndSameKeyReplaysOneAttempt() {
        var started = operations.start(owner,entryId,UUID.randomUUID(),mapper.createObjectNode());
        assertThat(started.original()).isNull();
        assertThat(started.prompts()).hasSize(1);
        jdbc.update("UPDATE reflection_entries SET structured_content=? WHERE id=?",
                "{\"free_note\":\"開始後に編集した原文\"}",uuidBytes(entryId));
        UUID key=UUID.randomUUID();
        var body=completeBody(started,"本人の回答");
        var completed=operations.complete(owner,started.id(),key,body);
        assertThat(completed.status()).isEqualTo(RecallSessionStatus.COMPLETED);
        assertThat(completed.original().structuredContent().path("free_note").asText()).isEqualTo("開始時の凍結原文");
        com.fasterxml.jackson.databind.JsonNode replayJson=mapper.valueToTree(operations.complete(owner,started.id(),key,body));
        com.fasterxml.jackson.databind.JsonNode completedJson=mapper.valueToTree(completed);
        assertThat(replayJson).isEqualTo(completedJson);
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entryId)).hasSize(1);
        assertThatThrownBy(()->operations.complete(owner,started.id(),key,completeBody(started,"別の回答")))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(RecallSessionErrorCode.COMMAND_CONFLICT));
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entryId)).hasSize(1);
    }

    @Test void incompleteCompletionRollsBackAndCancellationCreatesNoAttempt() {
        var started=operations.start(owner,entryId,UUID.randomUUID(),mapper.createObjectNode());
        UUID failedKey=UUID.randomUUID();
        var invalid=mapper.valueToTree(Map.of("version",started.version(),"answers",List.of(),"selfRating","FORGOT"));
        assertThatThrownBy(()->operations.complete(owner,started.id(),failedKey,invalid)).isInstanceOf(BusinessException.class);
        assertThat(operations.get(owner,started.id()).status()).isEqualTo(RecallSessionStatus.STARTED);
        assertThat(operations.get(owner,started.id()).version()).isEqualTo(started.version());
        var failedCommand = new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> commands.findOwnedForUpdate(owner,failedKey));
        assertThat(failedCommand).isEmpty();
        var cancelled=operations.cancel(owner,started.id(),UUID.randomUUID(),mapper.valueToTree(Map.of("version",started.version())));
        assertThat(cancelled.status()).isEqualTo(RecallSessionStatus.CANCELLED);
        assertThat(cancelled.original()).isNull();
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entryId)).isEmpty();
    }

    @Test void freshFrozenUserCannotStartNewRecall() {
        jdbc.update("UPDATE users SET status='FROZEN' WHERE id=?",owner);
        assertThatThrownBy(()->operations.start(owner,entryId,UUID.randomUUID(),mapper.createObjectNode()))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode().getCode()).isEqualTo("AUTHOPERATION_002"));
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entryId)).isEmpty();
    }

    private com.fasterxml.jackson.databind.JsonNode completeBody(RecallSessionResponse started,String text) {
        return mapper.valueToTree(Map.of("version",started.version(),"answers",List.of(Map.of(
                "promptId",started.prompts().getFirst().id().toString(),"state","ANSWERED","text",text)),"selfRating","REMEMBERED"));
    }
    private static byte[] uuidBytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
