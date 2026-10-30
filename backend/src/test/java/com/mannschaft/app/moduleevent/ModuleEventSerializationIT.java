package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** M17: 本番Serializerの往復と公式保存。ddl-autoの試練でありM01のFlyway型証明ではない。 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventSerializationIT extends AbstractMySqlIntegrationTest {
    @Autowired private EventSerializer serializer;
    @Autowired private EventPublicationRepository publications;
    @Autowired private PlatformTransactionManager transactions;
    private final List<UUID> owned = new ArrayList<>();
    public enum Scope { TEAM, ORGANIZATION }
    public record ShapeEvent(UUID id, Long userId, Long organizationId, Scope scope,
            boolean enabled, LocalDateTime occurredAt) implements ReliableEvent { }

    private static List<ShapeEvent> cases() {
        return List.of(
                new ShapeEvent(new UUID(0, 0), 1L, null, Scope.TEAM, false, LocalDateTime.of(1000, 1, 1, 0, 0)),
                new ShapeEvent(new UUID(-1, -1), Long.MAX_VALUE, Long.MAX_VALUE, Scope.ORGANIZATION, false,
                        LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999999999)),
                new ShapeEvent(UUID.fromString("80000000-0000-0000-7fff-ffffffffffff"), Long.MAX_VALUE, 1L,
                        Scope.TEAM, true, LocalDateTime.of(2026, 10, 29, 0, 0, 0, 1)),
                new ShapeEvent(UUID.fromString("7fffffff-ffff-ffff-8000-000000000000"), 1L, null,
                        Scope.ORGANIZATION, false, LocalDateTime.of(2026, 10, 29, 23, 59, 59, 999999999)));
    }

    @Test
    void M17_実Serializerは境界値とnullを往復して保持する() {
        for (ShapeEvent event : cases()) {
            Object encoded = serializer.serialize(event);
            assertThat(encoded).isInstanceOf(String.class);
            assertThat(serializer.deserialize(encoded, ShapeEvent.class)).as("境界値の意味を保持").isEqualTo(event);
        }
    }

    @Test
    void M17_同一shapeの実最大payloadを255文字以内で公式保存し読戻す() {
        var maximum = cases().stream().max(Comparator.comparingInt(event -> serializer.serialize(event).toString().length())).orElseThrow();
        String serialized = serializer.serialize(maximum).toString();
        assertThat(serialized.length()).as("同一schema境界検体の実最大serialized長・保存前判定").isLessThanOrEqualTo(255);
        UUID id = new TransactionTemplate(transactions).execute(tx -> {
            var publication = publications.create(TargetEventPublication.of(maximum,
                    PublicationTargetIdentifier.of("module-serialization-it"), Instant.now()));
            owned.add(publication.getIdentifier());
            return publication.getIdentifier();
        });
        var loaded = new TransactionTemplate(transactions).execute(tx -> publications.findIncompletePublications().stream()
                .filter(publication -> publication.getIdentifier().equals(id)).findFirst().orElseThrow());
        assertThat(loaded.getEvent()).isEqualTo(maximum);
        assertThat(serializer.serialize(loaded.getEvent()).toString()).isEqualTo(serialized);
    }

    @AfterEach
    void 自己publicationだけを回収する() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> publications.deletePublications(owned));
    }
}
