package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.NotificationTransactionBoundaryGuardTest.Violation;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 通知番人（{@link NotificationTransactionBoundaryGuardTest}）自身の検体テスト。
 *
 * <h2>なぜ「判定ロジックテスト」だけでは不十分なのか</h2>
 * <p>既存の {@code ScheduledBatchGuard} は {@code @Scheduled} の {@code @Repeatable} を見落とし、
 * それを受けて {@code TEST_CONVENTION.md} §9.6 が<b>負例 fixture を要求</b>している
 * （「違反 0 件は番人が動いていることの証明にはならない」）。
 * 本テストは本番と同一の判定ロジック（{@code scanSource}）に<b>実ファイルの検体ソース</b>を当て、
 * さらに<b>変異（mutation）</b>させて「検出できなくなること」まで確認する。
 * predicate を fixture に当てるだけでは predicate 自身の前提誤りに対する独立オラクルにならないため、
 * 変異テストを独立オラクルとして併置する。
 *
 * <h2>欠陥トポロジーの manifest</h2>
 * <p>CMP-056 で是正した18件・#2990 で挙がった18件は、以下の<b>トポロジー</b>に還元できる。
 * 18件を丸ごと複製するのではなく、トポロジーごとに最小 fixture を1つ持つ。
 *
 * <table border="1">
 *   <caption>欠陥トポロジーと検体の対応</caption>
 *   <tr><th>トポロジー</th><th>本番の実例</th><th>検体</th></tr>
 *   <tr><td>業務TX内で通知を try/catch して握る</td>
 *       <td>jobmatching 4件・バッチ5件・ヘルパ経由7件</td>
 *       <td>{@code TxNotificationFixture#notifyInsideTryWithinTx}</td></tr>
 *   <tr><td>業務TX内の単発通知（catch 無し）</td>
 *       <td>MemberPaymentService / TicketExpiryBatchService ほか</td>
 *       <td>{@code TxNotificationFixture#bareNotifyWithinTx}</td></tr>
 *   <tr><td>{@code NOT_SUPPORTED} で TX を中断しただけ</td><td>—</td>
 *       <td>{@code TxNotificationFixture#notifyWithNotSupported}</td></tr>
 *   <tr><td>{@code REQUIRES_NEW} で先に確定させる（逆向きの不整合）</td><td>—</td>
 *       <td>{@code TxNotificationFixture#notifyWithRequiresNew}</td></tr>
 *   <tr><td>{@code @Async} を AFTER_COMMIT の代用にする</td><td>家老の却下された原案</td>
 *       <td>{@code TxNotificationFixture#notifyWithAsyncAndTx}</td></tr>
 *   <tr><td>業務サービスからの {@code sendOne} 直呼び</td>
 *       <td>Runner の Javadoc が禁じるが番人が居なかった</td>
 *       <td>{@code TxNotificationFixture#sendOneFromBusinessService}</td></tr>
 *   <tr><td>{@code @Async} 自己呼び出しによる失効（サービス形）</td>
 *       <td>{@code NotificationCreditService:181}</td>
 *       <td>{@code AsyncSelfInvocationFixture#consume}</td></tr>
 *   <tr><td>{@code @Async} 自己呼び出しによる失効（バッチ・多段委譲）</td>
 *       <td>{@code NotificationCreditExpiryBatch:97,121,178}</td>
 *       <td>{@code AsyncSelfInvocationBatchFixture}</td></tr>
 *   <tr><td>素の {@code @EventListener} で通知を発火</td>
 *       <td>{@code ScheduleReminderNotificationListener:59} / {@code TeamSlotNoteNotifyListener:66}</td>
 *       <td>{@code PlainEventListenerFixture#onReminderNotification}</td></tr>
 *   <tr><td>{@code @Async} が executor 無指定（event-pool へ載る）</td>
 *       <td>credit 系アラート（Issue #2953 の自己投入経路）</td>
 *       <td>{@code AsyncSelfInvocationFixture#sendFreeQuotaAlertAsync}</td></tr>
 * </table>
 */
@DisplayName("通知番人 自身の検体テスト（負例・正例・変異）")
class NotificationTransactionBoundaryGuardConditionTest {

    private static final String FIXTURE_PACKAGE = "com.mannschaft.app.common.architecture.fixtures.notification";

    private static Set<String> keysOf(String simpleName) {
        Path root = NotificationTransactionBoundaryGuardTest.testSourceRoot();
        String fqcn = FIXTURE_PACKAGE + "." + simpleName;
        Path file = root.resolve(fqcn.replace('.', '/') + ".java");
        String source = NotificationTransactionBoundaryGuardTest.read(file);
        return NotificationTransactionBoundaryGuardTest.scanSource(fqcn, source).stream()
                .map(Violation::key)
                .collect(Collectors.toSet());
    }

    private static String sourceOf(String simpleName) {
        Path root = NotificationTransactionBoundaryGuardTest.testSourceRoot();
        return NotificationTransactionBoundaryGuardTest.read(
                root.resolve((FIXTURE_PACKAGE + "." + simpleName).replace('.', '/') + ".java"));
    }

    private static Set<String> keysOfMutated(String simpleName, String from, String to) {
        String source = sourceOf(simpleName);
        assertThat(source)
                .as("変異の起点となる文字列 '%s' が検体に存在しない＝変異テストが空振りしている", from)
                .contains(from);
        String fqcn = FIXTURE_PACKAGE + "." + simpleName;
        return NotificationTransactionBoundaryGuardTest.scanSource(fqcn, source.replace(from, to)).stream()
                .map(Violation::key)
                .collect(Collectors.toSet());
    }

    private static String key(String simpleName, String method,
                             NotificationTransactionBoundaryGuardTest.ViolationKind kind) {
        return FIXTURE_PACKAGE + "." + simpleName + "#" + method + " -> " + kind.name();
    }

    @Nested
    @DisplayName("負例: 違反として検出されなければならない")
    class 負例 {

        @Test
        @DisplayName("業務TX内の通知は try の内外どちらでも検出する")
        void TX内の通知を検出する() {
            Set<String> keys = keysOf("TxNotificationFixture");
            assertThat(keys)
                    .as("業務TX内で try/catch に包んだ通知（CMP-056 の代表トポロジー）を取りこぼしている")
                    .contains(key("TxNotificationFixture", "notifyInsideTryWithinTx",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_IN_TRY));
            assertThat(keys)
                    .as("catch を持たない単発通知を取りこぼしている（#2990 の一覧にも入っていなかった形）")
                    .contains(key("TxNotificationFixture", "bareNotifyWithinTx",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("@Async / REQUIRES_NEW / NOT_SUPPORTED 単独を AFTER_COMMIT の代用と認めない")
        void 免罪符を認めない() {
            Set<String> keys = keysOf("TxNotificationFixture");
            assertThat(keys)
                    .as("NOT_SUPPORTED は TX 参加を切るだけで『業務コミット後』の因果を保証しない")
                    .contains(key("TxNotificationFixture", "notifyWithNotSupported",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
            assertThat(keys)
                    .as("REQUIRES_NEW は業務コミット前に通知を先に確定させる（逆向きの不整合）")
                    .contains(key("TxNotificationFixture", "notifyWithRequiresNew",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
            assertThat(keys)
                    .as("@Async を付けても AFTER_COMMIT の代用にはならない（家老の原案が却下された理由）")
                    .contains(key("TxNotificationFixture", "notifyWithAsyncAndTx",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("@Transactional な入口から無印ヘルパへ委譲した通知を検出する")
        void 継承されたTX文脈を追う() {
            assertThat(keysOf("TxNotificationFixture"))
                    .as("『@Transactional な入口 → 無印の private ヘルパ → 通知』はバッチで最も多い形。"
                            + "自己呼び出しではプロキシを経ないため入口の TX がそのまま生きている。"
                            + "この伝播を追わないと ActionMemoReminderBatchService / "
                            + "TeamMemberTermReminderBatch / AttendanceRequirementBatchService の3件"
                            + "（いずれも #2990 に記載済み）を丸ごと取り逃す")
                    .contains(key("TxNotificationFixture", "notifyFromUnannotatedHelper",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("sendOne の直呼びを許可された入口以外で検出する")
        void sendOne直呼びを検出する() {
            Set<String> keys = keysOf("TxNotificationFixture");
            assertThat(keys)
                    .as("Runner の Javadoc が禁じている業務サービスからの sendOne 直呼びを取りこぼしている")
                    .contains(key("TxNotificationFixture", "sendOneFromBusinessService",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.DIRECT_RUNNER_CALL))
                    .contains(key("TxNotificationFixture", "sendOneFromPlainMethod",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.DIRECT_RUNNER_CALL));
        }

        @Test
        @DisplayName("@Async の自己呼び出しを検出する（#2990 が誤検出として除外していた形）")
        void Async自己呼び出しを検出する() {
            Set<String> service = keysOf("AsyncSelfInvocationFixture");
            assertThat(service)
                    .as("NotificationCreditService:181 と同型の @Async 自己呼び出しを取りこぼしている。"
                            + "『@Async 専用だから安全』は誤りで、自己呼び出しでは @Async が失効する")
                    .contains(key("AsyncSelfInvocationFixture", "consume",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION))
                    .contains(key("AsyncSelfInvocationFixture", "consumeViaThis",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION));

            Set<String> batch = keysOf("AsyncSelfInvocationBatchFixture");
            assertThat(batch)
                    .as("NotificationCreditExpiryBatch:97,121,178 と同型の多段委譲を取りこぼしている")
                    .contains(key("AsyncSelfInvocationBatchFixture", "process30DayAlert",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION))
                    .contains(key("AsyncSelfInvocationBatchFixture", "process7DayAlert",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION))
                    .contains(key("AsyncSelfInvocationBatchFixture", "processExpiry",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION));
        }

        @Test
        @DisplayName("素の @EventListener で通知を発火する形を検出する")
        void 素のEventListenerを検出する() {
            Set<String> keys = keysOf("PlainEventListenerFixture");
            assertThat(keys)
                    .as("ScheduleReminderNotificationListener:59 と同型の素の @EventListener を取りこぼしている")
                    .contains(key("PlainEventListenerFixture", "onReminderNotification",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.PLAIN_EVENT_LISTENER))
                    .as("TeamSlotNoteNotifyListener:66 と同型（REQUIRES_NEW 付きでも救われない）を取りこぼしている")
                    .contains(key("PlainEventListenerFixture", "onSlotNoteUpdatedWithRequiresNew",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.PLAIN_EVENT_LISTENER));
        }

        @Test
        @DisplayName("executor 無指定の @Async を検出する")
        void executor無指定のAsyncを検出する() {
            assertThat(keysOf("AsyncSelfInvocationFixture"))
                    .as("executor 無指定の @Async は @Primary の event-pool へ載る（Issue #2953 の自己投入経路）")
                    .contains(key("AsyncSelfInvocationFixture", "sendFreeQuotaAlertAsync",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_WITHOUT_EXECUTOR));
        }
    }

    @Nested
    @DisplayName("正例: 違反として挙げてはならない")
    class 正例 {

        @Test
        @DisplayName("AFTER_COMMIT リスナー（phase 明示・省略の両方）は違反にしない")
        void AFTER_COMMITリスナーは違反にしない() {
            Set<String> keys = keysOf("TxNotificationFixture");
            assertThat(keys)
                    .as("番人が厳しすぎる。正規形（AFTER_COMMIT + Runner）を違反にしてはならない")
                    .noneMatch(k -> k.contains("#afterCommitListener "))
                    .noneMatch(k -> k.contains("#afterCommitListenerDefaultPhase "));
        }

        @Test
        @DisplayName("通知を発火しないメソッド・リスナーは対象外")
        void 通知を発火しないものは対象外() {
            assertThat(keysOf("TxNotificationFixture"))
                    .noneMatch(k -> k.contains("#businessMethodWithoutNotification "));
            assertThat(keysOf("PlainEventListenerFixture"))
                    .as("通知を発火しない素の @EventListener（監査ログ等）を巻き込んではならない")
                    .noneMatch(k -> k.contains("#onSomethingWithoutNotification "))
                    .noneMatch(k -> k.contains("#onReminderAfterCommit "));
        }

        @Test
        @DisplayName("監査済み例外4クラスと配送層基盤は走査結果に現れない")
        void 監査済み例外と配送層は対象外() {
            for (String fqcn : NotificationTransactionBoundaryGuardTest.AUDITED_EXCEPTIONS) {
                assertThat(NotificationTransactionBoundaryGuardTest.scanSource(fqcn,
                        "@Transactional public void x(Long id) { helper.notify(id); }"))
                        .as("監査済み例外 %s は通知自体が業務目的であり、契約の対象外", fqcn)
                        .isEmpty();
            }
            for (String fqcn : NotificationTransactionBoundaryGuardTest.DELIVERY_INFRASTRUCTURE) {
                assertThat(NotificationTransactionBoundaryGuardTest.scanSource(fqcn,
                        "@Transactional public void x(Long id) { helper.notify(id); }"))
                        .as("配送層基盤 %s は通知配送の実装そのものであり、契約の対象外", fqcn)
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("変異テスト: アノテーションを1つ変えると番人が検出できなくなる／するようになる")
    class 変異 {

        @Test
        @DisplayName("@Async を外すと自己呼び出しの検出が消える")
        void Asyncを外すと検出が消える() {
            String target = key("AsyncSelfInvocationFixture", "consume",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_SELF_INVOCATION);
            assertThat(keysOf("AsyncSelfInvocationFixture")).contains(target);
            assertThat(keysOfMutated("AsyncSelfInvocationFixture",
                    "@Async\n    protected void sendFreeQuotaAlertAsync",
                    "protected void sendFreeQuotaAlertAsync"))
                    .as("@Async を外しても検出が残る＝番人は @Async を見ておらず、別の理由で当たっている")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("@Transactional を外すと TX 内通知の検出が消える")
        void Transactionalを外すと検出が消える() {
            String target = key("TxNotificationFixture", "bareNotifyWithinTx",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE);
            assertThat(keysOf("TxNotificationFixture")).contains(target);
            assertThat(keysOfMutated("TxNotificationFixture",
                    "@Transactional\n    public void bareNotifyWithinTx",
                    "public void bareNotifyWithinTx"))
                    .as("@Transactional を外しても検出が残る＝番人が TX 文脈を見ていない")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("phase を AFTER_COMMIT 以外へ変えると正例が違反に変わる")
        void phaseを変えると違反になる() {
            String target = key("TxNotificationFixture", "afterCommitListener",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.DIRECT_RUNNER_CALL);
            assertThat(keysOf("TxNotificationFixture")).doesNotContain(target);
            assertThat(keysOfMutated("TxNotificationFixture",
                    "phase = TransactionPhase.AFTER_COMMIT",
                    "phase = TransactionPhase.BEFORE_COMMIT"))
                    .as("AFTER_COMMIT 以外の phase を許してしまっている＝ホワイトリストが緩い")
                    .contains(target);
        }

        @Test
        @DisplayName("@TransactionalEventListener を素の @EventListener へ変えると違反になる")
        void EventListenerへ変えると違反になる() {
            assertThat(keysOfMutated("PlainEventListenerFixture",
                    "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)\n"
                            + "    public void onReminderAfterCommit",
                    "@EventListener\n    public void onReminderAfterCommit"))
                    .as("AFTER_COMMIT を素の @EventListener に落としても検出されない＝番人が効いていない")
                    .contains(key("PlainEventListenerFixture", "onReminderAfterCommit",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.PLAIN_EVENT_LISTENER));
        }

        @Test
        @DisplayName("executor 名を足すと ASYNC_WITHOUT_EXECUTOR が消える")
        void executor名を足すと検出が消える() {
            String target = key("AsyncSelfInvocationFixture", "sendFreeQuotaAlertAsync",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.ASYNC_WITHOUT_EXECUTOR);
            assertThat(keysOf("AsyncSelfInvocationFixture")).contains(target);
            assertThat(keysOfMutated("AsyncSelfInvocationFixture",
                    "@Async\n    protected void sendFreeQuotaAlertAsync",
                    "@Async(\"event-pool\")\n    protected void sendFreeQuotaAlertAsync"))
                    .as("executor 名を明示しても検出が残る＝番人が引数を見ていない")
                    .doesNotContain(target);
        }
    }

    @Test
    @DisplayName("検体そのものが空振りしていない（fixture から実際に違反が出ている）")
    void 検体が空振りしていない() {
        List<String> fixtures = List.of("TxNotificationFixture", "AsyncSelfInvocationFixture",
                "AsyncSelfInvocationBatchFixture", "PlainEventListenerFixture");
        for (String f : fixtures) {
            assertThat(keysOf(f))
                    .as("検体 %s から違反が1件も出ていない＝走査が空振りしており、"
                            + "以降の contains 判定がすべて無意味になる", f)
                    .isNotEmpty();
        }
    }
}
