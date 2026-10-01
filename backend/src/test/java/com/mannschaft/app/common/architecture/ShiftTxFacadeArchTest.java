package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可をトランザクションの外のファサードへ出した型（CMP-260923-0954 W2 / plan4 の AC-15・K7）の固定。
 *
 * <p>対象は<b>今回移した shift の交代・変更依頼・自動割当に限定</b>する（reservation など他ドメインや
 * {@code @SelfScopedEndpoint} の正当な Controller 直呼びは対象外。W6 で番人へ一般化する）。</p>
 * <ol>
 *   <li>tx 本体（{@code ShiftSwapService} / {@code ShiftChangeRequestService} / {@code ShiftAutoAssignService}）は
 *       {@code AccessControlService} / {@code ScopeConcealingAccessGate} に依存しない。</li>
 *   <li>Facade に {@code @Transactional} が無い（クラスにもメソッドにも）。</li>
 *   <li>Facade は実際に {@code AccessControlService} / Gate へ届く（認可が空洞化していない）。</li>
 *   <li>Controller は tx 本体を直接呼ばず、指定の Facade を呼ぶ（認可を飛ばして tx 本体へ入れない）。</li>
 *   <li>Facade の名前は {@code *AccessService} / {@code *AccessGuard} / {@code *AccessGate} でない
 *       （呼んだだけで認可シグナル扱いになり、Facade 内の認可漏れを AuthzControllerGuard が見逃すため）。</li>
 * </ol>
 */
@DisplayName("shift の認可ファサード型（W2）の ArchUnit 固定")
class ShiftTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.shift";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    /** tx 本体 → ファサード → Controller の対応（今回移した 3 組だけ）。 */
    private static final List<String[]> TRIPLES = List.of(
            new String[]{PKG + ".service.ShiftSwapService", PKG + ".service.ShiftSwapFacade",
                    PKG + ".controller.ShiftSwapController"},
            new String[]{PKG + ".service.ShiftChangeRequestService", PKG + ".service.ShiftChangeRequestFacade",
                    PKG + ".controller.ShiftChangeRequestController"},
            new String[]{PKG + ".service.ShiftAutoAssignService", PKG + ".service.ShiftAutoAssignFacade",
                    PKG + ".controller.ShiftAutoAssignController"});

    private static JavaClasses classesUnderTest;

    @BeforeAll
    static void importClasses() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static String[] txBodies() {
        return TRIPLES.stream().map(t -> t[0]).toArray(String[]::new);
    }

    private static String[] facades() {
        return TRIPLES.stream().map(t -> t[1]).toArray(String[]::new);
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
    @DisplayName("AC-15: tx 本体は AccessControlService / ScopeConcealingAccessGate に依存しない")
    void tx本体は認可クラスに依存しない() {
        noClasses().that().haveFullyQualifiedName(txBodies()[0])
                .or().haveFullyQualifiedName(txBodies()[1])
                .or().haveFullyQualifiedName(txBodies()[2])
                .should().dependOnClassesThat().haveFullyQualifiedName(ACCESS_CONTROL)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(GATE)
                .because("認可は tx の外の Facade に置く。tx 本体に残すと D-3T が common 経由で越境と数える")
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("AC-15: Facade に @Transactional が無い（クラスにもメソッドにも）")
    void Facadeにtransactionalが無い() {
        classes().that().haveFullyQualifiedName(facades()[0])
                .or().haveFullyQualifiedName(facades()[1])
                .or().haveFullyQualifiedName(facades()[2])
                .should(haveNoTransactionalAnywhere())
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("AC-15: Facade は AccessControlService / Gate へ実際に届く（認可が空洞化していない）")
    void Facadeは認可クラスに届く() {
        classes().that().haveFullyQualifiedName(facades()[0])
                .or().haveFullyQualifiedName(facades()[1])
                .or().haveFullyQualifiedName(facades()[2])
                .should().dependOnClassesThat().haveFullyQualifiedName(GATE)
                .check(classesUnderTest);
        classes().that().haveFullyQualifiedName(facades()[0])
                .or().haveFullyQualifiedName(facades()[1])
                .or().haveFullyQualifiedName(facades()[2])
                .should().dependOnClassesThat().haveFullyQualifiedName(ACCESS_CONTROL)
                .check(classesUnderTest);
        // 各 Facade の public メソッドのうち、tx 本体を呼ぶものは必ずその前に認可クラスへ届く（呼び出し順は
        // ITs / UT が応答で固定）。ここでは「全 public メソッドが認可クラスか、認可を行う同クラスの
        // private メソッドを呼ぶ」ことを検査する。
        classes().that().haveFullyQualifiedName(facades()[0])
                .or().haveFullyQualifiedName(facades()[1])
                .or().haveFullyQualifiedName(facades()[2])
                .should(haveEveryPublicMethodReachAuthorization())
                .check(classesUnderTest);
    }

    @Test
    @DisplayName("K7: Controller は tx 本体を直接呼ばず、指定の Facade を呼ぶ")
    void Controllerは指定のFacadeだけを呼ぶ() {
        for (String[] triple : TRIPLES) {
            noClasses().that().haveFullyQualifiedName(triple[2])
                    .should().dependOnClassesThat().haveFullyQualifiedName(triple[0])
                    .because("認可を飛ばして tx 本体へ入れてはならない")
                    .check(classesUnderTest);
            classes().that().haveFullyQualifiedName(triple[2])
                    .should().dependOnClassesThat().haveFullyQualifiedName(triple[1])
                    .check(classesUnderTest);
        }
    }

    @Test
    @DisplayName("Facade の名前は *AccessService / *AccessGuard / *AccessGate にしない")
    void Facadeの名前が認可シグナル扱いにならない() {
        for (String name : facades()) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            assertThat(simple).doesNotEndWith("AccessService").doesNotEndWith("AccessGuard")
                    .doesNotEndWith("AccessGate").endsWith("Facade");
        }
    }

    /** クラス・メソッドのどこにも {@code @Transactional} が付いていないこと。 */
    private static ArchCondition<JavaClass> haveNoTransactionalAnywhere() {
        return new ArchCondition<>("have no @Transactional on the class or any method") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isAnnotatedWith(Transactional.class)
                        || item.isMetaAnnotatedWith(Transactional.class)) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getName() + " のクラスに @Transactional がある"));
                }
                for (JavaMethod method : item.getMethods()) {
                    if (method.isAnnotatedWith(Transactional.class)
                            || method.isMetaAnnotatedWith(Transactional.class)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " に @Transactional がある"));
                    }
                }
            }
        };
    }

    /** public メソッドそれぞれが、認可クラス（Gate / AccessControlService）か同クラスの認可 private へ届くこと。 */
    private static ArchCondition<JavaClass> haveEveryPublicMethodReachAuthorization() {
        return new ArchCondition<>("have every public method reach an authorization class") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (!method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                        continue;
                    }
                    if (!reachesAuthorization(item, method, 0)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " は認可クラスへ届かない（認可が空洞化している）"));
                    }
                }
            }

            private boolean reachesAuthorization(JavaClass owner, JavaMethod method, int depth) {
                if (depth > 2) {
                    return false;
                }
                for (var call : method.getMethodCallsFromSelf()) {
                    String target = call.getTargetOwner().getName();
                    if (target.equals(ACCESS_CONTROL) || target.equals(GATE)) {
                        return true;
                    }
                    if (call.getTargetOwner().equals(owner)) {
                        var resolved = call.getTarget().resolveMember();
                        if (resolved.isPresent() && reachesAuthorization(owner, resolved.get(), depth + 1)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
    }
}
