package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.MinViewRoleAwareScheduleResolver;
import com.mannschaft.app.common.architecture.fixtures.MinViewRoleBlindScheduleResolver;
import com.mannschaft.app.common.architecture.fixtures.MinViewRoleFixtureProjection;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-017b 番人（{@link ScheduleMinViewRoleGuardArchTest}）が
 * <b>実際に赤を出せる</b>ことを実証するメタテスト（AC-18）。
 *
 * <p>本プロジェクトには「安全ゲートは、発火することを実証しない限り安全ゲートではない」
 * という戒めがある。違反 0 件は番人が動いていることの証明にならないため、
 * 既知の違反（blind）と既知の合格（aware）の<b>両方</b>を食わせ、
 * 偽陰性ゼロ・偽陽性ゼロを同時に固定する。</p>
 *
 * <p>判定は番人本体の {@code public static} メソッドを<b>直接呼ぶ</b>
 * （{@code AuthzControllerGuardConditionTest} と同じ作法。判定ロジックの二重実装を避ける）。
 * fixture は番人本体が {@code DO_NOT_INCLUDE_TESTS} で読み飛ばすため本番判定には混入しない。</p>
 */
@DisplayName("CMP-017b 番人が赤を出せることの実証（メタテスト）")
class ScheduleMinViewRoleGuardConditionTest {

    private static final String FIXTURES_PACKAGE =
            "com.mannschaft.app.common.architecture.fixtures";

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 射影が列を運ぶかの判定
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("aware/blind いずれの fixture 射影も minViewRole を運ぶ（＝差は「読むか否か」だけであることの前提固定）")
    void fixture射影はminViewRoleを運ぶ() {
        assertThat(ScheduleMinViewRoleGuardArchTest.projectionCarriesMinViewRole(
                fixtureClasses.get(MinViewRoleFixtureProjection.class)))
                .as("本メタテストは「列が無いから読めない」ではなく「列はあるのに読まない」を切り分ける")
                .isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-18 本体: blind → 違反検出 / aware → 合格
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blind: 射影に列があるのに読まない Resolver は違反として検出される（偽陰性ゼロ）")
    void blindResolverは違反として検出される() {
        assertThat(ScheduleMinViewRoleGuardArchTest.readsMinViewRole(
                fixtureClasses.get(MinViewRoleBlindScheduleResolver.class)))
                .as("射影に minViewRole があるのに閲覧判定が読まない Resolver は"
                        + "違反として検出されなければならない（CMP-017b の事故そのものの形）")
                .isFalse();
    }

    @Test
    @DisplayName("aware: 射影の minViewRole を読む Resolver は合格と判定される（偽陽性ゼロ）")
    void awareResolverは合格と判定される() {
        assertThat(ScheduleMinViewRoleGuardArchTest.readsMinViewRole(
                fixtureClasses.get(MinViewRoleAwareScheduleResolver.class)))
                .as("閾値を実際に評価する Resolver を違反にしてしまう番人は使い物にならない")
                .isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-17 判定ロジックの両側実証
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blind JPQL: minViewRole を SELECT しない JPQL は違反として検出される")
    void minViewRoleを欠くJPQLは違反として検出される() {
        String blindJpql = "SELECT new com.example.P(s.id, s.teamId, s.visibility, s.status) "
                + "FROM ScheduleEntity s WHERE s.id IN :ids";
        assertThat(ScheduleMinViewRoleGuardArchTest.jpqlSelectsMinViewRole(blindJpql)).isFalse();
    }

    @Test
    @DisplayName("aware JPQL: minViewRole を SELECT する JPQL は合格と判定される")
    void minViewRoleを含むJPQLは合格と判定される() {
        String awareJpql = "SELECT new com.example.P(s.id, s.teamId, s.minViewRole, s.status) "
                + "FROM ScheduleEntity s WHERE s.id IN :ids";
        assertThat(ScheduleMinViewRoleGuardArchTest.jpqlSelectsMinViewRole(awareJpql)).isTrue();
    }

    @Test
    @DisplayName("@Query 抽出: 連結された文字列リテラルを 1 本の JPQL として取り出せる")
    void 連結されたQueryを抽出できる() {
        String source = """
                    @Query("SELECT new com.example.P("
                        + "s.id, s.minViewRole) "
                        + "FROM ScheduleEntity s WHERE s.id IN :ids")
                    List<P> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);
                """;
        assertThat(ScheduleMinViewRoleGuardArchTest.extractQueryFor(
                source, "findVisibilityProjectionsByIdIn"))
                .contains("s.minViewRole")
                .contains("FROM ScheduleEntity s");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-23c 判定ロジックの両側実証
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blind builder: includeSupporters=true × MEMBER_PLUS の builder は違反として検出される")
    void 矛盾するbuilderは違反として検出される() {
        String violating = """
                ScheduleEntity.builder()
                        .title("x")
                        .includeSupporters(true)
                        .minViewRole(MinViewRole.MEMBER_PLUS)
                        .build();
                """;
        assertThat(ScheduleMinViewRoleGuardArchTest.builderViolatesTwoAxisInvariant(violating))
                .as("応援者に出欠を配りながら応援者に見せない組み合わせは検出されなければならない")
                .isTrue();
    }

    @Test
    @DisplayName("aware builder: includeSupporters=true × SUPPORTER_PLUS の builder は合格と判定される")
    void 整合するbuilderは合格と判定される() {
        String consistent = """
                ScheduleEntity.builder()
                        .title("x")
                        .includeSupporters(true)
                        .minViewRole(MinViewRole.SUPPORTER_PLUS)
                        .build();
                """;
        assertThat(ScheduleMinViewRoleGuardArchTest.builderViolatesTwoAxisInvariant(consistent))
                .isFalse();
    }

    @Test
    @DisplayName("aware builder: includeSupporters を設定しない builder は MEMBER_PLUS でも合格と判定される")
    void 配信しないbuilderはMEMBER_PLUSでも合格() {
        String consistent = """
                ScheduleEntity.builder()
                        .title("x")
                        .minViewRole(MinViewRole.MEMBER_PLUS)
                        .build();
                """;
        assertThat(ScheduleMinViewRoleGuardArchTest.builderViolatesTwoAxisInvariant(consistent))
                .isFalse();
    }
}
