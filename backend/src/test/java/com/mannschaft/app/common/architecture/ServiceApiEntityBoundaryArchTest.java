package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * モジュラーモノリス境界の番人テスト（D-1 派生・API 境界ルール、Issue #2959）:
 * <b>他ドメインから呼ばれうる Service API（{@code @Service} クラスの public メソッド）は、
 * 引数・戻り値に Entity（{@code @jakarta.persistence.Entity} 付きクラス）を公開してはならない</b>。
 *
 * <h2>導入の経緯</h2>
 * <p>{@link CrossDomainEntityImportArchTest}（D-1）は「消費側がドメイン越境 Entity を
 * 直接 import しているか」を bytecode 上の直接依存で検知する。しかし
 * {@code NotificationDeliveryRunner#sendOne} が {@code NotificationEntity} を返し、
 * 呼び出し元がその戻り値を null 判定にしか使わないケース（Issue #2959）では、
 * ローカル変数へ代入して null 比較するだけで Entity のメソッド・フィールドへ一切
 * アクセスしないため、bytecode 上の直接依存としては現れず D-1 が偽陰性となる
 * （{@code CrossDomainEntityImportArchTest} は {@code getDirectDependenciesFromSelf()} を
 * 見ているため、呼び出したメソッドの戻り値型までは展開されない）。
 *
 * <p>そこで本テストは<b>消費側ではなく提供側</b>を止める。他ドメインから呼ばれる
 * Service API のシグネチャ自体に Entity が現れないようにすれば、消費側がどう
 * 使おうと越境は原理的に発生しない（提供側 API を止める方が確実、という Codex の助言）。
 *
 * <h2>検査対象</h2>
 * <p>Spring {@code @Service} が付いたクラスの public メソッド。private/protected/
 * package-private メソッドは他ドメインから直接呼べないため対象外。
 *
 * <h2>違反条件（ジェネリクス入れ子を含む全関与型を検査）</h2>
 * <p>各メソッドの<b>戻り型・全パラメータ型</b>を
 * {@link com.tngtech.archunit.core.domain.JavaType#getAllInvolvedRawTypes()} で展開し、
 * {@code @jakarta.persistence.Entity} が付いたクラスが 1 つでも含まれれば違反とする
 * （{@code Optional<Entity>}・{@code List<Entity>} のような多段ラップも捕捉する）。
 * 判定は {@link ControllerEntityResponseArchTest#isJpaEntity} と同じくアノテーション基準
 * であり、{@code *Entity} という命名では判定しない。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>本ルールは新設のため、既存の Service API に Entity を公開している箇所が
 * 大量に存在する可能性が高い。{@link FreezingArchRule} で既存違反を凍結ストア
 * （{@code src/test/resources/archunit_store/}）に記録し、<b>新規違反のみ</b> fail させる
 * （{@code archunit.properties} の {@code freeze.refreeze=false} により、既存違反を
 * 1 件解消すると自動的にストアが縮小する chip-away 運用。{@code freeze.refreeze=true} には
 * 絶対にしないこと — 新規違反まで凍結され番人が無意味になる）。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ServiceApiEntityBoundaryArchTest {

    @ArchTest
    static final ArchRule service_api_must_not_expose_entities =
        FreezingArchRule.freeze(
            methods().that().areDeclaredInClassesThat().areAnnotatedWith(
                    org.springframework.stereotype.Service.class)
                .and().arePublic()
                .should(notExposeEntitiesInSignature())
                .because("CLAUDE.md ドメイン境界の原則 — 他ドメインから呼ばれうる Service API "
                    + "（@Service クラスの public メソッド）は引数・戻り値に Entity を公開しては"
                    + "ならない（ID 参照＋DTO 経由に限る）。消費側の直接依存が bytecode に現れない"
                    + "戻り値 null 判定パターンは D-1 では検知できないため、提供側 API を止めて塞ぐ"
                    + "（Issue #2959）。既存違反は凍結し、新規違反のみ fail させる"))
                // 凍結ストアの照合キー（rule description）を固定する。
                .as("service API must not expose entities in signature (D-1 API boundary)");

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaMethod> notExposeEntitiesInSignature() {
        return new ArchCondition<>(
                "not expose @jakarta.persistence.Entity classes via return type or parameters "
                    + "(including generic type arguments)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass entityType : exposedEntityTypes(method)) {
                    String message = String.format(
                        "%s exposes @Entity class %s in its signature at %s",
                        method.getFullName(), entityType.getName(),
                        method.getSourceCodeLocation());
                    events.add(SimpleConditionEvent.violated(method, message));
                }
            }
        };
    }

    /**
     * メソッドの戻り型・全パラメータ型（ジェネリクス入れ子を含む全関与生型）のうち、
     * {@code @jakarta.persistence.Entity} が付いたクラスを<b>名前順</b>で返す。
     */
    private static List<JavaClass> exposedEntityTypes(JavaMethod method) {
        List<JavaClass> found = new ArrayList<>(
            method.getReturnType().getAllInvolvedRawTypes().stream()
                .filter(ControllerEntityResponseArchTest::isJpaEntity)
                .collect(Collectors.toList()));
        for (JavaParameter parameter : method.getParameters()) {
            found.addAll(parameter.getType().getAllInvolvedRawTypes().stream()
                .filter(ControllerEntityResponseArchTest::isJpaEntity)
                .collect(Collectors.toList()));
        }
        return found.stream()
            .distinct()
            .sorted(Comparator.comparing(JavaClass::getName))
            .collect(Collectors.toList());
    }
}
