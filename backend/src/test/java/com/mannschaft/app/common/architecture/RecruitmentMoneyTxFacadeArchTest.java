package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.security.AuthorizedInService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * recruitment の金銭・制裁 6 EP を「認可（tx の外の Facade）→ tx 本体」に分けた型
 * （CMP-260923-0954 W4 / plan4 の AC-13・AC-15・K7）の固定。
 *
 * <p>Facade のクラス名は実装前のため固定せず、<b>「recruitment パッケージの、単純名が {@code Facade} で終わるクラス」</b>
 * という規則で捉える（{@code *AccessService} / {@code *AccessGate} / {@code *AccessGuard} にすると呼んだだけで
 * 認可シグナル扱いになり、Facade 内の認可漏れを AuthzControllerGuard が見逃すため）。対象は W4 で移す
 * Controller メソッド 6 本に限定する（他の EP・{@code @SelfScopedEndpoint} の直接呼び出しは対象外。W6 で一般化）。</p>
 * <ol>
 *   <li>Controller の対象メソッドは recruitment の {@code *Facade} を呼び、recruitment の {@code *Service}（tx 本体）を直接呼ばない。</li>
 *   <li>その Facade に {@code @Transactional} が無い（クラスにもメソッドにも）。</li>
 *   <li>Controller が呼ぶ Facade のメソッドは {@code AccessControlService} / {@code ScopeConcealingAccessGate} に実際に届く。</li>
 *   <li>Facade から呼ばれる tx 本体のメソッドは（同クラス内の呼び出しをたどっても）認可クラスに届かない。
 *       免除の tx 本体（{@code RecruitmentCancellationFeeWaiveService}）はクラスごと認可クラスに依存しない。</li>
 *   <li>移行後は {@code @AuthorizedInService} を外す（残すと印だけで AuthzControllerGuard を通る偽陽性になる）。</li>
 * </ol>
 */
@DisplayName("recruitment 金銭・制裁の認可ファサード型（W4）の ArchUnit 固定")
class RecruitmentMoneyTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.recruitment";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";
    private static final String WAIVE_TX_BODY = PKG + ".service.RecruitmentCancellationFeeWaiveService";

    /** W4 で Facade 経由に移す Controller メソッド（クラス FQN, メソッド名）。 */
    private static final List<String[]> TARGETS = List.of(
            new String[]{PKG + ".controller.RecruitmentCancellationRecordController", "waive"},
            new String[]{PKG + ".controller.RecruitmentPenaltyController", "liftPenalty"},
            new String[]{PKG + ".controller.RecruitmentListingController", "confirmApplication"},
            new String[]{PKG + ".controller.CancellationPolicyController", "get"},
            new String[]{PKG + ".controller.CancellationPolicyController", "update"},
            new String[]{PKG + ".controller.CancellationPolicyController", "archive"});

    private final JavaClasses classes = ProductionClasses.get();

    @Test
    @DisplayName("対象の Controller メソッドが実在する（リネームで番人が空振りしない）")
    void 対象メソッドが実在する() {
        for (String[] t : TARGETS) {
            assertThat(controllerMethods(t)).as(t[0] + "#" + t[1]).isNotEmpty();
        }
    }

    @Test
    @DisplayName("K7: 対象の Controller メソッドは recruitment の *Facade を呼ぶ")
    void Controllerは_Facadeを呼ぶ() {
        List<String> violations = new ArrayList<>();
        for (String[] t : TARGETS) {
            for (JavaMethod m : controllerMethods(t)) {
                if (m.getMethodCallsFromSelf().stream().noneMatch(c -> isRecruitmentFacade(c.getTargetOwner()))) {
                    violations.add(m.getFullName() + " が recruitment の *Facade を呼んでいない");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("K7: 対象の Controller メソッドは recruitment の *Service（tx 本体）を直接呼ばない")
    void Controllerは_tx本体を直接呼ばない() {
        List<String> violations = new ArrayList<>();
        for (String[] t : TARGETS) {
            for (JavaMethod m : controllerMethods(t)) {
                m.getMethodCallsFromSelf().stream()
                        .filter(c -> c.getTargetOwner().getName().startsWith(PKG + ".")
                                && c.getTargetOwner().getSimpleName().endsWith("Service"))
                        .forEach(c -> violations.add(m.getFullName() + " が認可を飛ばして "
                                + c.getTargetOwner().getName() + "#" + c.getName() + " を直接呼んでいる"));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15: Controller が呼ぶ Facade に @Transactional が無い（クラスにもメソッドにも）")
    void Facadeにtransactionalが無い() {
        Set<JavaClass> facades = calledFacades();
        assertThat(facades).as("対象の Controller メソッドが呼ぶ Facade が存在すること").isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (JavaClass facade : facades) {
            if (facade.isAnnotatedWith(Transactional.class) || facade.isMetaAnnotatedWith(Transactional.class)) {
                violations.add(facade.getName() + " のクラスに @Transactional がある");
            }
            for (JavaMethod method : facade.getMethods()) {
                if (method.isAnnotatedWith(Transactional.class) || method.isMetaAnnotatedWith(Transactional.class)) {
                    violations.add(method.getFullName() + " に @Transactional がある");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15: Controller が呼ぶ Facade のメソッドは AccessControlService / Gate に実際に届く（認可が空洞化していない）")
    void Facadeは認可クラスに届く() {
        List<JavaMethod> facadeMethods = calledFacadeMethods();
        assertThat(facadeMethods).as("対象の Controller メソッドが呼ぶ Facade のメソッドが存在すること").isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (JavaMethod m : facadeMethods) {
            if (!reachesAuthorization(m.getOwner(), m, 0, 2)) {
                violations.add(m.getFullName() + " は認可クラスへ届かない");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15: Facade から呼ばれる tx 本体のメソッドは認可クラスに届かない（認可は tx の外だけ）")
    void tx本体は認可クラスに届かない() {
        List<JavaMethod> facadeMethods = calledFacadeMethods();
        assertThat(facadeMethods).as("対象の Controller メソッドが呼ぶ Facade のメソッドが存在すること").isNotEmpty();
        Set<JavaMethod> txBodies = new LinkedHashSet<>();
        for (JavaMethod m : facadeMethods) {
            collectTxBodyCalls(m.getOwner(), m, 0, txBodies);
        }
        assertThat(txBodies).as("Facade が tx 本体（recruitment の非 Facade クラス）を呼んでいること").isNotEmpty();
        // メソッド単位の検査が空振りしないよう、移した 6 本の tx 本体が漏れなく検査対象に入っていることを固定する
        Set<String> bodyNames = new LinkedHashSet<>();
        txBodies.forEach(b -> bodyNames.add(b.getName()));
        assertThat(bodyNames).as("移した tx 本体メソッドが全て検査対象に入っていること")
                .contains("waive", "liftPenalty", "confirmApplication", "getPolicy", "updatePolicy", "archivePolicy");
        List<String> violations = new ArrayList<>();
        for (JavaMethod body : txBodies) {
            if (reachesAuthorization(body.getOwner(), body, 0, 3)) {
                violations.add(body.getFullName() + " が tx 本体なのに認可クラスへ届く");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15: 免除の tx 本体（RecruitmentCancellationFeeWaiveService）はクラスごと認可クラスに依存しない")
    void 免除のtx本体は認可クラスに依存しない() {
        assertThat(classes.contain(WAIVE_TX_BODY)).isTrue();
        noClasses().that().haveFullyQualifiedName(WAIVE_TX_BODY)
                .should().dependOnClassesThat().haveFullyQualifiedName(ACCESS_CONTROL)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(GATE)
                .because("認可は tx の外の Facade に置く。tx 本体に残すと D-3T が common 経由で越境と数える")
                .check(classes);
    }

    @Test
    @DisplayName("K7: Facade へ移した Controller メソッドに @AuthorizedInService を残さない（印だけで通る偽陽性を防ぐ）")
    void AuthorizedInServiceを残さない() {
        List<String> violations = new ArrayList<>();
        for (String[] t : TARGETS) {
            for (JavaMethod m : controllerMethods(t)) {
                if (m.isAnnotatedWith(AuthorizedInService.class)) {
                    violations.add(m.getFullName() + " に @AuthorizedInService が残っている");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

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

    private List<JavaMethod> calledFacadeMethods() {
        Set<JavaMethod> result = new LinkedHashSet<>();
        for (String[] t : TARGETS) {
            for (JavaMethod m : controllerMethods(t)) {
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    if (isRecruitmentFacade(call.getTargetOwner())) {
                        call.getTarget().resolveMember().ifPresent(result::add);
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    private Set<JavaClass> calledFacades() {
        Set<JavaClass> result = new LinkedHashSet<>();
        calledFacadeMethods().forEach(m -> result.add(m.getOwner()));
        return result;
    }

    /** method（とそこから呼ぶ同クラスのメソッド）が認可クラスを呼ぶか。 */
    private static boolean reachesAuthorization(JavaClass owner, JavaMethod method, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return false;
        }
        for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
            String target = call.getTargetOwner().getName();
            if (target.equals(ACCESS_CONTROL) || target.equals(GATE)) {
                return true;
            }
            if (call.getTargetOwner().equals(owner)) {
                Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isPresent() && !resolved.get().equals(method)
                        && reachesAuthorization(owner, resolved.get(), depth + 1, maxDepth)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Facade のメソッド（と同クラスの private 等）から呼ばれる、recruitment の非 Facade クラスのメソッドを集める。 */
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
