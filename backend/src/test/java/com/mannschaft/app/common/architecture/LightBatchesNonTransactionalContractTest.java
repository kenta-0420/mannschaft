package com.mannschaft.app.common.architecture;

import com.mannschaft.app.actionmemo.service.ActionMemoReminderBatchService;
import com.mannschaft.app.reflection.service.ReflectionSpacedReminderService;
import com.mannschaft.app.repairplan.batch.TeamMemberTermReminderBatch;
import com.mannschaft.app.todo.batch.TodoDueReminderBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2997（CMP-260827-1152）G8 軽量バッチ群 — 非トランザクションのオーケストレータ契約。
 *
 * <p>業務の書き込みを持たない通知バッチ（actionmemo / todo / repairplan）と、項目ごとの状態遷移を
 * リポジトリ単位のTXで確定する reflection のスケジュール入口は、メソッド全体を 1 つの
 * {@code @Transactional} で包んではならない。包むと、通知の永続化失敗（DB 例外）が rollback-only を残し、
 * try/catch で握っても他の受信者の通知行・状態遷移まで巻き込む（原則5）。</p>
 *
 * <p>是正前にこのテストが落ちる理由: 対象メソッドに {@code @Transactional} が付いていた
 * （actionmemo / repairplan の {@code execute()}、todo の {@code sendDueTomorrowReminders} /
 * {@code sendOverdueReminders}、reflection の {@code processDueReminders}）。
 * 設計書の前提は「メソッド全体を TX に入れる」ではなく「項目単位で独立 TX」である。</p>
 */
@DisplayName("Issue #2997 G8: 軽量バッチ群は非トランザクションのオーケストレータ")
class LightBatchesNonTransactionalContractTest {

    private static void assertNotTransactional(Class<?> type, String methodName, Class<?>... params)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, params);
        assertThat(AnnotatedElementUtils.hasAnnotation(method, Transactional.class))
                .as("%s#%s に @Transactional が付いていないこと", type.getSimpleName(), methodName)
                .isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(type, Transactional.class))
                .as("%s にクラスレベル @Transactional が付いていないこと", type.getSimpleName())
                .isFalse();
    }

    @Test
    @DisplayName("ActionMemoReminderBatchService#execute は非TX（通知失敗が他ユーザーを巻き込まない）")
    void actionMemoExecuteIsNotTransactional() throws Exception {
        assertNotTransactional(ActionMemoReminderBatchService.class, "execute");
    }

    @Test
    @DisplayName("TeamMemberTermReminderBatch#execute は非TX（通知失敗が他の理事を巻き込まない）")
    void teamMemberTermExecuteIsNotTransactional() throws Exception {
        assertNotTransactional(TeamMemberTermReminderBatch.class, "execute");
    }

    @Test
    @DisplayName("TodoDueReminderBatch の run / sendDueTomorrowReminders / sendOverdueReminders は非TX")
    void todoBatchIsNotTransactional() throws Exception {
        assertNotTransactional(TodoDueReminderBatch.class, "run");
        assertNotTransactional(TodoDueReminderBatch.class, "sendDueTomorrowReminders");
        assertNotTransactional(TodoDueReminderBatch.class, "sendOverdueReminders");
    }

    @Test
    @DisplayName("ReflectionSpacedReminderService#processDueReminders は非TX（項目ごとに独立して確定）")
    void reflectionProcessDueRemindersIsNotTransactional() throws Exception {
        assertNotTransactional(ReflectionSpacedReminderService.class, "processDueReminders");
    }
}
