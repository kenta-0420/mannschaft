package com.mannschaft.app.member;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.config.AsyncConfig;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.service.MemberSubtabVisibilityService;
import com.mannschaft.app.member.service.TeamPageService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-5〜8（軍議書 gungi-3387-d3t.md 第3版 §7）。
 *
 * <p>「権限確認は member の TX の外」「更新は書き込み専用 Bean の TX に閉じる」「監査は本当のコミット後に
 * 1回だけ積む」を実 DB・実 event-pool で確かめる。テスト側に TX は張らない（{@link MemberTxBoundaryITSupport}）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR #3387 D-3T 試練 AC-5〜8: サブタブ可視性の TX 境界と監査のタイミング（実 DB）")
class MemberSubtabVisibilityTxBoundaryIT extends MemberTxBoundaryITSupport {

    private static final String PROFILES = "member_profiles";
    private static final String LIST = "member_list";

    @Autowired private ApplicationContext applicationContext;
    @Autowired private TeamPageService teamPageService;

    /**
     * 注入した例外で失敗したことを確かめたうえで HTTP ステータスを返す。
     *
     * <p>原因を問わずに失敗を数えると、障害注入点に届かない別の失敗（spy の誤用・権限不足など）でも
     * 通ってしまう（偽緑）。そこで、例外ハンドラが解決した例外（または MockMvc から伝播した例外）の
     * root cause が、注入した型・メッセージと一致することを必須にする。未処理のまま伝播した場合は
     * 599 を返す。</p>
     */
    private int performExpectingInjectedFailure(RequestBuilder request,
                                                Class<? extends Throwable> injectedType,
                                                String injectedMessage) {
        Throwable failure;
        int status;
        try {
            MvcResult result = mockMvc.perform(request).andReturn();
            failure = result.getResolvedException();
            status = result.getResponse().getStatus();
        } catch (Exception e) {
            failure = e;
            status = 599; // 未処理例外として呼び出し元まで伝播した＝失敗
        }
        assertThat(failure).as("失敗の原因となった例外がある（status=%s）", status).isNotNull();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(root).as("root cause は注入した例外（実際: %s）", root).isExactlyInstanceOf(injectedType);
        assertThat(root.getMessage()).as("root cause のメッセージは注入したもの").isEqualTo(injectedMessage);
        return status;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-D3T-5: 複数件更新は丸ごと取り消される（自己呼び出しの罠の正本）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-D3T-5: [紹介→PUBLIC(正当), 一覧→PUBLIC(不正)] は 422 MEMBER_016・紹介の行は無い・監査0回")
    void AC5_複数件更新は丸ごと取り消される() throws Exception {
        long auditBefore = countAuditRows();
        setAuth(admId);

        mockMvc.perform(putSettingsRequest(PROFILES, "PUBLIC", LIST, "PUBLIC"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MEMBER_016"));

        awaitEventPoolIdle();
        assertThat(subtabMinRole(PROFILES)).as("先に INSERT した紹介の行も取り消されていること").isNull();
        assertThat(countSubtabRows()).isZero();
        assertThat(newAuditHandlerInvocations()).as("新設ハンドラは呼ばれない").isZero();
        assertThat(recordSyncInvocations()).as("AuditLogService.recordSync は呼ばれない").isZero();
        assertThat(countAuditRows()).as("audit_logs は増えない").isEqualTo(auditBefore);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-D3T-6: コミット直前の失敗では監査が出ない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-D3T-6: writer の TX の beforeCommit が落ちたら PUT は失敗・DB 不変・監査0回")
    void AC6_コミット直前の失敗では監査が出ない() throws Exception {
        long auditBefore = countAuditRows();
        AtomicInteger saveReached = new AtomicInteger();
        doAnswer(inv -> {
            saveReached.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("AC-6 injected beforeCommit failure");
                }
            });
            return callReal(inv);
        }).when(subtabRepository).save(any());
        setAuth(admId);

        int status = performExpectingInjectedFailure(putSettingsRequest(PROFILES, "PUBLIC"),
                IllegalStateException.class, "AC-6 injected beforeCommit failure");

        assertThat(saveReached.get()).as("障害注入点（repository.save）に到達している").isEqualTo(1);

        assertThat(status).as("PUT は失敗する（status=%s）", status).isGreaterThanOrEqualTo(400);
        awaitEventPoolIdle();
        assertThat(subtabMinRole(PROFILES)).as("DB は変わらない").isNull();
        assertThat(newAuditHandlerInvocations()).as("新設ハンドラは呼ばれない").isZero();
        assertThat(recordSyncInvocations()).as("AuditLogService.recordSync は呼ばれない").isZero();
        assertThat(countAuditRows()).as("audit_logs は増えない（コミット前に監査を積むと、ここで1行増える）")
                .isEqualTo(auditBefore);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-D3T-7: 保存後の失敗の扱い（障害注入）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-D3T-7: 保存後の失敗の扱い")
    class SaveFailureHandling {

        @Test
        @DisplayName("AC-7(a): 表示名の解決が落ちたら PUT は 5xx・DB 不変・監査0回（先読みは書き込みの前）")
        void AC7a_名前解決の失敗は保存前に止まる() throws Exception {
            long auditBefore = countAuditRows();
            doThrow(new IllegalStateException("AC-7a injected name resolution failure"))
                    .when(nameResolverService).resolveUserDisplayNames(any());
            setAuth(admId);

            int status = performExpectingInjectedFailure(putSettingsRequest(PROFILES, "PUBLIC"),
                    IllegalStateException.class, "AC-7a injected name resolution failure");

            org.mockito.Mockito.verify(nameResolverService, org.mockito.Mockito.atLeastOnce())
                    .resolveUserDisplayNames(any());
            assertThat(status).as("PUT は 5xx（status=%s）", status).isGreaterThanOrEqualTo(500);
            awaitEventPoolIdle();
            assertThat(subtabMinRole(PROFILES)).as("DB は変わらない").isNull();
            assertThat(newAuditHandlerInvocations()).isZero();
            assertThat(recordSyncInvocations()).as("監査は0回").isZero();
            assertThat(countAuditRows()).as("audit_logs は増えない").isEqualTo(auditBefore);
        }

        @Test
        @DisplayName("AC-7(b): event-pool を実際に飽和させても PUT は 200・DB 確定・投入拒否はログのみで監査は失われる")
        void AC7b_監査の投入拒否は呼び出し元へ伝播しない() throws Exception {
            // 前提の待機: 他のテストが残した非同期タスクを持ち込まない
            awaitEventPoolIdle();
            long auditBefore = countAuditRows();

            Logger asyncLogger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            Level originalLevel = asyncLogger.getLevel();
            asyncLogger.setLevel(Level.ERROR);
            asyncLogger.addAppender(appender);

            CountDownLatch hold = new CountDownLatch(1);
            AtomicInteger ownedStarted = new AtomicInteger();
            try {
                // 飽和: max 5 + queue 100 = 105 件を latch で止める
                int capacity = eventPool().getThreadPoolExecutor().getMaximumPoolSize()
                        + eventPool().getThreadPoolExecutor().getQueue().remainingCapacity();
                assertThat(capacity).as("前提: event-pool は max 5 / queue 100").isEqualTo(105);
                Runnable blocker = () -> {
                    ownedStarted.incrementAndGet();
                    try {
                        hold.await(120, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };
                AtomicInteger submitted = new AtomicInteger();
                // idle worker の dequeue 前は理論容量105でも投入できない。拒否を隠さず実容量を待つ。
                Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                    while (submitted.get() < capacity) {
                        ThreadPoolExecutor executor = eventPool().getThreadPoolExecutor();
                        if (executor.getQueue().remainingCapacity() == 0
                                && executor.getPoolSize() >= executor.getMaximumPoolSize()) {
                            return false;
                        }
                        eventPool().execute(blocker);
                        submitted.incrementAndGet();
                    }
                    return ownedStarted.get() == 5
                            && eventPool().getActiveCount() == 5
                            && eventPool().getThreadPoolExecutor().getQueue().size() == 100;
                });

                setAuth(admId);
                mockMvc.perform(putSettingsRequest(PROFILES, "PUBLIC"))
                        .andExpect(status().isOk());

                assertThat(subtabMinRole(PROFILES)).as("DB は確定済み").isEqualTo("PUBLIC");
                assertThat(appender.list)
                        .as("ERROR ログ「event-pool 投入拒否」が出る")
                        .anySatisfy(e -> {
                            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(e.getFormattedMessage()).contains("event-pool 投入拒否");
                        });
            } finally {
                // 後始末: latch を必ず解放し、プールが空に戻るまで待つ
                hold.countDown();
                awaitEventPoolIdle();
                asyncLogger.detachAppender(appender);
                asyncLogger.setLevel(originalLevel);
            }

            assertThat(countAuditRows())
                    .as("拒否された監査は失われる（プールが空になっても行は増えない）")
                    .isEqualTo(auditBefore);
        }

        @Test
        @DisplayName("AC-7(c): 正常な PUT は MEMBER_SUBTAB_VISIBILITY_UPDATED を1行・metadata は現行形式")
        void AC7c_正常なPUTは監査1行() throws Exception {
            long auditBefore = countAuditRows();
            setAuth(admId);

            mockMvc.perform(putSettingsRequest(PROFILES, "PUBLIC")).andExpect(status().isOk());

            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> countAuditRows() == auditBefore + 1);
            awaitEventPoolIdle();
            assertThat(countAuditRows()).isEqualTo(auditBefore + 1);

            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, target_user_id, team_id, organization_id, ip_address, user_agent, "
                            + "session_hash, metadata FROM audit_logs WHERE event_type = ? AND organization_id = ?",
                    AUDIT_EVENT_TYPE, orgId);
            assertThat(rows).hasSize(1);
            java.util.Map<String, Object> row = rows.get(0);
            assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(admId);
            assertThat(row.get("target_user_id")).isNull();
            assertThat(row.get("team_id")).isNull();
            assertThat(row.get("ip_address")).isNull();
            assertThat(row.get("user_agent")).isNull();
            assertThat(row.get("session_hash")).isNull();
            // MySQL の JSON 型は格納時にキー順・空白を正規化するため、木として比較する
            JsonNode actual = objectMapper.readTree(String.valueOf(row.get("metadata")));
            JsonNode expected = objectMapper.readTree("{\"scope_type\":\"ORGANIZATION\",\"scope_id\":" + orgId
                    + ",\"changes\":[{\"subtab_key\":\"member_profiles\",\"before\":\"MEMBER\",\"after\":\"PUBLIC\"}]}");
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("AC-7(c): 差分の無い PUT では監査0回")
        void AC7c_差分の無いPUTは監査0回() throws Exception {
            long auditBefore = countAuditRows();
            setAuth(admId);

            // 紹介の既定値は MEMBER。既定値と同じ値を送る＝差分なし
            mockMvc.perform(putSettingsRequest(PROFILES, "MEMBER")).andExpect(status().isOk());

            awaitEventPoolIdle();
            assertThat(newAuditHandlerInvocations()).isZero();
            assertThat(recordSyncInvocations()).isZero();
            assertThat(countAuditRows()).isEqualTo(auditBefore);
        }

        @Test
        @DisplayName("AC-7(d): 外側の TX から updateSettings を呼び、外側をロールバックしたら監査0回（伝播に依存しない）")
        void AC7d_外側TXのロールバックでは監査が出ない() {
            long auditBefore = countAuditRows();
            // spy フィールドではなく、TX 等のプロキシを通る Bean そのものを呼ぶ
            MemberSubtabVisibilityService proxied = applicationContext.getBean(MemberSubtabVisibilityService.class);
            UpdateMemberSubtabVisibilityRequest request = new UpdateMemberSubtabVisibilityRequest();
            UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem item =
                    new UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem();
            item.setSubtabKey(PROFILES);
            item.setMinRole(MinRole.PUBLIC);
            request.setSubtabs(List.of(item));

            TransactionTemplate outer = new TransactionTemplate(transactionManager);
            outer.setName("com.mannschaft.app.member.MemberSubtabVisibilityTxBoundaryIT.outer");
            outer.executeWithoutResult(txStatus -> {
                proxied.updateSettings(admId, ScopeType.ORGANIZATION, orgId, request);
                txStatus.setRollbackOnly();
            });

            awaitEventPoolIdle();
            assertThat(subtabMinRole(PROFILES)).as("外側ごと取り消されている").isNull();
            assertThat(newAuditHandlerInvocations()).isZero();
            assertThat(recordSyncInvocations()).as("監査は0回").isZero();
            assertThat(countAuditRows()).as("audit_logs は増えない").isEqualTo(auditBefore);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-D3T-8: 権限確認が member の TX の中で走らない（実行時）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-D3T-8: 権限確認・名前解決は member の TX の外で走る")
    class AccessControlOutsideMemberTx {

        private void assertPathOutsideMemberTx(Long userId, RequestBuilder request, int expectedStatus)
                throws Exception {
            observeAccessControlAndNameResolver();
            setAuth(userId);
            mockMvc.perform(request).andExpect(status().is(expectedStatus));
            assertPathObservations();
        }

        private void assertPathObservations() {
            assertOutsideMemberTx(List.copyOf(observations));
        }

        @Test
        @DisplayName("GET /team/pages（組織）")
        void 組織のページ一覧() throws Exception {
            assertPathOutsideMemberTx(memId, listOrgPagesRequest(), 200);
        }

        @Test
        @DisplayName("GET /team/pages（チーム）")
        void チームのページ一覧() throws Exception {
            assertPathOutsideMemberTx(memId, listTeamPagesRequest(), 200);
        }

        @Test
        @DisplayName("GET /team/pages/{id}")
        void ページ詳細() throws Exception {
            assertPathOutsideMemberTx(memId, getPageRequest(pubPageId), 200);
        }

        @Test
        @DisplayName("GET /team/members")
        void プロフィール一覧() throws Exception {
            assertPathOutsideMemberTx(memId, listProfilesRequest(pubPageId), 200);
        }

        @Test
        @DisplayName("GET /team/members/{id}")
        void プロフィール詳細() throws Exception {
            assertPathOutsideMemberTx(memId, getProfileRequest(visibleProfileId), 200);
        }

        @Test
        @DisplayName("GET /team/pages/{id}/sections")
        void セクション一覧() throws Exception {
            assertPathOutsideMemberTx(memId, listSectionsRequest(pubPageId), 200);
        }

        @Test
        @DisplayName("GET /team/members/lookup")
        void メンバー検索() throws Exception {
            assertPathOutsideMemberTx(memId, lookupRequest(pubPageId), 200);
        }

        @Test
        @DisplayName("GET member-subtab-visibility（設定行ありで名前解決も通る）")
        void サブタブ設定の取得() throws Exception {
            setProfilesSubtab(MinRole.SUPPORTER);
            assertPathOutsideMemberTx(memId, getSettingsRequest(), 200);
            assertThat(observations).as("名前解決も記録されていること")
                    .anySatisfy(o -> assertThat(o.target()).isEqualTo(NRS_FQCN));
        }

        @Test
        @DisplayName("PUT member-subtab-visibility（差分あり）")
        void サブタブ設定の更新() throws Exception {
            assertPathOutsideMemberTx(admId, putSettingsRequest(PROFILES, "PUBLIC"), 200);
            assertThat(observations).as("名前解決も記録されていること")
                    .anySatisfy(o -> assertThat(o.target()).isEqualTo(NRS_FQCN));
        }

        @Test
        @DisplayName("負例: 名前を付けない TransactionTemplate で getPage を包むと判定ヘルパは失敗を返す")
        void 負例_名前の無いTXは失敗() {
            observeAccessControlAndNameResolver();
            TransactionTemplate unnamed = new TransactionTemplate(transactionManager);
            unnamed.executeWithoutResult(s -> teamPageService.getPage(memId, pubPageId));

            assertThat(observations).as("前提: 記録がある").isNotEmpty();
            assertThatThrownBy(this::assertPathObservations).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("負例: member の名前を付けた TransactionTemplate で getPage を包むと判定ヘルパは失敗を返す")
        void 負例_memberの名前のTXは失敗() {
            observeAccessControlAndNameResolver();
            TransactionTemplate named = new TransactionTemplate(transactionManager);
            named.setName("com.mannschaft.app.member.X");
            named.executeWithoutResult(s -> teamPageService.getPage(memId, pubPageId));

            assertThat(observations).as("前提: 記録がある").isNotEmpty();
            assertThatThrownBy(this::assertPathObservations).isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("writer 側の対照: repository の書き込みは writer の TX（完全な TX 名）の中で走る")
        void writer側は書き込み専用BeanのTXの中() throws Exception {
            List<TxObservation> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
            doAnswer(inv -> {
                writes.add(new TxObservation("MemberSubtabRoleVisibilityRepository", inv.getMethod().getName(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.getCurrentTransactionName()));
                return callReal(inv);
            }).when(subtabRepository).save(any());
            setAuth(admId);

            mockMvc.perform(putSettingsRequest(PROFILES, "PUBLIC")).andExpect(status().isOk());

            assertThat(writes).as("前提: 書き込みが1件ある").hasSize(1);
            assertThat(writes.get(0).active()).as("TX が有効").isTrue();
            assertThat(writes.get(0).name()).as("TX 名が writer の applyUpdates と完全一致")
                    .isEqualTo(WRITER_TX_NAME);
        }
    }
}
