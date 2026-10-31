package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1455: 村ドメイン全域で、モデレーター権限（村長・長老）を判定するメソッドが
 * BAN 未検査の在籍述語 {@code findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull} を使わないことを守る番人。
 *
 * <p>{@link VillageMembershipModeratorPredicateGuardTest} は changeRole / ban /
 * checkVillageBulletinModerator の3メソッドに限った局所番人だった。本番人は
 * {@code com.mannschaft.app.village} 配下の全 main ソースを読み、メソッド単位で次を検査する。</p>
 *
 * <ul>
 *   <li><b>モデレーター判定メソッド</b>: メソッド名に Moderator / Reviewer / Headman / Elder を含む、
 *       または本文（コメント・文字列を除く）が {@code HEADMAN} / {@code ELDER} を参照する
 *       （= ロール比較を含む）メソッド。</li>
 *   <li>モデレーター判定メソッドの本文で生述語を呼んだら違反。{@code .filter(bannedAt == null)} を
 *       後付けしても違反とする（リポジトリ Javadoc の正準は {@code findActiveByVillageIdAndSubject} のみ）。</li>
 *   <li>モデレーター判定でないメソッド（会員判定 {@code isUserMember} や参加時の BAN 判別 {@code join} 等）での
 *       生述語は検査しない（BAN 済みも含めて在籍状態を知る正当な用途）。</li>
 * </ul>
 *
 * <p>既存の村番人と同じくソース解析方式で、コメント・文字列/テキストブロックは
 * {@link JavaSourceScanningUtils#maskCommentsAndLiterals(String)} で潰してから判定するため、
 * コメントや文字列中の述語名・ロール名で判定が偽装されない。</p>
 */
class VillageModeratorPredicateWideGuardTest {

    private static final Path VILLAGE_ROOT = Path.of("src/main/java/com/mannschaft/app/village");
    private static final String RAW = "findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull";
    /** 単語境界で囲むため、{@code ...LeftAtIsNullAndBannedAtIsNull}（正準の実体）には一致しない。 */
    private static final Pattern RAW_CALL = Pattern.compile("\\b" + RAW + "\\b");
    private static final Pattern MODERATOR_NAME = Pattern.compile("(?i)moderator|reviewer|headman|elder");
    private static final Pattern ROLE_REFERENCE = Pattern.compile("\\b(HEADMAN|ELDER)\\b");
    private static final Pattern IDENTIFIER_BEFORE_PAREN = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$");
    private static final Pattern THROWS_TAIL = Pattern.compile("\\bthrows\\s+[\\w$.,\\s<>]+$");
    private static final Pattern TYPE_BODY_PREFIX = Pattern.compile("\\b(record|new)\\s*$");
    private static final Set<String> NON_METHOD_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");
    /** 現 main で検出されるモデレーター判定メソッドの下限（11 ヘルパ＋本体の村長操作）。番人の空振り検出用。 */
    private static final int MIN_MODERATOR_METHODS = 11;

    record Method(String file, String name, String body) {
    }

    // ------------------------------------------------------------------ 実ソース

    @Test
    void 村ドメイン全域_モデレーター判定は生述語を使わない() throws IOException {
        List<String> violations = new ArrayList<>();
        int moderatorMethods = 0;
        for (Path file : villageSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Method m : extractMethods(file.getFileName().toString(), source)) {
                if (!isModeratorMethod(m)) continue;
                moderatorMethods++;
                if (RAW_CALL.matcher(m.body()).find()) {
                    violations.add(m.file() + "#" + m.name());
                }
            }
        }
        assertThat(moderatorMethods)
                .as("モデレーター判定メソッドを検出できていること（0件なら番人の抽出が壊れている）")
                .isGreaterThanOrEqualTo(MIN_MODERATOR_METHODS);
        assertThat(violations)
                .as("モデレーター判定には正準述語 findActiveByVillageIdAndSubject を使うこと"
                        + "（生述語は BAN 済みの村長・長老も返す。CMP-260826-1455）")
                .isEmpty();
    }

    @Test
    void 実ソース_既知のモデレーターヘルパを判定対象として検出する() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path file : villageSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (Method m : extractMethods(file.getFileName().toString(), source)) {
                if (isModeratorMethod(m)) found.add(m.file() + "#" + m.name());
            }
        }
        assertThat(found).contains(
                "VillageCalendarService.java#requireModerator",
                "VillageFestivalService.java#requireHeadmanOrElder",
                "VillageInvitationService.java#requireHeadmanOrElder",
                "VillageJoinRequestService.java#ensureReviewer",
                "VillageMatchRecruitService.java#ensureRecruitReviewer",
                "VillageMeetupService.java#requireOrganizerOrModerator",
                "VillageRecruitCategoryService.java#requireModerator",
                "VillageReportService.java#requireModerator",
                "VillageRepresentativeService.java#ensureModerator",
                "VillageService.java#requireHeadmanOrSystemAdmin",
                "VillageBulletinAccessService.java#requireHeadmanOrElder",
                "VillageBulletinAccessService.java#checkVillageBulletinModerator",
                "VillageMembershipService.java#changeRole",
                "VillageMembershipService.java#ban");
    }

    // ------------------------------------------------------------------ AC-1455-1: 赤 fixture

    @Test
    void 番人_名前でモデレーター判定と分かるメソッドの生述語_拒否する() {
        String source = wrap("""
                private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
                    VillageMembershipEntity m = membershipRepository.%s(
                            villageId, VillageSubjectType.USER, actorUserId)
                            .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
                    return m;
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#requireModerator");
    }

    @Test
    void 番人_名前は中立でもロール比較を含むメソッドの生述語_拒否する() {
        String source = wrap("""
                @Transactional(readOnly = true)
                public void approveAll(UUID villageId, @Param("u") Long actorUserId) throws IOException {
                    VillageMembershipEntity actor = membershipRepository.%s(villageId, USER, actorUserId)
                            .orElseThrow();
                    if (actor.getRole() != VillageRole.HEADMAN && actor.getRole() != VillageRole.ELDER) {
                        throw new BusinessException(VillageErrorCode.FORBIDDEN);
                    }
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#approveAll");
    }

    @Test
    void 番人_生述語にbannedAtフィルタを後付けしてもモデレーター判定では_拒否する() {
        String source = wrap("""
                private void ensureReviewer(UUID villageId, Long actorUserId) {
                    membershipRepository.%s(villageId, USER, actorUserId)
                            .filter(m -> m.getBannedAt() == null)
                            .filter(m -> m.getRole() == VillageRole.ELDER)
                            .orElseThrow();
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#ensureReviewer");
    }

    @Test
    void 番人_メソッド参照で生述語を渡しても_拒否する() {
        String source = wrap("""
                private boolean isHeadman(UUID villageId, Long userId) {
                    MembershipFinder finder = membershipRepository::%s;
                    return finder.find(villageId, USER, userId).isPresent();
                }
                """.formatted(RAW));
        assertThat(source).contains("::" + RAW + ";");
        assertThat(violations(source)).containsExactly("Fixture.java#isHeadman");
    }

    @Test
    void 番人_ネストしたrecordのメソッドも走査する() {
        String source = wrap("""
                record Inner(Repo repo) {
                    boolean isModerator(UUID v, Long u) {
                        return repo.%s(v, USER, u).isPresent();
                    }
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#isModerator");
    }

    @Test
    void 番人_フィールド初期化子の匿名クラスのメソッドも走査する() {
        String source = wrap("""
                private final Object guard = new Object() {
                    boolean isModerator(UUID v, Long u) {
                        return repo.%s(v, USER, u).isPresent();
                    }
                };
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#isModerator");
    }

    @Test
    void 番人_中立メソッド内の匿名クラスのメソッドも走査する() {
        String source = wrap("""
                void schedule(UUID v, Long u) {
                    Runnable r = new Runnable() {
                        public void run() { requireElder(v, u); }
                        void requireElder(UUID v, Long u) {
                            repo.%s(v, USER, u).orElseThrow();
                        }
                    };
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#requireElder");
    }

    @Test
    void 番人_ネストした型のメソッドも走査する() {
        String source = wrap("""
                static final class Inner {
                    void requireElder(UUID v, Long u) {
                        repo.%s(v, USER, u).orElseThrow();
                    }
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#requireElder");
    }

    // ------------------------------------------------------------------ AC-1455-2: 許可（偽陽性なし）

    @Test
    void 番人_bannedAtフィルタ付きの会員判定_許可する() {
        String source = wrap("""
                private boolean isUserMember(UUID villageId, Long userId) {
                    return membershipRepository
                            .%s(villageId, VillageSubjectType.USER, userId)
                            .filter(m -> m.getBannedAt() == null)
                            .isPresent();
                }
                """.formatted(RAW));
        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 番人_参加時のBAN判別_許可する() {
        String source = wrap("""
                public MembershipResponse join(UUID villageId, Long actorUserId, MembershipJoinRequest request) {
                    Optional<VillageMembershipEntity> existing = membershipRepository
                            .%s(villageId, request.subjectType(), request.subjectId());
                    if (existing.isPresent() && existing.get().getBannedAt() != null) {
                        throw new BusinessException(VillageErrorCode.MEMBER_BANNED);
                    }
                    return null;
                }
                """.formatted(RAW));
        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 番人_モデレーター判定が正準述語だけを使う_許可する() {
        String source = wrap("""
                private VillageMembershipEntity requireHeadmanOrElder(UUID villageId, Long actorUserId) {
                    VillageMembershipEntity m = membershipRepository
                            .findActiveByVillageIdAndSubject(villageId, USER, actorUserId)
                            .orElseThrow();
                    if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
                        throw new BusinessException(VillageErrorCode.FORBIDDEN);
                    }
                    return m;
                }
                private Optional<VillageMembershipEntity> active(UUID v, Long u) {
                    return membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull(
                            v, USER, u);
                }
                """);
        assertThat(violations(source)).isEmpty();
    }

    // ------------------------------------------------------------------ AC-1455-3: コメント・文字列による偽装

    @Test
    void 番人_コメントと文字列だけのロール名で会員判定をモデレーター扱いしない() {
        String source = wrap("""
                // HEADMAN / ELDER の判定はここでは行わない
                private boolean isUserMember(UUID villageId, Long userId) {
                    /* ELDER でも BAN 済みなら false */
                    log.debug("HEADMAN check skipped");
                    return membershipRepository.%s(villageId, USER, userId)
                            .filter(m -> m.getBannedAt() == null)
                            .isPresent();
                }
                """.formatted(RAW));
        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 番人_コメントと文字列だけに生述語名があるモデレーター判定_許可する() {
        String source = wrap("""
                private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
                    // 旧実装は %s を使っていた
                    String note = "%s は使わない";
                    return membershipRepository.findActiveByVillageIdAndSubject(villageId, USER, actorUserId)
                            .orElseThrow();
                }
                """.formatted(RAW, RAW));
        assertThat(violations(source)).isEmpty();
    }

    @Test
    void 番人_正準述語がコメントにしか無く実コードが生述語_拒否する() {
        String source = wrap("""
                private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
                    // membershipRepository.findActiveByVillageIdAndSubject(villageId, USER, actorUserId)
                    String s = "findActiveByVillageIdAndSubject";
                    return membershipRepository.%s(villageId, USER, actorUserId).orElseThrow();
                }
                """.formatted(RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#requireModerator");
    }

    @Test
    void 番人_文字列中の波括弧でメソッド境界を誤認しない() {
        String source = wrap("""
                private boolean isUserMember(UUID villageId, Long userId) {
                    String s = "} private void requireModerator() {";
                    return membershipRepository.%s(villageId, USER, userId)
                            .filter(m -> m.getBannedAt() == null).isPresent();
                }
                private void requireModerator(UUID v, Long u) {
                    String t = \"""
                            { unbalanced
                            \""";
                    membershipRepository.%s(v, USER, u).orElseThrow();
                }
                """.formatted(RAW, RAW));
        assertThat(violations(source)).containsExactly("Fixture.java#requireModerator");
    }

    // ------------------------------------------------------------------ 判定ロジック

    private static List<String> violations(String source) {
        List<String> out = new ArrayList<>();
        for (Method m : extractMethods("Fixture.java", source)) {
            if (isModeratorMethod(m) && RAW_CALL.matcher(m.body()).find()) {
                out.add(m.file() + "#" + m.name());
            }
        }
        return out;
    }

    private static boolean isModeratorMethod(Method m) {
        return MODERATOR_NAME.matcher(m.name()).find() || ROLE_REFERENCE.matcher(m.body()).find();
    }

    private static List<Path> villageSources() throws IOException {
        try (Stream<Path> s = Files.walk(VILLAGE_ROOT)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertThat(files).as("村ドメインのソースが見つかること").isNotEmpty();
            return files;
        }
    }

    /**
     * コメント・文字列を潰したソースからメソッド本体（波括弧の内側）を取り出す。
     * {@code {} の直前が「識別子 + 括弧の釣り合った引数 + 任意の throws 節」であればメソッド
     * （またはコンストラクタ）とみなし、対応する閉じ括弧までを本体とする。
     *
     * <p>{@code record Inner(...) {...}} と {@code new Foo() {...}}（匿名クラス）は形が似ているが型の本体なので
     * メソッドとはみなさない。また、メソッド本体の内側も読み飛ばさずに走査を続ける。これにより、
     * ネストした record・匿名クラス（フィールド初期化子・メソッド内のいずれも）・ローカルクラスの
     * メソッドも個別のメソッドとして抽出される（外側メソッドの本文にも内側が含まれたまま残る）。</p>
     */
    static List<Method> extractMethods(String file, String source) {
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        List<Method> methods = new ArrayList<>();
        int n = masked.length();
        for (int i = 0; i < n; i++) {
            if (masked.charAt(i) != '{') continue;
            String name = methodNameBefore(masked, i);
            if (name == null) continue;
            int end = matchingBrace(masked, i);
            methods.add(new Method(file, name, masked.substring(i + 1, end < 0 ? n : end)));
        }
        return methods;
    }

    private static String methodNameBefore(String s, int bracePos) {
        String head = s.substring(0, bracePos).stripTrailing();
        Matcher t = THROWS_TAIL.matcher(head);
        if (t.find()) head = head.substring(0, t.start()).stripTrailing();
        if (head.isEmpty() || head.charAt(head.length() - 1) != ')') return null;
        int depth = 0;
        int open = -1;
        for (int k = head.length() - 1; k >= 0; k--) {
            char ch = head.charAt(k);
            if (ch == ')') depth++;
            else if (ch == '(') {
                depth--;
                if (depth == 0) {
                    open = k;
                    break;
                }
            }
        }
        if (open < 0) return null;
        Matcher id = IDENTIFIER_BEFORE_PAREN.matcher(head.substring(0, open));
        if (!id.find()) return null;
        String name = id.group(1);
        if (NON_METHOD_KEYWORDS.contains(name)) return null;
        // 注釈 @Foo(...) { はありえないが、識別子直前が @ なら除外する。
        int before = id.start() - 1;
        if (before >= 0 && head.charAt(before) == '@') return null;
        // record Inner(...) { と new Foo(...) { は型の本体であってメソッドではない。
        if (TYPE_BODY_PREFIX.matcher(head.substring(0, id.start())).find()) return null;
        return name;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return k;
            }
        }
        return -1;
    }

    private static String wrap(String members) {
        return "package x;\nclass Fixture {\n" + members + "\n}\n";
    }
}
