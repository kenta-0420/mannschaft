package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260901-1538 柱③-A 検分P1-2/P2-5是正: 2 スレッドで同名を同時作成し、
 * {@code DuplicateNameGuardServiceImpl} のアドバイザリロック（{@code GET_LOCK}）が
 * 同名作成者同士を正しく直列化することを実 DB（Testcontainers MySQL）で検証する。
 *
 * <p>アドバイザリロックによる直列化が機能していない場合、両スレッドとも「同名候補ゼロ」の
 * スナップショットを見て未確認のまま作成に成功し、同名の重複が確認プロンプトを一切経ずに
 * 生成されてしまう（TOCTOU）。本 IT はこれが起きず、<b>後着スレッドが必ず先着スレッドの
 * コミット結果を候補として検知し 409 を受け取る</b>ことを実測する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 並行作成(アドバイザリロック直列化)統合テスト")
class DuplicateNameConcurrentCreationRedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String concurrentOrgName;

    @AfterEach
    void cleanUp() {
        if (concurrentOrgName != null) {
            jdbcTemplate.update("DELETE FROM organizations WHERE name = ?", concurrentOrgName);
        }
    }

    @Test
    @DisplayName("2スレッドが同時に同名(新規)を未確認で作成すると、一方のみ成功し他方は409(候補1件)を受ける")
    void concurrentUnconfirmedCreationsAreSerializedByAdvisoryLock() throws Exception {
        ensureAdminRole();
        concurrentOrgName = "並行作成IT組織" + System.nanoTime();
        CreateOrganizationRequest req1 = new CreateOrganizationRequest(
                concurrentOrgName, "OTHER", null, null, "PUBLIC", null, null);
        CreateOrganizationRequest req2 = new CreateOrganizationRequest(
                concurrentOrgName, "OTHER", null, null, "PUBLIC", null, null);

        Long userId1 = insertSyntheticUserId();
        Long userId2 = insertSyntheticUserId();

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptResult> future1 = executor.submit(() -> attemptCreate(startLatch, userId1, req1));
            Future<AttemptResult> future2 = executor.submit(() -> attemptCreate(startLatch, userId2, req2));

            // 2スレッドをほぼ同時に走らせ、GET_LOCK での競合を発生させやすくする。
            startLatch.countDown();

            AttemptResult result1 = future1.get(30, TimeUnit.SECONDS);
            AttemptResult result2 = future2.get(30, TimeUnit.SECONDS);

            // ちょうど一方だけが成功し、他方は DuplicateNameConfirmationRequiredException を受ける。
            boolean exactlyOneSucceeded = result1.succeeded ^ result2.succeeded;
            assertThat(exactlyOneSucceeded)
                    .as("2スレッドのうち成功はちょうど1件のはず（result1=%s, result2=%s）", result1, result2)
                    .isTrue();

            AttemptResult loser = result1.succeeded ? result2 : result1;
            assertThat(loser.thrownConfirmationRequired).isTrue();
            assertThat(loser.visibleCandidateCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        // DB上は結局1件のみ（後着はconfirmDuplicateしていないため作成されない）。
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organizations WHERE name = ?", Long.class, concurrentOrgName);
        assertThat(count).isEqualTo(1L);
    }

    private AttemptResult attemptCreate(CountDownLatch startLatch, Long userId, CreateOrganizationRequest req) {
        try {
            startLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            ApiResponse<OrganizationResponse> response = organizationService.createOrganization(userId, req);
            return new AttemptResult(true, false, response.getData().getBasicInfo().name(), 0);
        } catch (DuplicateNameConfirmationRequiredException e) {
            return new AttemptResult(false, true, null, e.getDetails().visibleCandidates().size());
        }
    }

    private Long insertSyntheticUserId() {
        String email = "dupname-concurrent-it-" + System.nanoTime() + "-" + Math.random() + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'DUPNAME', 'テスト', 'DUPNAME テスト', 'ACTIVE', "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** roles.ADMIN が Flyway seed 無効の test profile に存在しない場合に備え、事前投入する。 */
    private void ensureAdminRole() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = 'ADMIN'", Long.class);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                            + "VALUES ('ADMIN', 'ADMIN', 99, 0, NOW(), NOW())");
        }
        Long memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = 'MEMBER'", Long.class);
        if (memberCount == null || memberCount == 0) {
            jdbcTemplate.update(
                    "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                            + "VALUES ('MEMBER', 'MEMBER', 99, 0, NOW(), NOW())");
        }
    }

    private record AttemptResult(
            boolean succeeded, boolean thrownConfirmationRequired, String createdName, int visibleCandidateCount) {
    }
}
