package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeClaim;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 未実行の relay 公開窓口の実 DB 試練。6操作は stub のまま、実 assertion red の入力を固定する。
 * 共通 meta のみを使い、fixture Bean・mock・property・App設定は追加しない。
 * 自分の予約 ID だけを Repository で作成・削除し、将来時刻で通常ポーラーとの競合を避ける。
 * adapter/実 relay enqueue/配信/retention の結合 AC はこの試練では証明しない。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeRelayApiIT extends AbstractMySqlIntegrationTest {
    private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
    private final List<UUID> ownIds = new ArrayList<>();
    @Autowired private ModuleEventNoticeOutboxApi api;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManagerFactory factory;
    @Autowired private DataSource dataSource;
    @PersistenceContext private EntityManager entityManager;

    @AfterEach
    void 自分の予約だけを削除する() {
        if (!ownIds.isEmpty()) inTx(() -> entityManager.createQuery(
                "delete from ModuleEventNoticeOutboxEntity n where n.id in :ids")
                .setParameter("ids", ownIds).executeUpdate());
    }

    @Test
    void provider未登録でも既知版claimは配送許可とは別のstructuraldescriptorを返す() {
        ModuleEventNoticeOutboxEntity row = save(draft("PENDING"));
        ModuleEventNoticeClaim claim = claimOne();
        assertThat(claim.getId()).isEqualTo(row.getId());
        assertThat(claim.getClaimToken()).isNotNull();
        assertThat(claim.getNoticeRef()).isEqualTo(row.getNoticeRef()).isPositive();
        assertThat(claim.getDescriptorVersion()).isEqualTo(1);
        assertThat(claim.getAttemptCount()).isZero();
        assertThat(claim.getDescriptor()).isPresent();
        ModuleEventNoticeDescriptor descriptor = claim.getDescriptor().orElse(null);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.getNoticeKey()).isEqualTo(row.getNoticeKey());
        assertThat(descriptor.getImpact().getResolverKey()).isEqualTo("relay-api-unconnected");
        assertThat(descriptor.getImpact().getOperationId()).isEqualTo(row.getOperationId());
        assertThat(descriptor.getImpact().getEpisodeId()).isEqualTo(row.getEpisodeId());
        assertThat(descriptor.getImpact().getScopeType()).isEqualTo(ModuleEventNoticeScope.TEAM);
        assertThat(descriptor.getImpact().getOrganizationId()).isNull();
        assertThat(api.readDescriptor(row.getNoticeRef())).isEmpty();
        assertThat(load(row).getStatus()).isEqualTo("RELAYING");
        assertThat(load(row).getClaimToken()).isEqualTo(claim.getClaimToken());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 999})
    void 未知版は不正enumを解釈せず元の版と試行回数を保って先送りする(int version) {
        ModuleEventNoticeOutboxEntity row = save(draft("PENDING").toBuilder()
                .descriptorVersion(version).scopeType("FUTURE").phase("FUTURE").audience("FUTURE")
                .attemptCount(3).build());
        ModuleEventNoticeClaim claim = claimOne();
        assertThat(claim.getDescriptorVersion()).isEqualTo(version);
        assertThat(claim.getDescriptor()).isEmpty();
        assertThat(claim.getAttemptCount()).isEqualTo(3);
        assertThat(api.deferUnsupportedVersion(claim.getId(), UUID.randomUUID(), FUTURE.plusSeconds(60), FUTURE))
                .isFalse();
        assertThat(load(row).getStatus()).isEqualTo("RELAYING");
        assertThat(api.deferUnsupportedVersion(claim.getId(), claim.getClaimToken(), FUTURE.plusSeconds(60), FUTURE))
                .isTrue();
        ModuleEventNoticeOutboxEntity deferred = load(row);
        assertThat(deferred.getDescriptorVersion()).isEqualTo(version);
        assertThat(deferred.getScopeType()).isEqualTo("FUTURE");
        assertThat(deferred.getAttemptCount()).isEqualTo(3);
        assertThat(deferred.getStatus()).isEqualTo("PENDING");
        assertThat(deferred.getClaimToken()).isNull();
        assertThat(deferred.getNextAttemptAt()).isEqualTo(FUTURE.plusSeconds(60));
    }

    @Test
    void 既知版破損は版を偽装せず後続正常行のclaimも止めない() {
        ModuleEventNoticeOutboxEntity broken = save(draft("PENDING").toBuilder().scopeType("FUTURE").build());
        ModuleEventNoticeOutboxEntity normal = save(draft("PENDING"));
        List<ModuleEventNoticeClaim> claims = api.claim(100, FUTURE);
        assertThat(claims).hasSize(2);
        ModuleEventNoticeClaim bad = claims.stream().filter(c -> c.getId().equals(broken.getId())).findFirst().orElse(null);
        ModuleEventNoticeClaim good = claims.stream().filter(c -> c.getId().equals(normal.getId())).findFirst().orElse(null);
        assertThat(bad).isNotNull();
        assertThat(bad.getDescriptorVersion()).isEqualTo(1);
        assertThat(bad.getDescriptor()).isEmpty();
        assertThat(good).isNotNull();
        assertThat(good.getDescriptor()).isPresent();
    }

    @Test
    void callerrollbackでもinnerclaimはcommitされ元のTXresourceが復帰する() {
        ModuleEventNoticeOutboxEntity row = save(draft("PENDING"));
        rollbackCaller(() -> {
            ModuleEventNoticeClaim claim = claimOne();
            assertThat(claim.getId()).isEqualTo(row.getId());
        });
        assertThat(load(row).getStatus()).isEqualTo("RELAYING");
        assertThat(load(row).getClaimToken()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELAYED", "FAILED", "DEAD", "DEFER", "RECOVER"})
    void callerrollbackでもinner更新はcommitされ元のTXresourceが復帰する(String operation) {
        UUID token = UUID.randomUUID();
        ModuleEventNoticeOutboxEntity row = save(draft("RELAYING").toBuilder().claimToken(token)
                .claimedAt(FUTURE.minusSeconds(120)).descriptorVersion(operation.equals("DEFER") ? 999 : 1).build());
        rollbackCaller(() -> {
            boolean updated = switch (operation) {
                case "RELAYED" -> api.markRelayed(row.getId(), token, FUTURE);
                case "FAILED", "DEAD" -> api.markFailed(row.getId(), token, FUTURE.plusSeconds(30),
                        operation.equals("DEAD"), FUTURE);
                case "DEFER" -> api.deferUnsupportedVersion(row.getId(), token, FUTURE.plusSeconds(60), FUTURE);
                case "RECOVER" -> api.recoverStuck(FUTURE.minusSeconds(60), FUTURE) == 1;
                default -> throw new IllegalArgumentException("試験操作が不正");
            };
            assertThat(updated).isTrue();
        });
        ModuleEventNoticeOutboxEntity updated = load(row);
        String expected = operation.equals("RELAYED") ? "RELAYED" : operation.equals("DEAD") ? "DEAD" : "PENDING";
        assertThat(updated.getStatus()).isEqualTo(expected);
        assertThat(updated.getClaimToken()).isEqualTo(operation.equals("RELAYED") ? token : null);
        if (operation.equals("FAILED") || operation.equals("DEAD")) {
            assertThat(updated.getAttemptCount()).isEqualTo(1);
            assertThat(updated.getLastErrorCode()).isEqualTo("RELAY_FAILED");
            assertThat(updated.getClaimedAt()).isNull();
        }
    }

    @Test
    void 回収後の旧世代は新claimを上書きせず他rowも変更しない() {
        UUID oldToken = UUID.randomUUID();
        ModuleEventNoticeOutboxEntity row = save(draft("RELAYING").toBuilder().claimToken(oldToken)
                .claimedAt(FUTURE.minusSeconds(120)).build());
        ModuleEventNoticeOutboxEntity other = save(draft("DEAD"));
        assertThat(api.recoverStuck(FUTURE.minusSeconds(60), FUTURE)).isEqualTo(1);
        ModuleEventNoticeClaim claim = claimOne();
        assertThat(claim.getClaimToken()).isNotEqualTo(oldToken);
        assertThat(api.markRelayed(row.getId(), oldToken, FUTURE)).isFalse();
        assertThat(api.markFailed(row.getId(), oldToken, FUTURE, true, FUTURE)).isFalse();
        assertThat(load(row).getStatus()).isEqualTo("RELAYING");
        assertThat(load(row).getClaimToken()).isEqualTo(claim.getClaimToken());
        assertThat(load(row).getAttemptCount()).isZero();
        assertThat(load(other).getStatus()).isEqualTo("DEAD");
    }

    @Test
    void claimはWAITINGと期限未来を除外して指定上限だけ返す() {
        ModuleEventNoticeOutboxEntity waiting = save(draft("WAITING_RECOVERY").toBuilder().phase("RECOVERED").build());
        ModuleEventNoticeOutboxEntity future = save(draft("PENDING").toBuilder().nextAttemptAt(FUTURE.plusSeconds(1)).build());
        for (int i = 0; i < 3; i++) save(draft("PENDING"));
        assertThat(api.claim(2, FUTURE)).hasSize(2);
        assertThat(load(waiting).getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(load(future).getStatus()).isEqualTo("PENDING");
        assertThat(api.claim(100, FUTURE)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void claimの範囲外上限はDBを変更せず拒否する(int limit) {
        ModuleEventNoticeOutboxEntity row = save(draft("PENDING"));
        assertThatIllegalArgumentException().isThrownBy(() -> api.claim(limit, FUTURE));
        assertThat(load(row).getStatus()).isEqualTo("PENDING");
        assertThat(load(row).getClaimToken()).isNull();
    }

    @Test
    void 回収は一度に100件まででDEADは回収しない() {
        ModuleEventNoticeOutboxEntity dead = save(draft("DEAD").toBuilder().claimToken(UUID.randomUUID())
                .claimedAt(FUTURE.minusSeconds(120)).build());
        for (int i = 0; i < 101; i++) save(draft("RELAYING").toBuilder().claimToken(UUID.randomUUID())
                .claimedAt(FUTURE.minusSeconds(120)).build());
        assertThat(api.recoverStuck(FUTURE.minusSeconds(60), FUTURE)).isEqualTo(100);
        assertThat(api.recoverStuck(FUTURE.minusSeconds(60), FUTURE)).isEqualTo(1);
        assertThat(load(dead).getStatus()).isEqualTo("DEAD");
    }

    @Test
    void oldestはPENDINGのMINだけを独立TXで読みcaller未commit行を見ない() {
        assertThat(api.oldestPendingCreatedAt()).isEmpty();
        save(draft("PENDING").toBuilder().createdAt(FUTURE.minusSeconds(2)).build());
        save(draft("PENDING").toBuilder().createdAt(FUTURE.minusSeconds(1)).build());
        for (String status : List.of("WAITING_RECOVERY", "RELAYING", "RELAYED", "DEAD")) {
            save(draft(status).toBuilder().createdAt(FUTURE.minusSeconds(10)).build());
        }
        rollbackCaller(() -> {
            new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager)
                    .saveAndFlush(draft("PENDING").toBuilder().createdAt(FUTURE.minusSeconds(20)).build());
            assertThat(api.oldestPendingCreatedAt()).contains(FUTURE.minusSeconds(2));
        });
        assertThat(api.oldestPendingCreatedAt()).contains(FUTURE.minusSeconds(2));
    }

    private void rollbackCaller(Runnable action) {
        assertThatThrownBy(() -> inTx(() -> {
            Object persistenceResource = TransactionSynchronizationManager.getResource(factory);
            Object jdbcResource = TransactionSynchronizationManager.getResource(dataSource);
            assertThat(persistenceResource).isNotNull();
            assertThat(jdbcResource).isNotNull();
            action.run();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(factory)).isSameAs(persistenceResource);
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(jdbcResource);
            throw new CallerRollback();
        })).isInstanceOf(CallerRollback.class);
    }

    private ModuleEventNoticeClaim claimOne() {
        List<ModuleEventNoticeClaim> claims = api.claim(100, FUTURE);
        assertThat(claims).as("公開 relay claim の独立 TX 実装が必要").hasSize(1);
        return claims.getFirst();
    }

    private ModuleEventNoticeOutboxEntity draft(String status) {
        UUID id = UuidV7.generate();
        ownIds.add(id);
        return ModuleEventNoticeOutboxEntity.builder().id(id).noticeKey(UUID.randomUUID())
                .resolverKey("relay-api-unconnected").operationId(UUID.randomUUID()).episodeId(UUID.randomUUID())
                .scopeType("TEAM").scopeId(8101L).organizationId(null).phase("DELAYED").audience("CURRENT_ADMINS")
                .descriptorVersion(1).status(status).attemptCount(0).nextAttemptAt(FUTURE)
                .createdAt(FUTURE).updatedAt(FUTURE).build();
    }

    private ModuleEventNoticeOutboxEntity save(ModuleEventNoticeOutboxEntity row) {
        return inTx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager).saveAndFlush(row));
    }

    private ModuleEventNoticeOutboxEntity load(ModuleEventNoticeOutboxEntity row) {
        return inTx(() -> new SimpleJpaRepository<>(ModuleEventNoticeOutboxEntity.class, entityManager)
                .findById(row.getId()).orElseThrow());
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private static final class CallerRollback extends RuntimeException { }
}
