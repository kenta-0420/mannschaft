package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可をトランザクションの外のファサードへ出した型の <b>W6a（shift の schedules・slots・remind・PDF）</b>の固定
 * （CMP-260923-0954 W6a / plan4 の AC-15・K3・K7、ep_tables_w6.md「殿の判断（2026-10-03）」1〜4）。
 *
 * <p>W1・W2 の 5 組は {@code ShiftTxFacadeArchTest} が固定している。本クラスは W6a で新設する Facade だけを対象にし、
 * W1・W2 の Facade とは集合として区別する（横断の番人への統合は W6b）。対象は<b>Controller メソッドの正確な集合</b>
 * （{@link #TARGETS}）で持ち、Controller 側に EP が増えたら網羅の検査で赤になる。</p>
 * <ol>
 *   <li>対象 Controller メソッドは実在し、各 Controller の EP（{@code @*Mapping} 付き public メソッド）を網羅している。</li>
 *   <li>対象 Controller メソッドは指定の Facade を呼び、tx 本体（{@link #TX_BODIES}）を直接呼ばない
 *       （Controller クラスごと tx 本体に依存しない）。W1・W2 の Facade も呼ばない。</li>
 *   <li>Facade に {@code @Transactional} が無い（クラスにもメソッドにも）。</li>
 *   <li>Facade の public メソッドはすべて {@code AccessControlService} / {@code ScopeConcealingAccessGate} へ届く
 *       （直接か、同クラスの private メソッド経由で深さ 2 まで）。</li>
 *   <li>tx 本体はクラスごと {@code AccessControlService} / Gate に依存しない（殿の判断 3）。</li>
 *   <li>K3: {@code ShiftRequestService} が呼ぶ {@code ShiftScheduleService} の package-private の {@code find*} は
 *       名前・可視性・引数・戻り値の型を変えない。</li>
 *   <li>Facade の名前は規則で縛る: tx 本体 {@code ShiftXxxService} に対して同じパッケージの {@code ShiftXxxFacade}。
 *       {@code *AccessService} / {@code *AccessGuard} / {@code *AccessGate} にしない。</li>
 *   <li>対象 Controller メソッドに {@code @AuthorizedInService} / {@code @SelfScopedEndpoint} を残さない
 *       （印だけで AuthzControllerGuard を通る偽陽性を残さない）。</li>
 * </ol>
 *
 * <p>試練（red 先行）時点では Facade が存在しないため、1〜5・7・8 は赤、6 は緑（壊さないことの固定）。</p>
 */
@DisplayName("shift の認可ファサード型（W6a: schedules・slots・remind・PDF）の ArchUnit 固定")
class ShiftScheduleSlotTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.shift";
    private static final String SVC = PKG + ".service.";
    private static final String CTRL = PKG + ".controller.";
    private static final String ACCESS_CONTROL = "com.mannschaft.app.common.AccessControlService";
    private static final String GATE = "com.mannschaft.app.common.ScopeConcealingAccessGate";

    private static final String SCHEDULE_CONTROLLER = CTRL + "ShiftScheduleController";
    private static final String SLOT_CONTROLLER = CTRL + "ShiftSlotController";
    private static final String PDF_CONTROLLER = CTRL + "ShiftPdfController";

    /** tx 本体 → Facade の対応（命名規則 {@code *Service} → {@code *Facade} で導く）。 */
    private static final String SCHEDULE_SERVICE = SVC + "ShiftScheduleService";
    private static final String SLOT_SERVICE = SVC + "ShiftSlotService";
    private static final String PDF_SERVICE = SVC + "ShiftPdfService";
    /** remind の tx 本体。Facade は schedules 用（{@code ShiftScheduleFacade}）に置く（Controller が同じため）。 */
    private static final String REMINDER_SERVICE = SVC + "ShiftPreferenceReminderBatchService";

    private static final String SCHEDULE_FACADE = facadeOf(SCHEDULE_SERVICE);
    private static final String SLOT_FACADE = facadeOf(SLOT_SERVICE);
    private static final String PDF_FACADE = facadeOf(PDF_SERVICE);

    /** W6a の Facade（この順で固定）。 */
    private static final List<String> W6A_FACADES = List.of(SCHEDULE_FACADE, SLOT_FACADE, PDF_FACADE);

    /** W1・W2 の Facade（{@code ShiftTxFacadeArchTest} の対象）。W6a とは別集合であることを固定する。 */
    private static final List<String> W1_W2_FACADES = List.of(
            SVC + "ShiftSwapFacade", SVC + "ShiftChangeRequestFacade", SVC + "ShiftAutoAssignFacade",
            SVC + "ShiftRequestFacade", SVC + "ShiftPositionFacade");

    /** Controller から直接呼んではならない tx 本体（認可を抜いた本体）。 */
    private static final List<String> TX_BODIES = List.of(SCHEDULE_SERVICE, SLOT_SERVICE, PDF_SERVICE, REMINDER_SERVICE);

    /** 「殿の判断 3」でクラスごと認可クラスに依存しないと定めた tx 本体。 */
    private static final List<String> AUTHZ_FREE_SCHEDULE_SLOT = List.of(SCHEDULE_SERVICE, SLOT_SERVICE);

    /** 殿の判断 1・2 で W6a に含めた remind・PDF の tx 本体（Facade へ認可を移した後は認可クラスに依存しない）。 */
    private static final List<String> AUTHZ_FREE_REMIND_PDF = List.of(PDF_SERVICE, REMINDER_SERVICE);

    /** 対象: Controller FQN → （メソッド名 → 呼ぶべき Facade FQN）。 */
    private static final Map<String, Map<String, String>> TARGETS = targets();

    private static Map<String, Map<String, String>> targets() {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        Map<String, String> schedule = new LinkedHashMap<>();
        for (String m : List.of("listSchedules", "getSchedule", "createSchedule", "updateSchedule", "deleteSchedule",
                "transitionStatus", "getScheduleSummary", "remindUnsubmitted", "duplicateSchedule")) {
            schedule.put(m, SCHEDULE_FACADE);
        }
        map.put(SCHEDULE_CONTROLLER, schedule);
        Map<String, String> slot = new LinkedHashMap<>();
        for (String m : List.of("listSlots", "createSlot", "bulkCreateSlots", "updateSlot", "deleteSlot",
                "patchSlotAssignments")) {
            slot.put(m, SLOT_FACADE);
        }
        map.put(SLOT_CONTROLLER, slot);
        map.put(PDF_CONTROLLER, Map.of("exportPdf", PDF_FACADE));
        return map;
    }

    /**
     * K3: {@code ShiftRequestService} が呼ぶ {@code ShiftScheduleService} の構造メソッド（認可を持たない）。
     * 名前 → 「引数の型（FQN をカンマ区切り）→ 戻り値の型 FQN」。
     */
    private static final Map<String, String> K3_FINDERS = Map.of(
            "findScheduleOrThrow", "java.lang.Long->" + PKG + ".entity.ShiftScheduleEntity",
            "findSchedule", "java.lang.Long->" + Optional.class.getName(),
            "findScheduleForUpdate", "java.lang.Long->" + Optional.class.getName(),
            "findScheduleForUpdateOrThrow", "java.lang.Long->" + PKG + ".entity.ShiftScheduleEntity",
            "findExistingScheduleIds", Collection.class.getName() + "->" + Set.class.getName());

    private static JavaClasses classesUnderTest;

    @BeforeAll
    static void importClasses() {
        classesUnderTest = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static String facadeOf(String serviceFqn) {
        assertThat(serviceFqn).endsWith("Service");
        return serviceFqn.substring(0, serviceFqn.length() - "Service".length()) + "Facade";
    }

    private static Optional<JavaClass> find(String fqn) {
        return classesUnderTest.contain(fqn) ? Optional.of(classesUnderTest.get(fqn)) : Optional.empty();
    }

    private static boolean isPublic(JavaMethod method) {
        return method.getModifiers().contains(JavaModifier.PUBLIC);
    }

    private static boolean isEndpoint(JavaMethod method) {
        return isPublic(method) && (method.isAnnotatedWith(GetMapping.class) || method.isAnnotatedWith(PostMapping.class)
                || method.isAnnotatedWith(PatchMapping.class) || method.isAnnotatedWith(PutMapping.class)
                || method.isAnnotatedWith(DeleteMapping.class) || method.isAnnotatedWith(RequestMapping.class));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 対象の実在と網羅
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("対象の Controller・tx 本体・W6a の Facade が実在する（リネームで番人が空振りしない）")
    void 対象クラスが実在する() {
        List<String> missing = new ArrayList<>();
        for (String name : TARGETS.keySet()) {
            if (find(name).isEmpty()) {
                missing.add(name);
            }
        }
        for (String name : TX_BODIES) {
            if (find(name).isEmpty()) {
                missing.add(name);
            }
        }
        for (String name : W6A_FACADES) {
            if (find(name).isEmpty()) {
                missing.add(name);
            }
        }
        assertThat(missing).as("実在しないクラス（Facade は W6a で新設する）").isEmpty();
    }

    @Test
    @DisplayName("対象 Controller メソッドが実在し、各 Controller の EP を網羅している（EP が増えたら赤）")
    void 対象メソッドがControllerのEPを網羅している() {
        int total = 0;
        for (Map.Entry<String, Map<String, String>> entry : TARGETS.entrySet()) {
            JavaClass controller = find(entry.getKey()).orElseThrow(
                    () -> new AssertionError("Controller が無い: " + entry.getKey()));
            Set<String> endpoints = controller.getMethods().stream()
                    .filter(ShiftScheduleSlotTxFacadeArchTest::isEndpoint)
                    .map(JavaMethod::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(endpoints).as(entry.getKey() + " の EP と対象表が一致する")
                    .isEqualTo(new TreeSet<>(entry.getValue().keySet()));
            total += entry.getValue().size();
        }
        // schedules 9 本・slots 6 本・PDF 1 本（ep_tables_w6.md §3.1）。
        assertThat(total).as("W6a の対象 Controller メソッドの本数").isEqualTo(16);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. Controller → Facade（tx 本体を直接呼ばない）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K7: 対象 Controller メソッドは指定の Facade を呼び、tx 本体・W1/W2 の Facade を直接呼ばない")
    void Controllerは指定のFacadeだけを呼ぶ() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : TARGETS.entrySet()) {
            JavaClass controller = find(entry.getKey()).orElseThrow();
            for (Map.Entry<String, String> target : entry.getValue().entrySet()) {
                List<JavaMethod> methods = controller.getMethods().stream()
                        .filter(m -> m.getName().equals(target.getKey())).toList();
                if (methods.isEmpty()) {
                    violations.add(entry.getKey() + "#" + target.getKey() + " が無い");
                    continue;
                }
                for (JavaMethod method : methods) {
                    Set<String> owners = method.getMethodCallsFromSelf().stream()
                            .map(c -> c.getTargetOwner().getName()).collect(Collectors.toSet());
                    if (!owners.contains(target.getValue())) {
                        violations.add(method.getFullName() + " が " + target.getValue() + " を呼んでいない");
                    }
                    for (String txBody : TX_BODIES) {
                        if (owners.contains(txBody)) {
                            violations.add(method.getFullName() + " が認可を飛ばして tx 本体 " + txBody + " を直接呼んでいる");
                        }
                    }
                    for (String other : W1_W2_FACADES) {
                        if (owners.contains(other)) {
                            violations.add(method.getFullName() + " が W1/W2 の Facade " + other + " を呼んでいる");
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("K7: 対象 Controller はクラスごと tx 本体に依存しない（フィールドに tx 本体を持たない）")
    void Controllerはtx本体に依存しない() {
        List<String> violations = new ArrayList<>();
        for (String name : TARGETS.keySet()) {
            JavaClass controller = find(name).orElseThrow();
            controller.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass().getName())
                    .filter(TX_BODIES::contains)
                    .distinct()
                    .forEach(t -> violations.add(name + " → " + t));
        }
        assertThat(violations).as("Controller から tx 本体への依存").isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3・4. Facade の性質
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-15: W6a の Facade に @Transactional が無い（クラスにもメソッドにも）")
    void Facadeにtransactionalが無い() {
        List<String> violations = new ArrayList<>();
        for (String name : W6A_FACADES) {
            Optional<JavaClass> facade = find(name);
            if (facade.isEmpty()) {
                violations.add(name + " が無い");
                continue;
            }
            JavaClass c = facade.get();
            if (c.isAnnotatedWith(Transactional.class) || c.isMetaAnnotatedWith(Transactional.class)) {
                violations.add(name + " のクラスに @Transactional がある");
            }
            for (JavaMethod method : c.getMethods()) {
                if (method.isAnnotatedWith(Transactional.class) || method.isMetaAnnotatedWith(Transactional.class)) {
                    violations.add(method.getFullName() + " に @Transactional がある");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-15: W6a の Facade の public メソッドはすべて認可クラス（ACS / Gate）へ届く")
    void Facadeは認可クラスに届く() {
        List<String> violations = new ArrayList<>();
        for (String name : W6A_FACADES) {
            Optional<JavaClass> facade = find(name);
            if (facade.isEmpty()) {
                violations.add(name + " が無い");
                continue;
            }
            List<JavaMethod> publicMethods = facade.get().getMethods().stream()
                    .filter(ShiftScheduleSlotTxFacadeArchTest::isPublic).toList();
            if (publicMethods.isEmpty()) {
                violations.add(name + " に public メソッドが無い");
            }
            for (JavaMethod method : publicMethods) {
                if (!reachesAuthorization(facade.get(), method, 0)) {
                    violations.add(method.getFullName() + " は認可クラスへ届かない（認可が空洞化している）");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Controller が呼ぶ Facade のメソッドはその Facade の public メソッドである（Facade 経由が実際の入口）")
    void Controllerが呼ぶFacadeメソッドは認可へ届く() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : TARGETS.entrySet()) {
            Optional<JavaClass> controller = find(entry.getKey());
            if (controller.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> target : entry.getValue().entrySet()) {
                for (JavaMethod method : controller.get().getMethods()) {
                    if (!method.getName().equals(target.getKey())) {
                        continue;
                    }
                    var calls = method.getMethodCallsFromSelf().stream()
                            .filter(c -> c.getTargetOwner().getName().equals(target.getValue())).toList();
                    if (calls.isEmpty()) {
                        violations.add(method.getFullName() + " が Facade を呼んでいない");
                    }
                    for (var call : calls) {
                        Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                        if (resolved.isEmpty()
                                || !reachesAuthorization(resolved.get().getOwner(), resolved.get(), 0)) {
                            violations.add(method.getFullName() + " → " + call.getTarget().getFullName()
                                    + " が認可クラスへ届かない");
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    private static boolean reachesAuthorization(JavaClass owner, JavaMethod method, int depth) {
        if (depth > 2) {
            return false;
        }
        for (var call : method.getMethodCallsFromSelf()) {
            String target = call.getTargetOwner().getName();
            if (target.equals(ACCESS_CONTROL) || target.equals(GATE)) {
                return true;
            }
            if (call.getTargetOwner().equals(owner)) {
                Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isPresent() && reachesAuthorization(owner, resolved.get(), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. tx 本体は認可クラスに依存しない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("殿の判断 3・AC-15: ShiftScheduleService・ShiftSlotService はクラスごと ACS・Gate に依存しない")
    void スケジュールと枠のtx本体は認可クラスに依存しない() {
        assertThat(authorizationDependencies(AUTHZ_FREE_SCHEDULE_SLOT))
                .as("tx 本体から AccessControlService / ScopeConcealingAccessGate への依存（private・ラムダを含む）")
                .isEmpty();
    }

    @Test
    @DisplayName("殿の判断 1・2: remind・PDF の tx 本体（ShiftPreferenceReminderBatchService・ShiftPdfService）も ACS・Gate に依存しない")
    void remindとPDFのtx本体は認可クラスに依存しない() {
        assertThat(authorizationDependencies(AUTHZ_FREE_REMIND_PDF))
                .as("remind・PDF の認可は Facade へ移す（tx 本体に残すと越境の 404 隠蔽を tx 本体に頼る形が残る）")
                .isEmpty();
    }

    private static List<String> authorizationDependencies(List<String> classNames) {
        List<String> violations = new ArrayList<>();
        for (String name : classNames) {
            JavaClass c = find(name).orElseThrow(() -> new AssertionError("tx 本体が無い: " + name));
            c.getDirectDependenciesFromSelf().stream()
                    .filter(d -> d.getTargetClass().getName().equals(ACCESS_CONTROL)
                            || d.getTargetClass().getName().equals(GATE))
                    .map(d -> d.getOriginClass().getName() + " → " + d.getTargetClass().getName()
                            + " (" + d.getDescription() + ")")
                    .distinct()
                    .forEach(violations::add);
        }
        return violations;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. K3: ShiftRequestService が呼ぶ package-private の find* を変えない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K3: ShiftScheduleService の find*（ShiftRequestService が呼ぶ）は名前・可視性・引数・戻り値が変わらない")
    void ShiftRequestServiceが呼ぶfindは不変() {
        JavaClass service = find(SCHEDULE_SERVICE).orElseThrow();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> finder : K3_FINDERS.entrySet()) {
            String[] sig = finder.getValue().split("->");
            List<String> params = List.of(sig[0].split(","));
            List<JavaMethod> found = service.getMethods().stream()
                    .filter(m -> m.getName().equals(finder.getKey()))
                    .filter(m -> m.getRawParameterTypes().stream().map(JavaClass::getName).toList().equals(params))
                    .toList();
            if (found.size() != 1) {
                violations.add(finder.getKey() + "(" + sig[0] + ") が見つからない（" + found.size() + " 件）");
                continue;
            }
            JavaMethod method = found.get(0);
            Set<JavaModifier> modifiers = method.getModifiers();
            if (modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED)
                    || modifiers.contains(JavaModifier.PRIVATE)) {
                violations.add(method.getFullName() + " が package-private でない: " + modifiers);
            }
            if (!method.getRawReturnType().getName().equals(sig[1])) {
                violations.add(method.getFullName() + " の戻り値が " + method.getRawReturnType().getName()
                        + "（期待 " + sig[1] + "）");
            }
        }
        assertThat(violations).isEmpty();

        // 実際に ShiftRequestService から呼ばれていること（前提の固定。呼ばれなくなったら K3 の対象表を見直す）。
        JavaClass requestService = find(SVC + "ShiftRequestService").orElseThrow();
        Set<String> called = requestService.getMethodCallsFromSelf().stream()
                .filter(c -> c.getTargetOwner().getName().equals(SCHEDULE_SERVICE))
                .map(c -> c.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(called).as("ShiftRequestService → ShiftScheduleService の呼び出しは K3 の find* だけ")
                .isSubsetOf(K3_FINDERS.keySet())
                .isNotEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. Facade の命名規則
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Facade の名前は tx 本体の *Service → *Facade（同パッケージ）で、認可シグナル名でない。W1/W2 と別集合")
    void Facadeの命名規則() {
        for (String name : W6A_FACADES) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            assertThat(simple).doesNotEndWith("AccessService").doesNotEndWith("AccessGuard")
                    .doesNotEndWith("AccessGate").endsWith("Facade").startsWith("Shift");
            assertThat(name).startsWith(SVC);
            assertThat(find(name)).as(name + " が実在する").isPresent();
        }
        assertThat(W6A_FACADES).doesNotContainAnyElementsOf(W1_W2_FACADES);
        // 対象表に出てくる Facade は W6a の 3 本に限る（W1/W2 の Facade に相乗りしない）。
        Set<String> used = TARGETS.values().stream().flatMap(m -> m.values().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(used).isEqualTo(new TreeSet<>(W6A_FACADES));
        // W6a の Facade は W1/W2 の Facade に依存しない（認可を別の Facade に預けて空洞化させない）。
        List<String> crossFacade = new ArrayList<>();
        for (String name : W6A_FACADES) {
            find(name).ifPresent(c -> c.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass().getName())
                    .filter(W1_W2_FACADES::contains)
                    .distinct()
                    .forEach(t -> crossFacade.add(name + " → " + t)));
        }
        assertThat(crossFacade).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 印だけの偽陽性を残さない
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("対象 Controller メソッドに @AuthorizedInService / @SelfScopedEndpoint が付いていない")
    void 対象メソッドに認可の印が残っていない() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : TARGETS.entrySet()) {
            JavaClass controller = find(entry.getKey()).orElseThrow();
            for (JavaMethod method : controller.getMethods()) {
                if (!entry.getValue().containsKey(method.getName())) {
                    continue;
                }
                if (method.isAnnotatedWith(AuthorizedInService.class)) {
                    violations.add(method.getFullName() + " に @AuthorizedInService");
                }
                if (method.isAnnotatedWith(SelfScopedEndpoint.class)) {
                    violations.add(method.getFullName() + " に @SelfScopedEndpoint");
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
