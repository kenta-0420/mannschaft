package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-017b 番人 — {@code schedules.min_view_role} が「射影に載っているのに読まれない」
 * 死んだ認可軸へ再び戻ることを機械的に禁ずる。
 *
 * <p>本件の事故は「DDL に列があり、DTO で保存され、Mapper で返されるのに、
 * <b>閲覧判定だけが一切読まない</b>」という形で成立していた。列の存在も保存の存在も
 * 認可が効いている証拠にはならないため、番人は次の 3 点を独立に縛る。</p>
 *
 * <ol>
 *   <li><b>AC-16</b> {@code ScheduleVisibilityProjection} が {@code minViewRole} を運び、
 *       かつ {@code ScheduleVisibilityResolver} が {@code MinViewRole} を参照していること</li>
 *   <li><b>AC-17</b> {@code ScheduleRepository#findVisibilityProjectionsByIdIn} の JPQL が
 *       {@code minViewRole} を SELECT していること（SELECT 句とコンストラクタのズレ検出）</li>
 *   <li><b>AC-23c</b> {@code ScheduleEntity} を組み立てる自動生成経路が二軸の不変条件
 *       （{@code includeSupporters=TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}}）を破らないこと</li>
 * </ol>
 *
 * <p>判定ロジックはすべて {@code public static} メソッドとして本クラスに単一正準で置き、
 * メタテスト {@link ScheduleMinViewRoleGuardConditionTest} が fixture を食わせて
 * <b>赤を出せること</b>を実証する（判定ロジックの二重実装を禁ずる）。</p>
 */
@DisplayName("CMP-017b 番人: min_view_role が死んだ認可軸へ戻らないこと")
class ScheduleMinViewRoleGuardArchTest {

    /** 閲覧閾値 enum の完全修飾名。参照の有無はこの名前で判定する。 */
    public static final String MIN_VIEW_ROLE = "com.mannschaft.app.schedule.MinViewRole";

    private static final String PROJECTION =
            "com.mannschaft.app.schedule.visibility.ScheduleVisibilityProjection";
    private static final String RESOLVER =
            "com.mannschaft.app.schedule.visibility.ScheduleVisibilityResolver";

    private static final Path SCHEDULE_REPOSITORY = Path.of(
            "src/main/java/com/mannschaft/app/schedule/repository/ScheduleRepository.java");

    /** 自動生成経路 3 箇所（AC-23c）。いずれも {@code ScheduleEntity.builder()} を直接組む。 */
    private static final List<Path> SCHEDULE_BUILDER_SOURCES = List.of(
            Path.of("src/main/java/com/mannschaft/app/schedule/service/GoogleCalendarWebhookService.java"),
            Path.of("src/main/java/com/mannschaft/app/todo/service/TodoScheduleLinkService.java"),
            Path.of("src/main/java/com/mannschaft/app/timetable/personal/listener/"
                    + "PersonalTimetableLinkSyncListener.java"));

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProduction() {
        // DoNotIncludeTests: メタテストの fixture を本番判定へ混入させない
        //（VisibilityArchitectureTest と同じ作法）。
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.mannschaft.app.schedule");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 判定ロジック（単一正準・メタテストから直接呼ばれる）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 射影クラスが {@code minViewRole} を運んでいるか判定する。
     *
     * @param projection 判定対象の射影クラス
     * @return {@code MinViewRole} 型の {@code minViewRole} フィールドを持つなら true
     */
    public static boolean projectionCarriesMinViewRole(JavaClass projection) {
        return projection.tryGetField("minViewRole")
                .map(field -> MIN_VIEW_ROLE.equals(field.getRawType().getName()))
                .orElse(false);
    }

    /**
     * 判定側クラスが {@code MinViewRole} を実際に読んでいるか判定する。
     *
     * <p>列を射影に載せただけで読まない（＝本件の事故の形）を検出するため、
     * 型への依存・メソッド呼出・フィールドアクセスのいずれかで
     * {@code MinViewRole} に触れていることを要求する。</p>
     *
     * @param evaluator 判定側クラス（Resolver 等）
     * @return {@code MinViewRole} を参照しているなら true
     */
    public static boolean readsMinViewRole(JavaClass evaluator) {
        boolean viaDependency = evaluator.getDirectDependenciesFromSelf().stream()
                .anyMatch(dep -> MIN_VIEW_ROLE.equals(dep.getTargetClass().getName()));
        boolean viaMethodCall = evaluator.getMethodCallsFromSelf().stream()
                .anyMatch(call -> MIN_VIEW_ROLE.equals(call.getTarget().getOwner().getName())
                        || MIN_VIEW_ROLE.equals(call.getTarget().getRawReturnType().getName()));
        boolean viaFieldAccess = evaluator.getFieldAccessesFromSelf().stream()
                .anyMatch(access -> MIN_VIEW_ROLE.equals(access.getTarget().getRawType().getName()));
        return viaDependency || viaMethodCall || viaFieldAccess;
    }

    /**
     * 射影ロード用 JPQL が {@code minViewRole} を SELECT しているか判定する。
     *
     * <p>射影 record に列を足しても JPQL の SELECT 句を直さなければ
     * コンストラクタ実引数の数が合わず、あるいは黙って別列が入る。</p>
     *
     * @param jpql 判定対象の JPQL 文字列
     * @return {@code minViewRole} が現れるなら true
     */
    public static boolean jpqlSelectsMinViewRole(String jpql) {
        return jpql != null && jpql.contains("minViewRole");
    }

    /**
     * Java ソース中の {@code ScheduleEntity.builder()} 連鎖が二軸の不変条件を破っているか判定する。
     *
     * <p>破っている＝ {@code includeSupporters(true)} と
     * {@code minViewRole(MinViewRole.MEMBER_PLUS)}／{@code ADMIN_ONLY} を同時に設定している。</p>
     *
     * @param javaSource 判定対象の Java ソース全文
     * @return 不変条件を破る builder 連鎖が 1 つでもあれば true
     */
    public static boolean builderViolatesTwoAxisInvariant(String javaSource) {
        if (javaSource == null) {
            return false;
        }
        String[] chains = javaSource.split("ScheduleEntity\\.builder\\(\\)");
        for (int i = 1; i < chains.length; i++) {
            String chain = chains[i];
            int end = chain.indexOf(".build()");
            String body = end >= 0 ? chain.substring(0, end) : chain;
            String compact = body.replaceAll("\\s+", "");
            boolean supportersIncluded = compact.contains("includeSupporters(true)")
                    || compact.contains("includeSupporters(Boolean.TRUE)");
            boolean thresholdTooHigh = compact.contains("minViewRole(MinViewRole.MEMBER_PLUS)")
                    || compact.contains("minViewRole(MinViewRole.ADMIN_ONLY)");
            if (supportersIncluded && thresholdTooHigh) {
                return true;
            }
        }
        return false;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 番人本体
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-16 ScheduleVisibilityProjection が minViewRole を運んでいる")
    void 射影がminViewRoleを運ぶ() {
        assertThat(projectionCarriesMinViewRole(productionClasses.get(PROJECTION)))
                .as("射影に minViewRole が無い限り Resolver は閾値を物理的に評価できない"
                        + "（CMP-017b の事故の起点）")
                .isTrue();
    }

    @Test
    @DisplayName("AC-16 ScheduleVisibilityResolver が MinViewRole を参照している（列を足したが読まない、の再発防止）")
    void resolverがminViewRoleを読む() {
        assertThat(readsMinViewRole(productionClasses.get(RESOLVER)))
                .as("射影に列を足しただけで判定が読まなければ認可軸は死んだままである")
                .isTrue();
    }

    @Test
    @DisplayName("AC-17 findVisibilityProjectionsByIdIn の JPQL が minViewRole を SELECT している")
    void 射影JPQLがminViewRoleをselectする() throws IOException {
        String source = Files.readString(SCHEDULE_REPOSITORY, StandardCharsets.UTF_8);
        String jpql = extractQueryFor(source, "findVisibilityProjectionsByIdIn");

        assertThat(jpqlSelectsMinViewRole(jpql))
                .as("射影 record に列を足しても JPQL の SELECT 句が追随しなければ"
                        + "コンストラクタとズレる。抽出した JPQL: %s", jpql)
                .isTrue();
    }

    @Test
    @DisplayName("AC-23c 自動生成経路 3 箇所が二軸の不変条件を破らない")
    void 自動生成経路が不変条件を破らない() throws IOException {
        for (Path source : SCHEDULE_BUILDER_SOURCES) {
            String javaSource = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(builderViolatesTwoAxisInvariant(javaSource))
                    .as("%s が includeSupporters=TRUE と MEMBER_PLUS/ADMIN_ONLY を同時に設定している"
                            + "（応援者に出欠を配るが応援者は見られない自己矛盾）", source)
                    .isFalse();
        }
    }

    /**
     * メソッド宣言の直前にある {@code @Query("...")} の中身を連結して取り出す。
     *
     * @param source     Java ソース全文
     * @param methodName 対象メソッド名
     * @return JPQL 文字列（見つからなければ空文字）
     */
    static String extractQueryFor(String source, String methodName) {
        int methodAt = source.indexOf(methodName + "(");
        if (methodAt < 0) {
            return "";
        }
        int queryAt = source.lastIndexOf("@Query", methodAt);
        if (queryAt < 0) {
            return "";
        }
        String annotation = source.substring(queryAt, methodAt);
        StringBuilder jpql = new StringBuilder();
        Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(annotation);
        while (matcher.find()) {
            jpql.append(matcher.group(1));
        }
        return jpql.toString();
    }
}
