package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseCoordinator;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 Codex 再検分 P2-3: 遅延記録（PENDING）の回収が、日次締め cron の有効化
 * （{@code feature.shift-budget.monthly-close-cron-enabled}、既定 false）に依存しないこと。
 *
 * <p>手動締め運用（既定）でも回収経路が定期実行される必要がある。{@code @ConditionalOnProperty} で
 * 既定は生成されない Bean からしか {@link MonthlyShiftBudgetCloseCoordinator#recoverDeferrals()} を
 * 呼んでいなければ、PENDING は回収されず残り続ける。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("CMP-260909-2152 遅延記録の回収は既定設定でも定期実行される")
class ShiftBudgetDeferralRecoveryScheduleTest {

    @Test
    @DisplayName("P2-3: 条件付き Bean でない @Scheduled 入口のどれかが recoverDeferrals() を呼ぶ")
    void 遅延回収は条件なしの定期入口から呼ばれる() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app.shiftbudget");

        List<String> unconditionalCallers = new ArrayList<>();
        for (JavaClass c : classes) {
            if (c.isAnnotatedWith(ConditionalOnProperty.class)) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                if (!m.isAnnotatedWith(Scheduled.class)) {
                    continue;
                }
                boolean callsRecover = m.getMethodCallsFromSelf().stream().anyMatch(call ->
                        call.getTargetOwner().isEquivalentTo(MonthlyShiftBudgetCloseCoordinator.class)
                                && call.getName().equals("recoverDeferrals"));
                if (callsRecover) {
                    unconditionalCallers.add(c.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertThat(unconditionalCallers)
                .as("既定設定（cron・再試行バッチとも無効）でも遅延記録を回収する定期入口が要る")
                .isNotEmpty();
    }
}
