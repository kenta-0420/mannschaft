package com.mannschaft.app.digest;

import com.mannschaft.app.digest.event.DigestGenerationNotificationEvent;
import com.mannschaft.app.digest.event.DigestGenerationNotificationListener;
import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import com.mannschaft.app.digest.service.DigestGenerationRunner;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.listener.TeamSlotNoteNotifyListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2997 G7-a: digest / timetable の付随通知の境界宣言そのものを固定する。
 *
 * <p>モックを使った単体テストでは「{@code @Async} が付いているのに executor が違う」
 * 「{@code AFTER_COMMIT} のつもりが素の {@code @EventListener}」「executor に {@code @Transactional} が残っている」といった
 * 欠陥が現れない（モックが実行経路を消す）ため、宣言を検体にする。</p>
 */
@DisplayName("Issue #2997 G7-a: digest / timetable 付随通知の境界宣言")
class G7aListenerDeclarationTest {

    private static Method declared(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "#" + name + " が見当たらない"));
    }

    private static void assertAfterCommitAsyncEventPool(Method m) {
        TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).as("%s に @TransactionalEventListener", m.getName()).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        Async async = m.getAnnotation(Async.class);
        assertThat(async).as("%s に @Async", m.getName()).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");
        assertThat(m.getAnnotation(Transactional.class))
                .as("%s 自身はトランザクションを持たない（sendOne が受信者ごとの REQUIRES_NEW）", m.getName())
                .isNull();
    }

    @Test
    @DisplayName("digest 通知リスナーは AFTER_COMMIT + @Async(event-pool) で、自身は TX を持たない")
    void digestリスナー() {
        assertAfterCommitAsyncEventPool(
                declared(DigestGenerationNotificationListener.class, "onDigestGenerationNotification"));
        assertThat(DigestGenerationNotificationListener.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    @DisplayName("timetable 通知リスナーは AFTER_COMMIT + @Async(event-pool) で、同期 @EventListener でも readOnly TX でもない")
    void timetableリスナー() {
        Method m = declared(TeamSlotNoteNotifyListener.class, "onSlotNoteUpdated");
        assertAfterCommitAsyncEventPool(m);
        assertThat(m.getAnnotation(org.springframework.context.event.EventListener.class)).isNull();
        assertThat(TeamSlotNoteNotifyListener.class.getAnnotation(Transactional.class)).isNull();
        assertThat(m.getParameterTypes()).containsExactly(TimetableSlotNoteUpdatedEvent.class);
    }

    @Test
    @DisplayName("DigestAsyncExecutor は TX を持たず（AI 呼び出しを TX に含めない）、job-pool のまま")
    void executorはTXを持たない() {
        assertThat(DigestAsyncExecutor.class.getAnnotation(Transactional.class)).isNull();
        Method m = declared(DigestAsyncExecutor.class, "generateAiDigestAsync");
        assertThat(m.getAnnotation(Transactional.class)).isNull();
        assertThat(m.getAnnotation(Async.class).value()).isEqualTo("job-pool");
    }

    @Test
    @DisplayName("DigestGenerationRunner の確定は REQUIRES_NEW")
    void runnerはREQUIRES_NEW() {
        for (String name : new String[]{"complete", "fail"}) {
            Transactional tx = declared(DigestGenerationRunner.class, name).getAnnotation(Transactional.class);
            assertThat(tx).as("%s に @Transactional", name).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    @DisplayName("通知配送要求イベントは ID と種別のみを運ぶ record")
    void イベントはrecord() {
        assertThat(DigestGenerationNotificationEvent.class.isRecord()).isTrue();
    }
}
