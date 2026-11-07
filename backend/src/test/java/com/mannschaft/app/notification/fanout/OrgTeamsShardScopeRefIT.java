package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 6-E 試練 — AC-H32a: 宛先集合のキー（scope_ref = audience_snapshot_id）がシャード分割で子に引き継がれる。
 *
 * <p>受信者ソースの本物（{@code ORGANIZATION_TEAMS}）で 20,001 人を作るのは重いため、テストダブルの受信者ソースを使う
 * （陣立て書 段階6）。ダブルは本物と同じ契約を持つ: scope_ref を宛先集合のキー（UUID）として解釈し、
 * 宛先チームの行が引けなければ<b>空の頁</b>を返す（= 子シャードが親と違う scope_ref を持てば、そのシャードの宛先が空になる）。
 * 総数は 20,001 件を返し、enqueue は {@code shard_count=0}（AUTO）で行う（部隊 6-D の基盤）。</p>
 *
 * <p>main の {@code resolveAndSplitShards}（scope_ref を子へ複製する）は既に実装済みのため、本テストは
 * 6-E 着手前から緑になりうる回帰の杭である（6-E の実装で scope_ref の扱いを崩さないことを固定する）。</p>
 */
@Import(OrgTeamsShardScopeRefIT.DoubleSourceConfig.class)
@DisplayName("F01.2.1 6-E シャード分割での scope_ref の引き継ぎ（AC-H32a）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrgTeamsShardScopeRefIT extends AbstractMySqlIntegrationTest {

    private static final String DOUBLE_SCOPE = "TEST_6E_SHARD";

    /** ダブルが返す受信者（テストごとに差し替える）。 */
    private static volatile List<Long> doubleUsers = List.of();

    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutAudienceRepository audienceRepository;
    @Autowired
    private NotificationFanoutAudienceTeamRepository audienceTeamRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("H32a: 総数 20,001 件の AUTO ジョブは2シャードに分割され、子シャードの scope_ref は親と同じで、どのシャードの宛先も空にならない")
    void H32a_子シャードは親と同じscope_refを持ち宛先が空にならない() {
        UUID audienceId = UUID.randomUUID();
        NotificationFanoutAudienceEntity header = NotificationFanoutAudienceEntity.builder()
                .organizationId(1L).build();
        header.setId(audienceId);
        audienceRepository.saveAndFlush(header);
        audienceTeamRepository.saveAndFlush(NotificationFanoutAudienceTeamEntity.builder()
                .audienceSnapshotId(audienceId).teamId(1L).build());

        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            users.add(insertUser());
        }
        doubleUsers = List.copyOf(users);
        String type = "F0121_IT_H32A_" + UUID.randomUUID().toString().substring(0, 8);
        UUID key = UUID.randomUUID();

        UUID parentId = new TransactionTemplate(transactionManager).execute(status ->
                jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                        DOUBLE_SCOPE, audienceId.toString(), type, key, 1L, NotificationPriority.NORMAL, null,
                        "ANNOUNCEMENT_FEED", 1L, "/organizations/h32a/surveys/1", false,
                        FanoutMessageKind.SURVEY_PUBLISHED, List.of("H32a"),
                        FanoutEnqueueCommand.ShardMode.AUTO)).jobId());
        NotificationFanoutJob enqueued = jobRepository.findById(parentId).orElseThrow();
        assertThat(enqueued.getShardCount()).as("AUTO は shard_count=0 で登録する").isZero();

        worker.processOne(enqueued);

        List<NotificationFanoutJob> shards = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        DOUBLE_SCOPE, audienceId.toString(), type, key);
        assertThat(shards).as("20,001 件は2シャードに分割される").hasSize(2);
        assertThat(shards).extracting(NotificationFanoutJob::getScopeRef)
                .as("子シャードの scope_ref は親と同じ（audience_snapshot_id）")
                .containsOnly(audienceId.toString());
        assertThat(shards).extracting(NotificationFanoutJob::getShardCount).containsOnly((short) 2);

        for (NotificationFanoutJob shard : shards) {
            if (shard.getShardIndex() != 0) {
                worker.processOne(shard);
            }
        }

        List<Long> perUser = jdbc.queryForList(
                "SELECT user_id FROM notifications WHERE notification_type = ? ORDER BY user_id", Long.class, type);
        assertThat(perUser).as("2シャードの配信で全員に1件ずつ（宛先が空のシャードも重複もない）")
                .containsExactlyElementsOf(users.stream().sorted().toList());
        for (NotificationFanoutJob shard : jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        DOUBLE_SCOPE, audienceId.toString(), type, key)) {
            assertThat(shard.getStatus()).as("shardIndex=" + shard.getShardIndex())
                    .isEqualTo(NotificationFanoutJobStatus.DONE);
        }
        Set<Short> shardsWithRecipients = users.stream().map(u -> (short) (u % 2)).collect(Collectors.toSet());
        assertThat(shardsWithRecipients).as("フィクスチャの前提: 両方のシャードに受信者がいる").hasSize(2);
    }

    private long insertUser() {
        String email = "f0121-h32a-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, deleted_at, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'H32A', 'テスト', 'H32Aテスト', 'ACTIVE', NULL, "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** テストダブルの受信者ソース。 */
    @TestConfiguration
    static class DoubleSourceConfig {

        @Bean
        FanoutRecipientSource h32aShardSource(NotificationFanoutAudienceTeamRepository teamRepository) {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return DOUBLE_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    UUID audienceId = UUID.fromString(request.scopeRef());
                    if (teamRepository.findTeamIdsByAudienceSnapshotId(audienceId).isEmpty()) {
                        return List.of();
                    }
                    return doubleUsers.stream()
                            .filter(u -> u > request.cursorSubjectId())
                            .filter(u -> request.isSingleShard() || u % request.shardCount() == request.shardIndex())
                            .sorted()
                            .limit(request.limit())
                            .map(u -> new FanoutRecipient(u, "ja"))
                            .toList();
                }

                @Override
                public long countRecipients(String scopeRef, boolean includeSupporters) {
                    return 20_001L;
                }
            };
        }
    }
}
