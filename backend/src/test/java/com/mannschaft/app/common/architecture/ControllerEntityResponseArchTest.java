package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Controller レスポンスの JPA Entity 露出禁止の番人テスト（D-6）: <b>{@code @RestController}/
 * {@code @Controller} の公開 Mapping エンドポイントは、戻り値に JPA Entity
 * （{@code @jakarta.persistence.Entity} 付きクラス）を露出してはならない</b>。
 *
 * <p>設計指針: {@code CLAUDE.md}「ドメイン境界の原則」— API 応答は DTO を介すること。
 * Entity をそのまま返すと (1) 永続層の内部構造・機微カラムが API 契約へ漏れる、
 * (2) OpenAPI スキーマが Entity のグラフ（遅延ロード関連含む）へ膨張する、
 * (3) ドメイン間の Entity が応答型経由で越境する、という害がある。
 * これらを機械的に検知する。
 *
 * <h2>検査対象</h2>
 * <p>{@link ControllerEndpoints#areMappingEndpointsOfControllerClasses()} が選別する
 * 「{@code @RestController}/{@code @Controller} クラスの public Mapping メソッド」（＝公開EP）。
 *
 * <h2>違反条件（ジェネリクス入れ子を含む全関与型を検査）</h2>
 * <p>各 EP メソッドの戻り型を {@link com.tngtech.archunit.core.domain.JavaType#getAllInvolvedRawTypes()}
 * （ArchUnit 1.3.0）で展開し、<b>ジェネリクスの型引数を再帰的に含む全ての生型</b>を得る。
 * その中に {@code @jakarta.persistence.Entity} が付いたクラスが 1 つでも含まれれば違反とする。
 * これにより、素の {@code Entity} 返しだけでなく
 * {@code ApiResponse<Entity>}・{@code Page<Entity>}・
 * {@code ResponseEntity<ApiResponse<Page<Entity>>>} のような多段ラップも捕捉する。
 *
 * <h2>判定は {@code @Entity} アノテーション基準（命名判定はしない）</h2>
 * <p>{@code *Entity} という<b>クラス名</b>での判定は行わない。本リポには {@code *Entity} で
 * 終わらない {@code @Entity} 付きクラスが多数存在するため、必ず
 * {@code @jakarta.persistence.Entity} アノテーションの有無で判定する。
 *
 * <h2>凍結しない恒久ルール</h2>
 * <p>Controller から Entity を返す既存違反は Summary/Response DTO 化の是正
 * （PR #2472/#2473 等）で解消済みであり、現 HEAD では<b>違反 0 件</b>である。したがって
 * {@link com.tngtech.archunit.library.freeze.FreezingArchRule} を用いず、素の
 * {@link ArchRule} として<b>恒久導入</b>する（新規に Entity 返し EP が追加された瞬間に fail）。
 * 凍結ストアを持たないため {@link ArchUnitFreezeStoreIntegrityTest} の管理対象外。
 *
 * <h2>限界</h2>
 * <ul>
 *   <li>DTO の<b>フィールド内</b>に Entity を保持しているケースは対象外
 *       （戻り型のジェネリクスにしか現れないため）。</li>
 *   <li>{@code @RequestBody} で Entity を直バインドする引数側は対象外
 *       （本ルールは戻り型のみを見る）。</li>
 * </ul>
 *
 * <p>合格判定の単一正準は {@link #exposedEntityReturnTypes(JavaMethod)}。本番番人と
 * 偽陰性ゼロ証明メタテスト {@code ControllerEntityResponseConditionTest} の双方から呼ばれる。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ControllerEntityResponseArchTest {

    /** JPA Entity マーカーの FQN。命名判定ではなくこのアノテーション有無で Entity を判定する。 */
    static final String JPA_ENTITY_ANNOTATION = "jakarta.persistence.Entity";

    @ArchTest
    static final ArchRule controller_endpoints_must_not_expose_jpa_entities =
        methods().that(ControllerEndpoints.areMappingEndpointsOfControllerClasses())
            .should(notExposeJpaEntitiesInReturnType())
            .because("CLAUDE.md ドメイン境界の原則 — 公開Controllerエンドポイントは "
                + "@jakarta.persistence.Entity を戻り値（ジェネリクス入れ子を含む）に露出してはならない。"
                + "永続層の内部構造・機微カラムの API 漏洩・OpenAPI スキーマ膨張・Entity の越境を防ぐため、"
                + "応答は必ず DTO を介すこと。既存違反は DTO 化で解消済みのため凍結せず恒久ルールとする")
            // 恒久ルールのため凍結ストアは持たないが、照合しやすいよう rule description を固定する。
            .as("controller endpoints must not expose JPA entities (D-6)");

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaMethod> notExposeJpaEntitiesInReturnType() {
        return new ArchCondition<>(
                "not expose @jakarta.persistence.Entity classes via return type "
                    + "(including generic type arguments)") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                // (メソッド, Entity型) 単位で、Entity型は名前順に安定させて event 化する。
                for (JavaClass entityType : exposedEntityReturnTypes(method)) {
                    String message = String.format(
                        "%s exposes @Entity class %s via its return type %s at %s",
                        method.getFullName(), entityType.getName(),
                        method.getReturnType().getName(), method.getSourceCodeLocation());
                    events.add(SimpleConditionEvent.violated(method, message));
                }
            }
        };
    }

    /**
     * メソッドの戻り型（ジェネリクス入れ子を含む全関与生型）のうち、
     * {@code @jakarta.persistence.Entity} が付いたクラスを<b>名前順</b>で返す。
     *
     * <p>本番番人（{@link #notExposeJpaEntitiesInReturnType()}）と偽陰性ゼロ証明メタテスト
     * {@code ControllerEntityResponseConditionTest} の双方から呼ばれる合格判定の単一正準。
     * 判定ロジックを二重実装しないため package-visible の static ヘルパとして公開する。
     */
    static List<JavaClass> exposedEntityReturnTypes(JavaMethod method) {
        return method.getReturnType().getAllInvolvedRawTypes().stream()
            .filter(ControllerEntityResponseArchTest::isJpaEntity)
            .sorted(Comparator.comparing(JavaClass::getName))
            .collect(Collectors.toList());
    }

    /** クラスに {@code @jakarta.persistence.Entity} が付いているか（命名判定はしない）。 */
    static boolean isJpaEntity(JavaClass clazz) {
        return clazz.isAnnotatedWith(JPA_ENTITY_ANNOTATION);
    }
}
