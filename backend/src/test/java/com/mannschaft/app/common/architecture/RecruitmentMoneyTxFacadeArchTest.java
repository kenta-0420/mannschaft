package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Tag;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recruitment の金銭・制裁 6 EP を「認可（tx の外の Facade）→ tx 本体」に分けた型
 * （CMP-260923-0954 W4 / plan4 の AC-13・AC-15・K7）の、<b>W4 に固有の項目</b>の固定。
 *
 * <p>共通の規則（対象メソッドが指定の Facade を呼び recruitment の {@code *Service} を直接呼ばない、Facade 非 tx、
 * Facade が認可へ届く、Facade から呼ばれる tx 本体のメソッドが認可へ届かない、免除の tx 本体
 * {@code RecruitmentCancellationFeeWaiveService} がクラスごと認可クラスに依存しない、{@code @AuthorizedInService} を残さない）は
 * W6b で横断の番人 {@link AuthzTxFacadeRegistryArchTest} へ寄せた（W4 の 6 本は登録表の「W4」、免除の tx 本体は CLASS）。
 * ここには「移した 6 本の tx 本体メソッドが名前で漏れなく検査対象に入っている」ことだけを残す
 * （横断の番人は対象メソッドごとに tx 本体が 1 本以上あることまでしか見ない）。</p>
 */
@DisplayName("recruitment 金銭・制裁の認可ファサード型（W4）の固有項目の ArchUnit 固定")
@Tag(ArchUnitTestTag.ARCHUNIT)
class RecruitmentMoneyTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.recruitment";

    /** W4 で Facade 経由に移した Controller メソッド（クラス FQN, メソッド名）。 */
    private static final List<String[]> TARGETS = List.of(
            new String[]{PKG + ".controller.RecruitmentCancellationRecordController", "waive"},
            new String[]{PKG + ".controller.RecruitmentPenaltyController", "liftPenalty"},
            new String[]{PKG + ".controller.RecruitmentListingController", "confirmApplication"},
            new String[]{PKG + ".controller.CancellationPolicyController", "get"},
            new String[]{PKG + ".controller.CancellationPolicyController", "update"},
            new String[]{PKG + ".controller.CancellationPolicyController", "archive"});

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("AC-15: 移した 6 本の tx 本体メソッド（waive・liftPenalty・confirmApplication・get/update/archivePolicy）が Facade から呼ばれている")
    void 移したtx本体メソッドが全て検査対象に入っている() {
        Set<JavaMethod> txBodies = new LinkedHashSet<>();
        for (String[] t : TARGETS) {
            for (JavaMethod m : controllerMethods(t)) {
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    if (isRecruitmentFacade(call.getTargetOwner())) {
                        call.getTarget().resolveMember().ifPresent(fm -> collectTxBodyCalls(fm.getOwner(), fm, 0, txBodies));
                    }
                }
            }
        }
        Set<String> bodyNames = new LinkedHashSet<>();
        txBodies.forEach(b -> bodyNames.add(b.getName()));
        assertThat(bodyNames).as("移した tx 本体メソッドが全て検査対象に入っていること")
                .contains("waive", "liftPenalty", "confirmApplication", "getPolicy", "updatePolicy", "archivePolicy");
    }

    private List<JavaMethod> controllerMethods(String[] target) {
        if (!classes.contain(target[0])) {
            return List.of();
        }
        return classes.get(target[0]).getMethods().stream()
                .filter(m -> m.getName().equals(target[1]))
                .toList();
    }

    private static boolean isRecruitmentFacade(JavaClass owner) {
        return owner.getName().startsWith(PKG + ".") && owner.getSimpleName().endsWith("Facade");
    }

    /** Facade のメソッド（と同クラスの private 等）から呼ばれる、recruitment の非 Facade クラスの tx メソッドを集める。 */
    private static void collectTxBodyCalls(JavaClass facade, JavaMethod method, int depth, Set<JavaMethod> out) {
        if (depth > 2) {
            return;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            JavaClass owner = call.getTargetOwner();
            Optional<JavaMethod> resolved = call.getTarget().resolveMember();
            if (resolved.isEmpty()) {
                continue;
            }
            if (owner.equals(facade)) {
                if (!resolved.get().equals(method)) {
                    collectTxBodyCalls(facade, resolved.get(), depth + 1, out);
                }
            } else if (owner.getName().startsWith(PKG + ".service.") && !isRecruitmentFacade(owner)
                    && (owner.isAnnotatedWith(Transactional.class)
                    || resolved.get().isAnnotatedWith(Transactional.class))) {
                out.add(resolved.get());
            }
        }
    }
}
