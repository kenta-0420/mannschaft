package com.mannschaft.app.school.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 学校出欠の認可是正 第1段 — 学校ドメイン専用の認可番人（試練・red）。AC-19。
 *
 * <p>既存の {@code AuthzControllerGuardArchTest} は「Controller から 2 ホップ以内に AccessControlService の呼び出しが
 * あれば合格」とするため、{@code checkMembership} 単独の緩い認可（「クラス全体を返す EP が同級の誰でも通る」）も
 * 合格にしてしまう。これが今回の欠陥が既存番人を素通りした理由である。本番人は学校ドメインに限って、
 * 次の 2 点を機械的に固定する。</p>
 * <ol>
 *   <li><b>学校の Service は AccessControlService の素の所属・管理者判定を直接呼ばない</b>
 *       （{@code checkMembership}/{@code isMember}/{@code checkAdminOrAbove}/{@code isAdminOrAbove}）。
 *       判定は {@code SchoolAttendanceAccessPolicy} に一元化する。ケアリンク（保護者）判定などの他の窓口は対象外。</li>
 *   <li><b>学校の Controller の全 EP は、Service を経て {@code SchoolAttendanceAccessPolicy} に到達する</b>
 *       （呼び出し深さ 4 まで追う）。{@code @SelfScopedEndpoint} の本人 EP と下記の免除は対象外。</li>
 * </ol>
 *
 * <h2>免除（理由付き。<b>凍結ストアは使わない</b>。初回から違反 0 件で通すこと。負債にしない）</h2>
 * <ul>
 *   <li>{@code AttendanceRequirementService}（規程 CRUD）— 規程の定義は PII でなく、Wave5 で ADMIN 認可済み。</li>
 *   <li>{@code ClassHomeroomService} — 担任設定の管理は ADMIN（担任名簿を作る側であり Policy の入力）。</li>
 *   <li>{@code DisclosureService} — 開示は ADMIN ＋ SYSTEM_ADMIN の別仕様（F03.13 §開示）。</li>
 *   <li>{@code AttendanceBatchController} — SYSTEM_ADMIN の {@code @PreAuthorize}。</li>
 *   <li>{@code FamilyAttendanceNoticeController#submitNotice} — 保護者が自分の子へ送る連絡（ケアリンク判定）。</li>
 * </ul>
 *
 * <p>偽陰性への備え: Policy クラスが存在すること自体を先に固定する（クラスが無いのに「違反なし」で緑になる
 * 空虚な緑を防ぐ）。本テストは出陣前は Policy 不在で必ず赤になる。</p>
 */
@DisplayName("学校出欠 認可番人（AC-19・凍結なし）")
class SchoolAttendanceAuthzGuardArchTest {

    private static final String POLICY = "SchoolAttendanceAccessPolicy";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";

    private static final Set<String> FORBIDDEN_DIRECT_CALLS =
            Set.of("checkMembership", "isMember", "checkAdminOrAbove", "isAdminOrAbove");

    private static final Set<String> EXEMPT_SERVICES = Set.of(
            POLICY, "AttendanceRequirementService", "ClassHomeroomService", "DisclosureService");

    private static final Set<String> EXEMPT_CONTROLLERS = Set.of(
            "AttendanceRequirementController", "ClassHomeroomController", "AttendanceDisclosureController",
            "AttendanceBatchController");

    private static final Set<String> EXEMPT_ENDPOINTS = Set.of("FamilyAttendanceNoticeController#submitNotice");

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PatchMapping", "PutMapping", "DeleteMapping", "RequestMapping");

    private static final int MAX_DEPTH = 4;

    private static JavaClasses schoolClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.mannschaft.app.school");
    }

    @Test
    @DisplayName("AC-19: SchoolAttendanceAccessPolicy が存在する（空虚な緑の防止）")
    void policyが存在する() {
        boolean exists = schoolClasses().stream().anyMatch(c -> c.getSimpleName().equals(POLICY));

        assertThat(exists).as("学校出欠の認可判定を一元化する %s が存在すること", POLICY).isTrue();
    }

    @Test
    @DisplayName("AC-19: 学校の Service は checkMembership/isMember/checkAdminOrAbove/isAdminOrAbove を直接呼ばない")
    void serviceは素の所属判定を直接呼ばない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass service : schoolClasses()) {
            if (!service.getPackageName().endsWith(".school.service")
                    || EXEMPT_SERVICES.contains(service.getSimpleName())) {
                continue;
            }
            for (JavaMethod method : service.getMethods()) {
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().getName().equals(ACCESS_CONTROL)
                            && FORBIDDEN_DIRECT_CALLS.contains(call.getName())) {
                        violations.add(service.getSimpleName() + "#" + method.getName()
                                + " -> AccessControlService#" + call.getName());
                    }
                }
            }
        }

        assertThat(violations)
                .as("判定は %s に一元化する。素の所属判定の直接呼び出し（クラス全体を返す EP が checkMembership 単独で"
                        + "終わる欠陥の温床）が残っている", POLICY)
                .isEmpty();
    }

    @Test
    @DisplayName("AC-19: 学校の Controller の全 EP は Service を経て SchoolAttendanceAccessPolicy に到達する")
    void 全EPがpolicyに到達する() {
        List<String> violations = new ArrayList<>();
        for (JavaClass controller : schoolClasses()) {
            if (!controller.getPackageName().endsWith(".school.controller")
                    || EXEMPT_CONTROLLERS.contains(controller.getSimpleName())) {
                continue;
            }
            for (JavaMethod method : controller.getMethods()) {
                if (!isEndpoint(method) || isSelfScoped(method)
                        || EXEMPT_ENDPOINTS.contains(controller.getSimpleName() + "#" + method.getName())) {
                    continue;
                }
                if (!reachesPolicy(method)) {
                    violations.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("%s に到達しない EP（認可の穴）。@SelfScopedEndpoint の本人 EP と理由付き免除は対象外", POLICY)
                .isEmpty();
    }

    @Test
    @DisplayName("D-3T: 学校の業務 Service（TX）は SchoolAttendanceAccessPolicy を呼ばない（認可は TX の外の Facade）")
    void 業務servicePolicyを呼ばない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass service : schoolClasses()) {
            if (!service.getPackageName().endsWith(".school.service")
                    || service.getSimpleName().equals(POLICY)
                    || service.getSimpleName().endsWith("Facade")) {
                continue;
            }
            for (JavaMethod method : service.getMethods()) {
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().getSimpleName().equals(POLICY)) {
                        violations.add(service.getSimpleName() + "#" + method.getName()
                                + " -> " + POLICY + "#" + call.getName());
                    }
                }
            }
        }

        assertThat(violations)
                .as("認可は role・membership・family の Repository に到達するため、業務 @Transactional の中に置くと"
                        + "学校の TX 入口が他ドメインへ越境する（D-3T）。認可は TX の外の *Facade で行い、通過後に業務 Service を呼ぶ")
                .isEmpty();
    }

    @Test
    @DisplayName("D-3T: 認可ファサードと Policy は @Transactional を持たない（TX の外で認可する）")
    void facadeとpolicyはTXを持たない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : schoolClasses()) {
            if (!clazz.getPackageName().endsWith(".school.service")
                    || !(clazz.getSimpleName().endsWith("Facade") || clazz.getSimpleName().equals(POLICY))) {
                continue;
            }
            if (clazz.getAnnotations().stream().anyMatch(a -> a.getRawType().getSimpleName().equals("Transactional"))) {
                violations.add(clazz.getSimpleName() + "（クラス）");
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (method.getAnnotations().stream().anyMatch(a -> a.getRawType().getSimpleName().equals("Transactional"))) {
                    violations.add(clazz.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(violations)
                .as("認可は業務 TX の外で行う。Facade／Policy に @Transactional を付けると、認可が TX の中に戻り、"
                        + "例外による拒否が参加中の TX を rollback-only にする")
                .isEmpty();
    }

    @Test
    @DisplayName("D-3T: 認可ファサードは少なくとも1つ存在し、全て Policy を呼ぶ（空虚な緑の防止）")
    void facadeはPolicyを呼ぶ() {
        List<JavaClass> facades = schoolClasses().stream()
                .filter(c -> c.getPackageName().endsWith(".school.service") && c.getSimpleName().endsWith("Facade"))
                .toList();

        assertThat(facades).as("認可ファサードが存在すること").isNotEmpty();
        List<String> withoutPolicy = new ArrayList<>();
        for (JavaClass facade : facades) {
            boolean callsPolicy = facade.getMethods().stream()
                    .flatMap(m -> m.getMethodCallsFromSelf().stream())
                    .anyMatch(call -> call.getTargetOwner().getSimpleName().equals(POLICY));
            if (!callsPolicy) {
                withoutPolicy.add(facade.getSimpleName());
            }
        }
        assertThat(withoutPolicy).as("Policy を呼ばない Facade は認可の穴").isEmpty();
    }

    private static boolean isEndpoint(JavaMethod method) {
        return method.getAnnotations().stream()
                .map(JavaAnnotation::getRawType)
                .anyMatch(t -> MAPPING_ANNOTATIONS.contains(t.getSimpleName()));
    }

    private static boolean isSelfScoped(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getSimpleName().equals("SelfScopedEndpoint"));
    }

    /** 深さ {@value #MAX_DEPTH} まで学校ドメイン内の呼び出しを辿り、Policy クラスへの呼び出しがあるか。 */
    private static boolean reachesPolicy(JavaMethod start) {
        Set<JavaMethod> seen = new HashSet<>();
        Deque<JavaMethod> frontier = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        frontier.add(start);
        depths.add(0);
        while (!frontier.isEmpty()) {
            JavaMethod current = frontier.poll();
            int depth = depths.poll();
            if (!seen.add(current)) {
                continue;
            }
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getSimpleName().equals(POLICY)) {
                    return true;
                }
                if (depth >= MAX_DEPTH || !call.getTargetOwner().getPackageName().startsWith("com.mannschaft.app.school")) {
                    continue;
                }
                Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isPresent()) {
                    frontier.add(resolved.get());
                    depths.add(depth + 1);
                }
            }
        }
        return false;
    }
}
