package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1455: 村ドメイン全域で、BAN 未検査の在籍述語
 * {@code VillageMembershipRepository#findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull}（以下「生述語」）を
 * 認可判定に使わせない番人（ArchUnit のバイトコード解析）。
 *
 * <p>局所番人 {@link VillageMembershipModeratorPredicateGuardTest}（changeRole / ban / checkVillageBulletinModerator のみ）を
 * {@code com.mannschaft.app.village} 配下の全クラスへ広げる。ソースの字句解析では匿名クラス・record・
 * enum 定数本体・修飾名・型注釈の扱いで漏れが続いたため、判定の土台をバイトコードに置く。
 * バイトコードではネストした型・匿名クラス・enum 定数本体はすべて別クラスになり、コメント・文字列は
 * 呼び出しとして現れないため、それらによる誤認は構造的に起きない。</p>
 *
 * <h2>判定単位（論理メソッド）</h2>
 * <p>メソッド・コンストラクタ・static 初期化子ごとに判定する。ラムダの本体は javac が
 * {@code lambda$<囲むメソッド名>$<連番>} という合成メソッドに出すため、同じクラスの囲むメソッドへ帰属させる
 * （{@code .filter(m -> m.getBannedAt() == null)} の BAN 検査や、ラムダ内だけの生述語呼び出しを囲むメソッドの事実として扱う）。
 * メソッド参照 {@code repo::生述語} はメソッド呼び出しではなく参照として現れるので、呼び出しと参照の両方を数える。</p>
 *
 * <h2>規則</h2>
 * <ul>
 *   <li><b>R1（AC-1455-1）</b>: モデレーター判定の論理メソッドは生述語を呼ばない（BAN フィルタを後付けしても不可。
 *       正準は {@code findActiveByVillageIdAndSubject}）。モデレーター判定 = メソッド名に Moderator / Reviewer /
 *       Headman / Elder を含む、または {@code VillageRole.HEADMAN} / {@code VillageRole.ELDER} を参照する、
 *       または {@code VillageRole} で switch する（{@code VillageRole#ordinal()} を呼ぶ）。</li>
 *   <li><b>R2（AC-1455-2）</b>: それ以外で生述語を呼ぶ論理メソッドは、{@code getBannedAt} も呼ぶ（BAN フィルタ付きの会員判定・
 *       参加時の BAN 判別）か、{@link #ALLOWED_WITHOUT_BAN_CHECK} に理由付きで載っていること。</li>
 * </ul>
 *
 * <p>凍結ストアは使わない（{@code FreezingArchRule} 不使用・{@code archunit_store} に触れない）。
 * 見本は {@code fixtures.village} に本物のクラスとして置き、陽性・陰性を検出できることを確かめる。</p>
 */
@DisplayName("CMP-260826-1455 村ドメイン全域のモデレーター判定述語の番人")
@Tag(ArchUnitTestTag.ARCHUNIT)
class VillageModeratorPredicateWideGuardArchTest {

    private static final String VILLAGE_PACKAGE = "com.mannschaft.app.village";
    private static final String FIXTURE_PACKAGE = "com.mannschaft.app.common.architecture.fixtures.village";
    private static final String REPOSITORY = "com.mannschaft.app.village.repository.VillageMembershipRepository";
    private static final String ROLE_ENUM = "com.mannschaft.app.village.entity.enums.VillageRole";
    private static final String RAW = "findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull";
    private static final String BAN_CHECK = "getBannedAt";
    private static final Set<String> MODERATOR_ROLES = Set.of("HEADMAN", "ELDER");
    private static final Pattern MODERATOR_NAME = Pattern.compile("(?i)moderator|reviewer|headman|elder");
    private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$\\d+$");

    /**
     * BAN 検査なしに生述語を使ってよい論理メソッド（トップレベルクラスの単純名#メソッド名）と理由。
     * ここに足すときは、認可判定に使われないことを理由に書くこと。
     */
    static final Map<String, String> ALLOWED_WITHOUT_BAN_CHECK = Map.of(
            "VillageService#toResponse",
            "表示用の自分のロール取得。呼出し前に会員判定済みで、認可判定には使わない");

    /** 現 main で R1 の対象として検出されなければならないモデレーター判定（番人の空振り検出）。 */
    private static final Set<String> KNOWN_MODERATOR_METHODS = Set.of(
            "VillageCalendarService#requireModerator",
            "VillageFestivalService#requireHeadmanOrElder",
            "VillageInvitationService#requireHeadmanOrElder",
            "VillageJoinRequestService#ensureReviewer",
            "VillageMatchRecruitService#ensureRecruitReviewer",
            "VillageMeetupService#requireOrganizerOrModerator",
            "VillageRecruitCategoryService#requireModerator",
            "VillageReportService#requireModerator",
            "VillageRepresentativeService#ensureModerator",
            "VillageService#requireHeadmanOrSystemAdmin",
            "VillageBulletinAccessService#requireHeadmanOrElder",
            "VillageBulletinAccessService#checkVillageBulletinModerator",
            "VillageMembershipService#changeRole",
            "VillageMembershipService#ban");

    // ───────────────────────── 実ソース ─────────────────────────

    @Test
    @DisplayName("村ドメイン全域: モデレーター判定は生述語を使わず、他の生述語利用は BAN 検査付きか許可済み")
    void 村ドメイン全域_違反ゼロ() {
        Map<String, Facts> facts = analyze(importVillage());
        assertThat(violations(facts)).isEmpty();
    }

    @Test
    @DisplayName("実ソース: 既知のモデレーター判定14件を R1 の対象として検出する（空振りしない）")
    void 実ソース_既知のモデレーター判定を検出する() {
        Map<String, Facts> facts = analyze(importVillage());
        Set<String> moderators = new TreeSet<>();
        for (Facts f : facts.values()) {
            if (f.moderator) moderators.add(f.display);
        }
        assertThat(moderators).containsAll(KNOWN_MODERATOR_METHODS);
    }

    @Test
    @DisplayName("実ソース: 生述語の利用者が実在し、許可リストの各項目が実在する（規則が空振りしない・許可リストが腐らない）")
    void 実ソース_生述語利用者と許可リストが実在する() {
        Map<String, Facts> facts = analyze(importVillage());
        long rawUsers = facts.values().stream().filter(f -> f.callsRaw).count();
        assertThat(rawUsers).isGreaterThanOrEqualTo(10);
        for (String allowed : ALLOWED_WITHOUT_BAN_CHECK.keySet()) {
            assertThat(byDisplay(facts, allowed).callsRaw).as(allowed + " は生述語を使っている").isTrue();
        }
    }

    // ───────────────────────── 見本（陽性・陰性） ─────────────────────────

    @Test
    @DisplayName("見本: 陽性はすべて検出し、陰性は1件も検出しない")
    void 見本_陽性を全件検出し陰性を検出しない() {
        Set<String> found = violations(analyze(importFixtures()));
        assertThat(found).containsExactlyInAnyOrder(
                // AC-1455-1: 名前・ロール比較・filter 後付け・メソッド参照・ラムダ内・switch
                "R1 VillageGuardRogueModerators#requireModerator",
                "R1 VillageGuardRogueModerators#approveAll",
                "R1 VillageGuardRogueModerators#ensureReviewer",
                "R1 VillageGuardRogueModerators#isHeadman",
                "R1 VillageGuardRogueModerators#requireHeadmanOrElder",
                "R1 VillageGuardRogueModerators#approveBySwitch",
                // ネストした record・フィールド初期化子の匿名クラス・メソッド内の匿名クラス・enum 定数本体
                "R1 VillageGuardRogueModerators#isModeratorInRecord",
                "R1 VillageGuardRogueModerators#isModeratorInFieldAnonymous",
                "R1 VillageGuardRogueModerators#requireElderInAnonymous",
                "R1 VillageGuardRogueModerators#requireModeratorInEnumBody",
                // AC-1455-2 の裏: BAN 検査も許可もない生述語利用
                "R2 VillageGuardRogueModerators#loadForDisplay");
    }

    @Test
    @DisplayName("AC-1455-2/3: 陰性見本（BAN 検査付き会員判定・参加時 BAN 判別・正準述語・コメント/文字列の偽装・型名の Moderator）は違反ゼロ")
    void 見本_陰性は違反ゼロ() {
        Map<String, Facts> facts = analyze(importFixtures());
        Map<String, Facts> legit = new LinkedHashMap<>();
        facts.forEach((k, f) -> {
            if (f.display.startsWith("VillageGuardLegitMembers#")) legit.put(k, f);
        });
        assertThat(violations(legit)).isEmpty();
        Set<String> legitNames = new TreeSet<>();
        legit.values().forEach(f -> legitNames.add(f.display));
        // 陰性見本が空振りでないこと: 生述語を実際に呼ぶ見本と、モデレーター判定の見本がある
        assertThat(legitNames).contains(
                "VillageGuardLegitMembers#isUserMember",
                "VillageGuardLegitMembers#join",
                "VillageGuardLegitMembers#createRequest",
                "VillageGuardLegitMembers#isUserMemberInAdapter",
                "VillageGuardLegitMembers#isUserMemberInEnumBody");
        assertThat(byDisplay(legit, "VillageGuardLegitMembers#createRequest").callsRaw).isTrue();
        assertThat(byDisplay(legit, "VillageGuardLegitMembers#createRequest").callsBanCheck)
                .as("ラムダ内の getBannedAt を囲むメソッドへ帰属させる").isTrue();
        assertThat(byDisplay(legit, "VillageGuardLegitMembers#requireModeratorWithDecoyText").moderator).isTrue();
        assertThat(byDisplay(legit, "VillageGuardLegitMembers#requireModeratorWithDecoyText").callsRaw)
                .as("コメント・文字列の述語名は呼び出しではない").isFalse();
        assertThat(byDisplay(legit, "VillageGuardLegitMembers#isModeratorByFullName").callsRaw)
                .as("正準述語の実体（...AndBannedAtIsNull）は生述語ではない").isFalse();
    }

    // ───────────────────────── 判定ロジック ─────────────────────────

    /** 論理メソッド1つ分の事実。 */
    static final class Facts {
        /** 表示名（トップレベルクラスの単純名#論理メソッド名）。 */
        String display;
        boolean moderator;
        boolean callsRaw;
        boolean callsBanCheck;
    }

    private static JavaClasses importVillage() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(VILLAGE_PACKAGE);
        assertThat(classes).as("村ドメインのクラスを取り込めること").isNotEmpty();
        return classes;
    }

    private static JavaClasses importFixtures() {
        return new ClassFileImporter().importPackages(FIXTURE_PACKAGE);
    }

    static Map<String, Facts> analyze(JavaClasses classes) {
        Map<String, Facts> result = new LinkedHashMap<>();
        for (JavaClass c : classes) {
            for (JavaCodeUnit unit : c.getCodeUnits()) {
                String logicalName = logicalName(unit.getName());
                // 判定はクラス（ネスト・匿名を区別した実クラス）単位、表示はトップレベルクラス名で行う。
                Facts f = result.computeIfAbsent(c.getName() + "#" + logicalName, k -> new Facts());
                f.display = topLevelSimpleName(c) + "#" + logicalName;
                if (MODERATOR_NAME.matcher(logicalName).find()) f.moderator = true;
                if (referencesModeratorRole(unit)) f.moderator = true;
                if (callsOrReferences(unit, REPOSITORY, RAW)) f.callsRaw = true;
                if (callsOrReferences(unit, null, BAN_CHECK)) f.callsBanCheck = true;
            }
        }
        return result;
    }

    static Set<String> violations(Map<String, Facts> facts) {
        Set<String> out = new TreeSet<>();
        for (Facts f : facts.values()) {
            if (!f.callsRaw) continue;
            if (f.moderator) out.add("R1 " + f.display);
            else if (!f.callsBanCheck && !ALLOWED_WITHOUT_BAN_CHECK.containsKey(f.display)) out.add("R2 " + f.display);
        }
        return out;
    }

    /** 表示名が一意に定まる論理メソッドの事実を返す。 */
    private static Facts byDisplay(Map<String, Facts> facts, String display) {
        var hits = facts.values().stream().filter(f -> f.display.equals(display)).toList();
        assertThat(hits).as(display).hasSize(1);
        return hits.get(0);
    }

    /** ラムダの合成メソッド {@code lambda$foo$0} は囲むメソッド {@code foo} に帰属させる。 */
    private static String logicalName(String name) {
        Matcher m = LAMBDA.matcher(name);
        return m.matches() ? m.group(1) : name;
    }

    /** {@code a.b.Outer$Inner$1} → {@code Outer}。 */
    private static String topLevelSimpleName(JavaClass c) {
        String full = c.getName();
        int dollar = full.indexOf('$');
        String top = dollar < 0 ? full : full.substring(0, dollar);
        return top.substring(top.lastIndexOf('.') + 1);
    }

    private static boolean referencesModeratorRole(JavaCodeUnit unit) {
        for (JavaFieldAccess access : unit.getFieldAccesses()) {
            String owner = access.getTarget().getOwner().getName();
            String name = access.getTarget().getName();
            if (owner.equals(ROLE_ENUM) && MODERATOR_ROLES.contains(name)) return true;
            if (name.startsWith("$SwitchMap$") && name.endsWith(ROLE_ENUM.replace('.', '$'))) return true;
        }
        // VillageRole での switch は javac が VillageRole#ordinal() と合成の $SwitchMap 配列へ落とす。
        // ArchUnit は $SwitchMap への合成アクセスを取り込まないため、ordinal() の呼び出しで捉える。
        return callsOrReferences(unit, ROLE_ENUM, "ordinal");
    }

    /** メソッド呼び出しとメソッド参照の両方を見る。{@code owner} が null なら所有型を問わない。 */
    private static boolean callsOrReferences(JavaCodeUnit unit, String owner, String method) {
        return Stream.concat(
                        unit.getMethodCallsFromSelf().stream().map(call -> call.getTarget()),
                        unit.getMethodReferencesFromSelf().stream().map(ref -> ref.getTarget()))
                .anyMatch(t -> t.getName().equals(method)
                        && (owner == null || t.getOwner().getName().equals(owner)));
    }
}
