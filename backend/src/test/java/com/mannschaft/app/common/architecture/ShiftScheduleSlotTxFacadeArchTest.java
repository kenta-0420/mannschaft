package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Tag;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 認可をトランザクションの外のファサードへ出した型の <b>W6a（shift の schedules・slots・remind・PDF）に固有の項目</b>の固定
 * （CMP-260923-0954 W6a / plan4 の AC-15・K3・K7、ep_tables_w6.md「殿の判断（2026-10-03）」1〜4）。
 *
 * <p>共通の規則（対象 Controller メソッドが指定の Facade を呼び tx 本体・他の Facade を直接呼ばない、Facade 非 tx、
 * Facade の public メソッドが認可へ届く、tx 本体がクラスごと認可クラスに依存しない、認可の印を残さない、Facade の命名）は
 * W6b で横断の番人 {@link AuthzTxFacadeRegistryArchTest} へ寄せた。ここには W6a だけの次の項目を残す。</p>
 * <ol>
 *   <li>対象 Controller メソッド（{@link #TARGETS}）は各 Controller の EP（{@code @*Mapping} 付き public メソッド）と
 *       完全に一致する（EP が増えたら赤）。</li>
 *   <li>対象 Controller は<b>クラスごと</b> tx 本体（{@link #TX_BODIES}）に依存しない（フィールドにも持たない）。</li>
 *   <li>K3: {@code ShiftRequestService} が呼ぶ {@code ShiftScheduleService} の package-private の {@code find*} は
 *       名前・可視性・引数・戻り値の型を変えない。</li>
 *   <li>Facade の名前は tx 本体 {@code ShiftXxxService} に対して同じパッケージの {@code ShiftXxxFacade}。W1・W2 の Facade とは
 *       別集合で、W1・W2 の Facade に依存しない。</li>
 * </ol>
 */
@DisplayName("shift の認可ファサード型（W6a: schedules・slots・remind・PDF）の ArchUnit 固定")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ShiftScheduleSlotTxFacadeArchTest {

    private static final String PKG = "com.mannschaft.app.shift";
    private static final String SVC = PKG + ".service.";
    private static final String CTRL = PKG + ".controller.";

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

    private final JavaClasses classesUnderTest = ProductionClasses.get();

    private static String facadeOf(String serviceFqn) {
        assertThat(serviceFqn).endsWith("Service");
        return serviceFqn.substring(0, serviceFqn.length() - "Service".length()) + "Facade";
    }

    private Optional<JavaClass> find(String fqn) {
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
}
