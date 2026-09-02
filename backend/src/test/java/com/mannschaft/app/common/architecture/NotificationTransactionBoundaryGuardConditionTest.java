package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.NotificationTransactionBoundaryGuardTest.Violation;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** 検体の走査で報告された「判定不能（曖昧）」の一覧。 */
    private static List<String> ambiguitiesOf(String simpleName) {
        String fqcn = FIXTURE_PACKAGE + "." + simpleName;
        return NotificationTransactionBoundaryGuardTest
                .scanSourceDetailed(fqcn, sourceOf(simpleName)).ambiguities();
    }

    /** 変異させた検体の走査で報告された「判定不能（曖昧）」の一覧。 */
    private static List<String> ambiguitiesOfMutated(String simpleName, String from, String to) {
        String source = sourceOf(simpleName);
        assertThat(source)
                .as("変異の起点となる文字列 '%s' が検体に存在しない＝変異テストが空振りしている", from)
                .contains(from);
        return NotificationTransactionBoundaryGuardTest
                .scanSourceDetailed(FIXTURE_PACKAGE + "." + simpleName, source.replace(from, to))
                .ambiguities();
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

    @Nested
    @DisplayName("死角: 検出できる形と検出できない形を明示的に固定する（Codex 検分 8形状）")
    class 死角 {

        private static final String FIXTURE = "GuardBlindSpotFixture";

        private String blindSpotKey(String method,
                                    NotificationTransactionBoundaryGuardTest.ViolationKind kind) {
            return key(FIXTURE, method, kind);
        }

        @Test
        @DisplayName("形状6: 同一行アノテーションのメソッドを検出する（parse 落ちしない）")
        void 形状6_同一行アノテーション() {
            assertThat(keysOf(FIXTURE))
                    .as("`@Transactional public void x(...)` のように宣言と同じ行にアノテーションを置くと、"
                            + "行頭が @ になるため METHOD_DECL がメソッド自体を parse できず、"
                            + "本文の通知呼び出しごと不可視になっていた（静かな偽陰性）")
                    .contains(blindSpotKey("sameLineAnnotatedTxNotify",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("形状5: 完全修飾アノテーションでも TX 文脈を見失わない")
        void 形状5_完全修飾アノテーション() {
            assertThat(keysOf(FIXTURE))
                    .as("@org.springframework.transaction.annotation.Transactional と書かれると、"
                            + "literal 一致の hasTransactional では TX 文脈の判定が丸ごと外れる")
                    .contains(blindSpotKey("fullyQualifiedTxNotify",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("形状1: 別 Bean への1ホップ委譲を検出する（Issue #3039 で塞いだ）")
        void 形状1_別Beanへの委譲() {
            assertThat(keysOf(FIXTURE))
                    .as("@Transactional な業務メソッドが無印の別 Bean を呼び、その先で通知する形。"
                            + "委譲先は呼び出し元の業務TXにそのまま参加するため、失敗すれば業務ごと巻き戻る。"
                            + "レシーバの宣言から型を引き、その型のソースを引けば1ホップは追える")
                    .contains(blindSpotKey("delegateToAnotherBean",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE));
        }

        @Test
        @DisplayName("形状2: TransactionTemplate の lambda 内の通知を検出する（Issue #3039 で塞いだ）")
        void 形状2_TransactionTemplateのlambda() {
            Set<String> keys = keysOf(FIXTURE);
            assertThat(keys)
                    .as("外側メソッドに @Transactional が無くても execute(...) の引数の内側は確実に TX 内である。"
                            + "本番には TransactionTemplate を持つクラスが7つあり、死角として放置できない")
                    .contains(blindSpotKey("notifyInsideTransactionTemplate",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
            assertThat(keys)
                    .as("TransactionTemplate の外側で発火する通知まで違反にしている＝判定が『範囲』ではなく"
                            + "『TransactionTemplate を持つクラス全体』になっている")
                    .noneMatch(k -> k.contains("#notifyOutsideTransactionTemplate "));
        }

        @Test
        @DisplayName("形状3: 命名語彙の外の通知 API を型で捕まえる（Issue #3039 で塞いだ）")
        void 形状3_命名語彙の外のAPI() {
            assertThat(keysOf(FIXTURE))
                    .as("gateway.send / publishNotification / enqueue は綴りでは拾えない。"
                            + "語彙を広げるとアクセサ・ビルダーの偽陽性が増えるので広げず、"
                            + "『委譲先の型が実際に通知を発火するか』という型の軸で捕まえる")
                    .contains(blindSpotKey("notifyViaUnnamedApi",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE));
        }

        @Test
        @DisplayName("形状4: 合成アノテーションの TX 文脈を検出する（Issue #3039 で塞いだ）")
        void 形状4_合成アノテーション() {
            assertThat(NotificationTransactionBoundaryGuardTest.composedTxAnnotations())
                    .as("注釈の定義側の走査が空振りしている＝以降の判定が何も測っていない")
                    .contains("BusinessTransaction");
            assertThat(keysOf(FIXTURE))
                    .as("メタ注釈で @Transactional を持つ独自注釈は、注釈の定義側を走査すれば字句のまま解決できる")
                    .contains(blindSpotKey("composedAnnotationTxNotify",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("形状8: 通知発火 API のメソッド参照を検出する（Codex 独立検分 条件3 で塞いだ）")
        void 形状8_メソッド参照を検出する() {
            assertThat(keysOf(FIXTURE))
                    .as("実行位置が字句から決まらないということは『AFTER_COMMIT 境界の後だ』とも言えない、"
                            + "ということである。契約文書（原則5-2）に書くだけでは追加された瞬間に見逃すので、"
                            + "許可された入口の外では一律に違反とする機械ゲートにした")
                    .contains(blindSpotKey("notifyViaMethodReference",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.NOTIFY_METHOD_REFERENCE));
        }

        @Test
        @DisplayName("形状9: シャドーイングは塞げない。ただし先勝ちせず『判定不能』として落とす")
        void 形状9_シャドーイングは判定不能として報告される() {
            assertThat(ambiguitiesOf(FIXTURE))
                    .as("ローカル変数がフィールドを隠すと同じ名前に2つの型が対応する。"
                            + "字句走査にスコープは無いので正しくは解けないが、"
                            + "先勝ちで片方を採ると『実際に呼ばれているのは別の型なのに片方で判定する』ことになる。"
                            + "候補を捨てず、判定が割れたら違反でも合格でもなく判定不能として落とす")
                    .anySatisfy(a -> assertThat(a).contains("#localShadowsField"));
            assertThat(keysOf(FIXTURE))
                    .as("判定不能なものを違反として数えている（＝検出できるフリ）")
                    .noneMatch(k -> k.contains("#localShadowsField "));
        }

        @Test
        @DisplayName("形状10: 宣言が親クラスにあるレシーバへのメソッド参照を検出する（Codex 検分 High）")
        void 形状10_継承経由のメソッド参照() {
            assertThat(keysOf(FIXTURE))
                    .as("InheritingWorkerStub 自身の本体は空で、notify は BaseNotifierStub が宣言している。"
                            + "declaredMethodNames が対象クラスの typeBlock しか見ていないと、"
                            + "語彙が完全一致していても『レシーバ型がその API を宣言している』条件を満たせず"
                            + "NOTIFY_METHOD_REFERENCE が発火しない（静かな偽陰性）")
                    .contains(blindSpotKey("notifyViaInheritedMethodReference",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.NOTIFY_METHOD_REFERENCE));
        }

        @Test
        @DisplayName("形状11: 完全修飾で宣言されたフィールドへの委譲を検出する")
        void 形状11_完全修飾フィールド() {
            assertThat(keysOf(FIXTURE))
                    .as("private final com.x.Y y; は TYPED_DECLARATION が拾わないため型が解決できず、"
                            + "委譲先として追えないうえ曖昧性ゲートにも掛からず静かに追跡外だった")
                    .contains(blindSpotKey("delegateViaFullyQualifiedField",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE));
        }

        @Test
        @DisplayName("形状12: メソッド参照のレシーバ型が割れたら判定不能として報告する（経路間の非対称の解消）")
        void 形状12_メソッド参照の曖昧性() {
            assertThat(ambiguitiesOf(FIXTURE))
                    .as("委譲判定は『候補間で割れたら判定不能』なのに、メソッド参照の経路にだけ"
                            + "曖昧性ゲートが無かった。無関係な同名候補がたまたま同じ名前の API を持てば"
                            + "静かに違反へ倒れる（逆向きの偽陰性も同様）。扱いは経路によって変えてはならない")
                    .anySatisfy(a -> assertThat(a).contains("#methodReferenceOnAmbiguousReceiver"));
            assertThat(keysOf(FIXTURE))
                    .as("判定不能なものを違反として数えている（＝検出できるフリ）")
                    .noneMatch(k -> k.contains("#methodReferenceOnAmbiguousReceiver "));
        }

        @Test
        @DisplayName("形状7: 同名オーバーロードの畳み込みを @Transactional の宣言で分離する")
        void 形状7_オーバーロード畳み込み() {
            Set<String> keys = keysOf(FIXTURE);
            assertThat(keys)
                    .as("AFTER_COMMIT 入口 handle(String) から委譲された名前 'handle' が"
                            + "業務側 handle(Long) にも及び、sendOne 直呼びが許可扱いになっていた（偽陰性）。"
                            + "引数の型は字句から解決できないが、『自分で @Transactional を宣言している"
                            + "＝業務TXの入口であって AFTER_COMMIT 境界の内側ではない』という条件で分離できる")
                    .contains(blindSpotKey("handle",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.DIRECT_RUNNER_CALL))
                    .contains(blindSpotKey("handle",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE));
        }

        @Test
        @DisplayName("正規形の private ヘルパは巻き込まない（形状7 の分離が効きすぎていない）")
        void 正規形のヘルパは許可のまま() {
            assertThat(keysOf("TxNotificationFixture"))
                    .as("AFTER_COMMIT 入口 → 無印 private ヘルパ → runner.sendOne は金型の正規形であり、"
                            + "形状7 の分離（@Transactional 宣言で切る）がここまで巻き込んではならない")
                    .noneMatch(k -> k.contains("#afterCommitListener "));
        }
    }

    @Nested
    @DisplayName("死角を塞いだ判定の変異テスト（1文字変えると検出できなくなることの裏取り）")
    class 死角の変異 {

        private static final String FIXTURE = "GuardBlindSpotFixture";

        @Test
        @DisplayName("変異: executeWithoutResult を別名にすると TransactionTemplate 判定が消える")
        void TransactionTemplateの綴りを変えると消える() {
            String target = key(FIXTURE, "notifyInsideTransactionTemplate",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE);
            assertThat(keysOf(FIXTURE)).contains(target);
            assertThat(keysOfMutated(FIXTURE,
                    "transactionTemplate.executeWithoutResult(status -> {",
                    "transactionTemplate.runWithoutResult(status -> {"))
                    .as("execute / executeWithoutResult 以外でも検出が残る＝範囲判定ではなく"
                            + "『TransactionTemplate を持つクラス全体』を TX とみなしている")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: フィールドの型名を変えると TransactionTemplate 判定が消える")
        void 型名を変えると消える() {
            String target = key(FIXTURE, "notifyInsideTransactionTemplate",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE);
            assertThat(keysOfMutated(FIXTURE,
                    "private final TransactionTemplate transactionTemplate",
                    "private final Object transactionTemplate"))
                    .as("型宣言を変えても検出が残る＝変数名 'transactionTemplate' の綴りで判定している。"
                            + "本番の変数名は chunkTxTemplate / enqueueTxTemplate 等で統一されていないため、"
                            + "綴り判定では取りこぼす")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: 合成アノテーションの定義から @Transactional を外すと形状4 の検出が消える")
        void 合成アノテーションの定義を変えると消える() {
            String target = key(FIXTURE, "composedAnnotationTxNotify",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_BARE);
            assertThat(keysOf(FIXTURE)).contains(target);

            // 注釈の「定義側」を変異させる。定義を辿っていなければ結果は変わらない。
            Path fixtures = NotificationTransactionBoundaryGuardTest.testSourceRoot()
                    .resolve("com/mannschaft/app/common/architecture/fixtures/notification");
            List<Path> files = new ArrayList<>(NotificationTransactionBoundaryGuardTest.javaFiles(fixtures));
            assertThat(NotificationTransactionBoundaryGuardTest.findComposedTxAnnotations(files))
                    .as("検体パッケージから合成アノテーションを1件も見つけられていない")
                    .contains("BusinessTransaction");
            List<Path> withoutAnnotationDef = files.stream()
                    .filter(p -> !p.getFileName().toString().equals("BusinessTransaction.java"))
                    .collect(Collectors.toList());
            assertThat(NotificationTransactionBoundaryGuardTest
                    .findComposedTxAnnotations(withoutAnnotationDef))
                    .as("定義ファイルを除いても合成アノテーションが見つかる＝定義を辿っていない")
                    .doesNotContain("BusinessTransaction");
        }

        @Test
        @DisplayName("変異: Javadoc の中の @Transactional を合成アノテーションと誤認しない")
        void コメント中の綴りを拾わない() {
            // 本番の BackgroundFeaturePolicy は Javadoc に @TransactionalEventListener と書いている。
            // マスクを外すと 350 箇所の @BackgroundFeaturePolicy 付きメソッドが一斉に TX 扱いになる。
            assertThat(NotificationTransactionBoundaryGuardTest.composedTxAnnotations())
                    .as("Javadoc 中の言及を合成アノテーションとして拾っている（コメントのマスク漏れ）")
                    .doesNotContain("BackgroundFeaturePolicy");
        }

        @Test
        @DisplayName("変異: 委譲先から通知を消すと形状1 の検出が消える")
        void 委譲先が通知しなければ消える() {
            // 委譲先（WorkerStub#send）の中身を見ずに「別 Bean を呼んだ」だけで違反にしているなら、
            // 呼び先が通知を発火しない別メソッドへ変えても検出が残ってしまう。
            String target = key(FIXTURE, "delegateToAnotherBean",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE);
            assertThat(keysOf(FIXTURE)).contains(target);
            assertThat(keysOfMutated(FIXTURE,
                    "notificationWorker.send(userId);",
                    "repository.save(userId);"))
                    .as("委譲先を通知しないメソッドへ変えても検出が残る＝呼び先の中身を見ていない")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: レシーバの型宣言を消すと形状1・3 の検出が消える")
        void 型が解決できなければ消える() {
            String target = key(FIXTURE, "notifyViaUnnamedApi",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE);
            assertThat(keysOf(FIXTURE)).contains(target);
            assertThat(keysOfMutated(FIXTURE,
                    "private final GatewayStub gateway = new GatewayStub();",
                    "private final Object gateway = null;"))
                    .as("レシーバの型が解決できなくても検出が残る＝型ではなく変数名の綴りで判定している")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: メソッド参照の名前を語彙外にすると形状8 の検出が消える")
        void メソッド参照は語彙の完全一致で判定している() {
            String target = key(FIXTURE, "notifyViaMethodReference",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.NOTIFY_METHOD_REFERENCE);
            assertThat(keysOf(FIXTURE)).contains(target);
            // main の ::toNotificationResponse / ::getNotifyTeamSlotNoteUpdates（mapper・getter）を
            // 巻き込まないことの裏取り。HelperStub#toResponse は「型は宣言しているが語彙の外」であり、
            // 語彙の条件だけが検出を落とせる形になっている（型の条件では落ちない）。
            assertThat(keysOfMutated(FIXTURE,
                    "Consumer<Long> sink = notificationHelper::notify;",
                    "Consumer<Long> sink = notificationHelper::toResponse;"))
                    .as("語彙の外の API のメソッド参照でも検出が残る＝綴りを見ておらず、"
                            + "main の ::toNotificationResponse 等の mapper を巻き込む")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: レシーバの型が当該 API を宣言していないと形状8 の検出が消える")
        void メソッド参照は型で裏取りしている() {
            String target = key(FIXTURE, "notifyViaMethodReference",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.NOTIFY_METHOD_REFERENCE);
            assertThat(keysOfMutated(FIXTURE,
                    "private final HelperStub notificationHelper = new HelperStub();",
                    "private final RepositoryStub notificationHelper = new RepositoryStub();"))
                    .as("レシーバの型が notify を宣言していなくても検出が残る＝綴りだけで判定しており、"
                            + "通知系の型に属するという条件が効いていない")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("変異: シャドーイングを解消すると曖昧性が消えて違反として検出される")
        void シャドーイングが解消すれば判定できる() {
            assertThat(ambiguitiesOf(FIXTURE)).anySatisfy(a -> assertThat(a).contains("#localShadowsField"));
            // ローカルの型をフィールドと同じにすると候補が一致し、判定は割れなくなる。
            // 「常に曖昧と言っているだけ」ではないことの裏取り。
            assertThat(ambiguitiesOfMutated(FIXTURE,
                    "SilentWorkerStub shadowedWorker = new SilentWorkerStub();",
                    "WorkerStub shadowedWorker = new WorkerStub();"))
                    .as("候補が一致しても曖昧だと言い続ける＝『割れているか』を見ておらず、"
                            + "シャドーイングの有無だけで落としている")
                    .noneSatisfy(a -> assertThat(a).contains("#localShadowsField"));
            assertThat(keysOfMutated(FIXTURE,
                    "SilentWorkerStub shadowedWorker = new SilentWorkerStub();",
                    "WorkerStub shadowedWorker = new WorkerStub();"))
                    .as("候補が一致したのに違反として検出されない＝曖昧性の判定が委譲検出そのものを殺している")
                    .contains(key(FIXTURE, "localShadowsField",
                            NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE));
        }

        /** 検体パッケージの型を単純名で引く。 */
        private NotificationTransactionBoundaryGuardTest.TypeRef fixtureType(String simpleName) {
            return NotificationTransactionBoundaryGuardTest.typeIndex()
                    .getOrDefault(simpleName, List.of()).stream()
                    .filter(r -> r.fqcn().startsWith(FIXTURE_PACKAGE))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("索引に無い: " + simpleName));
        }

        @Test
        @DisplayName("変異: extends 節を消すと親の宣言が見えなくなる（形状10 は継承を辿って効いている）")
        void 継承を辿っていることの裏取り() {
            NotificationTransactionBoundaryGuardTest.TypeRef ref = fixtureType("InheritingWorkerStub");
            String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(
                    NotificationTransactionBoundaryGuardTest.read(ref.file()));

            // (1) 自分の本体には notify が無い。＝旧実装（typeBlock だけを見る）では見えない。
            String ownBlock = NotificationTransactionBoundaryGuardTest.typeBlock(masked, "InheritingWorkerStub");
            assertThat(NotificationTransactionBoundaryGuardTest.parseMethods(ownBlock).stream()
                    .map(NotificationTransactionBoundaryGuardTest.MethodBlock::name).toList())
                    .as("検体の本体にメソッドが書かれている＝『継承を辿らないと見えない』状況を作れていない")
                    .doesNotContain("notify");

            // (2) 継承を辿ると見える。
            assertThat(NotificationTransactionBoundaryGuardTest.declaredMethodNames(ref))
                    .as("継承階層を辿れていない＝親クラスに宣言を置くだけで機械ゲートをすり抜けられる")
                    .contains("notify");

            // (3) 変異: extends 節を1つ消すと親が引けなくなる（＝綴りで当てているのではない）。
            assertThat(NotificationTransactionBoundaryGuardTest
                    .superTypeNames(masked, "InheritingWorkerStub"))
                    .contains("BaseNotifierStub");
            assertThat(NotificationTransactionBoundaryGuardTest.superTypeNames(
                    masked.replace("InheritingWorkerStub extends BaseNotifierStub",
                            "InheritingWorkerStub"), "InheritingWorkerStub"))
                    .as("extends 節を消しても親が見つかる＝宣言を読まずに別の理由で当てている")
                    .isEmpty();
        }

        @Test
        @DisplayName("interface 側にしか宣言が無い形も辿れる（implements 節）")
        void インターフェース経由の宣言も辿る() {
            assertThat(NotificationTransactionBoundaryGuardTest.declaredMethodNames(
                    fixtureType("PortImplStub")))
                    .as("implements 節を辿れていない＝インターフェースに宣言を置くだけですり抜けられる")
                    .contains("notify");
        }

        @Test
        @DisplayName("変異: 完全修飾の型宣言を消すと形状11 の検出が消える")
        void 完全修飾フィールドの型宣言を消すと消える() {
            String target = key(FIXTURE, "delegateViaFullyQualifiedField",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.TX_NOTIFY_VIA_DELEGATE);
            assertThat(keysOf(FIXTURE)).contains(target);
            assertThat(keysOfMutated(FIXTURE,
                    "private final com.mannschaft.app.common.architecture.fixtures.notification"
                            + ".FullyQualifiedWorkerStub",
                    "private final Object"))
                    .as("完全修飾の型宣言を Object へ変えても検出が残る＝型ではなく変数名の綴りで当てている")
                    .doesNotContain(target);
        }

        @Test
        @DisplayName("import は authoritative — 索引に無い型を指す import で別候補へフォールバックしない")
        void importが索引に無ければ別候補へ倒れない() {
            // 索引に居る単純名（検体の WorkerStub）と同じ名前を、索引の外の型として import している状況。
            // 旧実装は「import 先が索引に無い」と同一パッケージ／全候補へフォールバックしており、
            // まったく別の型のソースを読んで発火判定する静かな誤解決が起きえた。
            assertThat(NotificationTransactionBoundaryGuardTest.resolveCandidates(
                    "WorkerStub", java.util.Map.of("WorkerStub", "com.example.external.WorkerStub"), ""))
                    .as("import が索引の外の型を指しているのに、索引側の同名型へ黙って倒れている")
                    .isEmpty();
            // 対照: import が索引の型を指していれば、その1件に解決できる。
            assertThat(NotificationTransactionBoundaryGuardTest.resolveCandidates(
                    "WorkerStub",
                    java.util.Map.of("WorkerStub", FIXTURE_PACKAGE + ".NotificationFixtureStubs.WorkerStub"),
                    ""))
                    .as("正しい import まで空にしている＝解決が効きすぎて委譲追跡が死ぬ")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("限界の固定: static import は型解決に使わない（検出できるフリをしない）")
        void staticImportは型解決に使わない() {
            // Javadoc の「名前解決の限界」節に明記した振る舞いを、検体として固定する。
            // static import が持ち込むのはメンバであって型名の束縛ではないため、
            // 単純名 -> 型の解決には使えない。ネスト型の static import も同様に再現できない。
            String src = """
                    package p;
                    import static p.Outer.Inner;
                    import static java.util.Map.entry;
                    import p.other.WorkerStub;
                    class C { }
                    """;
            assertThat(NotificationTransactionBoundaryGuardTest.singleTypeImports(src))
                    .as("static import を型解決に使っている＝Javadoc の限界の記述と実装が乖離している")
                    .doesNotContainKey("Inner")
                    .doesNotContainKey("entry")
                    .containsEntry("WorkerStub", "p.other.WorkerStub");
        }

        @Test
        @DisplayName("限界の固定: 完全修飾のネスト型宣言は拾わない（Map.Entry を巻き込まないための境界）")
        void 完全修飾のネスト型宣言は拾わない() {
            assertThat(NotificationTransactionBoundaryGuardTest.qualifiedDeclarationTypes(
                    "{ private final com.x.y.Worker worker = null; }"))
                    .as("完全修飾のトップレベル型を拾えていない＝形状11 の解決が効かない")
                    .containsEntry("Worker", "com.x.y.Worker");
            assertThat(NotificationTransactionBoundaryGuardTest.qualifiedDeclarationTypes(
                    "{ private final Map.Entry<String, String> entry = null; }"))
                    .as("JDK のネスト型まで拾っている＝無関係な変数が declaredTypes へ大量に入り、"
                            + "判定の当たり方が変わる。ここは意図的に閉じている")
                    .isEmpty();
        }

        @Test
        @DisplayName("declaresApi は委譲判定と同じ三値（候補が割れたら判定不能）")
        void declaresApiは三値である() {
            java.util.Map<String, String> noHints = java.util.Map.of();
            assertThat(NotificationTransactionBoundaryGuardTest.declaresApi(
                    Set.of("HelperStub"), "notify", noHints, FIXTURE_PACKAGE))
                    .as("宣言している型を false と判定している").isEqualTo(Boolean.TRUE);
            assertThat(NotificationTransactionBoundaryGuardTest.declaresApi(
                    Set.of("RepositoryStub"), "notify", noHints, FIXTURE_PACKAGE))
                    .as("宣言していない型を true と判定している").isEqualTo(Boolean.FALSE);
            assertThat(NotificationTransactionBoundaryGuardTest.declaresApi(
                    Set.of("HelperStub", "RepositoryStub"), "notify", noHints, FIXTURE_PACKAGE))
                    .as("候補が割れているのに片方へ倒している＝委譲判定と扱いが非対称")
                    .isNull();
        }

        @Test
        @DisplayName("変異: 委譲先入口の注釈を変えると導出される ImpactClass が反転する（任務4）")
        void 影響区分はソースの注釈から導出している() {
            NotificationTransactionBoundaryGuardTest.TypeRef worker = fixtureType("WorkerStub");
            assertThat(NotificationTransactionBoundaryGuardTest.delegateEntryImpact(worker, "send"))
                    .as("無印（＝呼び出し元のTXに参加する）を ROLLBACK_COUPLED と判定できていない")
                    .isEqualTo(NotificationTransactionBoundaryGuardTest.ImpactClass.ROLLBACK_COUPLED);

            NotificationTransactionBoundaryGuardTest.TypeRef executor =
                    NotificationTransactionBoundaryGuardTest.typeIndex()
                            .getOrDefault("ErrorReportAsyncExecutor", List.of()).stream().findFirst()
                            .orElseThrow(() -> new IllegalStateException("本番の @Async 委譲先が索引に無い"));
            assertThat(NotificationTransactionBoundaryGuardTest.delegateEntryImpact(
                    executor, "recordBackendException"))
                    .as("@Async(\"event-pool\") で別スレッド・別TXへ逃げる形を ROLLBACK_COUPLED と誤分類している。"
                            + "分類を誤ると是正の手当てを誤る（TXから切り離す／AFTER_COMMIT へ移す は別物）")
                    .isEqualTo(NotificationTransactionBoundaryGuardTest.ImpactClass.ORDERING_ONLY);
        }

        @Test
        @DisplayName("変異: @Transactional を外すと形状7 の DIRECT_RUNNER_CALL 分離が元に戻る")
        void オーバーロード分離はTransactionalの宣言で効いている() {
            String target = key(FIXTURE, "handle",
                    NotificationTransactionBoundaryGuardTest.ViolationKind.DIRECT_RUNNER_CALL);
            assertThat(keysOf(FIXTURE)).contains(target);
            assertThat(keysOfMutated(FIXTURE,
                    "@Transactional\n    public void handle(Long userId)",
                    "public void handle(Long userId)"))
                    .as("@Transactional を外しても DIRECT_RUNNER_CALL が残る＝別の理由で当たっている。"
                            + "分離条件は『自分で TX を開いていること』であり、無印なら畳み込みは残るのが正しい")
                    .doesNotContain(target);
        }
    }

    @Nested
    @DisplayName("偽陽性: 通知ではないのに綴りが一致する形を違反にしない")
    class 偽陽性 {

        @Test
        @DisplayName("ビルダーのセッタ連鎖・DTO アクセサ・Object#notifyAll を通知とみなさない")
        void 通知でない綴り一致を挙げない() {
            Set<String> keys = keysOf("GuardBlindSpotFixture");
            assertThat(keys)
                    .as("ビルダーの .notifyOnRsvp(...) は通知発火ではない。"
                            + "初版の番人は CareLinkService#toResponse / #toOverrideResponse を"
                            + "この形で baseline に凍結していた")
                    .noneMatch(k -> k.contains("#builderSettersAreNotNotifications "));
            assertThat(keys)
                    .as("record アクセサ request.notifyMembers() は通知発火ではない。"
                            + "初版の番人は TimetableChangeService#createChange / #updateChange"
                            + "（業務TX内は publishEvent だけという本戦役の正規形そのもの）を違反として数えていた")
                    .noneMatch(k -> k.contains("#accessorsAreNotNotifications "));
            assertThat(keys)
                    .as("Object#notifyAll() は引数ゼロであり通知発火ではない")
                    .noneMatch(k -> k.contains("#objectNotifyAllIsNotANotification "));
            assertThat(keys)
                    .as("createNotificationCreditCheckoutSession は Stripe の決済 API であり通知ではない。"
                            + "初版の番人は createNotification[A-Za-z]* と開いていたため"
                            + "NotificationCreditCheckoutService#createCheckout を凍結していた")
                    .noneMatch(k -> k.contains("#createNotificationPrefixedButNotANotification "));
        }

        @Test
        @DisplayName("判定は綴りの除外リストではなく形（レシーバ・引数）で行う")
        void 形で判定している() {
            assertThat(NotificationTransactionBoundaryGuardTest.firesNotification(
                    "{ return Foo.builder().id(x).notifyOnRsvp(e.getNotifyOnRsvp()).build(); }"))
                    .as("チェーンの途中のセッタを通知とみなしている")
                    .isFalse();
            assertThat(NotificationTransactionBoundaryGuardTest.firesNotification(
                    "{ return req.notifyMembers() == null; }"))
                    .as("引数ゼロのアクセサを通知とみなしている")
                    .isFalse();
            assertThat(NotificationTransactionBoundaryGuardTest.firesNotification(
                    "{ notificationHelper.notify(userId, \"T\", \"s\", \"b\"); }"))
                    .as("本物の通知発火を取りこぼしている＝絞り込みが効きすぎている")
                    .isTrue();
            assertThat(NotificationTransactionBoundaryGuardTest.firesNotification(
                    "{ notificationHelper\n        .notify(userId, \"T\"); }"))
                    .as("レシーバと . の間に改行があるだけで取りこぼしている")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("許可入口の厳密性")
    class 許可入口 {

        @Test
        @DisplayName("AFTER_COMMIT を含むだけの別の phase 値を許可しない")
        void 部分一致のphaseを許可しない() {
            assertThat(NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(
                    "@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)\n"))
                    .as("正規の AFTER_COMMIT を弾いている")
                    .isTrue();
            assertThat(NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(
                    "@TransactionalEventListener\n"))
                    .as("phase 省略時の既定は AFTER_COMMIT なので許可する")
                    .isTrue();
            assertThat(NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(
                    "@TransactionalEventListener(phase = CustomPhase.AFTER_COMMIT_POLICY)\n"))
                    .as("文字列 AFTER_COMMIT を含むだけの別の値を許可している。"
                            + "ホワイトリストはここ1箇所であり、緩めば契約全体が緩む")
                    .isFalse();
            assertThat(NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(
                    "@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)\n"))
                    .as("BEFORE_COMMIT を許可している")
                    .isFalse();
        }

        @Test
        @DisplayName("完全修飾の @TransactionalEventListener も許可入口として認識する")
        void 完全修飾の許可入口() {
            assertThat(NotificationTransactionBoundaryGuardTest.isAllowedEntryPoint(
                    "@org.springframework.transaction.event.TransactionalEventListener\n"))
                    .as("完全修飾で書かれた正規形を違反側へ落としてしまう")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("検体そのものが空振りしていない（fixture から実際に違反が出ている）")
    void 検体が空振りしていない() {
        List<String> fixtures = List.of("TxNotificationFixture", "AsyncSelfInvocationFixture",
                "AsyncSelfInvocationBatchFixture", "PlainEventListenerFixture", "GuardBlindSpotFixture");
        for (String f : fixtures) {
            assertThat(keysOf(f))
                    .as("検体 %s から違反が1件も出ていない＝走査が空振りしており、"
                            + "以降の contains 判定がすべて無意味になる", f)
                    .isNotEmpty();
        }
    }
    /**
     * 台帳ゲート（{@code validateLedger}）と検出力ゲートの<b>変異テスト</b>。
     *
     * <p>このリポジトリでは過去に「門番が門に辿り着く前に死ぬ」「一致検証だけは全壊で偽 green」が
     * 繰り返し起きている。<b>新しく足したゲートが本当に何かを測っているか</b>を、
     * 実際に壊して fail することで確かめる。
     */
    @Nested
    @DisplayName("台帳ゲート・検出力ゲートの変異テスト")
    class 台帳ゲートの変異 {

        private List<String> realLines() {
            return NotificationTransactionBoundaryGuardTest.readFreezeLines();
        }

        private Set<String> realFoundKeys() {
            return NotificationTransactionBoundaryGuardTest.mainScan().violations()
                    .stream()
                    .map(Violation::key)
                    .collect(Collectors.toCollection(java.util.TreeSet::new));
        }

        /** 凍結エントリのキー（分類部分を除いたもの）を1つ返す。 */
        private String anyActiveKey(List<String> lines) {
            return NotificationTransactionBoundaryGuardTest.entryLines(lines).stream()
                    .map(NotificationTransactionBoundaryGuardTest::entryKey)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("凍結エントリが1件も無い"));
        }

        /** 分類（{@code | ROLLBACK_COUPLED} 等）が付いている行を1つ返す。 */
        private String anyClassifiedLine(List<String> lines) {
            return NotificationTransactionBoundaryGuardTest.entryLines(lines).stream()
                    .filter(l -> NotificationTransactionBoundaryGuardTest.entryClassification(l) != null)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("分類済みエントリが1件も無い"));
        }

        @Test
        @DisplayName("変異: 分類を1件外すと fail する（任務5 の分類が黙って消えない）")
        void 分類を外すと落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            String classified = anyClassifiedLine(lines);
            String stripped = NotificationTransactionBoundaryGuardTest.entryKey(classified);
            assertThat(lines.removeIf(l -> l.strip().equals(classified))).isTrue();
            lines.add(stripped);

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, realFoundKeys()))
                    .as("分類をそっと外しても台帳ゲートが通ってしまう（対象 %s）", classified)
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("分類済みエントリ数が下限を割っている"));
        }

        @Test
        @DisplayName("変異: 分類に ImpactClass 以外の語を書くと fail する")
        void 出鱈目な分類は落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            String classified = anyClassifiedLine(lines);
            String key = NotificationTransactionBoundaryGuardTest.entryKey(classified);
            assertThat(lines.removeIf(l -> l.strip().equals(classified))).isTrue();
            lines.add(key + " | ATODEYARU");

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, realFoundKeys()))
                    .as("台帳の分類欄に何を書いても通ってしまう＝機械可読ではない")
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("ImpactClass の名前ではない"));
        }

        @Test
        @DisplayName("分類を持つ行のキーが baseline のキーとして正しく読めている")
        void 分類付きの行もキーとして読める() {
            String classified = anyClassifiedLine(realLines());
            String key = NotificationTransactionBoundaryGuardTest.entryKey(classified);
            assertThat(NotificationTransactionBoundaryGuardTest.readFreezeList())
                    .as("分類を付けた行が baseline のキー集合から落ちている＝"
                            + "分類の導入そのものが凍結を無効化している（静かな緩和）")
                    .contains(key);
            assertThat(realFoundKeys())
                    .as("分類を付けたキーが本番の検出結果に存在しない＝キーの切り出しが壊れている")
                    .contains(key);
        }

        @Test
        @DisplayName("現状の凍結ファイルは台帳検証を通る（ゲートが常に赤ではない）")
        void 現状は緑() {
            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(realLines(), realFoundKeys()))
                    .as("ゲートが常に赤なら、以降の変異テストは何も証明しない")
                    .isEmpty();
        }

        @Test
        @DisplayName("変異: 判定不能が台帳に載っていないと fail する（0件でも仕組みは動いている）")
        void 台帳に無い判定不能は落ちる() {
            List<String> lines = realLines();
            // 現状は0件。偽の判定不能を1件流し込み、台帳ゲートがそれを是正対象として要求することを確かめる。
            List<String> fabricated = List.of(
                    "com.example.Foo#bar : レシーバ型が一意に決まらず候補ごとに判定が割れる。候補=A/B");
            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(
                    lines, realFoundKeys(), fabricated, java.util.Map.of()))
                    .as("判定不能が発生しても台帳ゲートが通ってしまう＝曖昧性が是正対象の一覧から消える。"
                            + "この戦役では『一覧が実態より小さい』が既に5回起きている")
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("判定不能")
                            .contains("台帳に載っていない"));
        }

        @Test
        @DisplayName("変異: 台帳の AMBIGUOUS 行が現在は曖昧でないなら fail する（古い行が残らない）")
        void 解消済みの判定不能行は落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            lines.add("# AMBIGUOUS: com.example.Foo#bar : もう起きていない古い理由をここに書いておく");
            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(
                    lines, realFoundKeys(), List.of(), java.util.Map.of()))
                    .as("解消済みの判定不能行が残っていても通ってしまう＝台帳が実態から乖離する")
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("現在の判定では曖昧ではない"));
        }

        @Test
        @DisplayName("変異: 分類をソース由来の区分と食い違わせると fail する（任務4）")
        void 分類がソースと食い違うと落ちる() {
            List<String> lines = realLines();
            String classified = anyClassifiedLine(lines);
            String key = NotificationTransactionBoundaryGuardTest.entryKey(classified);
            String written = NotificationTransactionBoundaryGuardTest.entryClassification(classified);
            NotificationTransactionBoundaryGuardTest.ImpactClass opposite =
                    NotificationTransactionBoundaryGuardTest.ImpactClass.ROLLBACK_COUPLED.name().equals(written)
                            ? NotificationTransactionBoundaryGuardTest.ImpactClass.ORDERING_ONLY
                            : NotificationTransactionBoundaryGuardTest.ImpactClass.ROLLBACK_COUPLED;

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(
                    lines, realFoundKeys(), List.of(), java.util.Map.of(key, opposite)))
                    .as("台帳の分類が実ソースの注釈と食い違っていても通ってしまう＝"
                            + "分類は綴りチェックだけで、是正の手当てを誤る経路が残っている（対象 %s）", key)
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("ソースの注釈から導出した区分と一致しない"));
        }

        @Test
        @DisplayName("本番の分類9件がすべてソース由来の区分と一致している（実測での裏取り）")
        void 本番の分類はソースと一致している() {
            java.util.Map<String, NotificationTransactionBoundaryGuardTest.ImpactClass> derived =
                    NotificationTransactionBoundaryGuardTest.derivedImpacts(
                            NotificationTransactionBoundaryGuardTest.mainScan().violations());
            assertThat(derived)
                    .as("委譲違反の区分が1件も導出できていない＝任務4 の機械照合が何も測っていない")
                    .isNotEmpty();
            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(
                    realLines(), realFoundKeys(), List.of(), derived))
                    .as("台帳の分類とソース由来の区分が食い違っている")
                    .isEmpty();
        }

        @Test
        @DisplayName("変異: baseline から理由なく1行消すと fail する")
        void 理由なき削除は落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            String victim = anyActiveKey(lines);
            lines.removeIf(s -> s.strip().equals(victim));

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, realFoundKeys()))
                    .as("凍結エントリを1行そっと消しても台帳ゲートが通ってしまう（削除 %s）", victim)
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("台帳の総数が下限を割っている"));
        }

        @Test
        @DisplayName("変異: 消した行に REMOVED を足しても、実際にはまだ検出されるなら fail する")
        void 嘘の削除理由は落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            String victim = anyActiveKey(lines);
            lines.removeIf(s -> s.strip().equals(victim));
            // 「偽陽性だったので消した」と主張する。総数は保たれるので (d) は通るが、
            // (b) の実測裏取り（そのキーは今も検出されている）で落ちなければならない。
            lines.add("# REMOVED: " + victim + " : 偽陽性だったと主張するが実際にはまだ検出される行");

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, realFoundKeys()))
                    .as("削除理由さえ書けば検出中のキーを baseline から消せてしまう（削除 %s）", victim)
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("現在の判定では検出されている"));
        }

        @Test
        @DisplayName("変異: REMOVED の理由が実質空だと fail する")
        void 空の削除理由は落ちる() {
            List<String> lines = new ArrayList<>(realLines());
            String victim = anyActiveKey(lines);
            lines.removeIf(s -> s.strip().equals(victim));
            lines.add("# REMOVED: " + victim + " : 偽陽性");

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, realFoundKeys()))
                    .as("三文字の言い訳で baseline から行を消せてしまう")
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("削除理由が短すぎる"));
        }

        @Test
        @DisplayName("変異: 既存の REMOVED 行が再び検出されるようになったら fail する")
        void 既存のREMOVEDが再検出されたら落ちる() {
            List<String> lines = realLines();
            List<NotificationTransactionBoundaryGuardTest.RemovalEntry> removed =
                    NotificationTransactionBoundaryGuardTest.parseRemovalLedger(lines);
            assertThat(removed).as("REMOVED 行が1件も無い＝この変異テストが何も測っていない").isNotEmpty();

            // 「判定を戻して偽陽性がまた出るようになった」状態を、検出結果側を汚して模す。
            Set<String> polluted = new java.util.TreeSet<>(realFoundKeys());
            polluted.add(removed.get(0).key());

            assertThat(NotificationTransactionBoundaryGuardTest.validateLedger(lines, polluted))
                    .as("REMOVED と主張したキーが再び検出されても台帳ゲートが通ってしまう")
                    .isNotEmpty()
                    .anySatisfy(msg -> assertThat(msg).contains("現在の判定では検出されている"));
        }

        @Test
        @DisplayName("変異: 判定（構造条件）を厳しくすると検出力の下限を割る")
        void 判定を緩めると検出力が落ちる() {
            NotificationTransactionBoundaryGuardTest.DetectionPower actual =
                    NotificationTransactionBoundaryGuardTest.measureDetectionPower(
                            NotificationTransactionBoundaryGuardTest.mainSourceRoot());
            assertThat(actual.structuralNotifyCalls())
                    .as("現行の実測が凍結下限を割っている＝下限の設定ミス")
                    .isGreaterThanOrEqualTo(NotificationTransactionBoundaryGuardTest.STRUCTURAL_NOTIFY_CALLS_MIN);

            // 攻撃の模擬: notifyCallOffsets に「引数を2つ以上取ること」という条件を足して
            // 通知発火点を減らす（＝違反を静かに消す変異）。本番の判定は変えずに、
            // 同じ本番コーパスへ変異版の条件を当てて件数を数える。
            long mutated = 0;
            for (Path file : NotificationTransactionBoundaryGuardTest.javaFiles(
                    NotificationTransactionBoundaryGuardTest.mainSourceRoot())) {
                String src = JavaSourceScanningUtils.maskCommentsAndLiterals(
                        NotificationTransactionBoundaryGuardTest.read(file));
                for (String body : NotificationTransactionBoundaryGuardTest.parseMethods(src).stream()
                        .map(NotificationTransactionBoundaryGuardTest.MethodBlock::body).toList()) {
                    for (int off : NotificationTransactionBoundaryGuardTest.notifyCallOffsets(body)) {
                        int open = body.indexOf('(', off);
                        int close = open < 0 ? -1 : body.indexOf(')', open);
                        if (close > 0 && body.substring(open + 1, close).contains(",")) {
                            mutated++;
                        }
                    }
                }
            }
            assertThat(mutated)
                    .as("判定を厳しくしても検出力の下限（%s）を割らない＝下限が緩すぎて何も守っていない",
                            NotificationTransactionBoundaryGuardTest.STRUCTURAL_NOTIFY_CALLS_MIN)
                    .isLessThan(NotificationTransactionBoundaryGuardTest.STRUCTURAL_NOTIFY_CALLS_MIN);
        }
    }

}
