package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 PR-α の番人（凍結ストアを書き換えない。FreezingArchRule は使わない）。
 *
 * <p>試練133（AC58α 凍結差分）・142b（AC69 Guard 先頭）・143b（AC70 Abort・判定行の経路）・147（AC76α 登録表）。</p>
 *
 * <p>実装前の red 理由: 凍結ストアに旧 {@code MonthlyShiftBudgetCloseService} の54行が残る、Guard が段取り役の先頭に無い
 * （骨組みは UnsupportedOperationException を直接投げる）、投稿判定の Repository・Entity・α の Facade が無い、
 * 登録表に CMP2152 の行が無く認可シグナルに {@code ShiftBudgetScopeAccessPolicy} が無い。</p>
 */
@DisplayName("CMP-260909-2152 α 締め期間の番人（ArchUnit・凍結ストア不変）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ShiftBudgetClosePeriodArchTest {

    private static final String SB = "com.mannschaft.app.shiftbudget.";
    private static final String GUARD = "com.mannschaft.app.common.transaction.NonTransactionalEntryGuard";
    private static final String COORDINATOR = SB + "service.MonthlyShiftBudgetCloseCoordinator";
    private static final String STAGE = SB + "service.ShiftBudgetCloseStageService";
    private static final String POSTING_REPOSITORY = "com.mannschaft.app.budget.repository.BudgetShiftClosePostingRepository";
    private static final String POSTING_ENTITY = "com.mannschaft.app.budget.entity.BudgetShiftClosePostingEntity";
    private static final String POLICY = SB + "service.ShiftBudgetScopeAccessPolicy";
    private static final Path STORE = Paths.get("src", "test", "resources", "archunit_store");

    private final JavaClasses classes = ProductionClasses.get();

    private JavaClass require(String fqn) {
        assertThat(classes.contain(fqn)).as("%s が存在すること", fqn).isTrue();
        return classes.get(fqn);
    }

    // ───────────────────────── 試練133 AC58α ─────────────────────────

    private List<String> storeLinesContaining(String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(STORE)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.contains(needle)) {
                        hits.add(f.getFileName() + ": " + line);
                    }
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("試練133a AC58α 凍結ストアから旧 MonthlyShiftBudgetCloseService の54行（D-3T 21・D-3 7・D-5 7・D-1 18・D-1 API 境界 1）が消えている")
    void 試練133a_旧締めサービスの凍結行が削除されている() throws IOException {
        assertThat(Files.isDirectory(STORE)).as("凍結ストア %s", STORE.toAbsolutePath()).isTrue();
        assertThat(storeLinesContaining("MonthlyShiftBudgetCloseService")).isEmpty();
    }

    @Test
    @DisplayName("試練133b AC58α α の新クラスは凍結ストアに新規登録されない（新凍結例外0）")
    void 試練133b_新クラスは凍結ストアに無い() throws IOException {
        for (String name : List.of("MonthlyShiftBudgetCloseCoordinator", "ShiftBudgetCloseStageService",
                "ShiftBudgetMonthlyJournalService", "ShiftBudgetClosingSettingService", "ShiftBudgetClosePeriodResolver",
                "ShiftBudgetRecordDeferralService", "MonthlyShiftBudgetCloseFacade", "ShiftBudgetClosingSettingFacade",
                "ShiftBudgetClosePeriodFacade", "BudgetShiftClosePosting")) {
            assertThat(storeLinesContaining(name)).as("%s の凍結行", name).isEmpty();
        }
    }

    // ───────────────────────── 試練142b AC69 ─────────────────────────

    @Test
    @DisplayName("試練142b AC69 段取り役・Facade・遅延再実行入口の全公開メソッドは、最初の呼出が NonTransactionalEntryGuard.assertNoActiveTransaction（@Transactional なし）")
    void 試練142b_Guardが先頭() {
        List<String> targets = List.of(COORDINATOR, SB + "service.MonthlyShiftBudgetCloseFacade",
                SB + "service.ShiftBudgetClosingSettingFacade", SB + "service.ShiftBudgetClosePeriodFacade");
        List<String> violations = new ArrayList<>();
        for (String fqn : targets) {
            JavaClass c = require(fqn);
            assertThat(c.isAnnotatedWith("org.springframework.transaction.annotation.Transactional"))
                    .as("%s にクラス @Transactional が無い", fqn).isFalse();
            for (JavaMethod m : c.getMethods()) {
                if (!m.getModifiers().contains(JavaModifier.PUBLIC) || m.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                if (m.isAnnotatedWith("org.springframework.transaction.annotation.Transactional")) {
                    violations.add(m.getFullName() + " に @Transactional");
                }
                Optional<JavaMethodCall> first = m.getMethodCallsFromSelf().stream()
                        .min(Comparator.comparingInt(JavaAccess::getLineNumber));
                if (first.isEmpty() || !first.get().getTargetOwner().getName().equals(GUARD)
                        || !first.get().getName().equals("assertNoActiveTransaction")) {
                    violations.add(m.getFullName() + " の最初の呼出: "
                            + first.map(JavaMethodCall::getDescription).orElse("（呼出なし）"));
                }
            }
        }
        // 遅延再実行入口
        JavaClass deferral = require(SB + "service.ShiftBudgetRecordDeferralService");
        deferral.getMethods().stream().filter(m -> m.getName().equals("replay")).forEach(m -> {
            Optional<JavaMethodCall> first = m.getMethodCallsFromSelf().stream()
                    .min(Comparator.comparingInt(JavaAccess::getLineNumber));
            if (first.isEmpty() || !first.get().getTargetOwner().getName().equals(GUARD)) {
                violations.add(m.getFullName() + " の最初の呼出が Guard でない");
            }
        });
        assertThat(violations).isEmpty();
    }

    // ───────────────────────── 試練143b AC70 ─────────────────────────

    @Test
    @DisplayName("試練143b AC70 Abort（stage.abort）を呼ぶのは段取り役のみで、その呼出元は投稿判定（ShiftBudgetMonthlyPostResult）を読む")
    void 試練143b_Abortは判定経由のみ() {
        JavaClass stage = require(STAGE);
        List<String> callers = new ArrayList<>();
        for (JavaMethodCall call : stage.getMethodCallsToSelf()) {
            if (call.getName().equals("abort")) {
                callers.add(call.getOriginOwner().getName() + "#" + call.getOrigin().getName());
            }
        }
        assertThat(callers).as("abort の呼出元").isNotEmpty()
                .allSatisfy(c -> assertThat(c).startsWith(COORDINATOR + "#"));
        // 呼出元メソッドは判定（outcome）を読む
        JavaClass coordinator = require(COORDINATOR);
        for (JavaMethod m : coordinator.getMethods()) {
            boolean callsAbort = m.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> c.getTargetOwner().getName().equals(STAGE) && c.getName().equals("abort"));
            if (callsAbort) {
                boolean readsOutcome = m.getMethodCallsFromSelf().stream().anyMatch(c ->
                        c.getTargetOwner().getName().equals("com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostResult")
                                && c.getName().equals("outcome"));
                assertThat(readsOutcome).as("%s は判定 outcome を見て Abort する", m.getFullName()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("試練143b AC70 投稿判定行（budget_shift_close_postings）の UPDATE／DELETE 経路が無い（Repository に @Modifying・delete 呼出なし、Entity に setter なし）")
    void 試練143b_判定行は追記のみ() {
        JavaClass repo = require(POSTING_REPOSITORY);
        List<String> violations = new ArrayList<>();
        for (JavaMethod m : repo.getMethods()) {
            if (m.isAnnotatedWith("org.springframework.data.jpa.repository.Modifying")) {
                violations.add(m.getFullName() + " は @Modifying");
            }
        }
        for (JavaMethodCall call : repo.getMethodCallsToSelf()) {
            if (call.getName().startsWith("delete") || call.getName().startsWith("update")) {
                violations.add(call.getDescription());
            }
        }
        JavaClass entity = require(POSTING_ENTITY);
        for (JavaMethod m : entity.getMethods()) {
            if (m.getName().startsWith("set") && m.getModifiers().contains(JavaModifier.PUBLIC)) {
                violations.add(m.getFullName() + " は setter");
            }
        }
        assertThat(violations).isEmpty();
    }

    // ───────────────────────── 試練147 AC76α ─────────────────────────

    @Test
    @DisplayName("試練147 AC76α AuthzTxFacadeRegistry に CMP2152 の α 5行（手動締め・締め日設定 get/change・期間一覧2種）が登録され、認可シグナルに ShiftBudgetScopeAccessPolicy がある")
    void 試練147_登録表() {
        List<String> alpha = AuthzTxFacadeRegistryArchTest.ENTRIES.stream()
                .filter(e -> "CMP2152".equals(e.wave()))
                .map(e -> e.controller().substring(e.controller().lastIndexOf('.') + 1) + "#" + e.method() + "->"
                        + e.facade().substring(e.facade().lastIndexOf('.') + 1))
                .toList();
        assertThat(alpha).contains(
                "MonthlyShiftBudgetCloseController#close->MonthlyShiftBudgetCloseFacade",
                "ShiftBudgetClosingSettingController#get->ShiftBudgetClosingSettingFacade",
                "ShiftBudgetClosingSettingController#change->ShiftBudgetClosingSettingFacade",
                "ShiftBudgetClosePeriodController#listByAllocation->ShiftBudgetClosePeriodFacade",
                "ShiftBudgetClosePeriodController#listByOrganization->ShiftBudgetClosePeriodFacade");
        assertThat(AuthzTxFacadeRegistryArchTest.RULES.authzClasses())
                .as("R3／R4 の認可シグナルに Policy（本体から Policy を呼べば R4 が赤になる前提）").contains(POLICY);
        assertThat(AuthzTxFacadeRegistryArchTest.TX_BODIES.get(SB + "service.MonthlyShiftBudgetCloseFacade"))
                .extracting(AuthzTxFacadeRegistryArchTest.TxBody::fqn)
                .contains(COORDINATOR, STAGE, "com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService");
    }
}
