package com.mannschaft.app.common.architecture;

import com.mannschaft.app.analytics.service.AnalyticsBackfillRunner;
import com.mannschaft.app.analytics.service.AnalyticsBackfillService;
import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import com.mannschaft.app.errorreport.service.ErrorReportAiAnalysisAsyncRunner;
import com.mannschaft.app.errorreport.service.ErrorReportAiAnalysisService;
import com.mannschaft.app.notification.credit.batch.NotificationCreditExpiryBatch;
import com.mannschaft.app.notification.credit.batch.NotificationCreditExpiryRunner;
import com.mannschaft.app.notification.credit.service.NotificationCreditAlertSender;
import com.mannschaft.app.todo.event.MilestoneNotificationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2990 L4 — ASYNC 系是正の宣言レベル番人。
 *
 * <p>本テストは「是正が宣言として維持されているか」を機械的に確かめる。実行時のふるまい
 * （項目TXの独立性）は {@code NotificationCreditExpiryIsolationIT} が実DBで検証する。</p>
 *
 * <h2>なぜ宣言の検証に意味があるのか</h2>
 * <p>{@code @Async} / {@code @Transactional} は Spring のプロキシを経た呼び出しでのみ有効になる。
 * 「{@code @Async} が付いているのに非同期でない」「{@code @TransactionalEventListener} のつもりが
 * 素の {@code @EventListener}」といった欠陥は、モックを使った単体テストでは<b>まず現れない</b>
 * （モックが実行経路を消してしまうため）。実際 {@code AnalyticsBackfillServiceTest} は
 * 是正前後どちらでも緑であり、183日ぶんの処理が同期実行されていた欠陥を検出できていなかった。
 * そのため宣言そのものを検体として固定する。</p>
 */
@DisplayName("Issue #2990 L4: 通知まわり @Async 境界の宣言")
class NotificationAsyncBoundaryDeclarationTest {

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        type.getSimpleName() + "#" + name + " が見当たらない"));
    }

    private static void assertAsyncPool(Class<?> type, String methodName, String expectedPool) {
        Async async = method(type, methodName).getAnnotation(Async.class);
        assertThat(async)
                .as("%s#%s に @Async が付いていること", type.getSimpleName(), methodName)
                .isNotNull();
        assertThat(async.value())
                .as("%s#%s の executor は %s であること（無指定だと @Primary の event-pool へ暗黙に載る）",
                        type.getSimpleName(), methodName, expectedPool)
                .isEqualTo(expectedPool);
    }

    /** 対象クラスが {@code @Async} メソッドを一切宣言していないこと（自己呼び出しの再発防止）。 */
    private static void assertDeclaresNoAsyncMethod(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Async.class))
                .map(Method::getName)
                .toList())
                .as("%s は @Async メソッドを宣言しないこと"
                        + "（同一クラス内から呼ぶとプロキシを経ず @Async が失効するため、"
                        + "非同期実行は別 Bean に切り出す）", type.getSimpleName())
                .isEmpty();
    }

    @Nested
    @DisplayName("ASYNC_WITHOUT_EXECUTOR 6件: executor が明示されている")
    class ExecutorExplicit {

        @Test
        @DisplayName("重い処理は job-pool（通知配送用の event-pool を詰まらせない）")
        void 重い処理はjobPool() {
            assertAsyncPool(AnalyticsBackfillRunner.class, "executeAsync", "job-pool");
            assertAsyncPool(DigestAsyncExecutor.class, "generateAiDigestAsync", "job-pool");
        }

        @Test
        @DisplayName("通知送信は event-pool")
        void 通知送信はEventPool() {
            assertAsyncPool(NotificationCreditAlertSender.class, "sendNegativeBalanceAlert", "event-pool");
            assertAsyncPool(NotificationCreditAlertSender.class, "sendExpiryAlert", "event-pool");
            assertAsyncPool(NotificationCreditAlertSender.class, "sendCreditExpiredAlert", "event-pool");
            assertAsyncPool(MilestoneNotificationListener.class, "onMilestoneUnlocked", "event-pool");
            assertAsyncPool(ErrorReportAiAnalysisAsyncRunner.class, "analyzeAsync", "event-pool");
        }
    }

    @Nested
    @DisplayName("ASYNC_SELF_INVOCATION 5件: 非同期実行が別 Bean へ切り出されている")
    class NoSelfInvocation {

        @Test
        @DisplayName("受付サービス・バッチ本体は @Async メソッドを持たない")
        void 非同期メソッドを自クラスに持たない() {
            assertDeclaresNoAsyncMethod(AnalyticsBackfillService.class);
            assertDeclaresNoAsyncMethod(ErrorReportAiAnalysisService.class);
            assertDeclaresNoAsyncMethod(NotificationCreditExpiryBatch.class);
        }
    }

    @Nested
    @DisplayName("PLAIN_EVENT_LISTENER: マイルストーン通知は AFTER_COMMIT")
    class MilestoneListenerPhase {

        @Test
        @DisplayName("素の @EventListener ではなく @TransactionalEventListener(AFTER_COMMIT)")
        void afterCommitで発火する() {
            Method m = method(MilestoneNotificationListener.class, "onMilestoneUnlocked");

            assertThat(m.getAnnotation(EventListener.class))
                    .as("素の @EventListener は業務コミット前に発火するため使わないこと")
                    .isNull();

            TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
            assertThat(tel).as("@TransactionalEventListener が付いていること").isNotNull();
            assertThat(tel.phase())
                    .as("業務トランザクションのコミット後に発火すること")
                    .isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    @Nested
    @DisplayName("有効期限バッチ: 非トランザクションのオーケストレータ + 項目 REQUIRES_NEW")
    class ExpiryBatchBoundary {

        @Test
        @DisplayName("runBatch は @Transactional を持たない（全体を1TXで覆わない）")
        void runBatchは非トランザクション() {
            assertThat(method(NotificationCreditExpiryBatch.class, "runBatch")
                    .getAnnotation(Transactional.class))
                    .as("バッチ全体を単一 @Transactional で覆うと、通知の失敗で"
                            + "残高の失効分の差し引きごと巻き戻る（Issue #2990 の本体）")
                    .isNull();
            assertThat(NotificationCreditExpiryBatch.class.getAnnotation(Transactional.class))
                    .as("クラスレベルの @Transactional も付けないこと")
                    .isNull();
        }

        @Test
        @DisplayName("項目実行は REQUIRES_NEW の独立トランザクション")
        void 項目はRequiresNew() {
            for (String name : new String[]{"markAlertSent", "expireOne"}) {
                Transactional tx = method(NotificationCreditExpiryRunner.class, name)
                        .getAnnotation(Transactional.class);
                assertThat(tx).as("%s に @Transactional があること", name).isNotNull();
                assertThat(tx.propagation())
                        .as("%s は REQUIRES_NEW（1項目=1独立TX）であること", name)
                        .isEqualTo(Propagation.REQUIRES_NEW);
            }
        }
    }
}
