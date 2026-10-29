package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.EventPublicationAttemptEntity;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 未実行の Lifecycle 実 DB 試練。新4操作は stub のまま、実 assertion red 後まで green 実装しない。
 * 共通 meta のみ、fixture Bean/mock/設定/新 DDL は追加しない。検体は公式 Repository/EntityBuilder で作る。
 * candidate と個々の reserve は外側から別 Bean 呼出しとし、自己呼出しの TX 喪失を避ける契約。
 * 実 core hook/管理再開/Q afterCommit Ops/SPI close/recipient/retention/全 M の合格を意味しない。
 * 本表の Instant 原時刻と helper の SERVER_ZONE LocalDateTime を混同しない。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeLifecycleIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2100-01-01T01:00:00Z");
    private final List<UUID> ownOperations = new ArrayList<>();
    private final List<UUID> ownPublications = new ArrayList<>();
    @Autowired private ModuleEventNoticeLifecycleService lifecycle;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 自分の論理操作と本表補助行だけを掃除する() {
        for (UUID id : ownPublications) {
            jdbc.update("DELETE FROM event_publication_attempts WHERE publication_id = ?", bytes(id));
            jdbc.update("DELETE FROM event_publication WHERE id = ?", bytes(id));
        }
        if (!ownOperations.isEmpty()) inNewTx(() -> entityManager.createQuery(
                "delete from ModuleEventNoticeOutboxEntity n where n.operationId in :operations")
                .setParameter("operations", ownOperations).executeUpdate());
    }

    @Test
    void 空候補と正常高速成功は影響通知も復旧intentも作らない() {
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).isEmpty();
        Publication fixture = publication(binding(), NOW.minusSeconds(1), ModuleEventAttemptStatus.IN_PROGRESS);
        inNewTx(() -> { complete(fixture); return null; });
        assertThat(allOwnNotices()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void 候補の不正limitを拒否し予約を作らない(int limit) {
        publication(binding(), NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        assertThatIllegalArgumentException().isThrownBy(() -> lifecycle.findDelayedCandidateIds(NOW, limit));
        assertThat(allOwnNotices()).isEmpty();
    }

    @Test
    void 候補は101行あっても1件と100件の上限を守りscalarIDだけを返す() {
        ModuleEventImpactBinding binding = binding();
        List<UUID> helperIds = inNewTx(() -> {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 101; i++) ids.add(createPublication(binding, NOW.minusSeconds(3600),
                    ModuleEventAttemptStatus.PENDING).attemptId());
            return ids;
        });
        List<UUID> first = lifecycle.findDelayedCandidateIds(NOW, 1);
        List<UUID> hundred = lifecycle.findDelayedCandidateIds(NOW, 100);
        assertThat(first).hasSize(1).allMatch(helperIds::contains);
        assertThat(hundred).hasSize(100).doesNotHaveDuplicates().allMatch(helperIds::contains);
        assertThat(allOwnNotices()).isEmpty();
    }

    @Test
    void 最古100bindingが予約済みなら残りへ進み同bindingの兄弟を再選択しない() {
        List<Publication> fixtures = inNewTx(() -> {
            List<Publication> created = new ArrayList<>();
            for (int i = 0; i < 101; i++) created.add(createPublication(binding(), NOW.minusSeconds(3600),
                    ModuleEventAttemptStatus.PENDING));
            return created;
        });
        List<UUID> first = lifecycle.findDelayedCandidateIds(NOW, 100);
        assertThat(first).hasSize(100).doesNotHaveDuplicates();
        for (UUID id : first) assertThat(lifecycle.reserveDelayed(id, NOW)).isTrue();
        List<Publication> reserved = fixtures.stream().filter(f -> first.contains(f.attemptId())).toList();
        assertThat(reserved).hasSize(100);
        reserved.forEach(f -> assertPhase(f.binding(), "DELAYED", "PENDING"));
        Publication remaining = fixtures.stream().filter(f -> !first.contains(f.attemptId())).findFirst().orElseThrow();
        // 同じ episode の後発 listener は同じ2 intentへ収束し、候補枠を消費しない。
        Publication sibling = publication(reserved.getFirst().binding(), NOW.minusSeconds(3600),
                ModuleEventAttemptStatus.IN_PROGRESS);
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100))
                .containsExactly(remaining.attemptId()).doesNotContain(sibling.attemptId());
        assertThat(lifecycle.reserveDelayed(remaining.attemptId(), NOW)).isTrue();
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).isEmpty();
        assertThat(allOwnNotices()).hasSize(202);
    }

    @Test
    void 片方audienceだけの遅延intentでは候補から除外せず不足分を予約する() {
        Publication fixture = publication(binding(), NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isTrue();
        List<ModuleEventNoticeOutboxEntity> initial = rows(fixture.binding(), "DELAYED");
        ModuleEventNoticeOutboxEntity retained = initial.stream()
                .filter(n -> n.getAudience().equals("CURRENT_ADMINS")).findFirst().orElseThrow();
        inNewTx(() -> {
            new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).deleteById(initial.stream()
                    .filter(n -> n.getAudience().equals("AFFECTED_USERS")).findFirst().orElseThrow().getId());
            return null;
        });
        assertThat(rows(fixture.binding(), "DELAYED")).hasSize(1);
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).containsExactly(fixture.attemptId());
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isTrue();
        assertPhase(fixture.binding(), "DELAYED", "PENDING");
        assertThat(rows(fixture.binding(), "DELAYED")).anySatisfy(n -> {
            assertThat(n.getId()).isEqualTo(retained.getId());
            assertThat(n.getNoticeKey()).isEqualTo(retained.getNoticeKey());
            assertThat(n.getNoticeRef()).isEqualTo(retained.getNoticeRef());
        });
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void 遅延はpublication原時刻が厳密30分超のときだけ予約する(int offsetSeconds) {
        Publication fixture = publication(binding(), NOW.minusSeconds(1800).plusSeconds(offsetSeconds),
                ModuleEventAttemptStatus.IN_PROGRESS);
        List<UUID> candidates = lifecycle.findDelayedCandidateIds(NOW, 100);
        if (offsetSeconds < 0) assertThat(candidates).containsExactly(fixture.attemptId());
        else assertThat(candidates).isEmpty();
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isEqualTo(offsetSeconds < 0);
        if (offsetSeconds < 0) assertPhase(fixture.binding(), "DELAYED", "PENDING");
        else assertThat(allOwnNotices()).isEmpty();
    }

    @Test
    void 後発helperと直近試行でも古いpublicationの遅延時計をリセットしない() {
        Publication fixture = publication(binding(), NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        // helper createdAt/lastAttemptAt は NOW。publication の原時刻だけが一時間前。
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).containsExactly(fixture.attemptId());
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isTrue();
        assertPhase(fixture.binding(), "DELAYED", "PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HELPER_DELETED", "OFFICIAL_COMPLETED", "QUARANTINED_AFTER_CANDIDATE"})
    void candidate取得後のhelper削除や公式完了やQ化は遅延予約しない(String changed) {
        Publication fixture = publication(binding(), NOW.minusSeconds(3600), ModuleEventAttemptStatus.IN_PROGRESS);
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).containsExactly(fixture.attemptId());
        inNewTx(() -> {
            if (changed.equals("HELPER_DELETED")) {
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).deleteById(fixture.attemptId());
            } else if (changed.equals("OFFICIAL_COMPLETED")) publications.markCompleted(fixture.publicationId(), NOW);
            else {
                EventPublicationAttemptEntity helper = lock(fixture);
                new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).saveAndFlush(
                        helper.toBuilder().status(ModuleEventAttemptStatus.QUARANTINED).retryCount(10)
                                .quarantinedAt(LocalDateTime.ofInstant(NOW, SERVER_ZONE)).build());
            }
            return null;
        });
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isFalse();
        assertThat(allOwnNotices()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUARANTINED", "UNBOUND"})
    void Q状態又は未接続bindingのhelperは遅延通知へ進めない(String kind) {
        ModuleEventImpactBinding binding = binding();
        Publication fixture = publication(kind.equals("UNBOUND") ? null : binding, NOW.minusSeconds(3600),
                kind.equals("QUARANTINED") ? ModuleEventAttemptStatus.QUARANTINED : ModuleEventAttemptStatus.PENDING);
        assertThat(lifecycle.findDelayedCandidateIds(NOW, 100)).isEmpty();
        assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isFalse();
        assertThat(allOwnNotices()).isEmpty();
    }

    @Test
    void 同episodeの兄弟helperでも同phaseとaudienceはそれぞれ一件になる() {
        ModuleEventImpactBinding binding = binding();
        Publication first = publication(binding, NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        Publication second = publication(binding, NOW.minusSeconds(3600), ModuleEventAttemptStatus.IN_PROGRESS);
        assertThat(lifecycle.reserveDelayed(first.attemptId(), NOW)).isTrue();
        List<ModuleEventNoticeOutboxEntity> original = rows(binding, "DELAYED");
        assertThat(lifecycle.reserveDelayed(second.attemptId(), NOW)).isTrue();
        List<ModuleEventNoticeOutboxEntity> repeated = rows(binding, "DELAYED");
        assertPhase(binding, "DELAYED", "PENDING");
        assertThat(repeated).extracting(ModuleEventNoticeOutboxEntity::getNoticeKey)
                .containsExactlyInAnyOrderElementsOf(original.stream().map(ModuleEventNoticeOutboxEntity::getNoticeKey).toList());
    }

    @Test
    void 六scalarとphaseとaudienceの識別を混線させない() {
        ModuleEventImpactBinding first = organizationBinding(UUID.randomUUID(), UUID.randomUUID(), 9101L);
        List<ModuleEventImpactBinding> bindings = List.of(first,
                organizationBinding(first.getOperationId(), UUID.randomUUID(), 9101L),
                organizationBinding(first.getOperationId(), first.getEpisodeId(), 9102L),
                organizationBinding(UUID.randomUUID(), first.getEpisodeId(), 9101L),
                new ModuleEventImpactBinding("lifecycle-other-it", first.getOperationId(), first.getEpisodeId(),
                        ModuleEventNoticeScope.TEAM, 9101L, 9101L),
                new ModuleEventImpactBinding("lifecycle-it", first.getOperationId(), first.getEpisodeId(),
                        ModuleEventNoticeScope.ORGANIZATION, 9101L, 9101L),
                new ModuleEventImpactBinding("lifecycle-it", first.getOperationId(), first.getEpisodeId(),
                        ModuleEventNoticeScope.TEAM, 9102L, 9101L),
                new ModuleEventImpactBinding("lifecycle-it", first.getOperationId(), first.getEpisodeId(),
                        ModuleEventNoticeScope.TEAM, 9101L, null));
        Publication delayed = publication(first, NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        assertThat(lifecycle.reserveDelayed(delayed.attemptId(), NOW)).isTrue();
        List<Publication> quarantined = inNewTx(() -> bindings.stream()
                .map(binding -> createPublication(binding, NOW.minusSeconds(1), ModuleEventAttemptStatus.QUARANTINED)).toList());
        inNewTx(() -> {
            quarantined.forEach(fixture -> { lock(fixture); lifecycle.reserveQuarantined(fixture.binding(), NOW); });
            return null;
        });
        assertPhase(first, "DELAYED", "PENDING");
        bindings.forEach(binding -> assertPhase(binding, "QUARANTINED", "PENDING"));
        assertThat(allOwnNotices()).hasSize(18).extracting(ModuleEventNoticeOutboxEntity::getNoticeKey).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void Q予約は同M_TXで二audienceを作りrollbackなら両方消える(boolean commit) {
        Publication fixture = publication(binding(), NOW.minusSeconds(1), ModuleEventAttemptStatus.QUARANTINED);
        TransactionTemplate transaction = transaction();
        transaction.executeWithoutResult(status -> {
            lock(fixture);
            lifecycle.reserveQuarantined(fixture.binding(), NOW);
            assertPhase(fixture.binding(), "QUARANTINED", "PENDING");
            if (!commit) status.setRollbackOnly();
        });
        if (commit) {
            assertPhase(fixture.binding(), "QUARANTINED", "PENDING");
            assertThat(rows(fixture.binding(), "RECOVERED")).isEmpty();
        } else assertThat(allOwnNotices()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUARANTINED", "SUCCESS"})
    void core参加用操作は既存M_TXなしでは実行しない(String operation) {
        ModuleEventImpactBinding binding = binding();
        assertThatThrownBy(() -> {
            if (operation.equals("QUARANTINED")) lifecycle.reserveQuarantined(binding, NOW);
            else lifecycle.beforeSuccessfulRemoval(binding, NOW);
        }).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(allOwnNotices()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED"})
    void 既存impactのあるaudienceだけ成功前にWAITING_intentを残す(String impactPhase) {
        Publication fixture = publication(binding(), NOW.minusSeconds(1), ModuleEventAttemptStatus.IN_PROGRESS);
        seedImpact(fixture.binding(), impactPhase, ModuleEventNoticeAudience.CURRENT_ADMINS);
        inNewTx(() -> { lock(fixture); lifecycle.beforeSuccessfulRemoval(fixture.binding(), NOW); return null; });
        List<ModuleEventNoticeOutboxEntity> recovered = rows(fixture.binding(), "RECOVERED");
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().getAudience()).isEqualTo("CURRENT_ADMINS");
        assertThat(recovered.getFirst().getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(recovered.getFirst().getClaimToken()).isNull();
    }

    @Test
    void officialとhelperの成功削除後も復旧intentとdescriptorは残る() {
        Publication fixture = publication(binding(), NOW.minusSeconds(3600), ModuleEventAttemptStatus.IN_PROGRESS);
        seedImpact(fixture.binding(), "DELAYED", ModuleEventNoticeAudience.CURRENT_ADMINS);
        inNewTx(() -> { complete(fixture); return null; });
        List<ModuleEventNoticeOutboxEntity> recovered = rows(fixture.binding(), "RECOVERED");
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(recovered.getFirst().getOperationId()).isEqualTo(fixture.binding().getOperationId());
        assertThat(recovered.getFirst().getEpisodeId()).isEqualTo(fixture.binding().getEpisodeId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE id = ?", Long.class,
                bytes(fixture.publicationId()))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts WHERE id = ?", Long.class,
                bytes(fixture.attemptId()))).isZero();
    }

    @Test
    void 兄弟が未完了の一部成功でもintentをPENDINGへ即昇格させない() {
        ModuleEventImpactBinding binding = binding();
        Publication first = publication(binding, NOW.minusSeconds(3600), ModuleEventAttemptStatus.IN_PROGRESS);
        Publication second = publication(binding, NOW.minusSeconds(3600), ModuleEventAttemptStatus.PENDING);
        seedImpact(binding, "QUARANTINED", ModuleEventNoticeAudience.CURRENT_ADMINS);
        inNewTx(() -> { complete(first); return null; });
        List<ModuleEventNoticeOutboxEntity> recovered = rows(binding, "RECOVERED");
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication_attempts WHERE id = ?", Long.class,
                bytes(second.attemptId()))).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED", "RECOVERED"})
    void 同key再呼出しは既存statusとtokenとattemptをリセットしない(String phase) {
        Publication fixture = publication(binding(), NOW.minusSeconds(3600), phase.equals("QUARANTINED")
                ? ModuleEventAttemptStatus.QUARANTINED : ModuleEventAttemptStatus.IN_PROGRESS);
        if (phase.equals("RECOVERED")) {
            for (ModuleEventNoticeAudience audience : ModuleEventNoticeAudience.values()) seedImpact(fixture.binding(), "DELAYED", audience);
        }
        reserve(fixture, phase);
        List<ModuleEventNoticeOutboxEntity> original = rows(fixture.binding(), phase);
        assertThat(original).hasSize(2);
        UUID id = original.getFirst().getId();
        UUID token = UUID.randomUUID();
        inNewTx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(
                original.getFirst().toBuilder().status("RELAYING").claimToken(token).claimedAt(NOW)
                        .attemptCount(3).nextAttemptAt(NOW.plusSeconds(60)).build()));
        reserve(fixture, phase);
        List<ModuleEventNoticeOutboxEntity> repeated = rows(fixture.binding(), phase);
        assertThat(repeated).hasSize(2);
        ModuleEventNoticeOutboxEntity unchanged = repeated.stream().filter(n -> n.getId().equals(id)).findFirst().orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo("RELAYING");
        assertThat(unchanged.getClaimToken()).isEqualTo(token);
        assertThat(unchanged.getAttemptCount()).isEqualTo(3);
        assertThat(unchanged.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(unchanged.getNoticeKey()).isEqualTo(original.getFirst().getNoticeKey());
        assertThat(unchanged.getNoticeRef()).isEqualTo(original.getFirst().getNoticeRef());
    }

    private void reserve(Publication fixture, String phase) {
        if (phase.equals("DELAYED")) assertThat(lifecycle.reserveDelayed(fixture.attemptId(), NOW)).isTrue();
        else inNewTx(() -> {
            lock(fixture);
            if (phase.equals("QUARANTINED")) lifecycle.reserveQuarantined(fixture.binding(), NOW);
            else lifecycle.beforeSuccessfulRemoval(fixture.binding(), NOW);
            return null;
        });
    }

    private void complete(Publication fixture) {
        EventPublicationAttemptEntity helper = lock(fixture);
        lifecycle.beforeSuccessfulRemoval(fixture.binding(), NOW);
        publications.markCompleted(fixture.publicationId(), NOW);
        new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).delete(helper);
    }

    private EventPublicationAttemptEntity lock(Publication fixture) {
        return entityManager.find(EventPublicationAttemptEntity.class, fixture.attemptId(), LockModeType.PESSIMISTIC_WRITE);
    }

    private Publication publication(ModuleEventImpactBinding binding, Instant original, ModuleEventAttemptStatus status) {
        return inNewTx(() -> createPublication(binding, original, status));
    }

    private Publication createPublication(ModuleEventImpactBinding binding, Instant original, ModuleEventAttemptStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, SERVER_ZONE);
        TargetEventPublication publication = publications.create(TargetEventPublication.of(
                new LifecycleFixtureEvent(UUID.randomUUID(), null, LocalDateTime.ofInstant(original, SERVER_ZONE)),
                PublicationTargetIdentifier.of("module-notice-lifecycle-it-" + UUID.randomUUID()), original));
        ownPublications.add(publication.getIdentifier());
        EventPublicationAttemptEntity helper = new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager)
                .saveAndFlush(EventPublicationAttemptEntity.builder().id(UuidV7.generate()).publicationId(publication.getIdentifier())
                        .retryCount(status == ModuleEventAttemptStatus.QUARANTINED ? 10 : 0).status(status)
                        .claimToken(UUID.randomUUID()).lastAttemptAt(now).leaseUntil(now.plusMinutes(5))
                        .quarantinedAt(status == ModuleEventAttemptStatus.QUARANTINED ? now : null)
                        .resolverKey(binding == null ? null : binding.getResolverKey())
                        .operationId(binding == null ? null : binding.getOperationId()).episodeId(binding == null ? null : binding.getEpisodeId())
                        .scopeType(binding == null ? null : binding.getScopeType()).scopeId(binding == null ? null : binding.getScopeId())
                        .organizationId(binding == null ? null : binding.getOrganizationId()).createdAt(now).updatedAt(now).version(0).build());
        return new Publication(publication.getIdentifier(), helper.getId(), binding);
    }

    private ModuleEventImpactBinding binding() {
        UUID operation = UUID.randomUUID();
        ownOperations.add(operation);
        return new ModuleEventImpactBinding("lifecycle-it", operation, UUID.randomUUID(), ModuleEventNoticeScope.TEAM, 8301L, null);
    }

    private ModuleEventImpactBinding organizationBinding(UUID operation, UUID episode, long organization) {
        ownOperations.add(operation);
        return new ModuleEventImpactBinding("lifecycle-it", operation, episode, ModuleEventNoticeScope.TEAM,
                9101L, organization);
    }

    private void seedImpact(ModuleEventImpactBinding binding, String phase, ModuleEventNoticeAudience audience) {
        inNewTx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(
                ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate()).noticeKey(UUID.randomUUID())
                        .resolverKey(binding.getResolverKey()).operationId(binding.getOperationId()).episodeId(binding.getEpisodeId())
                        .scopeType(binding.getScopeType().name()).scopeId(binding.getScopeId()).organizationId(binding.getOrganizationId())
                        .phase(phase).audience(audience.name()).descriptorVersion(1).status("PENDING").attemptCount(0)
                        .nextAttemptAt(NOW).createdAt(NOW).updatedAt(NOW).build()));
    }

    private void assertPhase(ModuleEventImpactBinding binding, String phase, String status) {
        List<ModuleEventNoticeOutboxEntity> rows = rows(binding, phase);
        assertThat(rows).hasSize(2).extracting(ModuleEventNoticeOutboxEntity::getAudience)
                .containsExactlyInAnyOrder("CURRENT_ADMINS", "AFFECTED_USERS");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(status);
            assertThat(row.getId().version()).isEqualTo(7);
            assertThat(row.getNoticeRef()).isPositive();
            assertThat(row.getDescriptorVersion()).isEqualTo(1);
        });
    }

    private List<ModuleEventNoticeOutboxEntity> rows(ModuleEventImpactBinding binding, String phase) {
        return allOwnNotices().stream().filter(n -> n.getResolverKey().equals(binding.getResolverKey())
                && n.getOperationId().equals(binding.getOperationId()) && n.getEpisodeId().equals(binding.getEpisodeId())
                && n.getScopeType().equals(binding.getScopeType().name()) && n.getScopeId().equals(binding.getScopeId())
                && Objects.equals(n.getOrganizationId(), binding.getOrganizationId()) && n.getPhase().equals(phase)).toList();
    }

    private List<ModuleEventNoticeOutboxEntity> allOwnNotices() {
        if (ownOperations.isEmpty()) return List.of();
        // REQUIRED 読取で同 TX の予約を検証し、rollback 試練の未commit行を別 TX へ逃がさない。
        return new TransactionTemplate(transactions).execute(status -> entityManager.createQuery(
                "select n from ModuleEventNoticeOutboxEntity n where n.operationId in :operations", ModuleEventNoticeOutboxEntity.class)
                .setParameter("operations", ownOperations).getResultList());
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private <T> T inNewTx(Supplier<T> action) { return transaction().execute(status -> action.get()); }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private record Publication(UUID publicationId, UUID attemptId, ModuleEventImpactBinding binding) { }
    record LifecycleFixtureEvent(UUID fixtureId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }
}
