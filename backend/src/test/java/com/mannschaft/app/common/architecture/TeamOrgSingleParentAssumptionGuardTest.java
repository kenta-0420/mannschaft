package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.SingleParentReductionFixture;
import com.mannschaft.app.common.architecture.fixtures.SingleParentViolationFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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
 *   <li>{@code team_org_memberships} を読む {@code @Query} の「取得件数が1」の {@code LIMIT}（許可リスト以外）。
 *       {@code LIMIT 1}・{@code LIMIT 1 OFFSET n}・{@code LIMIT n, 1} が対象で、{@code LIMIT n, 20}
 *       （offset が n、件数が 20）は対象外</li>
 *   <li>チーム→親組織の集合を取得したメソッドが、同じメソッド内で {@code Stream.findFirst/findAny}・
 *       {@code List.get/getFirst} により1件へ縮約すること
 *       （代表親組織を採る正当な箇所は {@link #REDUCTION_ALLOWLIST} に明示する。許可は
 *       順序付き取得（{@link #ORDERED_READERS}）との組合せに限り、順序なしの取得へ差し替えると違反になる）</li>
 * </ol>
 *
 * <p><b>検出対象外（静的解析の限界）</b>: 縮約が別メソッドやラムダ本体（コンパイラが別メソッドに切り出す）にある場合、
 * {@code collect}・{@code reduce}・ローカル {@code var} を経由して別メソッドへ渡してから縮約する場合、
 * {@code Map<Long, Long>} を継承した独自クラスを返す場合、{@code Iterator.next} で先頭だけ採る形
 * （拡張 for の全件走査と区別できないため {@code Iterator.next} は縮約として数えない）は検出できない。無理に広げると偽陽性が増えるため、
 * これらは検分（人の目）と {@code TeamOrgMultiParent*IT} / {@code TeamOrgSingleParentResponseParityIT} で担保する。</p>
 *
 * <p>検出器の自己検証: 違反の検体（{@link SingleParentViolationFixture}）に同じ検出関数を当て、
 * 3種とも検出できる（偽陰性でない）ことを毎回確かめる。検体はテスト側にあり、本番のスキャン対象に入らない。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("F01.2.1 §9.4 単一親前提の番人（TeamOrgSingleParentAssumptionGuardTest）")
class TeamOrgSingleParentAssumptionGuardTest {

    private static final String MEMBERSHIP_REPOSITORY_FQN =
            "com.mannschaft.app.team.repository.TeamOrgMembershipRepository";

    /** チーム→組織を表す名前（findOrganizationIdByTeamIdIn 等）。 */
    private static final Pattern TEAM_TO_ORG_NAME =
            Pattern.compile("(team.*org|org.*team)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FIRST_STYLE_NAME =
            Pattern.compile("^(find|get|read|query)(First|Top|One|Any)\\d*By.*");

    /**
     * {@code LIMIT count} / {@code LIMIT offset, count} / {@code LIMIT count OFFSET offset}。
     * グループ1が最初の数値、グループ2が {@code ,} の後の数値（あれば件数はこちら）。量指定子の入れ子が無く、バックトラックは線形。
     */
    private static final Pattern LIMIT_CLAUSE =
            Pattern.compile("\\bLIMIT\\s+(\\d+)(?:\\s*,\\s*(\\d+))?", Pattern.CASE_INSENSITIVE);

    /** 縮約の起点になる、チーム→親組織の集合を返すメソッド（所有クラス#メソッド名）。 */
    private static final Set<String> TEAM_ORG_COLLECTION_READERS = Set.of(
            MEMBERSHIP_REPOSITORY_FQN + "#findByTeamIdAndStatus",
            MEMBERSHIP_REPOSITORY_FQN + "#findActiveByTeamIdOrderByRespondedAtAndOrganizationId",
            MEMBERSHIP_REPOSITORY_FQN + "#findOrganizationIdsByTeamIdIn",
            MEMBERSHIP_REPOSITORY_FQN + "#findOrganizationIdsInPrimaryOrderByTeamIdIn",
            MEMBERSHIP_REPOSITORY_FQN + "#findDistinctOrganizationIdsByTeamIdIn",
            "com.mannschaft.app.team.service.TeamOrgMembershipQueryService#findActiveOrganizationIds",
            "com.mannschaft.app.team.service.TeamOrgMembershipQueryService#findActiveOrganizationIdsInPrimaryOrder");

    /** 集合を1件へ縮約する呼び出し（所有クラス#メソッド名）。 */
    private static final Set<String> REDUCTION_CALLS = Set.of(
            "java.util.stream.Stream#findFirst",
            "java.util.stream.Stream#findAny",
            "java.util.List#get",
            "java.util.List#getFirst",
            "java.util.SequencedCollection#getFirst");

    /**
     * 許可リストのメソッド内で禁じる呼び出し。許可するのは「順序付き取得の先頭の抽出（get(0)・getFirst・findFirst）」だけで、
     * 末尾の抽出（{@code get(size - 1)}・{@code getLast}・{@code reversed}）やそれ以外の縮約
     * （{@code reduce}・{@code skip}・{@code max}・{@code min}・{@code Collections.reverse}）は許可メソッド内でも拒否する。
     * 定数インデックスの値そのもの（get(1) 等）はバイトコード上の定数で、この静的解析では読めない（検出対象外）。
     */
    private static final Set<String> NON_HEAD_EXTRACTION_CALLS = Set.of(
            "java.util.List#size",
            "java.util.Collection#size",
            "java.util.List#getLast",
            "java.util.SequencedCollection#getLast",
            "java.util.List#reversed",
            "java.util.Collections#reverse",
            "java.util.stream.Stream#findAny",
            "java.util.stream.Stream#reduce",
            "java.util.stream.Stream#skip",
            "java.util.stream.Stream#max",
            "java.util.stream.Stream#min");

    /**
     * 代表親組織の許可に使ってよい、成立時刻 → organization_id で順序を固定した取得（§9.3）。
     * 許可リストのメソッドも、これ以外の reader（順序なし）を呼べば違反になる。
     */
    private static final Set<String> ORDERED_READERS = Set.of(
            MEMBERSHIP_REPOSITORY_FQN + "#findActiveByTeamIdOrderByRespondedAtAndOrganizationId",
            MEMBERSHIP_REPOSITORY_FQN + "#findOrganizationIdsInPrimaryOrderByTeamIdIn",
            "com.mannschaft.app.team.service.TeamOrgMembershipQueryService#findActiveOrganizationIdsInPrimaryOrder");

    /**
     * 代表親組織（§9.3: 最初に成立した加盟 → organization_id 昇順）を採る正当な縮約。順序を DB で固定した取得結果の
     * 先頭を採るもの限定。
     * <ul>
     *   <li>{@code TeamOrgMembershipQueryService#findPrimaryParentOrganizationId}: 代表親組織の唯一の入口</li>
     *   <li>{@code ScheduleService#resolveOrganizationIdForTeam}: 予約タスクのテナントキー（§9.2 #8）</li>
     *   <li>{@code MeController#getMyTeams}: 互換フィールド organizationId（§9.2 #7）</li>
     * </ul>
     */
    private static final Set<String> REDUCTION_ALLOWLIST = Set.of(
            "com.mannschaft.app.team.service.TeamOrgMembershipQueryService.findPrimaryParentOrganizationId",
            "com.mannschaft.app.schedule.service.ScheduleService.resolveOrganizationIdForTeam",
            "com.mannschaft.app.role.controller.MeController.getMyTeams");

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
                            && hasLimitCountOne(sql));
        }
    }

    @Test
    @DisplayName("AC-N07 チーム→親組織の集合を、代表親組織の入口以外で1件へ縮約していない")
    void noReductionOfParentOrganizationsToSingleValue() {
        JavaClasses production = productionClasses();

        assertThat(reductionViolations(production, REDUCTION_ALLOWLIST))
                .as("複数親が任意の1件に潰れる。代表親組織が要るなら findPrimaryParentOrganizationId を使う")
                .isEmpty();

        // 許可リストの実在確認（改名・削除で許可が空振りして検出器が黙るのを防ぐ）。
        List<String> detectedWithoutAllowlist = reductionViolations(production, Set.of());
        for (String allowed : REDUCTION_ALLOWLIST) {
            assertThat(detectedWithoutAllowlist)
                    .as("許可リストの %s が縮約として検出されない（改名・削除したなら許可を外すこと）", allowed)
                    .contains(allowed);
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
        String owner = SingleParentViolationFixture.class.getName();
        assertThat(teamToOrganizationLongMapViolations(specimenClasses()))
                .containsExactlyInAnyOrder(
                        owner + ".findOrganizationIdByTeamIdIn",
                        owner + ".resolveTeamOrganizationsAsHashMap",
                        owner + ".resolveTeamOrganizationsAsLinkedHashMap");
    }

    @Test
    @DisplayName("自己検証: 集合を findFirst・List.get で1件へ縮約する実装の検体を検出し、contains だけの検体と代表親の許可は検出しない")
    void detectorCatchesReductionSpecimen() {
        String owner = SingleParentReductionFixture.class.getName();
        JavaClasses specimens = new ClassFileImporter().importClasses(SingleParentReductionFixture.class);

        // 全件走査（拡張 for）・包含判定・無関係な List.get は縮約ではないので、どの場合も出ない。
        assertThat(reductionViolations(specimens, Set.of()))
                .containsExactlyInAnyOrder(
                        owner + ".reduceByFindFirst",
                        owner + ".reduceByListGet",
                        owner + ".representativeParent",
                        owner + ".representativeParentUnordered",
                        owner + ".representativeParentLast",
                        owner + ".representativeParentByFindAny");
        // 代表親組織を採る正当な箇所は、明示した許可＋順序付き取得の組合せだけが通る。
        // 許可があっても順序なし取得へ差し替えた representativeParentUnordered は検出される。
        assertThat(reductionViolations(specimens,
                Set.of(owner + ".representativeParent", owner + ".representativeParentUnordered",
                        owner + ".representativeParentLast", owner + ".representativeParentByReduce",
                        owner + ".representativeParentByFindAny")))
                .containsExactlyInAnyOrder(
                        owner + ".reduceByFindFirst",
                        owner + ".reduceByListGet",
                        owner + ".representativeParentUnordered",
                        owner + ".representativeParentLast",
                        owner + ".representativeParentByReduce",
                        owner + ".representativeParentByFindAny");
    }

    @Test
    @DisplayName("自己検証: team_org_memberships への LIMIT 1 の検体を検出し、他テーブルの LIMIT 1 は検出しない")
    void detectorCatchesLimitOneSpecimen() {
        String owner = SingleParentViolationFixture.class.getName();
        assertThat(limitOneViolations(specimenClasses()))
                .containsExactlyInAnyOrder(
                        owner + ".findAnyOrganizationIdByTeamId",
                        owner + ".findAnyOrganizationIdByTeamIdOffsetZero");
    }

    @Test
    @DisplayName("自己検証: LIMIT offset, count は件数側で判定する（LIMIT 0, 1 は違反・LIMIT 1, 20 は非違反）")
    void limitCountIsDistinguishedFromOffset() {
        String head = "SELECT organization_id FROM team_org_memberships WHERE team_id = :t ";
        assertThat(isLimitOneViolation("k", head + "LIMIT 0, 1", Set.of())).isTrue();
        assertThat(isLimitOneViolation("k", head + "LIMIT 0,1", Set.of())).isTrue();
        assertThat(isLimitOneViolation("k", head + "LIMIT 1 OFFSET 5", Set.of())).isTrue();
        assertThat(isLimitOneViolation("k", head + "LIMIT 1", Set.of())).isTrue();
        assertThat(isLimitOneViolation("k", head + "LIMIT 1, 20", Set.of())).isFalse();
        assertThat(isLimitOneViolation("k", head + "LIMIT 10", Set.of())).isFalse();
        assertThat(isLimitOneViolation("k", head + "LIMIT 20 OFFSET 1", Set.of())).isFalse();
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
                if (isLongToLongMap(method) && TEAM_TO_ORG_NAME.matcher(method.getName()).find()) {
                    violations.add(javaClass.getName() + "." + method.getName());
                }
            }
        }
        return violations;
    }

    /** 戻り値が Map&lt;Long, Long&gt;（raw 型は Map、型引数は JavaParameterizedType から読む）。 */
    private static boolean isLongToLongMap(JavaMethod method) {
        // Map インタフェースだけでなく HashMap・LinkedHashMap 等の実装型で返す書き方も対象にする。
        if (!method.getRawReturnType().isAssignableTo(java.util.Map.class)) {
            return false;
        }
        if (!(method.getReturnType() instanceof JavaParameterizedType parameterized)) {
            return false;
        }
        List<JavaType> args = parameterized.getActualTypeArguments();
        return args.size() == 2
                && args.get(0).toErasure().isEquivalentTo(Long.class)
                && args.get(1).toErasure().isEquivalentTo(Long.class);
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
        if (!sql.toLowerCase(Locale.ROOT).contains("team_org_memberships") || !hasLimitCountOne(sql)) {
            return false;
        }
        return !(allowlist.contains(key) && ORDER_BY.matcher(sql).find());
    }

    /** いずれかの LIMIT 句の取得件数が 1（{@code LIMIT n, c} は c、それ以外は最初の数値が件数）。 */
    static boolean hasLimitCountOne(String sql) {
        Matcher matcher = LIMIT_CLAUSE.matcher(sql);
        while (matcher.find()) {
            String count = matcher.group(2) != null ? matcher.group(2) : matcher.group(1);
            if (Long.parseLong(count) == 1L) {
                return true;
            }
        }
        return false;
    }

    /**
     * チーム→親組織の集合を取得したメソッドが、同じメソッド内で1件へ縮約している箇所（{@code owner.method}）。
     * ラムダ本体は別メソッドに切り出されるため対象外（クラス Javadoc の「検出対象外」）。
     */
    static List<String> reductionViolations(JavaClasses classes, Set<String> allowlist) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethod method : javaClass.getMethods()) {
                String key = javaClass.getName() + "." + method.getName();
                boolean allowed = allowlist.contains(key);
                boolean readsParents = false;
                boolean readsUnordered = false;
                boolean reduces = false;
                boolean nonHead = false;
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    String callee = call.getTargetOwner().getName() + "#" + call.getName();
                    if (TEAM_ORG_COLLECTION_READERS.contains(callee)) {
                        readsParents = true;
                        readsUnordered |= !ORDERED_READERS.contains(callee);
                    }
                    reduces |= REDUCTION_CALLS.contains(callee);
                    nonHead |= NON_HEAD_EXTRACTION_CALLS.contains(callee);
                }
                // 許可されたメソッドは、順序付き取得の先頭の抽出に限って縮約を許す
                // （順序なし取得・末尾抽出・その他の縮約は許可メソッド内でも拒否する）。
                if (readsParents && (reduces || (allowed && nonHead))
                        && (!allowed || readsUnordered || nonHead)) {
                    violations.add(key);
                }
            }
        }
        return violations;
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
        return ProductionClasses.get();
    }

    private static JavaClasses specimenClasses() {
        return new ClassFileImporter().importClasses(SingleParentViolationFixture.class);
    }
}
