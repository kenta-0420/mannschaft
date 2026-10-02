package com.mannschaft.app.schedule.batch;

import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google Calendar Phase 4 — Webhook チャンネル日次更新バッチ受け入れテスト（red 先行）。
 *
 * <p>対象 AC: AC-9 — 日次バッチが期限切れ前（3 日以内）のチャンネルを全件更新する。</p>
 *
 * <p><b>AC-9 仕様（設計書 P4-3 参照）</b>:</p>
 * <ul>
 *   <li>毎日 02:00 JST に実行（{@code @Scheduled(cron = "0 0 2 * * ?")}）</li>
 *   <li>対象: {@code expires_at &lt;= NOW() + 3日} のチャンネル全件</li>
 *   <li>処理: Google Calendar Watch API でチャンネルを再登録し、
 *       {@code channel_id} / {@code resource_id} / {@code channel_token} /
 *       {@code expires_at} を更新する</li>
 *   <li>外部境界: {@link com.mannschaft.app.schedule.service.GoogleApiClient}.watch() を呼ぶ</li>
 * </ul>
 *
 * <p><b>red の理由</b>: {@code GoogleWebhookChannelRenewalBatch} クラスは
 * Phase 4 出陣で実装予定のため、{@code @Autowired(required = false)} で null になる。
 * 各テストメソッドの冒頭で {@code assertThat(batch).isNotNull()} にて red を確認する。</p>
 *
 * <p><b>テスト対象外</b>: {@code @Scheduled} による自動実行タイミング（cron 式の精度）。
 * これは結合テストの責務外であり、Spring の Scheduler 実装を信頼する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Google Calendar Webhook チャンネル日次更新バッチ受け入れテスト（Phase 4 red）")
class GoogleWebhookChannelRenewalBatchTest extends AbstractMySqlIntegrationTest {

    /**
     * Phase 4 出陣で実装予定のバッチクラス。
     * 未実装のため null。各テストで assertThat(batch).isNotNull() にて red を確認する。
     *
     * <p>NOTE: Phase 4 出陣後は {@code required = true} に変更し、
     * {@code GoogleApiClient} は {@code @MockitoBean} で差し替えること。</p>
     */
    @Autowired(required = false)
    private GoogleWebhookChannelRenewalBatch batch;

    @Autowired
    private GoogleCalendarWebhookChannelRepository webhookChannelRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long USER_A = 9_810_001L;
    private static final Long USER_B = 9_810_002L;
    private static final Long USER_C = 9_810_003L;

    @BeforeEach
    void setUp() {
        insertUser(USER_A, "batch.user.a@example.com");
        insertUser(USER_B, "batch.user.b@example.com");
        insertUser(USER_C, "batch.user.c@example.com");
        em.flush();
    }

    // ========================================
    // AC-9: 日次バッチが期限 3 日以内チャンネルを全件更新
    // ========================================

    @Nested
    @DisplayName("AC-9: 日次バッチ — 期限 3 日以内のチャンネル全件更新")
    class AC9DailyBatchRenewExpiring {

        @Test
        @DisplayName("AC-9: 期限が 3 日以内のチャンネルは全件更新される")
        void batchRenewsChannelsExpiringWithin3Days() {
            // red 確認
            assertThat(batch)
                    .as("Phase 4 未実装: GoogleWebhookChannelRenewalBatch が Bean として存在しない (red)")
                    .isNotNull();

            // TODO（Phase 4 出陣後に実装）:
            // given:
            //   チャンネル A: expires_at = NOW() + 2日（期限 3 日以内 = バッチ対象）
            //   チャンネル B: expires_at = NOW() + 4日（期限 3 日超 = バッチ対象外）
            //
            //   GoogleApiClient.watch() のモックを設定:
            //     ユーザー A のチャンネルについて新しい channelId/resourceId/expiresAt を返す
            //     ユーザー B のチャンネルには呼ばれない
            //
            // when:
            //   batch.renewExpiringChannels(); // 手動実行（バッチの公開メソッド）
            //
            // then:
            //   チャンネル A の expires_at が NOW() + 6日23時間 付近に更新されている
            //   チャンネル B の expires_at は変わっていない
            //   GoogleApiClient.watch() がチャンネル A 分のみ 1 回呼ばれた（verify）
        }

        @Test
        @DisplayName("AC-9: 期限 3 日超のチャンネルはバッチ処理対象外（変更なし）")
        void batchDoesNotRenewChannelsExpiredAfter3Days() {
            assertThat(batch)
                    .as("Phase 4 未実装: GoogleWebhookChannelRenewalBatch が Bean として存在しない (red)")
                    .isNotNull();

            // TODO: expires_at = NOW() + 10日 のチャンネルを登録してバッチ実行
            // → GoogleApiClient.watch() が呼ばれないことを verify
        }

        @Test
        @DisplayName("AC-9: 有効期限切れ済み（expires_at < NOW()）のチャンネルもバッチで更新される")
        void batchAlsoRenewsAlreadyExpiredChannels() {
            assertThat(batch)
                    .as("Phase 4 未実装: GoogleWebhookChannelRenewalBatch が Bean として存在しない (red)")
                    .isNotNull();

            // TODO: expires_at = NOW() - 1日（既に期限切れ）のチャンネルも
            // 条件 expires_at <= NOW() + 3日 に含まれるため更新対象
        }

        @Test
        @DisplayName("AC-9: 複数ユーザーの期限間近チャンネルがすべて更新される")
        void batchRenewsAllExpiringChannelsAcrossMultipleUsers() {
            assertThat(batch)
                    .as("Phase 4 未実装: GoogleWebhookChannelRenewalBatch が Bean として存在しない (red)")
                    .isNotNull();

            // TODO:
            //   ユーザー A, B の両方に expires_at = NOW() + 1日 のチャンネルを登録
            //   batch.renewExpiringChannels() 実行
            //   → 両方のチャンネルが更新されている
            //   GoogleApiClient.watch() が 2 回呼ばれた（各ユーザーに 1 回ずつ）
        }

        @Test
        @DisplayName("AC-9: チャンネルが存在しない場合はバッチが正常終了し GoogleApiClient を呼ばない")
        void batchHandlesEmptyChannelsGracefully() {
            assertThat(batch)
                    .as("Phase 4 未実装: GoogleWebhookChannelRenewalBatch が Bean として存在しない (red)")
                    .isNotNull();

            // TODO:
            //   DB にチャンネルが 0 件の状態でバッチ実行
            //   → 例外が発生しないこと
            //   → GoogleApiClient.watch() が呼ばれないこと
        }
    }

    // ========================================
    // Repository レベルの確認（実 DB）
    // ========================================

    @Nested
    @DisplayName("GoogleCalendarWebhookChannelRepository — 期限切れチャンネル検索")
    class RepositoryExpiryQuery {

        @Test
        @DisplayName("findByExpiresAtLessThanEqual が期限 3 日以内のチャンネルのみ返す")
        void findByExpiresAtLessThanEqual_returnsOnlyExpiringChannels() {
            // given: DB にチャンネル 2 件挿入
            LocalDateTime nearExpiry = LocalDateTime.now().plusDays(2);    // 2日後: 3日以内
            LocalDateTime farExpiry = LocalDateTime.now().plusDays(5);     // 5日後: 3日超

            GoogleCalendarWebhookChannelEntity channelA = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(USER_A)
                    .channelId("repo-test-channel-A")
                    .resourceId("repo-test-resource-A")
                    .channelToken("token-A")
                    .expiresAt(nearExpiry)
                    .build();
            GoogleCalendarWebhookChannelEntity channelB = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(USER_B)
                    .channelId("repo-test-channel-B")
                    .resourceId("repo-test-resource-B")
                    .channelToken("token-B")
                    .expiresAt(farExpiry)
                    .build();
            webhookChannelRepository.save(channelA);
            webhookChannelRepository.save(channelB);
            em.flush();
            em.clear();

            // when: 3日後を閾値として検索
            LocalDateTime threshold = LocalDateTime.now().plusDays(3);
            List<GoogleCalendarWebhookChannelEntity> result =
                    webhookChannelRepository.findByExpiresAtLessThanEqual(threshold);

            // then: チャンネル A のみ返る
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getChannelId()).isEqualTo("repo-test-channel-A");
        }

        @Test
        @DisplayName("findByChannelId が正しいチャンネルを返す（Webhook 受信時の逆引き用）")
        void findByChannelId_returnsCorrectChannel() {
            // given
            GoogleCalendarWebhookChannelEntity channel = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(USER_C)
                    .channelId("lookup-test-channel-001")
                    .resourceId("lookup-test-resource-001")
                    .channelToken("lookup-test-token")
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            webhookChannelRepository.save(channel);
            em.flush();
            em.clear();

            // when
            var found = webhookChannelRepository.findByChannelId("lookup-test-channel-001");

            // then
            assertThat(found).isPresent();
            assertThat(found.get().getUserId()).isEqualTo(USER_C);
            assertThat(found.get().getChannelToken()).isEqualTo("lookup-test-token");
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private void insertUser(Long userId, String email) {
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'バッチ', 'テスト', 'バッチ テスト', 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        em.createNativeQuery("UPDATE users SET id = :uid WHERE email = :email")
                .setParameter("uid", userId)
                .setParameter("email", email)
                .executeUpdate();
    }
}
