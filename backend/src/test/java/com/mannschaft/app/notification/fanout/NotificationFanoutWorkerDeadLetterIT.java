package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-030 の受け入れ条件を符号化した red 試練。
 *
 * <p><b>バグ</b>: 旧 {@link NotificationFanoutWorker#processOne} は受信者ソース解決
 * （{@code resolve().orElseThrow()}）とシャード分割（{@code resolveAndSplitShards}）を配信ループの
 * {@code try} の<b>外</b>で呼んでいた。これらが例外を投げると {@code catch}→{@code recordFailure} に届かず、
 * claim 済み（RUNNING）ジョブが RUNNING のまま停滞→{@link NotificationFanoutStuckRecoveryBatch} が
 * PENDING へ戻す→再 claim→同一例外、を延々繰り返す<b>無限 RUNNING ループ</b>（DEAD_LETTER にも配信にも
 * 至らない）を招いた。実機では ORGANIZATION scope の survey ジョブが「未登録の fan-out scope_type: ORGANIZATION」で
 * 反復停滞した。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-A 未登録 scope_type → {@link #acA_unregisteredScopeReachesDeadLetterNotStuckRunning()}</li>
 *   <li>AC-B 分割失敗ジョブ → {@link #acB_splitFailureReachesDeadLetterNotStuckRunning()}</li>
 *   <li>AC-C 正常ジョブは DONE（回帰なし） → {@link #acC_normalJobStillCompletesDone()}</li>
 *   <li>AC-D 失敗は last_error に残る → AC-A/AC-B のアサーションに内包</li>
 * </ul>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で静かに SKIP する。実 RUN（skipped=0）で
 * 判定を確認すること。</p>
 */
@DisplayName("CMP-030 fan-out worker の解決／分割失敗が DEAD_LETTER に落ちる（無限 RUNNING ループ根治）")
@Import(NotificationFanoutWorkerDeadLetterIT.DeadLetterRecipientSourceConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutWorkerDeadLetterIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutWorkerDeadLetterIT.class);

    /** レジストリに登録済みの正常受信者ソース（AC-C）。 */
    static final String NORMAL_SCOPE = "DL_IT_NORMAL";
    /** レジストリに登録済みだが countRecipients が例外を投げる＝分割失敗ソース（AC-B）。 */
    static final String SPLIT_FAIL_SCOPE = "DL_IT_SPLITFAIL";
    /** レジストリに<b>未登録</b>の scope_type（AC-A・実機 ORGANIZATION 反復停滞の再現）。 */
    static final String UNREGISTERED_SCOPE = "DL_IT_UNREGISTERED";

    /** 正常ソースの 1 scope あたりの合成受信者数。 */
    static final int NORMAL_RECIPIENTS = 5;

    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;

    static long recipientBase(long scopeId) {
        return scopeId * 1000L;
    }

    // =====================================================================
    // AC-A 未登録 scope_type: 有限回で DEAD_LETTER・RUNNING 残置なし・last_error 記録
    // =====================================================================
    @Test
    @DisplayName("AC-A 未登録 scope_type のジョブは無限 RUNNING ループにならず有限回で DEAD_LETTER に落ちる")
    void acA_unregisteredScopeReachesDeadLetterNotStuckRunning() {
        NotificationFanoutJob job = saveWithMessages(
                newSplitPendingJob(UNREGISTERED_SCOPE, 30_001L, "DL_IT_A", UUID.randomUUID()));

        AtomicReference<Exception> escaped = new AtomicReference<>();
        NotificationFanoutJob reloaded = driveToTerminal(job.getId(), 10, escaped);

        log.info("[AC-A] escaped={} status={} retryCount={} lastError={}",
                escaped.get(), reloaded.getStatus(), reloaded.getRetryCount(), reloaded.getLastError());

        assertThat(escaped.get())
                .as("AC-A: processOne は例外を貫通させない（貫通＝recordFailure に届かず RUNNING 残置＝無限ループの根）")
                .isNull();
        assertThat(reloaded.getStatus())
                .as("AC-A: 未登録 scope_type は有限回で DEAD_LETTER に落ちる（RUNNING 残置しない）")
                .isEqualTo(NotificationFanoutJobStatus.DEAD_LETTER);
        assertThat(reloaded.getStatus())
                .as("AC-A: RUNNING のまま停滞しない")
                .isNotEqualTo(NotificationFanoutJobStatus.RUNNING);
        // AC-D: 失敗理由が可視化されている。
        assertThat(reloaded.getLastError())
                .as("AC-D: 失敗は握り潰さず last_error に残す")
                .isNotNull()
                .contains(UNREGISTERED_SCOPE);
    }

    // =====================================================================
    // AC-B 分割失敗（resolveAndSplitShards が例外）: 有限回で DEAD_LETTER・RUNNING 残置なし
    // =====================================================================
    @Test
    @DisplayName("AC-B resolveAndSplitShards が例外を投げるジョブも recordFailure 経由で DEAD_LETTER に落ちる")
    void acB_splitFailureReachesDeadLetterNotStuckRunning() {
        NotificationFanoutJob job = saveWithMessages(
                newSplitPendingJob(SPLIT_FAIL_SCOPE, 30_002L, "DL_IT_B", UUID.randomUUID()));

        AtomicReference<Exception> escaped = new AtomicReference<>();
        NotificationFanoutJob reloaded = driveToTerminal(job.getId(), 12, escaped);

        log.info("[AC-B] escaped={} status={} retryCount={} lastError={}",
                escaped.get(), reloaded.getStatus(), reloaded.getRetryCount(), reloaded.getLastError());

        assertThat(escaped.get())
                .as("AC-B: 分割失敗の例外は processOne を貫通せず recordFailure に落ちる（RUNNING 残置＝無限ループの根を断つ）")
                .isNull();
        assertThat(reloaded.getStatus())
                .as("AC-B: 分割失敗はリトライ上限超で DEAD_LETTER に落ちる（RUNNING 残置しない）")
                .isEqualTo(NotificationFanoutJobStatus.DEAD_LETTER);
        assertThat(reloaded.getStatus())
                .as("AC-B: RUNNING のまま停滞しない")
                .isNotEqualTo(NotificationFanoutJobStatus.RUNNING);
        assertThat(reloaded.getRetryCount())
                .as("AC-B: リトライが計上される")
                .isGreaterThan(0);
        assertThat(reloaded.getLastError())
                .as("AC-D: 失敗は握り潰さず last_error に残す")
                .isNotNull();
    }

    // =====================================================================
    // AC-C 正常ジョブは従来どおり DONE（回帰なし）
    // =====================================================================
    @Test
    @DisplayName("AC-C 登録済み scope_type の正常ジョブは従来どおり DONE 化する（回帰なし）")
    void acC_normalJobStillCompletesDone() {
        long scopeId = 30_003L;
        NotificationFanoutJob job = saveWithMessages(
                newSplitPendingJob(NORMAL_SCOPE, scopeId, "DL_IT_C", UUID.randomUUID()));

        worker.processOne(jobRepository.findById(job.getId()).orElseThrow());

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-C] status={} insertedCount={}", reloaded.getStatus(), reloaded.getInsertedCount());

        assertThat(reloaded.getStatus())
                .as("AC-C: 正常ジョブは DONE（回帰なし）")
                .isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(reloaded.getInsertedCount())
                .as("AC-C: 全受信者ぶんの通知が生成される")
                .isEqualTo(NORMAL_RECIPIENTS);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /**
     * ジョブを終端（DONE / DEAD_LETTER）まで駆動する。各周回で next_attempt_at を過去に戻し PENDING に置き直して
     * 再試行可能にする（バックオフで未来に置かれた時刻を進める代替）。processOne が例外を貫通したら {@code escaped}
     * に格納して打ち切る（＝旧バグの RUNNING 残置・無限ループの検出点）。
     */
    private NotificationFanoutJob driveToTerminal(UUID jobId, int maxCycles, AtomicReference<Exception> escaped) {
        for (int i = 0; i < maxCycles; i++) {
            NotificationFanoutJob current = jobRepository.findById(jobId).orElseThrow();
            NotificationFanoutJobStatus st = current.getStatus();
            if (st == NotificationFanoutJobStatus.DONE || st == NotificationFanoutJobStatus.DEAD_LETTER) {
                return current;
            }
            current.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            current.setStatus(NotificationFanoutJobStatus.PENDING);
            jobRepository.saveAndFlush(current);
            try {
                worker.processOne(jobRepository.findById(jobId).orElseThrow());
            } catch (Exception e) {
                escaped.set(e);
                return jobRepository.findById(jobId).orElseThrow();
            }
        }
        return jobRepository.findById(jobId).orElseThrow();
    }

    /** shard_count=0（未評価）の PENDING 親ジョブ。worker 初回処理で resolveAndSplitShards 経路を通す。 */
    private NotificationFanoutJob newSplitPendingJob(String scopeType, long scopeId, String type, UUID sourceEvent) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(sourceEvent)
                .scopeType(scopeType)
                .scopeRef(String.valueOf(scopeId))
                .notificationType(type)
                .priority(NotificationPriority.NORMAL)
                .sourceType("DL_IT")
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .shardIndex((short) 0)
                .shardCount((short) 0) // 0＝未評価（worker が resolveAndSplitShards で確定）
                .nextAttemptAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * テスト用受信者ソース群。
     * <ul>
     *   <li>{@link #NORMAL_SCOPE}: 連続 subject_id を返す正常ソース（countRecipients は既定 -1＝単一シャード）。</li>
     *   <li>{@link #SPLIT_FAIL_SCOPE}: countRecipients が例外を投げ、resolveAndSplitShards を失敗させる。</li>
     *   <li>{@link #UNREGISTERED_SCOPE}: <b>Bean を登録しない</b>（レジストリ未解決を再現）。</li>
     * </ul>
     */
    @TestConfiguration
    static class DeadLetterRecipientSourceConfig {

        @Bean
        FanoutRecipientSource normalSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return NORMAL_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    String scopeRef = request.scopeRef();
                    long cursorSubjectId = request.cursorSubjectId();
                    int limit = request.limit();
                    long base = recipientBase(Long.parseLong(scopeRef));
                    // Issue #2871: 受信者は user_id ＋ locale の組。擬似ソースは既定ロケールで返す。
                    java.util.List<FanoutRecipient> page = new java.util.ArrayList<>();
                    for (int i = 1; i <= NORMAL_RECIPIENTS && page.size() < limit; i++) {
                        long id = base + i;
                        if (id > cursorSubjectId) {
                            page.add(new FanoutRecipient(id, "ja"));
                        }
                    }
                    return page;
                }
            };
        }

        @Bean
        FanoutRecipientSource splitFailSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return SPLIT_FAIL_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    String scopeRef = request.scopeRef();
                    long cursorSubjectId = request.cursorSubjectId();
                    int limit = request.limit();
                    return List.of(); // 到達しない（分割で失敗する）。
                }

                @Override
                public long countRecipients(String scopeRef, boolean includeSupporters) {
                    throw new IllegalStateException("AC-B 分割失敗シミュレーション（scopeRef=" + scopeRef + "）");
                }
            };
        }
    }

    /**
     * Issue #2871: ワーカーはジョブのロケール別文面（子表）を読んでから配信する。
     *
     * <p>本番では enqueue が「親ジョブ 1 行＋文面 6 行」を同一トランザクションで確定するため、
     * 文面の無いジョブは存在しない。IT だけがジョブ行を直接組み立てているので、
     * ここで同じ前提（6 配信ロケールぶんの文面）を用意する。</p>
     */
    private com.mannschaft.app.notification.fanout.NotificationFanoutJob saveWithMessages(
            com.mannschaft.app.notification.fanout.NotificationFanoutJob job) {
        com.mannschaft.app.notification.fanout.NotificationFanoutJob saved = jobRepository.save(job);
        jobRepository.flush();
        java.util.List<com.mannschaft.app.notification.fanout.NotificationFanoutJobMessage> rows =
                new java.util.ArrayList<>();
        for (String tag : com.mannschaft.app.common.i18n.DeliveryLocales.TAGS) {
            rows.add(com.mannschaft.app.notification.fanout.NotificationFanoutJobMessage.builder()
                    .jobId(saved.getId())
                    .locale(tag)
                    .title("IT-title-" + tag)
                    .body("IT-body-" + tag)
                    .build());
        }
        fanoutJobMessageRepositoryForIt.saveAll(rows);
        return saved;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.mannschaft.app.notification.fanout.NotificationFanoutJobMessageRepository
            fanoutJobMessageRepositoryForIt;
}
