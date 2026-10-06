package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Tag;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recruitment の募集書込系・参加者管理・テンプレート 11 EP を「認可（tx の外の Facade）→ tx 本体」に分けた型
 * （CMP-260923-0954 W5 / plan4 の AC-13・AC-15・K7）の、<b>W5 に固有の項目</b>の固定。
 *
 * <p>共通の規則（対象メソッドが指定の Facade を呼び recruitment の {@code *Service} を直接呼ばない、Facade 非 tx、
 * Facade が認可へ届く、対象メソッドごとに tx 本体の呼び出しがあり tx 本体は認可へ届かない、{@code @AuthorizedInService} を
 * 残さない）は W6b で横断の番人 {@link AuthzTxFacadeRegistryArchTest} へ寄せた（W5 の 11 本は登録表の「W5」）。
 * ここには、Facade のクラス名に依らず「W5 の Facade と W4（金銭・制裁）の Facade が交わらない」ことだけを残す。</p>
 */
@DisplayName("recruitment 募集・テンプレートの認可ファサード型（W5）の固有項目の ArchUnit 固定")
@Tag(ArchUnitTestTag.ARCHUNIT)
class RecruitmentListingTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.recruitment";

    /** W5 で Facade 経由に移した Controller メソッド（クラス FQN, メソッド名）。 */
    private static final List<String[]> TARGETS = List.of(
            new String[]{PKG + ".controller.RecruitmentListingController", "update"},
            new String[]{PKG + ".controller.RecruitmentListingController", "publish"},
            new String[]{PKG + ".controller.RecruitmentListingController", "cancel"},
            new String[]{PKG + ".controller.RecruitmentListingController", "archive"},
            new String[]{PKG + ".controller.RecruitmentListingController", "getDistributionTargets"},
            new String[]{PKG + ".controller.RecruitmentListingController", "setDistributionTargets"},
            new String[]{PKG + ".controller.RecruitmentApplicationController", "listParticipants"},
            new String[]{PKG + ".controller.RecruitmentApplicationController", "markAttended"},
            new String[]{PKG + ".controller.RecruitmentTemplateController", "getTemplate"},
            new String[]{PKG + ".controller.RecruitmentTemplateController", "updateTemplate"},
            new String[]{PKG + ".controller.RecruitmentTemplateController", "archiveTemplate"});

    /** W4（金銭・制裁）で Facade 経由に移した Controller メソッド。W5 の Facade と交わらないことの基準。 */
    private static final List<String[]> W4_TARGETS = List.of(
            new String[]{PKG + ".controller.RecruitmentCancellationRecordController", "waive"},
            new String[]{PKG + ".controller.RecruitmentPenaltyController", "liftPenalty"},
            new String[]{PKG + ".controller.RecruitmentListingController", "confirmApplication"},
            new String[]{PKG + ".controller.CancellationPolicyController", "get"},
            new String[]{PKG + ".controller.CancellationPolicyController", "update"},
            new String[]{PKG + ".controller.CancellationPolicyController", "archive"});

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("W5 の Facade は W4（金銭・制裁）の Facade と別のクラス（金銭の Facade に相乗りしない）")
    void W4のFacadeと交わらない() {
        Set<JavaClass> w5 = calledFacades(TARGETS);
        assertThat(w5).as("W5 の対象 Controller メソッドが呼ぶ Facade が存在すること").isNotEmpty();
        Set<JavaClass> w4 = calledFacades(W4_TARGETS);
        assertThat(w4).as("W4 の対象 Controller メソッドが呼ぶ Facade が存在すること（基準の空振り防止）").isNotEmpty();
        assertThat(w5).as("W5 の Facade と W4 の Facade は交わらない").doesNotContainAnyElementsOf(w4);
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

    private Set<JavaClass> calledFacades(List<String[]> targets) {
        Set<JavaClass> result = new LinkedHashSet<>();
        for (String[] t : targets) {
            for (JavaMethod m : controllerMethods(t)) {
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    if (isRecruitmentFacade(call.getTargetOwner())) {
                        call.getTarget().resolveMember().ifPresent(fm -> result.add(fm.getOwner()));
                    }
                }
            }
        }
        return result;
    }
}
