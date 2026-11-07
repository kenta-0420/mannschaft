package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.EventPublicationAttemptEntity;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxApiFixtures.FixtureResolver;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxApiFixtures.Scenario;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * 未実行の公開 M 読取窓口の実 DB 試練。先行 SUT は empty stub、green 実装前の入力を固定する。
 * 共通 meta に専用 fixture import を足すため MCC は共通群と別1個。Mock/property/基底設定を追加しない。
 * 実 Spring の caller 停止・別 M 読取 TX・TX 外 SPI・caller resource 復帰を測る。
 * この SPI 検体で将来の C/F active/role/tenant/close 接続を合格にせず、実採用ロットの AC は別途必要。
 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeOutboxApiFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeOutboxApiIT extends AbstractMySqlIntegrationTest {
    private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
    private final List<UUID> noticeKeys = new ArrayList<>();
    private final List<UUID> publicationIds = new ArrayList<>();
    private final List<UUID> operations = new ArrayList<>();
    @Autowired private ModuleEventNoticeOutboxApi api;
    @Autowired private ModuleEventNoticeReadService reads;
    @Autowired private FixtureResolver resolver;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory factory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("jpaEventPublicationRepository") private EventPublicationRepository publications;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 自分の通知と補助行と本表だけを掃除する() {
        for (UUID id : publicationIds) {
            jdbc.update("DELETE FROM event_publication_attempts WHERE publication_id = ?", bytes(id));
            jdbc.update("DELETE FROM event_publication WHERE id = ?", bytes(id));
        }
        if (!noticeKeys.isEmpty()) inTx(() -> entityManager.createQuery(
                "delete from ModuleEventNoticeOutboxEntity n where n.noticeKey in :keys")
                .setParameter("keys", noticeKeys).executeUpdate());
        operations.forEach(resolver.scenarios::remove);
    }

    @Test
    void 既知版のdescriptorは公開scalarだけを正規aliasで読み戻す() {
        ModuleEventNoticeOutboxEntity row = notice("DELAYED", "PENDING");
        ModuleEventNoticeDescriptor descriptor = api.readDescriptor(row.getNoticeRef()).orElse(null);
        assertThat(descriptor).as("公開 readDescriptor の実 DB 実装が必要").isNotNull();
        assertThat(descriptor.getNoticeKey()).isEqualTo(row.getNoticeKey());
        assertThat(descriptor.getNoticeRef()).isEqualTo(row.getNoticeRef());
        assertThat(descriptor.getImpact().getResolverKey()).isEqualTo(ModuleEventNoticeOutboxApiFixtures.RESOLVER_KEY);
        assertThat(descriptor.getImpact().getOperationId()).isEqualTo(row.getOperationId());
        assertThat(descriptor.getImpact().getEpisodeId()).isEqualTo(row.getEpisodeId());
        assertThat(descriptor.getImpact().getScopeType()).isEqualTo(ModuleEventNoticeScope.TEAM);
        assertThat(descriptor.getImpact().getOrganizationId()).isNull();
        assertThat(descriptor.getImpact().getScopeId()).isEqualTo(8001L);
        assertThat(descriptor.getPhase()).isEqualTo(ModuleEventNoticePhase.DELAYED);
        assertThat(descriptor.getAudience()).isEqualTo(ModuleEventNoticeAudience.CURRENT_ADMINS);
        ModuleEventNoticeReadService.Snapshot snapshot = inTx(() -> reads.snapshot(row.getNoticeRef())).orElse(null);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getStatus()).isEqualTo("PENDING");
        assertThat(snapshot.isPending()).isFalse();
    }

    @Test
    void 不在と未登録resolverは正常空ページと区別する() {
        assertThat(api.readDescriptor(Long.MAX_VALUE)).isEmpty();
        ModuleEventNoticeOutboxEntity missing = save(draft("DELAYED", "PENDING")
                .toBuilder().resolverKey("notice-api-missing").build());
        assertThat(api.readDescriptor(missing.getNoticeRef())).isEmpty();
        assertThat(api.nextRecipients(missing.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(api.eligibleRecipients(missing.getNoticeRef(), List.of(11L))).isEmpty();
        ModuleEventNoticeOutboxEntity connected = notice("DELAYED", "PENDING");
        assertThat(api.nextRecipients(connected.getNoticeRef(), 0, 10)).contains(List.of());
        assertThat(api.eligibleRecipients(connected.getNoticeRef(), List.of(11L))).contains(List.of());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 999})
    void 不正又は未知版はenum変換で落とさず許可もしない(int version) {
        ModuleEventNoticeOutboxEntity row = save(draft("FUTURE", "PENDING").toBuilder()
                .descriptorVersion(version).scopeType("FUTURE").build());
        assertThatCode(() -> api.readDescriptor(row.getNoticeRef())).doesNotThrowAnyException();
        assertThat(api.readDescriptor(row.getNoticeRef())).isEmpty();
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(scenario(row).recoveryCalls).hasValue(0);
        assertThat(scenario(row).pageCalls).hasValue(0);
    }

    @Test
    void callerの未commit行は独立M読取へ混入せず外側TXを保持する() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ModuleEventNoticeOutboxEntity row = new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class,
                    entityManager).saveAndFlush(draft("DELAYED", "PENDING"));
            Object callerResource = TransactionSynchronizationManager.getResource(factory);
            assertThat(callerResource).isNotNull();
            assertThat(reads.snapshot(row.getNoticeRef())).isEmpty();
            assertThat(TransactionSynchronizationManager.getResource(factory)).isSameAs(callerResource);
            assertThat(entityManager.contains(row)).isTrue();
            status.setRollbackOnly();
        });
    }

    @Test
    void 実callerTXを停止しM読取終了後のSPIから元resourceへ復帰する() {
        ModuleEventNoticeOutboxEntity row = notice("DELAYED", "PENDING");
        Scenario scenario = scenario(row);
        scenario.recipients = recipients();
        inTx(() -> {
            Object callerResource = TransactionSynchronizationManager.getResource(factory);
            assertThat(callerResource).isNotNull();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(api.nextRecipients(row.getNoticeRef(), 0, 2)).hasValueSatisfying(page -> assertThat(page).hasSize(2));
            assertThat(api.eligibleRecipients(row.getNoticeRef(), List.of(11L))).hasValueSatisfying(page -> assertThat(page).hasSize(1));
            assertThat(scenario.observedNoTransaction).isTrue();
            assertThat(scenario.recoveryCalls.get()).isGreaterThanOrEqualTo(2);
            assertThat(scenario.pageCalls).hasValue(1);
            assertThat(scenario.eligibleCalls).hasValue(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(factory)).isSameAs(callerResource);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED"})
    void keysetは六localeを有界に返し現在候補の権限変更を再評価する(String phase) {
        ModuleEventNoticeOutboxEntity row = notice(phase, "PENDING");
        Scenario scenario = scenario(row);
        scenario.recipients = recipients();
        List<ModuleEventNoticeRecipient> first = api.nextRecipients(row.getNoticeRef(), 0, 2).orElse(null);
        List<ModuleEventNoticeRecipient> second = api.nextRecipients(row.getNoticeRef(), 22, 2).orElse(null);
        List<ModuleEventNoticeRecipient> third = api.nextRecipients(row.getNoticeRef(), 44, 2).orElse(null);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(third).isNotNull();
        assertThat(first).extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(11L, 22L);
        assertThat(second).extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(33L, 44L);
        assertThat(third).extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(55L, 66L);
        List<String> locales = new ArrayList<>();
        first.forEach(recipient -> locales.add(recipient.getLocale()));
        second.forEach(recipient -> locales.add(recipient.getLocale()));
        third.forEach(recipient -> locales.add(recipient.getLocale()));
        assertThat(locales).containsExactly("ja", "en", "zh", "ko", "es", "de");
        assertThat(api.nextRecipients(row.getNoticeRef(), 66, 2)).contains(List.of());
        List<ModuleEventNoticeRecipient> eligible = api.eligibleRecipients(row.getNoticeRef(),
                List.of(11L, 22L, 999L)).orElse(null);
        assertThat(eligible).isNotNull();
        assertThat(eligible).extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(11L, 22L);
        scenario.recipients = List.of(new ModuleEventNoticeRecipient(22L, "en"));
        List<ModuleEventNoticeRecipient> changed = api.eligibleRecipients(row.getNoticeRef(),
                List.of(11L, 22L, 999L)).orElse(null);
        assertThat(changed).isNotNull();
        assertThat(changed).extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(22L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_RECOVERY", "DEAD", "FUTURE"})
    void 保留やDEADや未知statusは正常終端に読み替えず保留する(String status) {
        ModuleEventNoticeOutboxEntity row = notice("RECOVERED", status);
        scenario(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario(row).recipients = recipients();
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(api.eligibleRecipients(row.getNoticeRef(), List.of(11L))).isEmpty();
        assertThat(scenario(row).pageCalls).hasValue(0);
        assertThat(scenario(row).eligibleCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RELAYING", "RELAYED"})
    void 復旧は有効statusとpending0と業務CLOSED_COMPLETEが揃った場合だけ許可する(String status) {
        ModuleEventNoticeOutboxEntity row = notice("RECOVERED", status);
        scenario(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario(row).recipients = recipients();
        assertThat(api.eligibleRecipients(row.getNoticeRef(), List.of(11L)).orElse(List.of()))
                .extracting(ModuleEventNoticeRecipient::getUserId).containsExactly(11L);
        assertThat(scenario(row).observedNoTransaction).isTrue();
        assertThat(scenario(row).recoveryCalls).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = ModuleEventRecoveryState.class, names = {"OPEN", "UNKNOWN"})
    void 復旧の未closeや不明を正常空終端や復旧へ変換しない(ModuleEventRecoveryState recovery) {
        ModuleEventNoticeOutboxEntity row = notice("RECOVERED", "RELAYED");
        scenario(row).recovery = recovery;
        scenario(row).recipients = recipients();
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(scenario(row).pageCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(ModuleEventAttemptStatus.class)
    void 業務CLOSEDでも全statusの未完了listenerがあれば復旧を許可しない(ModuleEventAttemptStatus status) {
        ModuleEventNoticeOutboxEntity row = notice("RECOVERED", "RELAYED");
        scenario(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario(row).recipients = recipients();
        pending(row, status);
        assertThat(reads.snapshot(row.getNoticeRef()))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.isPending()).isTrue());
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(scenario(row).pageCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED"})
    void 実復旧済みの旧phaseは正常空終端となり遅れて配送しない(String phase) {
        ModuleEventNoticeOutboxEntity row = notice(phase, "RELAYED");
        scenario(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario(row).recipients = recipients();
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).contains(List.of());
        assertThat(api.eligibleRecipients(row.getNoticeRef(), List.of(11L))).contains(List.of());
        assertThat(scenario(row).pageCalls).hasValue(0);
        assertThat(scenario(row).eligibleCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOVERED", "DELAYED", "QUARANTINED"})
    void CLOSED評価後の後発attemptを別M読取で検出し許可や正常終端にしない(String phase) {
        ModuleEventNoticeOutboxEntity row = notice(phase, "RELAYED");
        Scenario scenario = scenario(row);
        scenario.recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario.recipients = recipients();
        scenario.afterClosedDecision = () -> pending(row, ModuleEventAttemptStatus.PENDING);
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(scenario.recoveryCalls).as("CLOSED 決定後に初めて後発行を追加する検体").hasValue(1);
        assertThat(publicationIds).hasSize(1);
        assertThat(scenario.pageCalls).hasValue(0);
    }

    @Test
    void CLOSED評価後にdescriptorが消えたら保存済みsnapshotで配送しない() {
        ModuleEventNoticeOutboxEntity row = notice("RECOVERED", "RELAYED");
        Scenario scenario = scenario(row);
        scenario.recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        scenario.recipients = recipients();
        scenario.afterClosedDecision = () -> inTx(() -> {
            new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).deleteById(row.getId());
            return null;
        });
        assertThat(api.eligibleRecipients(row.getNoticeRef(), List.of(11L))).isEmpty();
        assertThat(scenario.recoveryCalls).hasValue(1);
        assertThat(scenario.eligibleCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED"})
    void 旧phaseでも業務状態不明を現在許可や正常終端にしない(String phase) {
        ModuleEventNoticeOutboxEntity row = notice(phase, "RELAYED");
        scenario(row).recovery = ModuleEventRecoveryState.UNKNOWN;
        scenario(row).recipients = recipients();
        assertThat(api.nextRecipients(row.getNoticeRef(), 0, 10)).isEmpty();
        assertThat(scenario(row).recoveryCalls).hasValue(1);
        assertThat(scenario(row).pageCalls).hasValue(0);
    }

    @Test
    void 不正provider返却や不正入力を正常空や許可へ変換しない() {
        ModuleEventNoticeOutboxEntity row = notice("DELAYED", "PENDING");
        scenario(row).badPage = true;
        assertThatIllegalStateException().isThrownBy(() -> api.nextRecipients(row.getNoticeRef(), 0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> api.readDescriptor(0));
        assertThatIllegalArgumentException().isThrownBy(() -> api.nextRecipients(row.getNoticeRef(), -1, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> api.nextRecipients(row.getNoticeRef(), 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> api.nextRecipients(row.getNoticeRef(), 0, 501));
        assertThatIllegalArgumentException().isThrownBy(() -> api.eligibleRecipients(row.getNoticeRef(), null));
        assertThatIllegalArgumentException().isThrownBy(() -> api.eligibleRecipients(row.getNoticeRef(), List.of(11L, 11L)));
        assertThatIllegalArgumentException().isThrownBy(() -> api.eligibleRecipients(row.getNoticeRef(),
                LongStream.rangeClosed(1, 501).boxed().toList()));
    }

    private Scenario scenario(ModuleEventNoticeOutboxEntity row) { return resolver.scenarios.get(row.getOperationId()); }

    private ModuleEventNoticeOutboxEntity notice(String phase, String status) { return save(draft(phase, status)); }

    private ModuleEventNoticeOutboxEntity draft(String phase, String status) {
        UUID key = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        noticeKeys.add(key);
        operations.add(operation);
        resolver.scenarios.put(operation, new Scenario());
        return ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate()).noticeKey(key)
                .resolverKey(ModuleEventNoticeOutboxApiFixtures.RESOLVER_KEY).operationId(operation)
                .episodeId(UUID.randomUUID()).scopeType("TEAM").scopeId(8001L).organizationId(null)
                .phase(phase).audience("CURRENT_ADMINS").descriptorVersion(1).status(status).attemptCount(0)
                .nextAttemptAt(FUTURE).createdAt(FUTURE).updatedAt(FUTURE).build();
    }

    private ModuleEventNoticeOutboxEntity save(ModuleEventNoticeOutboxEntity row) {
        return inTx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(row));
    }

    private void pending(ModuleEventNoticeOutboxEntity row, ModuleEventAttemptStatus status) {
        UUID id = inTx(() -> {
            LocalDateTime future = LocalDateTime.ofInstant(FUTURE, SERVER_ZONE);
            TargetEventPublication publication = publications.create(TargetEventPublication.of(
                    new NoticeReadFixtureEvent(UUID.randomUUID(), null, future),
                    PublicationTargetIdentifier.of("module-notice-read-it-" + UUID.randomUUID()), FUTURE));
            new SimpleJpaRepository<>(EventPublicationAttemptEntity.class, entityManager).saveAndFlush(
                    EventPublicationAttemptEntity.builder().publicationId(publication.getIdentifier())
                            .retryCount(status == ModuleEventAttemptStatus.QUARANTINED ? 10 : 0).status(status)
                            .claimToken(UUID.randomUUID()).lastAttemptAt(FUTURE).leaseUntil(FUTURE)
                            .resolverKey(row.getResolverKey()).operationId(row.getOperationId()).episodeId(row.getEpisodeId())
                            .scopeType(ModuleEventNoticeScope.TEAM).scopeId(row.getScopeId()).organizationId(row.getOrganizationId())
                            .createdAt(FUTURE).updatedAt(FUTURE).version(0).build());
            return publication.getIdentifier();
        });
        publicationIds.add(id);
    }

    private List<ModuleEventNoticeRecipient> recipients() {
        return List.of(new ModuleEventNoticeRecipient(11L, "ja"), new ModuleEventNoticeRecipient(22L, "en"),
                new ModuleEventNoticeRecipient(33L, "zh"), new ModuleEventNoticeRecipient(44L, "ko"),
                new ModuleEventNoticeRecipient(55L, "es"), new ModuleEventNoticeRecipient(66L, "de"));
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    record NoticeReadFixtureEvent(UUID fixtureId, Long organizationId, LocalDateTime occurredAt) implements ReliableEvent { }
}
