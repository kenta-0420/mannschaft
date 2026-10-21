package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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

/**
 * 推移的クロスドメイン {@code @Transactional} 番人（D-3T）。
 *
 * <p>既存 D-3 ({@link CrossDomainTransactionalArchTest}) は宣言クラスから Repository への
 * 直接依存だけを検査する。本番人はその判定を変更せず、{@code @Transactional} 宣言メソッド
 * （直接注釈・合成注釈ともに対象。クラス宣言の場合は継承したアプリ内メソッドを含む）を入口として、
 * アプリ内の method call と
 * constructor call を visited 集合つき BFS で推移的に辿る。途中のドメインや {@code common}
 * を横断しても探索を継続し、入口の所属ドメインと異なる Repository 型への到達を検出する。
 *
 * <h2>凍結キーと診断の分離</h2>
 * <p>凍結ストアへ渡す違反文は「入口メソッド + 到達 Repository 型」だけで構成し、呼び出し経路や
 * 行番号を含めない。経路診断は {@code -Darchunit.d3t.diagnostics=true} 指定時だけ標準エラーへ
 * 別途出力する。中継 helper の移動や行追加だけで既存負債が新規違反へ化けることを防ぎつつ、
 * 通常 CI のログを既存負債で埋めない。
 *
 * <h2>静的解析上の限界</h2>
 * <p>ArchUnit が {@link com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget#resolveMember()}
 * で解決できる呼び出しと、取込済み本番集合に具象実装が一つだけあるアプリ内interface呼び出しを
 * 展開する。具象実装は子interface・抽象基底を通じた実装も含めて数え、抽象クラス自身は数えない。
 * 候補が0件または複数のときは全実装へ広げず、従来解決できていたdefault本体だけを保持する。
 * reflectionやその他の動的ディスパッチ、lambdaの合成メソッドを常に追跡できるとは限らない。
 * 呼び出し先型自体がRepositoryパッケージなら、実装解決の前に検出する。
 */
@AnalyzeClasses(packages = "com.mannschaft.app", importOptions = ImportOption.DoNotIncludeTests.class)
@ArchTag(ArchUnitTestTag.ARCHUNIT)
class CrossDomainTransactionalTransitiveArchTest {

    private static final String REPOSITORY_MARKER = ".repository";
    private static final String DIAGNOSTICS_PROPERTY = "archunit.d3t.diagnostics";

    @ArchTest
    static final ArchRule transactional_entries_should_not_reach_other_domain_repositories =
        FreezingArchRule.freeze(
            classes().that().resideInAPackage("com.mannschaft.app..")
                .should(notReachOtherDomainRepositories())
                .because("CLAUDE.md DB 設計の原則 #5 — @Transactional 宣言入口から"
                    + "推移的に到達する Repository も単一ドメイン内に閉じること。"
                    + "既存 D-3 は直接依存の契約として維持し、D-3T は委譲による迂回を検出する")
                .as("transactional entry should not transitively reach other-domain repositories (D-3T)"));

    static List<TransitiveViolation> findViolations(JavaClass clazz, Iterable<JavaClass> candidates) {
        return findViolations(clazz, new SingleImplementationIndex(candidates));
    }

    private static List<TransitiveViolation> findViolations(
            JavaClass clazz, SingleImplementationIndex implementations) {
        String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
        if (sourceDomain == null || DomainPackages.isSharedDomain(sourceDomain)) {
            return List.of();
        }
        Map<String, TransitiveViolation> unique = new LinkedHashMap<>();
        for (TransactionalEntry entry : transactionalEntries(clazz)) {
            collectViolations(entry, sourceDomain, unique, implementations);
        }
        return unique.values().stream()
            .sorted(Comparator.comparing(TransitiveViolation::freezeKey))
            .toList();
    }

    private static List<TransactionalEntry> transactionalEntries(JavaClass clazz) {
        boolean classTransactional = clazz.isAnnotatedWith(Transactional.class)
            || clazz.isMetaAnnotatedWith(Transactional.class);
        return clazz.getAllMethods().stream()
            .filter(method -> method.getOwner().getPackageName()
                .startsWith(DomainPackages.ROOT_PACKAGE + "."))
            .filter(method -> classTransactional
                || (method.getOwner().equals(clazz) && (method.isAnnotatedWith(Transactional.class)
                    || method.isMetaAnnotatedWith(Transactional.class))))
            .sorted(Comparator.comparing(JavaMethod::getFullName))
            .map(method -> new TransactionalEntry(method, logicalEntryName(clazz, method)))
            .toList();
    }

    private static void collectViolations(
            TransactionalEntry entry,
            String sourceDomain,
            Map<String, TransitiveViolation> unique,
            SingleImplementationIndex implementations) {
        Deque<PathNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new PathNode(entry.method(), List.of(displayName(entry.method()))));
        visited.add(entry.method().getFullName());

        while (!queue.isEmpty()) {
            PathNode current = queue.removeFirst();
            List<JavaCall<?>> calls = current.codeUnit().getCallsFromSelf().stream()
                .sorted(Comparator.comparing(CrossDomainTransactionalTransitiveArchTest::callSortKey))
                .toList();
            for (JavaCall<?> call : calls) {
                JavaClass targetOwner = call.getTarget().getOwner();
                List<String> path = append(current.path(), displayName(call));
                String targetDomain = DomainPackages.domainOf(targetOwner.getPackageName());
                if (isOtherDomainRepository(targetOwner, sourceDomain, targetDomain)) {
                    TransitiveViolation violation = new TransitiveViolation(
                        entry.logicalName(), sourceDomain, targetOwner.getName(), targetDomain,
                        String.join(" -> ", path));
                    unique.putIfAbsent(violation.freezeKey(), violation);
                }
                if (!isWithinApp(targetOwner)) {
                    continue;
                }
                Optional<? extends JavaCodeUnit> resolved = implementations.resolve(call);
                if (resolved.isEmpty()) {
                    continue;
                }
                JavaCodeUnit callee = resolved.get();
                if (visited.add(callee.getFullName())) {
                    queue.addLast(new PathNode(callee, path));
                }
            }
        }
    }

    private static boolean isOtherDomainRepository(
            JavaClass target, String sourceDomain, String targetDomain) {
        return isRepositoryPackage(target.getPackageName())
            && targetDomain != null
            && !DomainPackages.isSharedDomain(targetDomain)
            && !sourceDomain.equals(targetDomain);
    }

    private static boolean isWithinApp(JavaClass clazz) {
        return clazz.getPackageName().startsWith(DomainPackages.ROOT_PACKAGE + ".");
    }

    private static boolean isRepositoryPackage(String packageName) {
        return packageName.contains(REPOSITORY_MARKER + ".")
            || packageName.endsWith(REPOSITORY_MARKER);
    }

    private static String callSortKey(JavaCall<?> call) {
        return call.getTarget().getOwner().getName() + "#" + call.getTarget().getFullName();
    }

    private static String logicalEntryName(JavaClass transactionalClass, JavaMethod method) {
        if (method.getOwner().equals(transactionalClass)) {
            return method.getFullName();
        }
        String parameters = method.getRawParameterTypes().stream()
            .map(JavaClass::getName)
            .collect(Collectors.joining(", "));
        return transactionalClass.getName() + "." + method.getName() + "(" + parameters + ")"
            + " [inherited from " + method.getOwner().getName() + "]";
    }

    private static String displayName(JavaCodeUnit codeUnit) {
        return codeUnit.getOwner().getSimpleName() + "." + codeUnit.getName() + "()";
    }

    private static String displayName(JavaCall<?> call) {
        return call.getTarget().getOwner().getSimpleName() + "." + call.getTarget().getName()
            + "() [" + call.getSourceCodeLocation() + "]";
    }

    private static List<String> append(List<String> path, String next) {
        List<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(next);
        return List.copyOf(result);
    }

    static ArchCondition<JavaClass> notReachOtherDomainRepositories() {
        return new ArchCondition<>("not transitively reach other-domain repositories from @Transactional entries") {
            private SingleImplementationIndex implementations = new SingleImplementationIndex(List.of());

            @Override
            public void init(Collection<JavaClass> classes) {
                // @AnalyzeClassesが既に取り込んだ集合だけを使い、クラスごとの全候補走査を避ける。
                implementations = new SingleImplementationIndex(classes);
            }

            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (TransitiveViolation violation : findViolations(clazz, implementations)) {
                    if (Boolean.getBoolean(DIAGNOSTICS_PROPERTY)) {
                        System.err.println("[D-3T] " + violation.diagnosticPath());
                    }
                    events.add(SimpleConditionEvent.violated(clazz, violation.freezeKey()));
                }
            }
        };
    }

    /** 取込集合内の単一具象実装を一度だけ索引化し、interface呼び出しだけを補完する。 */
    private static final class SingleImplementationIndex {
        private final Map<String, JavaClass> singleImplementations = new LinkedHashMap<>();

        private SingleImplementationIndex(Iterable<JavaClass> importedClasses) {
            Map<String, List<JavaClass>> candidates = new LinkedHashMap<>();
            for (JavaClass candidate : importedClasses) {
                if (!isWithinApp(candidate) || candidate.isInterface()
                        || candidate.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    continue;
                }
                for (JavaClass contract : candidate.getAllRawInterfaces()) {
                    candidates.computeIfAbsent(contract.getName(), ignored -> new ArrayList<>()).add(candidate);
                }
            }
            candidates.forEach((contract, implementations) -> {
                if (implementations.size() == 1) {
                    singleImplementations.put(contract, implementations.getFirst());
                }
            });
        }

        private Optional<? extends JavaCodeUnit> resolve(JavaCall<?> call) {
            Optional<? extends JavaCodeUnit> original = call.getTarget().resolveMember();
            JavaClass owner = call.getTarget().getOwner();
            if (!(call instanceof JavaMethodCall methodCall) || !owner.isInterface()) {
                return original;
            }
            // interfaceのstatic/private本体は実装クラスのoverride対象ではない。
            if (original.isPresent() && (original.get().getModifiers().contains(JavaModifier.STATIC)
                    || original.get().getModifiers().contains(JavaModifier.PRIVATE))) {
                return original;
            }
            JavaClass implementation = singleImplementations.get(owner.getName());
            if (implementation == null) {
                return original;
            }
            String[] parameterTypes = methodCall.getTarget().getRawParameterTypes().stream()
                .map(JavaClass::getName).toArray(String[]::new);
            Optional<JavaClass> current = Optional.of(implementation);
            while (current.isPresent()) {
                Optional<JavaMethod> method = current.get()
                    .tryGetMethod(methodCall.getTarget().getName(), parameterTypes);
                if (method.isPresent()) {
                    // subclass側の宣言を先に採り、未呼出overloadや隠れた基底実装へ広げない。
                    return method;
                }
                current = current.get().getRawSuperclass();
            }
            // クラスに本体が無いときは、子interfaceの再宣言も含めて最具体の宣言を選ぶ。
            // 抽象宣言を先に除くと、子が再抽象化した親defaultを誤って採ってしまう。
            List<JavaMethod> declarations = implementation.getAllRawInterfaces().stream()
                .flatMap(contract -> contract.tryGetMethod(methodCall.getTarget().getName(), parameterTypes).stream())
                .filter(method -> !method.getModifiers().contains(JavaModifier.STATIC)
                    && !method.getModifiers().contains(JavaModifier.PRIVATE))
                .toList();
            List<JavaMethod> mostSpecific = declarations.stream()
                .filter(method -> declarations.stream().noneMatch(other ->
                    !other.getOwner().equals(method.getOwner())
                        && other.getOwner().isAssignableTo(method.getOwner().getName())))
                .toList();
            if (mostSpecific.size() == 1
                    && !mostSpecific.getFirst().getModifiers().contains(JavaModifier.ABSTRACT)) {
                return Optional.of(mostSpecific.getFirst());
            }
            // 解決不能・曖昧な枝を全defaultへ広げず、従来の静的解決だけを保持する。
            return original;
        }
    }

    record TransitiveViolation(
        String entryFullName,
        String sourceDomain,
        String repositoryType,
        String targetDomain,
        String diagnosticPath
    ) {
        String freezeKey() {
            return String.format(
                "@Transactional entry %s (domain '%s') reaches other-domain repository %s (domain '%s') [D-3T]",
                entryFullName, sourceDomain, repositoryType, targetDomain);
        }
    }

    private record PathNode(JavaCodeUnit codeUnit, List<String> path) { }

    private record TransactionalEntry(JavaMethod method, String logicalName) { }
}
