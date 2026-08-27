package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * モジュラーモノリス境界の番人テスト（D-1 派生・API 境界ルール、Issue #2959）:
 * <b>他ドメインから呼ばれうる Service API（{@code @Service} クラスから到達可能な public メソッド、
 * 継承分を含む）は、引数・戻り値に Entity（{@code @jakarta.persistence.Entity} 付きクラス）を
 * 公開してはならない</b>。
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
 * <p>そこで本テストは消費側ではなく提供側を止める。他ドメインから呼ばれる
 * Service API のシグネチャ自体に Entity が現れないようにすれば、消費側がどう
 * 使おうと越境は原理的に発生しない（提供側 API を止める方が確実、という Codex の助言）。
 *
 * <h2>検査対象は classes() 起点・継承分を含む（Codex 検分是正）</h2>
 * <p>初版は {@code methods().that().areDeclaredInClassesThat().areAnnotatedWith(Service.class)}
 * というメソッド起点の実装だった。{@code areDeclaredInClassesThat()} は
 * メソッドの宣言クラスを見るため、{@code @Service} クラスがアノテーションの無い
 * 親クラスやデフォルトインターフェースメソッドから継承した public メソッドは、宣言元が
 * {@code @Service} でないという理由だけで判定対象から静かに外れていた（Codex 検分 P1）。
 *
 * <p>是正後は {@code classes().that().areAnnotatedWith(Service.class)} というクラス起点に
 * 変更し、{@link JavaClass#getAllMethods()}（継承分を含む全メソッド。宣言クラスのみを返す
 * {@link JavaClass#getMethods()} とは異なる）を検査することで、親クラス由来・デフォルト
 * インターフェースメソッド由来の public メソッドも捕捉する。{@code java.lang.Object} 由来
 * （{@code equals}/{@code hashCode}/{@code toString} 等）や synthetic メソッド（ブリッジメソッド等）
 * は {@link #isEligibleServiceApiMethod(JavaMethod)} で除外する。
 *
 * <h2>重複報告の抑止</h2>
 * <p>複数の {@code @Service} クラスが同一の親クラス由来メソッドを継承している場合、そのメソッドは
 * 各サブクラスの {@code getAllMethods()} に重複して現れる。{@link JavaMethod#getFullName()}
 * （宣言元クラス＋シグネチャで一意）を鍵に {@code seenMethods} で重複排除し、同一メソッドが
 * 凍結ストアへ複数回書き込まれることを防ぐ。
 *
 * <h2>凍結ストアの照合キー（rule description）は変更しない</h2>
 * <p>{@code .as(...)} の文字列 {@code "service API must not expose entities in signature
 * (D-1 API boundary)"} は初版から据え置き。ここを変えると既存の凍結エントリが孤児化し、
 * 全違反が新規扱いで一斉に fail してしまう。検査ロジックの是正（継承分を含める）によって
 * 対象が広がった分の新規違反は、本 PR で同じキーのまま凍結し直す。
 *
 * <h2>違反条件（ジェネリクス入れ子を含む全関与型を検査）</h2>
 * <p>各メソッドの戻り型・全パラメータ型を
 * {@link com.tngtech.archunit.core.domain.JavaType#getAllInvolvedRawTypes()} で展開し、
 * {@code @jakarta.persistence.Entity} が付いたクラスが 1 つでも含まれれば違反とする
 * （{@code Optional<Entity>}・{@code List<Entity>} のような多段ラップも捕捉する）。
 * 判定は {@link ControllerEntityResponseArchTest#isJpaEntity} と同じくアノテーション基準
 * であり、{@code *Entity} という命名では判定しない。
 *
 * <h2>凍結方式（FreezingArchRule）</h2>
 * <p>{@link FreezingArchRule} で既存違反を凍結ストア（{@code src/test/resources/archunit_store/}）
 * に記録し、新規違反のみ fail させる（{@code archunit.properties} の
 * {@code freeze.refreeze=false} により、既存違反を 1 件解消すると自動的にストアが縮小する
 * chip-away 運用。{@code freeze.refreeze=true} には絶対にしないこと。新規違反まで凍結され
 * 番人が無意味になる）。
 *
 * <h2>自己検証</h2>
 * <p>{@code ServiceApiEntityBoundaryArchTestSelfVerificationTest} が、{@code @Service} の付いて
 * いない親クラスで Entity を返す public メソッドを宣言し、それを継承した {@code @Service}
 * サブクラスを用意したテスト fixture に対して、{@link #isEligibleServiceApiMethod} と
 * {@link #exposedEntityTypes} が実際に違反として検出することを固定する
 * （継承経由の捕捉が「直したつもり」で終わっていないことの裏付け）。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ServiceApiEntityBoundaryArchTest {

    @ArchTest
    static final ArchRule service_api_must_not_expose_entities =
        FreezingArchRule.freeze(
            classes().that().areAnnotatedWith(org.springframework.stereotype.Service.class)
                .should(notExposeEntitiesInReachableApi())
                .because("CLAUDE.md ドメイン境界の原則 — 他ドメインから呼ばれうる Service API "
                    + "（@Service クラスから到達可能な public メソッド。継承分を含む）は引数・戻り値に "
                    + "Entity を公開してはならない（ID 参照＋DTO 経由に限る）。消費側の直接依存が "
                    + "bytecode に現れない戻り値 null 判定パターンは D-1 では検知できないため、"
                    + "提供側 API を止めて塞ぐ（Issue #2959）。既存違反は凍結し、新規違反のみ fail させる"))
                // 凍結ストアの照合キー（rule description）を固定する。初版から変更しないこと。
                .as("service API must not expose entities in signature (D-1 API boundary)");

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /**
     * {@code @Service} クラスが持つ全メソッド（継承分を含む）のうち、対象となる public メソッドが
     * Entity を公開していないことを検査する {@link ArchCondition}。
     *
     * <p>同一メソッド（{@link JavaMethod#getFullName()} で一意）は、複数の {@code @Service}
     * サブクラスから到達可能でも 1 度しか報告しない（{@code seenMethods} による重複排除）。
     */
    static ArchCondition<JavaClass> notExposeEntitiesInReachableApi() {
        return new ArchCondition<>(
                "not expose @jakarta.persistence.Entity classes via a reachable public method's "
                    + "return type or parameters (including inherited methods and generic type "
                    + "arguments)") {

            private final Set<String> seenMethods = new HashSet<>();

            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaMethod method : clazz.getAllMethods()) {
                    if (!isEligibleServiceApiMethod(method)) {
                        continue;
                    }
                    if (!seenMethods.add(method.getFullName())) {
                        // 別の @Service サブクラス経由で既に報告済みの継承メソッド。
                        continue;
                    }
                    for (JavaClass entityType : exposedEntityTypes(method)) {
                        String message = String.format(
                            "%s exposes @Entity class %s in its signature at %s",
                            method.getFullName(), entityType.getName(),
                            method.getSourceCodeLocation());
                        events.add(SimpleConditionEvent.violated(method, message));
                    }
                }
            }
        };
    }

    /**
     * 検査対象とする public メソッドかどうか。{@code java.lang.Object} 由来
     * （{@code equals}/{@code hashCode}/{@code toString} 等）と synthetic メソッド
     * （ブリッジメソッド等、コンパイラが生成し実装が伴わない）は誤検出を避けるため除外する。
     */
    private static boolean isEligibleServiceApiMethod(JavaMethod method) {
        if (!method.getModifiers().contains(JavaModifier.PUBLIC)) {
            return false;
        }
        if (method.getModifiers().contains(JavaModifier.SYNTHETIC)) {
            return false;
        }
        return !"java.lang.Object".equals(method.getOwner().getFullName());
    }

    /**
     * メソッドの戻り型・全パラメータ型（ジェネリクス入れ子を含む全関与生型）のうち、
     * {@code @jakarta.persistence.Entity} が付いたクラスを名前順で返す。
     */
    static List<JavaClass> exposedEntityTypes(JavaMethod method) {
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
