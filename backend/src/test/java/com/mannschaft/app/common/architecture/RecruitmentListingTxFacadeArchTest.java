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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recruitment の募集書込系・参加者管理・テンプレート 11 EP を「認可（tx の外の Facade）→ tx 本体」に分けた型
 * （CMP-260923-0954 W5 / plan4 の AC-13・AC-15・K7）の固定（試練＝実装前の red）。
 *
 * <p>Facade のクラス名は実装前のため固定せず、<b>「recruitment パッケージの、単純名が {@code Facade} で終わるクラス」</b>
 * という規則で捉える（{@code *AccessService} / {@code *AccessGate} / {@code *AccessGuard} にすると呼んだだけで
 * 認可シグナル扱いになり、Facade 内の認可漏れを AuthzControllerGuard が見逃すため）。
 * 対象は W5 で移す Controller メソッド 11 本に限定する（K7。from-template・no-show 異議・申込・募集詳細 GET は
 * 認可の型を変えない／Gate を使わないため対象外。他の EP・{@code @SelfScopedEndpoint} の直接呼び出しも対象外）。</p>
 * <ol>
 *   <li>Controller の対象メソッドは recruitment の {@code *Facade} を呼び、recruitment の {@code *Service}（tx 本体）を直接呼ばない。</li>
 *   <li>その Facade は W4 の金銭・制裁の Facade とは別のクラス（W4 の対象 Controller メソッドが呼ぶ Facade と交わらない）。</li>
 *   <li>その Facade に {@code @Transactional} が無い（クラスにもメソッドにも）。</li>
 *   <li>Controller が呼ぶ Facade のメソッドは {@code AccessControlService} / {@code ScopeConcealingAccessGate} に実際に届く。</li>
 *   <li>対象メソッドごとに、Facade から呼ばれる tx 本体のメソッドが 1 本以上あり（網羅の固定）、
 *       それらは（同クラス内の呼び出しをたどっても）認可クラスに届かない。</li>
 *   <li>移行後は {@code @AuthorizedInService} を外す（残すと印だけで AuthzControllerGuard を通る偽陽性になる）。</li>
 * </ol>
 */
@DisplayName("recruitment 募集・テンプレートの認可ファサード型（W5）の ArchUnit 固定")
class RecruitmentListingTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.recruitment";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    /** W5 で Facade 経由に移す Controller メソッド（クラス FQN, メソッド名）。 */
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
    @DisplayName("対象の Controller メソッドが実在する（リネームで番人が空振りしない）")
    void 対象メソッドが実在する() {
        for (String[] t : TARGETS) {
            assertThat(controllerMethods(t)).as(t[0] + "#" + t[1]).isNotEmpty();
        }
        for (String[] t : W4_TARGETS) {
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
    @DisplayName("W5 の Facade は W4（金銭・制裁）の Facade と別のクラス（金銭の Facade に相乗りしない）")
    void W4のFacadeと交わらない() {
        Set<JavaClass> w5 = calledFacades(TARGETS);
        assertThat(w5).as("W5 の対象 Controller メソッドが呼ぶ Facade が存在すること").isNotEmpty();
        Set<JavaClass> w4 = calledFacades(W4_TARGETS);
        assertThat(w4).as("W4 の対象 Controller メソッドが呼ぶ Facade が存在すること（基準の空振り防止）").isNotEmpty();
        assertThat(w5).as("W5 の Facade と W4 の Facade は交わらない").doesNotContainAnyElementsOf(w4);
    }

    @Test
    @DisplayName("AC-15: Controller が呼ぶ Facade に @Transactional が無い（クラスにもメソッドにも）")
    void Facadeにtransactionalが無い() {
        Set<JavaClass> facades = calledFacades(TARGETS);
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
    @DisplayName("AC-15: Controller が呼ぶ Facade のメソッドは AccessControlService / Gate に実際に届く（対象メソッドごと）")
    void Facadeは認可クラスに届く() {
        List<String> violations = new ArrayList<>();
        for (String[] t : TARGETS) {
            List<JavaMethod> facadeMethods = calledFacadeMethods(List.<String[]>of(t));
            if (facadeMethods.isEmpty()) {
                violations.add(t[0] + "#" + t[1] + " が Facade のメソッドを呼んでいない");
                continue;
            }
            for (JavaMethod m : facadeMethods) {
                if (!reachesAuthorization(m.getOwner(), m, 0, 2)) {
                    violations.add(m.getFullName() + "（" + t[1] + " から）は認可クラスへ届かない");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15/K7: 対象メソッドごとに tx 本体の呼び出しがあり（網羅）、tx 本体は認可クラスに届かない（認可は tx の外だけ）")
    void tx本体は認可クラスに届かない() {
        Map<String, Set<JavaMethod>> bodiesByTarget = new LinkedHashMap<>();
        for (String[] t : TARGETS) {
            Set<JavaMethod> bodies = new LinkedHashSet<>();
            for (JavaMethod m : calledFacadeMethods(List.<String[]>of(t))) {
                collectTxBodyCalls(m.getOwner(), m, 0, bodies);
            }
            bodiesByTarget.put(t[0] + "#" + t[1], bodies);
        }
        // メソッド単位の検査が空振りしないよう、移した 11 本すべてについて tx 本体が検査対象に入っていることを固定する
        List<String> uncovered = new ArrayList<>();
        bodiesByTarget.forEach((target, bodies) -> {
            if (bodies.isEmpty()) {
                uncovered.add(target + " の Facade が tx 本体（recruitment.service の @Transactional）を呼んでいない");
            }
        });
        assertThat(uncovered).as("移した対象メソッドすべての tx 本体が検査対象に入っていること").isEmpty();

        List<String> violations = new ArrayList<>();
        bodiesByTarget.forEach((target, bodies) -> {
            for (JavaMethod body : bodies) {
                if (reachesAuthorization(body.getOwner(), body, 0, 3)) {
                    violations.add(body.getFullName() + "（" + target + " の tx 本体）が認可クラスへ届く");
                }
            }
        });
        assertThat(violations).isEmpty();
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

    private List<JavaMethod> calledFacadeMethods(List<String[]> targets) {
        Set<JavaMethod> result = new LinkedHashSet<>();
        for (String[] t : targets) {
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

    private Set<JavaClass> calledFacades(List<String[]> targets) {
        Set<JavaClass> result = new LinkedHashSet<>();
        calledFacadeMethods(targets).forEach(m -> result.add(m.getOwner()));
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

    /** Facade のメソッド（と同クラスの private 等）から呼ばれる、recruitment の非 Facade の tx 本体メソッドを集める。 */
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
