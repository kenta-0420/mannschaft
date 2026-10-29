package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * M 通知予約の実 MySQL 契約試練。Store は assertion red 待ちの未実装骨格。
 * fixture は Entity persist のみ。scheduler の実現在時刻から離れた将来時刻を使い、
 * cleanup はこの試験が作成した noticeKey のみに限定する。
 * 業務 SPI 呼出しや core 成功 hook の原子性は後続の実結合試練で検証し、この試練だけで合格にしない。
 * 未完の結合 AC: core 所有の attempt に nullable scalar binding
 * (resolverKey / operationId / episodeId / scopeType / scopeId / organizationId) を
 * pure identify と同じ publisher TX で保存し、episode の pending 存在判定索引を接続する。
 * 後発 attempt が存在する場合、CLOSED_COMPLETE でも昇格 CAS が拒否される実 DB 負例が必須。
 * 現在の空 episode 試練はその負例・binding 保存・本番 pending SQL の合格証拠にはしない。
 * payload の全件 deserialize や JSON 検索で pending を照合しない。
 * schema 自動生成と Flyway の両経路の確認を統合実行時に分けて記録する。
 */
@ModuleEventMySqlTest
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("M 通知予約の alias・冪等・有界 claim・世代 CAS")
class ModuleEventNoticeOutboxIT extends AbstractMySqlIntegrationTest {
    private static final AtomicLong CLOCK_SEQUENCE = new AtomicLong();
    private final Instant now = Instant.parse("2100-01-01T00:00:00Z")
            .plusSeconds(CLOCK_SEQUENCE.incrementAndGet() * 3600);
    private final List<UUID> noticeKeys = new ArrayList<>();

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void 自隊の予約だけ削除する() {
        if (!noticeKeys.isEmpty()) {
            inTx(() -> entityManager.createQuery(
                    "delete from ModuleEventNoticeOutboxEntity n where n.noticeKey in :keys")
                    .setParameter("keys", noticeKeys).executeUpdate());
        }
    }

    @Test
    void UUIDv7主キーと正数Longaliasを独立採番して再読できる() {
        ModuleEventNoticeOutboxEntity first = persist(reservation());
        ModuleEventNoticeOutboxEntity second = persist(reservation());
        assertThat(first.getId().version()).isEqualTo(7);
        assertThat(first.getNoticeRef()).isPositive();
        assertThat(second.getNoticeRef()).isPositive().isNotEqualTo(first.getNoticeRef());
        assertThat(read(first.getId()).getNoticeRef()).isEqualTo(first.getNoticeRef());
    }

    @Test
    void 同じnoticeKeyの予約は同一IDとaliasに収束する() {
        ModuleEventNoticeOutboxEntity draft = reservation();
        ModuleEventNoticeOutboxEntity first = inTx(() -> store().reserve(draft)).orElse(null);
        assertThat(first).as("reserve の実 DB 実装が必要").isNotNull();
        ModuleEventNoticeOutboxEntity repeated = inTx(() -> store().reserve(
                draft.toBuilder().id(UuidV7.generate()).build())).orElse(null);
        assertThat(repeated).isNotNull();
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(repeated.getNoticeRef()).isEqualTo(first.getNoticeRef());
        assertThat(inTx(() -> entityManager.createQuery(
                "select count(n) from ModuleEventNoticeOutboxEntity n where n.noticeKey = :key", Long.class)
                .setParameter("key", draft.getNoticeKey()).getSingleResult())).isEqualTo(1L);
    }

    @Test
    void 別TXの同時予約が一行へ収束し競合後もdescriptor不一致を拒否する() throws Exception {
        ModuleEventNoticeOutboxEntity firstDraft = reservation();
        ModuleEventNoticeOutboxEntity secondDraft = firstDraft.toBuilder().id(UuidV7.generate()).build();
        CyclicBarrier barrier = new CyclicBarrier(2);
        // 共通 EntityManager は Spring の thread-bound proxy。各 worker が別の実 TX を開始する。
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<ModuleEventNoticeOutboxEntity> first = workers.submit(() -> inTx(() -> {
                awaitBothTransactions(barrier);
                return store().reserve(firstDraft).orElse(null);
            }));
            Future<ModuleEventNoticeOutboxEntity> second = workers.submit(() -> inTx(() -> {
                awaitBothTransactions(barrier);
                return store().reserve(secondDraft).orElse(null);
            }));
            // Future は TX commit 後に返る。片方の unique 例外や rollback-only も失敗として伝播する。
            ModuleEventNoticeOutboxEntity firstResult = first.get(30, TimeUnit.SECONDS);
            ModuleEventNoticeOutboxEntity secondResult = second.get(30, TimeUnit.SECONDS);
            assertThat(firstResult).isNotNull();
            assertThat(secondResult).isNotNull();
            assertThat(firstResult.getId()).isEqualTo(secondResult.getId());
            assertThat(firstResult.getNoticeRef()).isPositive().isEqualTo(secondResult.getNoticeRef());
            assertThat(countOwn()).isEqualTo(1L);
            assertThat(read(firstResult.getId()).getEpisodeId()).isEqualTo(firstDraft.getEpisodeId());
        }
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().reserve(
                firstDraft.toBuilder().id(UuidV7.generate()).episodeId(UUID.randomUUID()).build())));
        assertThat(countOwn()).isEqualTo(1L);
    }

    private static void awaitBothTransactions(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同時予約試練が中断された", interrupted);
        } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException("同時予約の両 TX が barrier に到達しなかった", failed);
        }
    }

    @Test
    void 同じnoticeKeyで異なるepisodeの再予約を拒否する() {
        ModuleEventNoticeOutboxEntity draft = reservation();
        assertThat(inTx(() -> store().reserve(draft))).isPresent();
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().reserve(
                draft.toBuilder().id(UuidV7.generate()).episodeId(UUID.randomUUID()).build())));
    }

    @Test
    void claimの上限はSQL側で100件となり未取得行を残す() {
        for (int i = 0; i < 102; i++) {
            persist(reservation());
        }
        List<ModuleEventNoticeOutboxEntity> claimed = inTx(() -> store().claim(100, now));
        assertThat(claimed).hasSize(100);
        assertThat(claimed).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo("RELAYING");
            assertThat(row.getClaimToken()).isNotNull();
        });
        assertThat(inTx(() -> store().claim(100, now))).hasSize(2);
    }

    @Test
    void claimの0件と101件の要求を拒否する() {
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().claim(0, now)));
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().claim(101, now)));
    }

    @Test
    void 未来のretry時刻をclaimしない() {
        ModuleEventNoticeOutboxEntity due = persist(reservation());
        ModuleEventNoticeOutboxEntity future = persist(reservation().toBuilder()
                .nextAttemptAt(now.plusSeconds(60)).build());
        assertThat(inTx(() -> store().claim(100, now)))
                .extracting(ModuleEventNoticeOutboxEntity::getId).containsExactly(due.getId());
        assertThat(read(future.getId()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void lease回収後の旧世代hookは新claimを上書きしない() {
        ModuleEventNoticeOutboxEntity row = persist(reservation());
        List<ModuleEventNoticeOutboxEntity> first = inTx(() -> store().claim(1, now));
        assertThat(first).hasSize(1);
        UUID oldToken = first.getFirst().getClaimToken();
        assertThat(inTx(() -> store().recoverStuck(now.plusSeconds(1), now.plusSeconds(2)))).isEqualTo(1);
        List<ModuleEventNoticeOutboxEntity> second = inTx(() -> store().claim(1, now.plusSeconds(2)));
        assertThat(second).hasSize(1);
        UUID newToken = second.getFirst().getClaimToken();
        assertThat(newToken).isNotNull().isNotEqualTo(oldToken);
        assertThat(inTx(() -> store().markRelayed(row.getId(), oldToken, now.plusSeconds(3)))).isFalse();
        assertThat(read(row.getId()).getClaimToken()).isEqualTo(newToken);
        assertThat(inTx(() -> store().markRelayed(row.getId(), newToken, now.plusSeconds(4)))).isTrue();
        assertThat(read(row.getId()).getStatus()).isEqualTo("RELAYED");
        assertThat(inTx(() -> store().markRelayed(row.getId(), newToken, now.plusSeconds(5)))).isFalse();
    }

    @Test
    void 未知版をenum変換せず繰り延べて後続の既知版へ進む() {
        ModuleEventNoticeOutboxEntity unknown = persist(reservation().toBuilder()
                .descriptorVersion(999).scopeType("FUTURE").phase("FUTURE")
                .nextAttemptAt(now.minusSeconds(1)).build());
        ModuleEventNoticeOutboxEntity healthy = persist(reservation());
        List<ModuleEventNoticeOutboxEntity> first = inTx(() -> store().claim(1, now));
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().getId()).isEqualTo(unknown.getId());
        assertThat(first.getFirst().getDescriptorVersion()).isEqualTo(999);
        assertThat(inTx(() -> store().deferUnsupportedVersion(unknown.getId(),
                first.getFirst().getClaimToken(), now.plusSeconds(60), now))).isTrue();
        assertThat(inTx(() -> store().claim(1, now)))
                .extracting(ModuleEventNoticeOutboxEntity::getId).containsExactly(healthy.getId());
    }

    @Test
    void 影響通知のない通常高速成功では復旧intentも通知も作らない() {
        ModuleEventNoticeOutboxEntity recovery = reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build();
        assertThat(inTx(() -> store().reserveRecoveryIntent(recovery))).isEmpty();
        assertThat(countOwn()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED"})
    void 既存の影響通知がある場合だけ復旧intentを冪等予約する(String phase) {
        ModuleEventNoticeOutboxEntity incident = persist(reservation().toBuilder().phase(phase).build());
        ModuleEventNoticeOutboxEntity draft = recoveryFor(incident);
        ModuleEventNoticeOutboxEntity first = inTx(() -> store().reserveRecoveryIntent(draft)).orElse(null);
        assertThat(first).isNotNull();
        assertThat(first.getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(first.getPhase()).isEqualTo("RECOVERED");
        ModuleEventNoticeOutboxEntity repeated = inTx(() -> store().reserveRecoveryIntent(
                draft.toBuilder().id(UuidV7.generate()).build())).orElse(null);
        assertThat(repeated).isNotNull();
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(countOwn()).isEqualTo(2L);
    }

    @Test
    void 再予約は既存statusとleaseとretry時刻をリセットしない() {
        UUID token = UUID.randomUUID();
        Instant next = now.plusSeconds(300);
        ModuleEventNoticeOutboxEntity existing = persist(reservation().toBuilder()
                .status("RELAYING").claimToken(token).claimedAt(now)
                .nextAttemptAt(next).attemptCount(3).build());
        ModuleEventNoticeOutboxEntity result = inTx(() -> store().reserve(existing.toBuilder()
                .id(UuidV7.generate()).status("PENDING").claimToken(null).claimedAt(null)
                .nextAttemptAt(now).attemptCount(0).build())).orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getStatus()).isEqualTo("RELAYING");
        assertThat(result.getClaimToken()).isEqualTo(token);
        assertThat(result.getNextAttemptAt()).isEqualTo(next);
        assertThat(result.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void WAITINGはrelayから除外し評価claimだけが取得する() {
        ModuleEventNoticeOutboxEntity waiting = persist(reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build());
        ModuleEventNoticeOutboxEntity pending = persist(reservation());
        assertThat(inTx(() -> store().claim(100, now)))
                .extracting(ModuleEventNoticeOutboxEntity::getId).containsExactly(pending.getId());
        List<ModuleEventNoticeOutboxEntity> evaluation = inTx(() -> store().claimRecovery(100, now));
        assertThat(evaluation).hasSize(1);
        assertThat(evaluation.getFirst().getId()).isEqualTo(waiting.getId());
        assertThat(evaluation.getFirst().getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(evaluation.getFirst().getClaimToken()).isNotNull();
        assertThat(inTx(() -> store().claimRecovery(100, now))).isEmpty();
    }

    @Test
    void 復旧評価claimも100件に制限し不正limitを拒否する() {
        for (int i = 0; i < 102; i++) {
            persist(reservation().toBuilder().phase("RECOVERED").status("WAITING_RECOVERY").build());
        }
        assertThat(inTx(() -> store().claimRecovery(100, now))).hasSize(100);
        assertThat(inTx(() -> store().claimRecovery(100, now))).hasSize(2);
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().claimRecovery(0, now)));
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().claimRecovery(101, now)));
    }

    @ParameterizedTest
    @EnumSource(value = ModuleEventRecoveryState.class, names = {"OPEN", "UNKNOWN"})
    void 未確定の業務評価は復旧扱いせず次回まで繰り延べる(ModuleEventRecoveryState recovery) {
        ModuleEventNoticeOutboxEntity waiting = persist(reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build());
        List<ModuleEventNoticeOutboxEntity> claimed = inTx(() -> store().claimRecovery(1, now));
        assertThat(claimed).hasSize(1);
        assertThat(inTx(() -> store().applyRecoveryEvaluation(waiting.getId(),
                claimed.getFirst().getClaimToken(), recovery, now.plusSeconds(60), now))).isTrue();
        ModuleEventNoticeOutboxEntity deferred = read(waiting.getId());
        assertThat(deferred.getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(deferred.getNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(deferred.getAttemptCount()).isZero();
        assertThat(inTx(() -> store().claimRecovery(1, now))).isEmpty();
        assertThat(inTx(() -> store().claim(1, now))).isEmpty();
    }

    @Test
    void 失効した復旧評価tokenは新世代を昇格させない() {
        ModuleEventNoticeOutboxEntity waiting = persist(reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build());
        List<ModuleEventNoticeOutboxEntity> first = inTx(() -> store().claimRecovery(1, now));
        assertThat(first).hasSize(1);
        UUID oldToken = first.getFirst().getClaimToken();
        assertThat(inTx(() -> store().recoverStuck(now.plusSeconds(1), now.plusSeconds(2)))).isEqualTo(1);
        List<ModuleEventNoticeOutboxEntity> second = inTx(() -> store().claimRecovery(1, now.plusSeconds(2)));
        assertThat(second).hasSize(1);
        UUID newToken = second.getFirst().getClaimToken();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(inTx(() -> store().applyRecoveryEvaluation(waiting.getId(), oldToken,
                ModuleEventRecoveryState.CLOSED_COMPLETE, now.plusSeconds(60), now.plusSeconds(3)))).isFalse();
        assertThat(read(waiting.getId()).getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(read(waiting.getId()).getClaimToken()).isEqualTo(newToken);
    }

    @Test
    void CLOSED_COMPLETEの現世代だけPENDINGへ昇格してrelayへ渡す() {
        // この episode に publication はなく M pending=0。非0との競合は core binding 接続後の試練。
        ModuleEventNoticeOutboxEntity waiting = persist(reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build());
        List<ModuleEventNoticeOutboxEntity> claimed = inTx(() -> store().claimRecovery(1, now));
        assertThat(claimed).hasSize(1);
        UUID evaluationToken = claimed.getFirst().getClaimToken();
        assertThat(inTx(() -> store().applyRecoveryEvaluation(waiting.getId(), evaluationToken,
                ModuleEventRecoveryState.CLOSED_COMPLETE, now.plusSeconds(60), now))).isTrue();
        assertThat(read(waiting.getId()).getStatus()).isEqualTo("PENDING");
        List<ModuleEventNoticeOutboxEntity> relaying = inTx(() -> store().claim(1, now));
        assertThat(relaying).hasSize(1);
        UUID relayToken = relaying.getFirst().getClaimToken();
        assertThat(relayToken).isNotNull().isNotEqualTo(evaluationToken);
        assertThat(inTx(() -> store().markRelayed(waiting.getId(), relayToken, now.plusSeconds(1)))).isTrue();
        assertThat(read(waiting.getId()).getClaimToken()).as("retention 世代 CAS のため保持").isEqualTo(relayToken);
    }

    @Test
    void 復旧intent予約TXがrollbackしたら予約も残らない() {
        ModuleEventNoticeOutboxEntity incident = persist(reservation());
        ModuleEventNoticeOutboxEntity recovery = recoveryFor(incident);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(store().reserveRecoveryIntent(recovery)).isPresent();
            status.setRollbackOnly();
        });
        assertThat(countOwn()).isEqualTo(1L);
    }

    @Test
    void relay失敗は現世代だけattemptを増やしPENDINGへ戻して他行を変えない() {
        ModuleEventNoticeOutboxEntity row = persist(reservation().toBuilder().attemptCount(2).build());
        ModuleEventNoticeOutboxEntity other = persist(reservation().toBuilder()
                .attemptCount(7).nextAttemptAt(now.plusSeconds(120)).build());
        ModuleEventNoticeOutboxEntity claimed = inTx(() -> store().claim(1, now)).getFirst();
        assertThat(claimed.getId()).isEqualTo(row.getId());
        UUID token = claimed.getClaimToken();
        Instant failedAt = now.plusSeconds(1);
        Instant next = now.plusSeconds(60);
        assertThat(inTx(() -> store().markFailed(row.getId(), token, "RELAY_FAILED", next, false, failedAt)))
                .as("markFailed の実 DB 実装が必要").isTrue();
        ModuleEventNoticeOutboxEntity failed = read(row.getId());
        assertThat(failed.getStatus()).isEqualTo("PENDING");
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getNextAttemptAt()).isEqualTo(next);
        assertThat(failed.getClaimToken()).isNull();
        assertThat(failed.getClaimedAt()).isNull();
        assertThat(failed.getDeadAt()).isNull();
        assertThat(failed.getLastErrorCode()).isEqualTo("RELAY_FAILED");
        assertThat(failed.getUpdatedAt()).isEqualTo(failedAt);
        assertThat(inTx(() -> store().markFailed(row.getId(), token, "RELAY_FAILED", next, false, failedAt)))
                .as("同世代 callback の再送で attempt を二重加算しない").isFalse();
        assertThat(read(row.getId()).getAttemptCount()).isEqualTo(3);
        ModuleEventNoticeOutboxEntity untouched = read(other.getId());
        assertThat(untouched.getStatus()).isEqualTo("PENDING");
        assertThat(untouched.getAttemptCount()).isEqualTo(7);
        assertThat(untouched.getNextAttemptAt()).isEqualTo(now.plusSeconds(120));
        assertThat(untouched.getClaimToken()).isNull();
        assertThat(untouched.getLastErrorCode()).isNull();
        assertThat(untouched.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void 最終relay失敗はDEADへ移りclaimもlease回収も再開しない() {
        ModuleEventNoticeOutboxEntity row = persist(reservation().toBuilder().attemptCount(9).build());
        UUID token = inTx(() -> store().claim(1, now)).getFirst().getClaimToken();
        Instant failedAt = now.plusSeconds(1);
        assertThat(inTx(() -> store().markFailed(row.getId(), token, "RELAY_FAILED",
                now.plusSeconds(60), true, failedAt))).isTrue();
        ModuleEventNoticeOutboxEntity dead = read(row.getId());
        assertThat(dead.getStatus()).isEqualTo("DEAD");
        assertThat(dead.getAttemptCount()).isEqualTo(10);
        assertThat(dead.getDeadAt()).isEqualTo(failedAt);
        assertThat(dead.getClaimToken()).isNull();
        assertThat(dead.getClaimedAt()).isNull();
        assertThat(dead.getLastErrorCode()).isEqualTo("RELAY_FAILED");
        assertThat(inTx(() -> store().recoverStuck(now.plusSeconds(60), now.plusSeconds(61)))).isZero();
        assertThat(inTx(() -> store().claim(100, now.plusSeconds(120)))).isEmpty();
        assertThat(inTx(() -> store().markFailed(row.getId(), token, "RELAY_FAILED",
                now.plusSeconds(120), false, now.plusSeconds(62)))).isFalse();
        assertThat(read(row.getId()).getAttemptCount()).isEqualTo(10);
        assertThat(read(row.getId()).getStatus()).isEqualTo("DEAD");
    }

    @Test
    void 旧世代の失敗は新claimのtokenやattemptを変更しない() {
        ModuleEventNoticeOutboxEntity row = persist(reservation());
        UUID oldToken = inTx(() -> store().claim(1, now)).getFirst().getClaimToken();
        assertThat(inTx(() -> store().recoverStuck(now.plusSeconds(1), now.plusSeconds(2)))).isEqualTo(1);
        UUID token = inTx(() -> store().claim(1, now.plusSeconds(2))).getFirst().getClaimToken();
        assertThat(token).isNotEqualTo(oldToken);
        assertThat(inTx(() -> store().markFailed(row.getId(), oldToken, "RELAY_FAILED",
                now.plusSeconds(60), true, now.plusSeconds(3)))).isFalse();
        ModuleEventNoticeOutboxEntity unchanged = read(row.getId());
        assertThat(unchanged.getStatus()).isEqualTo("RELAYING");
        assertThat(unchanged.getClaimToken()).isEqualTo(token);
        assertThat(unchanged.getAttemptCount()).isZero();
        assertThat(unchanged.getLastErrorCode()).isNull();
        assertThat(unchanged.getDeadAt()).isNull();
    }

    @Test
    void PENDINGとWAITING評価中とRELAYEDにはrelay失敗を適用しない() {
        ModuleEventNoticeOutboxEntity pending = persist(reservation());
        assertThat(inTx(() -> store().markFailed(pending.getId(), UUID.randomUUID(), "RELAY_FAILED",
                now.plusSeconds(60), true, now))).isFalse();
        assertThat(read(pending.getId()).getAttemptCount()).isZero();
        UUID relayToken = inTx(() -> store().claim(1, now)).getFirst().getClaimToken();
        assertThat(inTx(() -> store().markRelayed(pending.getId(), relayToken, now))).isTrue();
        assertThat(inTx(() -> store().markFailed(pending.getId(), relayToken, "RELAY_FAILED",
                now.plusSeconds(60), true, now))).isFalse();
        assertThat(read(pending.getId()).getStatus()).isEqualTo("RELAYED");
        ModuleEventNoticeOutboxEntity waiting = persist(reservation().toBuilder()
                .phase("RECOVERED").status("WAITING_RECOVERY").build());
        UUID evaluationToken = inTx(() -> store().claimRecovery(1, now)).getFirst().getClaimToken();
        assertThat(inTx(() -> store().markFailed(waiting.getId(), evaluationToken, "RELAY_FAILED",
                now.plusSeconds(60), true, now))).isFalse();
        ModuleEventNoticeOutboxEntity unchanged = read(waiting.getId());
        assertThat(unchanged.getStatus()).isEqualTo("WAITING_RECOVERY");
        assertThat(unchanged.getClaimToken()).isEqualTo(evaluationToken);
        assertThat(unchanged.getAttemptCount()).isZero();
        assertThat(unchanged.getLastErrorCode()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNKNOWN_CODE", "RELAY_FAILED exception: user@example.invalid",
            "RELAY_FAILED\noriginal payload", "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"})
    void 固定code以外は例外で拒否しraw本文や未知codeを保存しない(String invalidCode) {
        ModuleEventNoticeOutboxEntity row = persist(reservation());
        UUID token = inTx(() -> store().claim(1, now)).getFirst().getClaimToken();
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> store().markFailed(
                row.getId(), token, invalidCode, now.plusSeconds(60), true, now.plusSeconds(1))));
        ModuleEventNoticeOutboxEntity unchanged = read(row.getId());
        assertThat(unchanged.getStatus()).isEqualTo("RELAYING");
        assertThat(unchanged.getClaimToken()).isEqualTo(token);
        assertThat(unchanged.getAttemptCount()).isZero();
        assertThat(unchanged.getLastErrorCode()).isNull();
        assertThat(unchanged.getDeadAt()).isNull();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void PENDINGが空なら他状態の古い予約があっても最古時刻はempty() {
        persist(reservation().toBuilder().status("WAITING_RECOVERY").phase("RECOVERED").build());
        persist(reservation().toBuilder().status("RELAYING").claimToken(UUID.randomUUID()).claimedAt(now).build());
        persist(reservation().toBuilder().status("RELAYED").relayedAt(now).build());
        persist(reservation().toBuilder().status("DEAD").deadAt(now).build());
        assertThat(inTx(() -> store().oldestPendingCreatedAt())).isEmpty();
    }

    @Test
    void 最古時刻はPENDINGのMINcreatedAtだけを返す() {
        Instant oldest = now.minusSeconds(120);
        persist(reservation().toBuilder().createdAt(oldest).build());
        persist(reservation().toBuilder().createdAt(now.minusSeconds(60)).build());
        persist(reservation().toBuilder().status("WAITING_RECOVERY").phase("RECOVERED")
                .createdAt(now.minusSeconds(500)).build());
        persist(reservation().toBuilder().status("RELAYING").claimToken(UUID.randomUUID()).claimedAt(now)
                .createdAt(now.minusSeconds(400)).build());
        persist(reservation().toBuilder().status("RELAYED").relayedAt(now)
                .createdAt(now.minusSeconds(300)).build());
        persist(reservation().toBuilder().status("DEAD").deadAt(now)
                .createdAt(now.minusSeconds(200)).build());
        assertThat(inTx(() -> store().oldestPendingCreatedAt()))
                .as("SQL MIN の実 DB 実装が必要。retry 時刻の早さとは区別する").contains(oldest);
    }

    private ModuleEventNoticeOutboxEntity recoveryFor(ModuleEventNoticeOutboxEntity incident) {
        UUID key = UUID.randomUUID();
        noticeKeys.add(key);
        return incident.toBuilder().id(UuidV7.generate()).noticeRef(null).noticeKey(key)
                .phase("RECOVERED").status("WAITING_RECOVERY").claimToken(null).claimedAt(null).build();
    }

    private long countOwn() {
        return inTx(() -> entityManager.createQuery(
                "select count(n) from ModuleEventNoticeOutboxEntity n where n.noticeKey in :keys", Long.class)
                .setParameter("keys", noticeKeys).getSingleResult());
    }

    private ModuleEventNoticeOutboxEntity reservation() {
        UUID noticeKey = UUID.randomUUID();
        noticeKeys.add(noticeKey);
        return ModuleEventNoticeOutboxEntity.builder().id(UuidV7.generate()).noticeKey(noticeKey)
                .resolverKey("notice-it").operationId(UUID.randomUUID()).episodeId(UUID.randomUUID())
                .scopeType("TEAM").scopeId(1L).organizationId(null).phase("DELAYED")
                .audience("CURRENT_ADMINS").descriptorVersion(1).status("PENDING").attemptCount(0)
                .nextAttemptAt(now).createdAt(now).updatedAt(now).build();
    }

    private ModuleEventNoticeOutboxEntity persist(ModuleEventNoticeOutboxEntity row) {
        return inTx(() -> {
            entityManager.persist(row);
            entityManager.flush();
            entityManager.clear();
            return row;
        });
    }

    private ModuleEventNoticeOutboxEntity read(UUID id) {
        return inTx(() -> {
            entityManager.clear();
            return entityManager.find(ModuleEventNoticeOutboxEntity.class, id);
        });
    }

    private ModuleEventNoticeOutboxStore store() {
        return new ModuleEventNoticeOutboxStore(entityManager);
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }
}
