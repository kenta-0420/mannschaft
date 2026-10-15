package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import org.junit.jupiter.api.Tag;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可をトランザクションの外のファサードへ出した型（CMP-260923-0954 W2・W1 / plan4 の AC-15・K7）の、<b>W1・W2 に固有の項目</b>の固定。
 *
 * <p>共通の規則（Controller メソッド → Facade、Facade 非 tx、Facade の public メソッドが認可へ届く、tx 本体が認可クラスに
 * 依存しない、認可の印を残さない、Facade の命名）は W6b で横断の番人 {@link AuthzTxFacadeRegistryArchTest} へ寄せた。
 * ここには W1・W2 だけの次の項目を残す。</p>
 * <ol>
 *   <li>W1・W2 の Facade は<b>すべて Gate に</b>届き、{@code ShiftRequestFacade} 以外は {@code AccessControlService} にも届く
 *       （横断の番人は「Gate か ACS」までしか見ない）。</li>
 *   <li>Controller は<b>クラスごと</b> tx 本体に依存しない（フィールドにも持たない）。
 *       {@code ShiftRequestController} だけは自己スコープの {@code listMyRequests}（{@code @SelfScopedEndpoint}）が
 *       tx 本体を直接呼ぶことを許し、それ以外の public メソッドはすべて Facade を呼び tx 本体を呼ばない
 *       （登録表に無い public メソッドが増えても赤になる）。</li>
 * </ol>
 */
@DisplayName("shift の認可ファサード型（W1・W2）の固有項目の ArchUnit 固定")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ShiftTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.shift";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    /** tx 本体 → ファサード → Controller の対応（移した組だけ）。 */
    private static final List<String[]> TRIPLES = List.of(
            new String[]{PKG + ".service.ShiftSwapService", PKG + ".service.ShiftSwapFacade",
                    PKG + ".controller.ShiftSwapController"},
            new String[]{PKG + ".service.ShiftChangeRequestService", PKG + ".service.ShiftChangeRequestFacade",
                    PKG + ".controller.ShiftChangeRequestController"},
            new String[]{PKG + ".service.ShiftAutoAssignService", PKG + ".service.ShiftAutoAssignFacade",
                    PKG + ".controller.ShiftAutoAssignController"},
            new String[]{PKG + ".service.ShiftRequestService", PKG + ".service.ShiftRequestFacade",
                    PKG + ".controller.ShiftRequestController"},
            new String[]{PKG + ".service.ShiftPositionService", PKG + ".service.ShiftPositionFacade",
                    PKG + ".controller.ShiftPositionController"});

    /** 自己スコープ（{@code @SelfScopedEndpoint}）で tx 本体の直呼びを許す Controller メソッド。 */
    private static final String SELF_SCOPED_CONTROLLER = PKG + ".controller.ShiftRequestController";
    private static final String SELF_SCOPED_METHOD = "listMyRequests";

    private final JavaClasses classesUnderTest = ProductionClasses.get();

    private static String[] facades() {
        return TRIPLES.stream().map(t -> t[1]).toArray(String[]::new);
    }

    private static DescribedPredicate<JavaClass> anyOf(String[] names) {
        List<String> list = List.of(names);
        return DescribedPredicate.describe("is one of " + list, c -> list.contains(c.getName()));
    }

    @Test
    @DisplayName("対象クラスが実在する（リネームで番人が空振りしない）")
    void 対象クラスが実在する() {
        for (String[] triple : TRIPLES) {
            for (String name : triple) {
                assertThat(classesUnderTest.contain(name)).as(name).isTrue();
            }
        }
    }

    @Test
    @DisplayName("AC-15: W1・W2 の Facade はすべて Gate へ届き、ShiftRequestFacade 以外は AccessControlService にも届く")
    void Facadeは認可クラスに届く() {
        // Gate へは全 Facade が届く。AccessControlService へは、Gate だけで足りるシフト希望の Facade 以外が届く
        // （W2 の 3 Facade は従来どおり両方、ポジションは一覧・作成が ACL・更新・削除が Gate）。
        classes().that(anyOf(facades()))
                .should().dependOnClassesThat().haveFullyQualifiedName(GATE)
                .check(classesUnderTest);
        classes().that(anyOf(Arrays.stream(facades())
                        .filter(f -> !f.equals(PKG + ".service.ShiftRequestFacade")).toArray(String[]::new)))
                .should().dependOnClassesThat().haveFullyQualifiedName(ACCESS_CONTROL)
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("K7: Controller はクラスごと tx 本体に依存せず、指定の Facade を呼ぶ（listMyRequests だけ例外）")
    void Controllerは指定のFacadeだけを呼ぶ() {
        for (String[] triple : TRIPLES) {
            if (triple[2].equals(SELF_SCOPED_CONTROLLER)) {
                // 自己スコープの EP だけ tx 本体の直呼びを許し、それ以外のメソッドは Facade 経由のみ。
                classes().that().haveFullyQualifiedName(triple[2])
                        .should(haveEveryMethodExceptSelfScopedUseFacadeNotTxBody(triple[0], triple[1]))
                        .check(classesUnderTest);
            } else {
                noClasses().that().haveFullyQualifiedName(triple[2])
                        .should().dependOnClassesThat().haveFullyQualifiedName(triple[0])
                        .because("認可を飛ばして tx 本体へ入れてはならない")
                        .check(classesUnderTest);
            }
            classes().that().haveFullyQualifiedName(triple[2])
                    .should().dependOnClassesThat().haveFullyQualifiedName(triple[1])
                    .check(classesUnderTest);
        }
    }

    /**
     * 自己スコープの EP（{@value #SELF_SCOPED_METHOD}）以外の Controller メソッドは、tx 本体を直接呼ばず Facade を呼ぶこと。
     * {@value #SELF_SCOPED_METHOD} は tx 本体（listMyRequests）を呼び、Facade は呼ばない（呼び出し元 userId のみを条件にする）。
     */
    private static ArchCondition<JavaClass> haveEveryMethodExceptSelfScopedUseFacadeNotTxBody(
            String txBody, String facade) {
        return new ArchCondition<>("route every method except " + SELF_SCOPED_METHOD + " through the facade") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    boolean callsTx = method.getMethodCallsFromSelf().stream()
                            .anyMatch(c -> c.getTargetOwner().getName().equals(txBody));
                    boolean callsFacade = method.getMethodCallsFromSelf().stream()
                            .anyMatch(c -> c.getTargetOwner().getName().equals(facade));
                    boolean endpoint = method.getModifiers().contains(JavaModifier.PUBLIC);
                    if (!endpoint) {
                        continue;
                    }
                    if (method.getName().equals(SELF_SCOPED_METHOD)) {
                        if (!callsTx) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " は自己スコープの EP なのに tx 本体を呼んでいない（想定した構成が変わった）"));
                        }
                        continue;
                    }
                    if (callsTx) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " が認可を飛ばして tx 本体を直接呼んでいる"));
                    }
                    if (!callsFacade) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " が Facade を呼んでいない"));
                    }
                }
            }
        };
    }
}
