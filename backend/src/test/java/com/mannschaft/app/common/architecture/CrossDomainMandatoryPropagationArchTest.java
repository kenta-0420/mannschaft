package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ドメインをまたぐ {@code Propagation.MANDATORY} の番人（D-3P。docs/architecture/notification_outbox.md §8）。
 *
 * <p>D-3T（{@link CrossDomainTransactionalTransitiveArchTest}）は interface 呼び出しの先を辿らず、Repository の判定も
 * パッケージ名に依るため、「別ドメインの {@code MANDATORY} メソッドを呼んで、そのドメインの表へ同じ tx で書く」越境を
 * 見逃していた（F01.2.1 の加盟通知・6-E の告知 push）。本番人はその形を直接禁じる。</p>
 *
 * <ul>
 *   <li><b>D-3P-1</b>: ドメイン X のクラスが、ドメイン Y（≠X）の {@code MANDATORY} メソッドを直接呼ばない。
 *       呼ぶ側・呼ばれる側のどちらかが {@code common} なら対象外。</li>
 *   <li><b>D-3P-2</b>: ドメイン Y の {@code MANDATORY} メソッドが、ドメイン X（≠Y・common 以外）で宣言された
 *       interface のメソッドを実装しない（逆向きポート。X の tx に Y の書き込みを参加させる形）。</li>
 *   <li><b>D-3P-3</b>: notification 以外のドメインが、通知ドメインの「呼び出し側 tx に参加して登録する」入口
 *       （{@code NotificationFanoutJobService#enqueueInCurrentTransaction*}・{@code #enqueueInOwnTransaction}・
 *       {@code NotificationFanoutAudienceService}・{@code NotificationOutboxIngestService}）に依存しない。
 *       送り手ドメインは自ドメインの outbox 表に書き、取り込みは通知ドメインの relay が行う。</li>
 * </ul>
 *
 * <h2>MANDATORY の判定（実効値）</h2>
 * <p>メソッドの {@code @Transactional} → 宣言クラスの {@code @Transactional} → 継承元（親クラス・interface）の
 * 同じシグネチャのメソッドの {@code @Transactional} → その宣言型の {@code @Transactional} の順に最初に見つかったもの
 * （Spring の {@code AnnotationTransactionAttributeSource} の探索順に合わせる）。</p>
 *
 * <h2>検出範囲（静的解析の限界）</h2>
 * <p>直接のメソッド呼び出しと interface の実装関係だけを見る。イベント経由（{@code @EventListener}・
 * {@code @TransactionalEventListener}）、{@code REQUIRED} や無印での参加、リフレクション、プロキシ越しの動的ディスパッチは
 * 対象外。{@code REQUIRED} の越境は D-3T が Repository への到達で見る。</p>
 *
 * <h2>凍結しない</h2>
 * <p>D-3P は凍結ストアを使わない。既存の違反は {@link #AUDITED_REVERSE_PORTS}（理由つきの監査済み例外）だけで、
 * 新しい違反は必ず赤になる。監査済み例外が実在しなくなったら {@code og03_*} が赤になる（台帳の腐り防止）。</p>
 */
@AnalyzeClasses(packages = "com.mannschaft.app", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag(ArchUnitTestTag.ARCHUNIT)
class CrossDomainMandatoryPropagationArchTest {

    static final String NOTIFICATION_DOMAIN = "notification";

    /** D-3P-3 で依存を禁じる通知ドメインの入口（型ごと）。 */
    static final Set<String> FORBIDDEN_NOTIFICATION_TYPES = Set.of(
            "com.mannschaft.app.notification.fanout.NotificationFanoutAudienceService",
            "com.mannschaft.app.notification.outbox.NotificationOutboxIngestService");

    /** D-3P-3 で依存を禁じる通知ドメインの入口（メソッド単位）。 */
    static final String FANOUT_JOB_SERVICE = "com.mannschaft.app.notification.fanout.NotificationFanoutJobService";

    /**
     * D-3P-2 の監査済み例外（キーは「実装クラスの完全修飾名.メソッド名」）。
     *
     * <p>{@code lockForAffiliation}: チームの加盟申請は、チーム行 → 組織行の固定順で行ロックを取り、件数の確認と INSERT を
     * 直列化する（F01.2.1 §6.1 step 7・§6.9）。組織行のロックは組織ドメインの Repository でしか取れず、team の tx の中で
     * 取る必要がある（ロックを tx の外へ出すと直列化が崩れる）ため、逆向きポートを監査済みとして残す（陣立て書 Q4 の裁可）。
     * 組織の表へは書かない（{@code SELECT ... FOR UPDATE} のみ）。</p>
     */
    static final Map<String, String> AUDITED_REVERSE_PORTS = Map.of(
            "com.mannschaft.app.organization.service.OrganizationAffiliationPortAdapter.lockForAffiliation",
            "加盟申請の直列化に組織行の行ロックが team の tx 内で必要（F01.2.1 §6.1・§6.9、陣立て書 Q4）。書き込みはしない");

    // =====================================================================
    // 本番コードへの規則
    // =====================================================================

    @ArchTest
    static final ArchRule d3p1_no_cross_domain_mandatory_call =
            classes().that().resideInAPackage("com.mannschaft.app..")
                    .should(conditionOf("not call other-domain MANDATORY methods (D-3P-1)",
                            CrossDomainMandatoryPropagationArchTest::findMandatoryCallViolations))
                    .because("別ドメインの MANDATORY メソッドを呼ぶと、そのドメインの書き込みが呼び出し側の tx に参加する"
                            + "（ポート越しでも越境 tx）。送り手ドメインの outbox に書き、相手ドメインがコミット後に取り込む")
                    .as("D-3P-1 cross-domain MANDATORY call");

    @ArchTest
    static final ArchRule d3p2_no_reverse_mandatory_port =
            classes().that().resideInAPackage("com.mannschaft.app..")
                    .should(conditionOf("not implement other-domain interfaces with MANDATORY methods (D-3P-2)",
                            clazz -> findReversePortViolations(clazz).stream()
                                    .filter(v -> !AUDITED_REVERSE_PORTS.containsKey(v.auditKey()))
                                    .toList()))
                    .because("別ドメインが宣言したポートを MANDATORY で実装すると、そのドメインの tx に自ドメインの書き込みが"
                            + "参加する（逆向きの越境 tx）")
                    .as("D-3P-2 reverse MANDATORY port");

    @ArchTest
    static final ArchRule d3p3_no_dependency_on_notification_tx_participation_entry =
            classes().that().resideInAPackage("com.mannschaft.app..")
                    .should(conditionOf("not depend on notification tx-participating entries (D-3P-3)",
                            CrossDomainMandatoryPropagationArchTest::findNotificationEntryViolations))
                    .because("通知の登録は送り手ドメインの outbox を経由し、通知ドメインの relay が取り込む"
                            + "（docs/architecture/notification_outbox.md）")
                    .as("D-3P-3 notification tx-participating entry dependency");

    /** OG03: P1 時点の実測値（D-3P-1=0、D-3P-2=監査済みの1件だけ、D-3P-3=0）と、旧経路の削除。 */
    @ArchTest
    static void og03_P1時点でD3P1とD3P3は0件でD3P2は監査済みの1件だけ(JavaClasses classes) {
        List<Violation> d3p1 = collect(classes, CrossDomainMandatoryPropagationArchTest::findMandatoryCallViolations);
        List<Violation> d3p2 = collect(classes, CrossDomainMandatoryPropagationArchTest::findReversePortViolations);
        List<Violation> d3p3 = collect(classes, CrossDomainMandatoryPropagationArchTest::findNotificationEntryViolations);

        assertThat(d3p1).as("D-3P-1 は0件（FanoutTeamAffiliationNotifier の越境 enqueue は outbox へ移す）").isEmpty();
        assertThat(d3p3).as("D-3P-3 は0件").isEmpty();
        assertThat(d3p2).extracting(Violation::auditKey)
                .as("D-3P-2 は監査済みの lockForAffiliation の1件だけ（監査済み例外が腐っていないことも兼ねる）")
                .containsExactlyInAnyOrderElementsOf(AUDITED_REVERSE_PORTS.keySet());
    }

    /** OG03: 旧経路（コミット前の越境 enqueue・コミット後の通知ドメイン tx での登録）が削除されている。 */
    @ArchTest
    static void og03_旧経路のFanoutTeamAffiliationNotifierとenqueueAfterCommitは削除済み(JavaClasses classes) {
        assertThat(classes.contain("com.mannschaft.app.team.service.FanoutTeamAffiliationNotifier"))
                .as("FanoutTeamAffiliationNotifier は削除する（OutboxTeamAffiliationNotifier に置き換える）").isFalse();
        JavaClass notifier = classes.get("com.mannschaft.app.team.service.TeamAffiliationNotifier");
        assertThat(notifier.getMethods()).extracting(JavaMethod::getName)
                .as("enqueueAfterCommit は廃止する（2-C も業務の tx の中で outbox に書く）")
                .doesNotContain("enqueueAfterCommit");
    }

    // =====================================================================
    // 判定（ConditionTest からも呼ぶ）
    // =====================================================================

    /** D-3P-1: {@code clazz} のコードから、別ドメインの MANDATORY メソッドへの直接呼び出し。 */
    static List<Violation> findMandatoryCallViolations(JavaClass clazz) {
        String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
        if (sourceDomain == null || DomainPackages.isSharedDomain(sourceDomain)) {
            return List.of();
        }
        Map<String, Violation> unique = new LinkedHashMap<>();
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (JavaMethodCall call : codeUnit.getMethodCallsFromSelf()) {
                JavaClass targetOwner = call.getTarget().getOwner();
                String targetDomain = DomainPackages.domainOf(targetOwner.getPackageName());
                if (targetDomain == null || DomainPackages.isSharedDomain(targetDomain)
                        || targetDomain.equals(sourceDomain)) {
                    continue;
                }
                Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isEmpty()) {
                    continue;
                }
                if (effectivePropagation(resolved.get()).orElse(null) != Propagation.MANDATORY) {
                    continue;
                }
                Violation violation = new Violation("D-3P-1",
                        codeUnit.getFullName(),
                        String.format("%s (domain '%s') calls MANDATORY %s (domain '%s') [D-3P-1]",
                                codeUnit.getFullName(), sourceDomain, call.getTarget().getFullName(), targetDomain));
                unique.putIfAbsent(violation.message(), violation);
            }
        }
        return sorted(unique);
    }

    /** D-3P-2: {@code clazz} の MANDATORY メソッドが、別ドメインで宣言された interface のメソッドを実装している。 */
    static List<Violation> findReversePortViolations(JavaClass clazz) {
        String implDomain = DomainPackages.domainOf(clazz.getPackageName());
        if (implDomain == null || DomainPackages.isSharedDomain(implDomain) || clazz.isInterface()) {
            return List.of();
        }
        List<JavaClass> foreignInterfaces = clazz.getAllRawInterfaces().stream()
                .filter(i -> {
                    String d = DomainPackages.domainOf(i.getPackageName());
                    return d != null && !DomainPackages.isSharedDomain(d) && !d.equals(implDomain);
                })
                .toList();
        if (foreignInterfaces.isEmpty()) {
            return List.of();
        }
        Map<String, Violation> unique = new LinkedHashMap<>();
        for (JavaMethod method : implementationMethods(clazz)) {
            if (effectivePropagation(method).orElse(null) != Propagation.MANDATORY) {
                continue;
            }
            for (JavaClass port : foreignInterfaces) {
                if (findSameSignature(port, method).isEmpty()) {
                    continue;
                }
                String auditKey = clazz.getName() + "." + method.getName();
                Violation violation = new Violation("D-3P-2", auditKey,
                        String.format("%s.%s (domain '%s') implements %s (domain '%s') with MANDATORY [D-3P-2]",
                                clazz.getName(), method.getName(), implDomain, port.getName(),
                                DomainPackages.domainOf(port.getPackageName())));
                unique.putIfAbsent(violation.message(), violation);
            }
        }
        return sorted(unique);
    }

    /** D-3P-3: notification 以外のクラスが、通知ドメインの tx 参加型の入口に依存している。 */
    static List<Violation> findNotificationEntryViolations(JavaClass clazz) {
        String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
        if (sourceDomain == null || NOTIFICATION_DOMAIN.equals(sourceDomain)) {
            return List.of();
        }
        Map<String, Violation> unique = new LinkedHashMap<>();
        clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
            String target = dependency.getTargetClass().getName();
            if (FORBIDDEN_NOTIFICATION_TYPES.contains(target)) {
                Violation violation = new Violation("D-3P-3", clazz.getName(),
                        String.format("%s (domain '%s') depends on %s [D-3P-3]", clazz.getName(), sourceDomain, target));
                unique.putIfAbsent(violation.message(), violation);
            }
        });
        for (JavaCodeUnit codeUnit : clazz.getCodeUnits()) {
            for (JavaCall<?> call : codeUnit.getCallsFromSelf()) {
                if (!FANOUT_JOB_SERVICE.equals(call.getTarget().getOwner().getName())) {
                    continue;
                }
                String name = call.getTarget().getName();
                if (name.startsWith("enqueueInCurrentTransaction") || name.equals("enqueueInOwnTransaction")) {
                    Violation violation = new Violation("D-3P-3", codeUnit.getFullName(),
                            String.format("%s (domain '%s') calls %s [D-3P-3]", codeUnit.getFullName(), sourceDomain,
                                    call.getTarget().getFullName()));
                    unique.putIfAbsent(violation.message(), violation);
                }
            }
        }
        return sorted(unique);
    }

    /**
     * {@code method} の実効の propagation。Spring の {@code AnnotationTransactionAttributeSource} の探索順に合わせ、
     * メソッド → 宣言クラス（{@code @Transactional} は {@code @Inherited} なので親クラスの宣言も含む）→
     * 継承元（親クラス・interface）の同じシグネチャのメソッド → その宣言型、の順に最初に見つかった {@code @Transactional}。
     */
    static Optional<Propagation> effectivePropagation(JavaMethod method) {
        Optional<Transactional> onMethod = method.tryGetAnnotationOfType(Transactional.class);
        if (onMethod.isPresent()) {
            return Optional.of(onMethod.get().propagation());
        }
        for (JavaClass type = method.getOwner(); type != null; type = type.getRawSuperclass().orElse(null)) {
            Optional<Transactional> onType = type.tryGetAnnotationOfType(Transactional.class);
            if (onType.isPresent()) {
                return Optional.of(onType.get().propagation());
            }
        }
        for (JavaClass ancestor : ancestors(method.getOwner())) {
            Optional<JavaMethod> inherited = findSameSignature(ancestor, method);
            if (inherited.isPresent()) {
                Optional<Transactional> onInherited = inherited.get().tryGetAnnotationOfType(Transactional.class);
                if (onInherited.isPresent()) {
                    return Optional.of(onInherited.get().propagation());
                }
                Optional<Transactional> onAncestor = ancestor.tryGetAnnotationOfType(Transactional.class);
                if (onAncestor.isPresent()) {
                    return Optional.of(onAncestor.get().propagation());
                }
            }
        }
        return Optional.empty();
    }

    /** {@code clazz} のインスタンスで呼ばれる実装メソッド（自クラス宣言＋アプリ内の親クラスから継承したもの）。 */
    private static List<JavaMethod> implementationMethods(JavaClass clazz) {
        Map<String, JavaMethod> bySignature = new LinkedHashMap<>();
        JavaClass current = clazz;
        while (current != null && current.getPackageName().startsWith(DomainPackages.ROOT_PACKAGE + ".")) {
            for (JavaMethod method : current.getMethods()) {
                if (method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT)
                        || method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.SYNTHETIC)
                        || method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.BRIDGE)) {
                    continue;
                }
                bySignature.putIfAbsent(signature(method), method);
            }
            current = current.getRawSuperclass().orElse(null);
        }
        return new ArrayList<>(bySignature.values());
    }

    /** 親クラスと interface を幅優先で（自分は含まない）。 */
    private static List<JavaClass> ancestors(JavaClass clazz) {
        List<JavaClass> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<JavaClass> queue = new ArrayDeque<>();
        clazz.getRawSuperclass().ifPresent(queue::add);
        queue.addAll(clazz.getRawInterfaces());
        while (!queue.isEmpty()) {
            JavaClass next = queue.removeFirst();
            if (!seen.add(next.getName())) {
                continue;
            }
            result.add(next);
            next.getRawSuperclass().ifPresent(queue::add);
            queue.addAll(next.getRawInterfaces());
        }
        return result;
    }

    private static Optional<JavaMethod> findSameSignature(JavaClass type, JavaMethod method) {
        String signature = signature(method);
        return type.getMethods().stream().filter(m -> signature(m).equals(signature)).findFirst();
    }

    private static String signature(JavaMethod method) {
        return method.getName() + method.getRawParameterTypes().stream()
                .map(JavaClass::getName).collect(Collectors.joining(",", "(", ")"));
    }

    private static List<Violation> sorted(Map<String, Violation> unique) {
        return unique.values().stream().sorted(Comparator.comparing(Violation::message)).toList();
    }

    private static List<Violation> collect(JavaClasses classes,
                                           java.util.function.Function<JavaClass, List<Violation>> finder) {
        List<Violation> all = new ArrayList<>();
        for (JavaClass clazz : classes) {
            all.addAll(finder.apply(clazz));
        }
        return all;
    }

    private static ArchCondition<JavaClass> conditionOf(String description,
                                                        java.util.function.Function<JavaClass, List<Violation>> finder) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (Violation violation : finder.apply(clazz)) {
                    events.add(SimpleConditionEvent.violated(clazz, violation.message()));
                }
            }
        };
    }

    /**
     * 違反1件。
     *
     * @param rule     D-3P-1 / D-3P-2 / D-3P-3
     * @param auditKey 監査済み例外の照合キー（D-3P-2 は「実装クラス.メソッド名」、ほかは呼び出し元）
     * @param message  違反文
     */
    record Violation(String rule, String auditKey, String message) {
    }
}
