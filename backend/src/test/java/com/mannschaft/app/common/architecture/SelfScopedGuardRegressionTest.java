package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 試練D（第三陣）: {@code @SelfScopedEndpoint} 注釈を削除した後も
 * {@link AuthzControllerGuardArchTest} / {@link SelfScopedEndpointMarkerGuardTest} が
 * 緑であることを固定する回帰テスト。
 *
 * <h2>背景</h2>
 * <p>{@code @SelfScopedEndpoint} は「リソースID・スコープIDを受け取り検索条件に用いる EP には
 * 付与してはならない」という Javadoc 上の禁止に抵触していた 5 メソッドから、注釈と虚偽の
 * 根拠コメントを削除し、代わりに {@code AccessControlService} を Service から直接呼ぶ実装へ
 * 移行する戦役（PR #3326〜#3328）の検分用試練。</p>
 *
 * <h2>AC-34</h2>
 * <p>5 メソッドが、{@code @SelfScopedEndpoint} 注釈（マーカーシグナル C）に一切頼らず、
 * 呼び出しグラフ経由の認可シグナル（{@link AuthzControllerGuardArchTest} のシグナル B）だけで
 * captured されることを、{@code hasAuthorizationCallSignal} を private のままリフレクションで
 * 直接呼び出して検証する。マーカー付き・無しのどちらの状態でも「マーカーに頼らない」ことを
 * 検証したいため、{@code hasAuthorizationSignal}（マーカーも見る）ではなく、あえて
 * マーカー非依存のシグナル B のみを見る。</p>
 *
 * <p><b>2026-09-17 時点の実測</b>: 本戦役は PR #3326（{@code PerformancePersonalController
 * #getMyPerformance}）のみ {@code origin/main} にマージ済みで、PR #3327（Shift 3メソッド）・
 * PR #3328（{@code NotificationPreferenceController#updatePreference}）は未マージ。
 * 未マージの 4 メソッドは {@code Service} 層がまだ {@code AccessControlService} を呼んでおらず、
 * 本試練は該当パラメータで <b>意図的に red</b> になる（試練の性質上、これは正しい）。</p>
 */
class SelfScopedGuardRegressionTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void importClasses() {
        importedClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mannschaft.app");
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-34
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-34: 5メソッドは注釈に頼らず call-based 認可シグナルで captured される")
    class Ac34CallBasedSignal {

        static Stream<Arguments> targets() {
            return Stream.of(
                Arguments.of(
                    "com.mannschaft.app.shift.controller.ShiftAvailabilityController",
                    "getAvailabilityDefaults"),
                Arguments.of(
                    "com.mannschaft.app.shift.controller.ShiftAvailabilityController",
                    "setAvailabilityDefaults"),
                Arguments.of(
                    "com.mannschaft.app.shift.controller.ShiftAvailabilityController",
                    "deleteAvailabilityDefaults"),
                Arguments.of(
                    "com.mannschaft.app.notification.controller.NotificationPreferenceController",
                    "updatePreference"),
                Arguments.of(
                    "com.mannschaft.app.performance.controller.PerformancePersonalController",
                    "getMyPerformance")
            );
        }

        @ParameterizedTest(name = "{0}#{1}")
        @MethodSource("targets")
        @DisplayName("AC-34: マーカー非依存の呼び出しグラフ認可シグナル（B）を持つこと")
        void メソッドがマーカー非依存の認可呼び出しシグナルを持つこと(String className, String methodName)
                throws Exception {
            JavaMethod method = findMethod(className, methodName);
            boolean hasCallSignal = invokeHasAuthorizationCallSignal(method);
            assertTrue(hasCallSignal,
                className + "#" + methodName + " は @SelfScopedEndpoint 注釈なしでも "
                    + "AccessControlService/*AccessGuard/*AccessService への呼び出し"
                    + "（深さ " + 2 + " 以内）を持つべきである。Service 層が "
                    + "AccessControlService をまだ直接呼んでいない可能性がある"
                    + "（対応 PR が未マージの場合はこの赤が正しい）。");
        }

        private JavaMethod findMethod(String className, String methodName) {
            JavaClass javaClass = importedClasses.get(className);
            return javaClass.getMethods().stream()
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "メソッドが見つかりません: " + className + "#" + methodName));
        }

        /**
         * {@link AuthzControllerGuardArchTest#hasAuthorizationCallSignal(JavaMethod)} は private の
         * ままリフレクションで呼ぶ。マーカーシグナル（C）を混ぜずに呼び出しグラフのみを見たいため、
         * package-visible な {@code hasAuthorizationSignal} は使わない。
         */
        private boolean invokeHasAuthorizationCallSignal(JavaMethod method) throws Exception {
            Method m = AuthzControllerGuardArchTest.class
                .getDeclaredMethod("hasAuthorizationCallSignal", JavaMethod.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, method);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-34b
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-34b: NotificationPreferenceController#listPreferences の根拠コメントが実態と合っていること")
    class Ac34bListPreferencesRationale {

        private static final String SERVICE_FQN =
            "com.mannschaft.app.notification.service.NotificationPreferenceService";

        /**
         * listPreferences の根拠コメントは「preferenceRepository.findByUserId の検索条件が
         * userId のみで、他ユーザーの識別子を受け取らない」と主張する。
         *
         * <p>この主張が崩れるのは、{@code listPreferences} の実装に
         * {@code findByUserId(userId)} 単一引数以外の Repository 呼び出し（例えば所属チーム
         * 経由で他ユーザーの設定行まで拾うような join、別 Repository への問い合わせ）が
         * 直接足されたとき。そうなった場合に本テストが赤くなることで、「読み取り側に
         * 所属判定が入った後も自己スコープの主張が成立するか」を継続的に見張る。
         *
         * <p><b>文字列部分一致からの是正</b>: 旧版はソースをテキストとして
         * {@code contains("preferenceRepository.findByUserId(userId)")} /
         * {@code !contains("findByUserIdAnd")} で判定しており、{@code findAll()} や
         * 別 Repository への問い合わせ、スコープ横断処理を <b>追加しても検出できない</b>
         * 空証明だった。ArchUnit の呼び出しグラフ（{@link JavaMethod#getMethodCallsFromSelf()}）で
         * {@code listPreferences} から直接呼ばれる Repository 呼び出し集合を取り、
         * {@code findByUserId} 以外が1件も無いことを検証する（{@link AuthzControllerGuardArchTest}
         * と同じ直接呼び出し判定方式）。</p>
         *
         * <p>2026-09-18 時点の実測: {@code NotificationPreferenceService#listPreferences} は
         * {@code preferenceRepository.findByUserId(userId)} を直接呼ぶのみで、他の Repository
         * 呼び出しは一切無い。コメントの主張は現状の実装と一致している（PR #3328 が読み取り側へ
         * 追加した {@code ScopeAffiliationCache} 所属判定は {@code fillScopeName} 経由の別呼び出しで
         * あり、{@code @SelfScopedEndpoint} の宣言範囲外の別軸として Service 側コメントが
         * 明示的に切り分けている）。
         */
        @Test
        @DisplayName("listPreferences から直接呼ばれる Repository は findByUserId(userId) 単一引数のみであること")
        void listPreferencesはuserId単一引数のfindByUserIdのみを呼ぶこと() {
            JavaClass serviceClass = importedClasses.get(SERVICE_FQN);
            JavaMethod method = serviceClass.getMethods().stream()
                .filter(m -> m.getName().equals("listPreferences"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "listPreferences メソッドが見つかりません: " + SERVICE_FQN
                        + "。シグネチャが変わっていないか確認してください。"));

            boolean sawFindByUserId = false;
            java.util.Set<String> otherRepositoryCalls = new java.util.TreeSet<>();

            for (com.tngtech.archunit.core.domain.JavaMethodCall call : method.getMethodCallsFromSelf()) {
                JavaClass owner = call.getTarget().getOwner();
                if (!owner.getSimpleName().endsWith("Repository")) {
                    continue;
                }
                String calledMethodName = call.getTarget().getName();
                if ("findByUserId".equals(calledMethodName)) {
                    sawFindByUserId = true;
                    continue;
                }
                otherRepositoryCalls.add(owner.getSimpleName() + "#" + calledMethodName);
            }

            assertTrue(sawFindByUserId,
                "listPreferences は preferenceRepository.findByUserId(userId) を呼ぶべきである"
                    + "（@SelfScopedEndpoint の根拠コメントの主張）。呼び出しグラフから "
                    + "findByUserId 呼び出しが検出できませんでした。");

            assertTrue(otherRepositoryCalls.isEmpty(),
                "listPreferences が findByUserId 以外の Repository 呼び出しを持つように"
                    + "なっている場合、「userId のみで他ユーザーの識別子を受け取らない」という"
                    + "@SelfScopedEndpoint の根拠コメントは実態と合わなくなっている可能性がある。"
                    + "コメントを実態に合わせて見直すこと。検出された呼び出し: " + otherRepositoryCalls);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-35
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-35: 契約テスト必須の縛りから外れても SelfScopedEndpointMarkerGuardTest は赤にならない")
    class Ac35MarkerGuardStaysGreen {

        /**
         * {@link SelfScopedEndpointMarkerGuardTest} は {@code @SelfScopedEndpoint} トークンが
         * 実際に出現する箇所だけを走査して契約テストを要求する（
         * {@link SelfScopedEndpointMarkerGuardTest#extractTargets}）。注釈を削除した後のソースを
         * 与えれば targets が空になり、契約テスト必須の縛りは自然に外れる（免除リストではなく
         * 「対象が存在しない」ことによって）。
         *
         * <p>本テストは、注釈が実際に削除済みのメソッド（{@code getMyPerformance}）については
         * 現物ソースをそのまま、まだ削除されていないメソッド（Shift 3件・updatePreference）に
         * ついては注釈を機械的に取り除いた「削除後」シミュレーションソースを与えて、
         * どちらの場合も {@code extractTargets} が対象を検出しない（＝契約テストを要求しない）
         * ことを固定する。</p>
         */
        @Test
        @DisplayName("対象5メソッドの現物ソースに @SelfScopedEndpoint が存在しないこと（シミュレーションなし）")
        void 対象5メソッドの現物ソースにSelfScopedEndpoint注釈が存在しないこと() throws IOException {
            List<Path> paths = List.of(
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "shift", "controller", "ShiftAvailabilityController.java"),
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "notification", "controller", "NotificationPreferenceController.java"),
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "performance", "controller", "PerformancePersonalController.java")
            );

            List<String> methodsToCheck = List.of(
                "getAvailabilityDefaults", "setAvailabilityDefaults", "deleteAvailabilityDefaults",
                "updatePreference", "getMyPerformance");

            StringBuilder failures = new StringBuilder();
            for (Path path : paths) {
                String rawContent = Files.readString(path, StandardCharsets.UTF_8);

                // シミュレーションで加工しない現物ソースをそのまま渡す。listPreferences は
                // 意図的に @SelfScopedEndpoint を維持しているため extractTargets が検出しても
                // methodsToCheck に含まれず無視される。
                SelfScopedEndpointMarkerGuardTest.Src src =
                    new SelfScopedEndpointMarkerGuardTest.Src(path.toString().replace('\\', '/'), rawContent);
                List<SelfScopedEndpointMarkerGuardTest.Target> targets =
                    SelfScopedEndpointMarkerGuardTest.extractTargets(src);

                for (SelfScopedEndpointMarkerGuardTest.Target t : targets) {
                    if (methodsToCheck.contains(t.methodName)) {
                        failures.append("  x ").append(t).append('\n');
                    }
                }
            }

            if (failures.length() > 0) {
                fail("対象5メソッドの現物ソースに @SelfScopedEndpoint が検出された"
                    + "（注釈削除の回帰、または再付与）:\n" + failures);
            }
        }

        @Test
        @DisplayName("注釈削除後、5メソッドは SelfScopedEndpointMarkerGuardTest の走査対象から外れること")
        void 注釈削除後は5メソッドが契約テスト必須の走査対象から外れること() throws IOException {
            List<Path> paths = List.of(
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "shift", "controller", "ShiftAvailabilityController.java"),
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "notification", "controller", "NotificationPreferenceController.java"),
                Paths.get("src", "main", "java", "com", "mannschaft", "app",
                    "performance", "controller", "PerformancePersonalController.java")
            );

            List<String> methodsToCheck = List.of(
                "getAvailabilityDefaults", "setAvailabilityDefaults", "deleteAvailabilityDefaults",
                "updatePreference", "getMyPerformance");

            StringBuilder failures = new StringBuilder();
            for (Path path : paths) {
                String rawContent = Files.readString(path, StandardCharsets.UTF_8);
                String simulated = stripAllSelfScopedEndpointAnnotations(rawContent);

                SelfScopedEndpointMarkerGuardTest.Src src =
                    new SelfScopedEndpointMarkerGuardTest.Src(path.toString().replace('\\', '/'), simulated);
                List<SelfScopedEndpointMarkerGuardTest.Target> targets =
                    SelfScopedEndpointMarkerGuardTest.extractTargets(src);

                for (SelfScopedEndpointMarkerGuardTest.Target t : targets) {
                    if (methodsToCheck.contains(t.methodName)) {
                        failures.append("  ✗ ").append(t).append('\n');
                    }
                }
            }

            if (failures.length() > 0) {
                fail("注釈削除をシミュレートしたソースでも @SelfScopedEndpoint が検出された "
                    + "（削除漏れ、または extractTargets の誤検出）:\n" + failures);
            }
        }

        /**
         * {@code @SelfScopedEndpoint(...)} 呼び出し全体（引数括弧含む）をソースから機械的に
         * 取り除く。パーサは {@code SelfScopedEndpointMarkerGuardTest} と同じマスク方式で
         * 括弧の対応を取る。
         */
        private String stripAllSelfScopedEndpointAnnotations(String content) {
            String masked = SelfScopedEndpointMarkerGuardTest.mask(content);
            String token = "@SelfScopedEndpoint";
            StringBuilder out = new StringBuilder(content);

            int searchFrom = 0;
            while (true) {
                int at = masked.indexOf(token, searchFrom);
                if (at < 0) {
                    break;
                }
                int afterToken = at + token.length();
                int cursor = afterToken;
                while (cursor < masked.length() && Character.isWhitespace(masked.charAt(cursor))) {
                    cursor++;
                }
                int end;
                if (cursor < masked.length() && masked.charAt(cursor) == '(') {
                    end = matchParenLocal(masked, cursor) + 1;
                } else {
                    end = afterToken;
                }
                // 原文・マスク文とも同じオフセットを指すので、そのまま原文を消して良い。
                for (int i = at; i < end; i++) {
                    out.setCharAt(i, ' ');
                }
                searchFrom = end;
            }
            return out.toString();
        }

        private int matchParenLocal(String s, int open) {
            int depth = 0;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return s.length() - 1;
        }
    }
}
