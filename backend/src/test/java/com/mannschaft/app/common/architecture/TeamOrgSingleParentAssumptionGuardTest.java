package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.SingleParentViolationFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §9.4（AC-N07）— 「チームの親組織は1つ」という単一親前提が再び入り込まないための番人。
 *
 * <p>1チームは複数の組織へ同時に ACTIVE で加盟できる。次の3つの書き方は、複数親のとき「任意の1件」に
 * 潰れる（{@code HashMap.put} の後勝ち・並び順不定の先頭・ORDER BY なしの {@code LIMIT 1}）ため禁じる。</p>
 * <ol>
 *   <li>{@code TeamOrgMembershipRepository} の {@code findFirstBy*} / {@code findTopBy*} 系メソッド
 *       （代表親組織は {@code findPrimaryParentOrganizationId} 1箇所に集約する。§9.3）</li>
 *   <li>チーム→組織を {@code Map<Long, Long>} で返すメソッド（複数親を後勝ちで潰す。
 *       {@code Map<Long, Set<Long>>} / {@code Map<Long, List<Long>>} を使う）</li>
 *   <li>{@code team_org_memberships} を読む {@code @Query} の {@code LIMIT 1}（許可リスト以外）</li>
 * </ol>
 *
 * <p>検出器の自己検証: 違反の検体（{@link SingleParentViolationFixture}）に同じ検出関数を当て、
 * 3種とも検出できる（偽陰性でない）ことを毎回確かめる。検体はテスト側にあり、本番のスキャン対象に入らない。</p>
 */
@DisplayName("F01.2.1 §9.4 単一親前提の番人（TeamOrgSingleParentAssumptionGuardTest）")
class TeamOrgSingleParentAssumptionGuardTest {

    private static final String MEMBERSHIP_REPOSITORY_FQN =
            "com.mannschaft.app.team.repository.TeamOrgMembershipRepository";

    /** チーム→組織を表す名前（findOrganizationIdByTeamIdIn 等）。 */
    private static final Pattern TEAM_TO_ORG_NAME =
            Pattern.compile("(team.*org|org.*team)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FIRST_STYLE_NAME =
            Pattern.compile("^(find|get|read|query)(First|Top|One|Any)\\d*By.*");

    private static final Pattern LIMIT_ONE = Pattern.compile("\\bLIMIT\\s+1\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code team_org_memberships} を読んで {@code LIMIT 1} を使ってよいメソッド。
     * 複数親でも結果が決定的になるよう、{@code ORDER BY} で順序を固定していること（{@link #ORDER_BY}）を併せて要求する。
     * <ul>
     *   <li>{@code ErrorReportRepository#findOrganizationIdByUserId}: §9.2 #18。直属組織 → チーム経由の
     *       代表親組織 → organization_id の順で決定的に1件採る参考情報。</li>
     * </ul>
     */
    private static final Set<String> LIMIT_ONE_ALLOWLIST = Set.of(
            "com.mannschaft.app.errorreport.repository.ErrorReportRepository.findOrganizationIdByUserId");

    private static final Pattern ORDER_BY = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);

    // =========================================================================
    // 本番コードの走査
    // =========================================================================

    @Test
    @DisplayName("AC-N07 TeamOrgMembershipRepository に findFirstBy 系メソッドが無い")
    void repositoryHasNoFindFirstStyleMethod() {
        JavaClasses production = productionClasses();
        assertThat(production.contain(MEMBERSHIP_REPOSITORY_FQN))
                .as("番人の対象クラスが存在しない（改名・移動したなら番人を追従させる）")
                .isTrue();

        assertThat(findFirstStyleViolations(production.get(MEMBERSHIP_REPOSITORY_FQN)))
                .as("代表親組織は TeamOrgMembershipQueryService#findPrimaryParentOrganizationId に集約する（§9.3）")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-N07 チーム→組織を Map<Long, Long> で返すメソッドが無い")
    void noTeamToOrganizationLongMap() {
        assertThat(teamToOrganizationLongMapViolations(productionClasses()))
                .as("複数親が後勝ちで1件に潰れる。Map<Long, Set<Long>> か Map<Long, List<Long>> を使う")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-N07 team_org_memberships を読む @Query の LIMIT 1 は許可リストの決定的な1件取得だけ")
    void noUnorderedLimitOneOnTeamOrgMemberships() {
        JavaClasses production = productionClasses();

        assertThat(limitOneViolations(production))
                .as("team_org_memberships への LIMIT 1 は任意の1件になる。許可リスト（ORDER BY 付き）以外は書かない")
                .isEmpty();

        // 許可リストの実在確認（改名・削除で許可が空振りして検出器が黙るのを防ぐ）。
        for (String allowed : LIMIT_ONE_ALLOWLIST) {
            assertThat(queryOf(production, allowed))
                    .as("許可リストの %s が見つからないか、@Query に team_org_memberships と LIMIT 1 が無い（許可を外すこと）", allowed)
                    .matches(sql -> sql != null
                            && sql.toLowerCase(Locale.ROOT).contains("team_org_memberships")
                            && LIMIT_ONE.matcher(sql).find());
        }
    }

    @Test
    @DisplayName("旧 findOrganizationIdByTeamIdIn が TeamOrgMembershipRepository に残っていない")
    void legacyMethodIsGone() {
        JavaClass repository = productionClasses().get(MEMBERSHIP_REPOSITORY_FQN);
        assertThat(repository.getMethods())
                .extracting(JavaMethod::getName)
                .doesNotContain("findOrganizationIdByTeamIdIn", "findFirstByTeamIdAndStatus");
    }

    // =========================================================================
    // 検出器の自己検証（違反の検体で落ちること）
    // =========================================================================

    @Test
    @DisplayName("自己検証: findFirstBy 系メソッドの検体を検出する")
    void detectorCatchesFindFirstSpecimen() {
        JavaClass specimen = specimenClasses().get(SingleParentViolationFixture.class);

        assertThat(findFirstStyleViolations(specimen))
                .containsExactlyInAnyOrder(
                        SingleParentViolationFixture.class.getName() + ".findFirstByTeamIdAndStatus",
                        SingleParentViolationFixture.class.getName() + ".findTopByTeamId");
    }

    @Test
    @DisplayName("自己検証: Map<Long, Long> のチーム→組織解決メソッドの検体を検出し、集合を返す形は検出しない")
    void detectorCatchesLongMapSpecimen() {
        assertThat(teamToOrganizationLongMapViolations(specimenClasses()))
                .containsExactly(SingleParentViolationFixture.class.getName() + ".findOrganizationIdByTeamIdIn");
    }

    @Test
    @DisplayName("自己検証: team_org_memberships への LIMIT 1 の検体を検出し、他テーブルの LIMIT 1 は検出しない")
    void detectorCatchesLimitOneSpecimen() {
        assertThat(limitOneViolations(specimenClasses()))
                .containsExactly(SingleParentViolationFixture.class.getName() + ".findAnyOrganizationIdByTeamId");
    }

    @Test
    @DisplayName("自己検証: ORDER BY の無い LIMIT 1 は、許可リストにあっても許さない")
    void allowedLimitOneStillNeedsOrderBy() {
        String owner = "com.example.Allowed";
        String key = owner + ".byTeam";
        String unordered = "SELECT organization_id FROM team_org_memberships WHERE team_id = :t LIMIT 1";
        String ordered = "SELECT organization_id FROM team_org_memberships WHERE team_id = :t "
                + "ORDER BY organization_id LIMIT 1";

        assertThat(isLimitOneViolation(key, unordered, Set.of(key))).isTrue();
        assertThat(isLimitOneViolation(key, ordered, Set.of(key))).isFalse();
        assertThat(isLimitOneViolation(key, ordered, Set.of())).isTrue();
    }

    // =========================================================================
    // 検出関数（本番・検体で共通）
    // =========================================================================

    static List<String> findFirstStyleViolations(JavaClass repository) {
        List<String> violations = new ArrayList<>();
        for (JavaMethod method : repository.getMethods()) {
            if (FIRST_STYLE_NAME.matcher(method.getName()).matches()) {
                violations.add(repository.getName() + "." + method.getName());
            }
        }
        return violations;
    }

    static List<String> teamToOrganizationLongMapViolations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                String returnType = method.getReturnType().getName().replace(" ", "");
                if (returnType.equals("java.util.Map<java.lang.Long,java.lang.Long>")
                        && TEAM_TO_ORG_NAME.matcher(method.getName()).find()) {
                    violations.add(javaClass.getName() + "." + method.getName());
                }
            }
        }
        return violations;
    }

    static List<String> limitOneViolations(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                String sql = querySql(method);
                String key = javaClass.getName() + "." + method.getName();
                if (sql != null && isLimitOneViolation(key, sql, LIMIT_ONE_ALLOWLIST)) {
                    violations.add(key);
                }
            }
        }
        return violations;
    }

    static boolean isLimitOneViolation(String key, String sql, Set<String> allowlist) {
        if (!sql.toLowerCase(Locale.ROOT).contains("team_org_memberships") || !LIMIT_ONE.matcher(sql).find()) {
            return false;
        }
        return !(allowlist.contains(key) && ORDER_BY.matcher(sql).find());
    }

    private static String querySql(JavaMethod method) {
        return method.tryGetAnnotationOfType(Query.class).map(Query::value).orElse(null);
    }

    private static String queryOf(JavaClasses classes, String key) {
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                if ((javaClass.getName() + "." + method.getName()).equals(key)) {
                    return querySql(method);
                }
            }
        }
        return null;
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static JavaClasses specimenClasses() {
        return new ClassFileImporter().importClasses(SingleParentViolationFixture.class);
    }
}
