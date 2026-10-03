package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
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
 * （クラス宣言の場合は継承したアプリ内メソッドを含む）を入口として、アプリ内の method call と
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
 * で解決できる呼び出しだけを展開する。このため interface 呼び出しから runtime proxy の具象実装、
 * reflection、動的ディスパッチ先は追跡できない。また lambda の合成メソッドはコンパイラ実装に依存し、
 * 常に展開できるとは限らない。解決不能な枝を推測で全実装へ広げると誤検知が急増するため、これらは
 * 保守的に打ち切る。ただし呼び出し先型自体が Repository パッケージなら解決前に検出する。
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

    static List<TransitiveViolation> findViolations(JavaClass clazz) {
        String sourceDomain = DomainPackages.domainOf(clazz.getPackageName());
        if (sourceDomain == null || DomainPackages.isSharedDomain(sourceDomain)) {
            return List.of();
        }
        Map<String, TransitiveViolation> unique = new LinkedHashMap<>();
        for (TransactionalEntry entry : transactionalEntries(clazz)) {
            collectViolations(entry, sourceDomain, unique);
        }
        return unique.values().stream()
            .sorted(Comparator.comparing(TransitiveViolation::freezeKey))
            .toList();
    }

    private static List<TransactionalEntry> transactionalEntries(JavaClass clazz) {
        boolean classTransactional = clazz.isAnnotatedWith(Transactional.class);
        return clazz.getAllMethods().stream()
            .filter(method -> method.getOwner().getPackageName()
                .startsWith(DomainPackages.ROOT_PACKAGE + "."))
            .filter(method -> classTransactional
                || (method.getOwner().equals(clazz) && method.isAnnotatedWith(Transactional.class)))
            .sorted(Comparator.comparing(JavaMethod::getFullName))
            .map(method -> new TransactionalEntry(method, logicalEntryName(clazz, method)))
            .toList();
    }

    private static void collectViolations(
            TransactionalEntry entry,
            String sourceDomain,
            Map<String, TransitiveViolation> unique) {
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
                Optional<? extends JavaCodeUnit> resolved = call.getTarget().resolveMember();
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

    private static ArchCondition<JavaClass> notReachOtherDomainRepositories() {
        return new ArchCondition<>("not transitively reach other-domain repositories from @Transactional entries") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (TransitiveViolation violation : findViolations(clazz)) {
                    if (Boolean.getBoolean(DIAGNOSTICS_PROPERTY)) {
                        System.err.println("[D-3T] " + violation.diagnosticPath());
                    }
                    events.add(SimpleConditionEvent.violated(clazz, violation.freezeKey()));
                }
            }
        };
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