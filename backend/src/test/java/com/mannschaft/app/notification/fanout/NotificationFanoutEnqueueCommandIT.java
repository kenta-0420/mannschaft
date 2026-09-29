package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 6-D 試練: 通知の基盤（{@code enqueueInCurrentTransaction(FanoutEnqueueCommand)}）。
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-E11 同じ冪等キーで2回 enqueue してもジョブは1件・業務はコミット
 *       → {@link #e11_同一トランザクションで2回enqueueしてもジョブは1件で業務はコミットされる()}、
 *         {@link #e11_別トランザクションから同じキーでenqueueしても例外なくジョブは1件で両方の業務がコミットされる()}</li>
 *   <li>AC-E12（回帰）既存9引数版の重複は例外が伝播しロールバック
 *       → {@link #e12_既存9引数版の重複enqueueは例外が伝播し呼び出し元がロールバックされる()}（現状で緑）</li>
 *   <li>AC-B18（基盤）enqueue 後の配信失敗で業務はロールバックされない
 *       → {@link #b18_Workerの配信が失敗しても業務行は残りジョブは失敗として記録される()}</li>
 *   <li>§6.7 新版の文面・action_url・シャードの扱い
 *       → {@link #新版_FIXED_SINGLEは文面6行とaction_urlを保存しshard_countは1()}、
 *         {@link #新版_AUTOは未評価で登録され分割時に子シャードへaction_urlと文面が複製される()}</li>
 *   <li>§6.7・§14.2 加盟の通知種別9つの {@link FanoutMessageKind}
 *       → {@link #加盟の通知種別9つの文面種別がある()}</li>
 * </ul>
 *
 * <p>業務行の代わりに {@code organizations} 行を呼び出し元トランザクションで INSERT し、コミットの有無を
 * SELECT で確かめる。配信の失敗は、{@code nextPage} で例外を投げるテスト用受信者ソースで起こす。</p>
 */
@DisplayName("F01.2.1 6-D 通知の基盤 試練（AC-E11・E12・B18・新版の文面）")
@Import(NotificationFanoutEnqueueCommandIT.TestSourcesConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutEnqueueCommandIT extends AbstractMySqlIntegrationTest {

    /** Worker の配信時に必ず例外を投げる受信者ソース（AC-B18）。 */
    static final String FAIL_SCOPE = "F0121_IT_FAIL";
    /** 受信者数 25,000 を返し、AUTO で2シャードに分割させる受信者ソース。 */
    static final String AUTO_SCOPE = "F0121_IT_AUTO";
    /** 何も返さない受信者ソース（文面・冪等の検証用）。 */
    static final String NOOP_SCOPE = "F0121_IT_NOOP";

    static final String FAIL_MARKER = "F0121-B18-配信失敗シミュレーション";

    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutJobMessageRepository jobMessageRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    // =====================================================================
    // AC-E11 冪等 enqueue（新版）
    // =====================================================================

    @Test
    @DisplayName("AC-E11: 同一トランザクションで同じ冪等キーを2回 enqueue しても、ジョブは1件で業務行はコミットされる")
    void e11_同一トランザクションで2回enqueueしてもジョブは1件で業務はコミットされる() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String businessName = "F0121-E11-same-" + UUID.randomUUID();

        UUID[] returned = new UUID[2];
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            insertBusinessRow(businessName);
            returned[0] = jobService.enqueueInCurrentTransaction(command(NOOP_SCOPE, scopeRef, key,
                    FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).getId();
            returned[1] = jobService.enqueueInCurrentTransaction(command(NOOP_SCOPE, scopeRef, key,
                    FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).getId();
        })).as("AC-E11: 2回目の enqueue は例外を投げず no-op で確定する").doesNotThrowAnyException();

        assertThat(countJobs(NOOP_SCOPE, scopeRef, "F0121_IT_E11", key)).as("AC-E11: ジョブ行は1件のまま").isEqualTo(1L);
        assertThat(returned[1]).as("AC-E11: 2回目は既存のジョブ行を再読込して返す").isEqualTo(returned[0]);
        assertThat(countMessages(returned[0])).as("AC-E11: 文面行も重複しない（6配信ロケールぶん）")
                .isEqualTo((long) DeliveryLocales.TAGS.size());
        assertThat(countBusinessRows(businessName)).as("AC-E11: 呼び出し元の業務はロールバックされずコミットされる")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-E11: 別トランザクションから同じ冪等キーで enqueue しても例外なく、ジョブは1件で両方の業務がコミットされる")
    void e11_別トランザクションから同じキーでenqueueしても例外なくジョブは1件で両方の業務がコミットされる() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String first = "F0121-E11-tx1-" + UUID.randomUUID();
        String second = "F0121-E11-tx2-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            insertBusinessRow(first);
            jobService.enqueueInCurrentTransaction(command(NOOP_SCOPE, scopeRef, key,
                    FanoutEnqueueCommand.ShardMode.FIXED_SINGLE));
        });
        assertThatCode(() -> tx.executeWithoutResult(status -> {
            insertBusinessRow(second);
            jobService.enqueueInCurrentTransaction(command(NOOP_SCOPE, scopeRef, key,
                    FanoutEnqueueCommand.ShardMode.FIXED_SINGLE));
        })).as("AC-E11: 別トランザクションからの2回目も例外を投げない（rollback-only にもならない）")
                .doesNotThrowAnyException();

        assertThat(countJobs(NOOP_SCOPE, scopeRef, "F0121_IT_E11", key)).as("AC-E11: ジョブ行は1件のまま").isEqualTo(1L);
        assertThat(countBusinessRows(first)).as("AC-E11: 1回目の業務はコミット").isEqualTo(1L);
        assertThat(countBusinessRows(second)).as("AC-E11: 2回目の業務もロールバックされずコミット").isEqualTo(1L);
    }

    @Test
    @DisplayName("新版はトランザクション外から呼ぶと例外になる（MANDATORY）")
    void 新版はトランザクション外から呼べない() {
        assertThatThrownBy(() -> jobService.enqueueInCurrentTransaction(command(NOOP_SCOPE, randomRef(),
                UUID.randomUUID(), FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // =====================================================================
    // AC-E12 回帰防止（既存9引数版）
    // =====================================================================

    @Test
    @DisplayName("AC-E12: 既存9引数版で同じ冪等キーを2回目に呼ぶと DataIntegrityViolationException が伝播し、呼び出し元はロールバックされる")
    void e12_既存9引数版の重複enqueueは例外が伝播し呼び出し元がロールバックされる() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String first = "F0121-E12-tx1-" + UUID.randomUUID();
        String second = "F0121-E12-tx2-" + UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            insertBusinessRow(first);
            jobService.enqueueInCurrentTransaction(NOOP_SCOPE, scopeRef, "F0121_IT_E12", key, null,
                    NotificationPriority.NORMAL, null, "F0121_IT", 1L);
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertBusinessRow(second);
            jobService.enqueueInCurrentTransaction(NOOP_SCOPE, scopeRef, "F0121_IT_E12", key, null,
                    NotificationPriority.NORMAL, null, "F0121_IT", 1L);
        })).as("AC-E12: 既存版の重複は uk_fanout_idempotency 違反が呼び出し元へ伝播する")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countJobs(NOOP_SCOPE, scopeRef, "F0121_IT_E12", key)).as("AC-E12: ジョブ行は1回目の1件だけ").isEqualTo(1L);
        assertThat(countBusinessRows(first)).as("AC-E12: 1回目の業務はコミット済み").isEqualTo(1L);
        assertThat(countBusinessRows(second)).as("AC-E12: 2回目の呼び出し元はロールバックされる").isZero();
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(NOOP_SCOPE, scopeRef, "F0121_IT_E12", key)
                .orElseThrow();
        assertThat(job.getShardCount()).as("AC-E12: 既存版は shard_count=1 固定のまま").isEqualTo((short) 1);
        assertThat(job.getActionUrl()).as("AC-E12: 既存版は action_url を持たないまま").isNull();
        assertThat(countMessages(job.getId())).as("AC-E12: 既存版は文面行を作らないまま").isZero();
    }

    // =====================================================================
    // AC-B18 基盤: 配信の失敗で業務をロールバックしない
    // =====================================================================

    @Test
    @DisplayName("AC-B18: enqueue した業務はコミットされ、その後 Worker の配信が失敗しても業務行は残る")
    void b18_Workerの配信が失敗しても業務行は残りジョブは失敗として記録される() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String businessName = "F0121-B18-" + UUID.randomUUID();

        UUID jobId = tx.execute(status -> {
            insertBusinessRow(businessName);
            return jobService.enqueueInCurrentTransaction(command(FAIL_SCOPE, scopeRef, key,
                    FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).getId();
        });
        assertThat(countBusinessRows(businessName)).as("AC-B18: enqueue した業務はコミットされる").isEqualTo(1L);

        assertThatCode(() -> worker.processOne(jobRepository.findById(jobId).orElseThrow()))
                .as("AC-B18: 配信の失敗は Worker の中で記録され、例外として外へ出ない")
                .doesNotThrowAnyException();

        NotificationFanoutJob reloaded = jobRepository.findById(jobId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("AC-B18: 配信は失敗として記録される（DONE にはならない）")
                .isIn(NotificationFanoutJobStatus.FAILED, NotificationFanoutJobStatus.DEAD_LETTER);
        assertThat(reloaded.getLastError()).as("AC-B18: 失敗理由が残る").contains(FAIL_MARKER);
        assertThat(countBusinessRows(businessName))
                .as("AC-B18: 配信が失敗しても業務行はロールバックされない").isEqualTo(1L);
    }

    // =====================================================================
    // 新版の文面・action_url・シャードの扱い
    // =====================================================================

    @Test
    @DisplayName("新版 FIXED_SINGLE: 文面6行と action_url が保存され、shard_count=1 で登録される")
    void 新版_FIXED_SINGLEは文面6行とaction_urlを保存しshard_countは1() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String actionUrl = "/organizations/f0121-it/member-teams?view=applications";

        UUID jobId = tx.execute(status -> jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                NOOP_SCOPE, scopeRef, "F0121_IT_TEXT", key, 42L, NotificationPriority.HIGH, 7L,
                "TEAM_ORG_MEMBERSHIP", 99L, actionUrl, false,
                FanoutMessageKind.SURVEY_PUBLISHED, List.of("加盟アンケートX"),
                FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).getId());

        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getActionUrl()).as("action_url が保存される").isEqualTo(actionUrl);
        assertThat(job.getShardCount()).as("FIXED_SINGLE は shard_count=1").isEqualTo((short) 1);
        assertThat(job.getShardIndex()).isEqualTo((short) 0);
        assertThat(job.getStatus()).isEqualTo(NotificationFanoutJobStatus.PENDING);
        assertThat(job.getSourceEventUuid()).as("冪等キーが source_event_uuid に入る").isEqualTo(key);
        assertThat(job.getOrganizationId()).isEqualTo(42L);
        assertThat(job.getPriority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(job.getActorId()).isEqualTo(7L);
        assertThat(job.getSourceType()).isEqualTo("TEAM_ORG_MEMBERSHIP");
        assertThat(job.getSourceId()).isEqualTo(99L);
        assertThat(job.getIncludeSupporters()).isFalse();

        List<NotificationFanoutJobMessage> messages = jobMessageRepository.findByJobId(jobId);
        assertThat(messages).extracting(NotificationFanoutJobMessage::getLocale)
                .as("文面は6配信ロケールぶん描画される")
                .containsExactlyInAnyOrderElementsOf(DeliveryLocales.TAGS);
        NotificationFanoutJobMessage ja = messages.stream()
                .filter(m -> "ja".equals(m.getLocale())).findFirst().orElseThrow();
        assertThat(ja.getTitle()).isNotBlank();
        assertThat(ja.getBody()).as("利用者が書いた中身がそのまま差し込まれる").contains("加盟アンケートX");
    }

    @Test
    @DisplayName("新版 AUTO: shard_count=0（未評価）で登録され、分割で作られた子シャードへ action_url と文面が複製される")
    void 新版_AUTOは未評価で登録され分割時に子シャードへaction_urlと文面が複製される() {
        String scopeRef = randomRef();
        UUID key = UUID.randomUUID();
        String actionUrl = "/teams/f0121-it/affiliations";

        UUID parentId = tx.execute(status -> jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                AUTO_SCOPE, scopeRef, "F0121_IT_AUTO", key, null, NotificationPriority.NORMAL, null,
                null, null, actionUrl, true,
                FanoutMessageKind.SURVEY_PUBLISHED, List.of("AUTOアンケート"),
                FanoutEnqueueCommand.ShardMode.AUTO)).getId());

        NotificationFanoutJob parent = jobRepository.findById(parentId).orElseThrow();
        assertThat(parent.getShardCount()).as("AUTO は shard_count=0（未評価）で登録する").isEqualTo((short) 0);

        int shards = jobService.resolveAndSplitShards(parentId);
        assertThat(shards).as("受信者 25,000 件は2シャードに分割される").isEqualTo(2);

        List<NotificationFanoutJob> all = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        AUTO_SCOPE, scopeRef, "F0121_IT_AUTO", key);
        assertThat(all).hasSize(2);
        NotificationFanoutJob child = all.get(1);
        assertThat(child.getShardIndex()).isEqualTo((short) 1);
        assertThat(child.getActionUrl()).as("子シャードにも action_url が複製される").isEqualTo(actionUrl);

        Set<String> parentTexts = texts(parentId);
        assertThat(parentTexts).hasSize(DeliveryLocales.TAGS.size());
        assertThat(texts(child.getId())).as("子シャードにも親と同じ文面が複製される").isEqualTo(parentTexts);
    }

    // =====================================================================
    // 加盟の通知種別9つ（§6.7・§14.2）
    // =====================================================================

    @Test
    @DisplayName("§6.7: 加盟の通知種別9つに対応する FanoutMessageKind があり、§14.2 の文面キーを使う")
    void 加盟の通知種別9つの文面種別がある() {
        Set<String> titleKeys = Arrays.stream(FanoutMessageKind.values())
                .map(FanoutMessageKind::titleKey)
                .collect(Collectors.toSet());
        assertThat(titleKeys).as("§14.2 の加盟の通知9種の title キーがすべて FanoutMessageKind にある")
                .contains(
                        "notification.teamAffiliation.applicationReceived.title",
                        "notification.teamAffiliation.applicationApproved.title",
                        "notification.teamAffiliation.applicationRejected.title",
                        "notification.teamAffiliation.inviteReceived.title",
                        "notification.teamAffiliation.inviteAccepted.title",
                        "notification.teamAffiliation.pendingExpired.title",
                        "notification.teamAffiliation.pendingCancelledBySystem.title",
                        "notification.teamAffiliation.membershipLeft.title",
                        "notification.teamAffiliation.membershipRemoved.title");
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private static FanoutEnqueueCommand command(String scopeType, String scopeRef, UUID key,
                                                FanoutEnqueueCommand.ShardMode shardMode) {
        return new FanoutEnqueueCommand(scopeType, scopeRef, "F0121_IT_E11", key, null,
                NotificationPriority.NORMAL, null, "F0121_IT", 1L, "/f0121-it", false,
                FanoutMessageKind.SURVEY_PUBLISHED, List.of("冪等アンケート"), shardMode);
    }

    private static String randomRef() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000L, 9_000_000_000L));
    }

    /** 呼び出し元の業務行（進行中トランザクションに参加して INSERT する）。 */
    private void insertBusinessRow(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('f0121-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
    }

    private long countBusinessRows(String name) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM organizations WHERE name = ?", Long.class, name);
        return n == null ? 0L : n;
    }

    private long countJobs(String scopeType, String scopeRef, String notificationType, UUID key) {
        return jobRepository.findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                scopeType, scopeRef, notificationType, key).size();
    }

    private long countMessages(UUID jobId) {
        return jobMessageRepository.findByJobId(jobId).size();
    }

    private Set<String> texts(UUID jobId) {
        return jobMessageRepository.findByJobId(jobId).stream()
                .map(m -> m.getLocale() + "|" + m.getTitle() + "|" + m.getBody())
                .collect(Collectors.toSet());
    }

    /** テスト用の受信者ソース群。 */
    @TestConfiguration
    static class TestSourcesConfig {

        @Bean
        FanoutRecipientSource f0121FailSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return FAIL_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    throw new IllegalStateException(FAIL_MARKER + "（scopeRef=" + request.scopeRef() + "）");
                }
            };
        }

        @Bean
        FanoutRecipientSource f0121AutoSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return AUTO_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    return List.of();
                }

                @Override
                public long countRecipients(String scopeRef, boolean includeSupporters) {
                    return 25_000L;
                }
            };
        }

        @Bean
        FanoutRecipientSource f0121NoopSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return NOOP_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    return List.of();
                }
            };
        }
    }
}
