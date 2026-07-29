package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.cache.annotation.Cacheable;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 「認可を内包した {@code @Cacheable}」検出番人（issue #2496）。
 *
 * <h2>背景 — 既存の認可番人が揃って見逃す「第三の型」</h2>
 * <p>既存の認可番人は 2 種類ある:</p>
 * <ul>
 *   <li>{@code AuthzControllerGuardArchTest} — 認可を<b>呼んでいるか</b>（呼び出し辺の有無）</li>
 *   <li>{@link AuthzGateReturnValueGuardTest} — 呼んだ結果を<b>捨てていないか</b>（戻り値の消費）</li>
 * </ul>
 * <p>ところが認可ゲートが {@link Cacheable} メソッドの<b>内側</b>にある場合、
 * 呼んでもいるし戻り値も捨てていない。<b>ただキャッシュヒット時に実行されないだけ</b>である。
 * 上記 2 番人はいずれも緑になる。これが issue #2496 で顕在化した第三の型:</p>
 * <pre>{@code
 *   @Cacheable(value = "xxx", key = "#teamId + ...")
 *   public List<Foo> list(Long teamId, Long userId) {
 *       accessControlService.checkMembership(userId, teamId, "TEAM"); // 2回目以降は実行されない
 *       return repository.find(teamId);
 *   }
 * }</pre>
 * <p>1 回目は認可が効くので通常のテストも通る。2 回目以降だけ素通りするため、
 * 単体テスト・契約テストでも極めて捕まえにくい。</p>
 *
 * <h2>本番人が固定する不変条件</h2>
 * <p><b>{@link Cacheable} が付いたメソッドの本体から、例外送出型の認可ゲートを呼んではならない。</b>
 * 認可はキャッシュの<b>外側</b>（キャッシュ対象メソッドを呼ぶ側）で行うこと。</p>
 *
 * <h2>なぜ「キーに行為者が入っていない」との AND 条件にしないのか</h2>
 * <p>issue では「認可を内包し、<b>かつ</b>キーに行為者が入っていない {@code @Cacheable}」の
 * 検出が検討された。本番人はより強い「認可の内包そのものを禁止」を採用する。理由は、
 * キーに {@code userId} が入っていても<b>認可の内包は依然として誤り</b>だからである ——
 * 同一ユーザーが一覧を温めた直後にチームから除名／降格されても、TTL 満了または
 * evict までの窓のあいだ、そのユーザーは自分が温めたエントリにヒットし続け、
 * 所属チェックを一切受けない。AND 条件にすると、この「正しく見える危険な状態」を
 * 番人が承認してしまう。</p>
 *
 * <h2>検出対象（誤検出を抑えるための限定）</h2>
 * <ol>
 *   <li><b>認可クラス</b>: 単純名が {@code AccessControlService} /
 *       {@code ContentVisibilityChecker}、または {@code AccessGuard} /
 *       {@code AuthorizationService} で終わるクラス。</li>
 *   <li><b>例外送出型ゲートメソッド</b>: 名前が {@code check} / {@code require} /
 *       {@code assert} で始まるメソッド。これらは「拒否時に例外を投げる」様式であり、
 *       スキップされた瞬間に認可が消える。</li>
 * </ol>
 * <p><b>照会系（{@code getRoleName} / {@code isSystemAdmin} / {@code isAdmin} 等）は対象外。</b>
 * これらは「ロールを調べて<b>返す</b>」関数であり、キャッシュ対象メソッドの戻り値そのものを
 * 組み立てるための正当な入力になり得る（例: {@code RoleResolver#resolveViewerRole} は
 * 閲覧者ロールを解決して返すのが責務であり、キーに {@code userId} を含む正当な実装）。
 * 例外送出型ゲートだけに絞ることで、この正当形を巻き込まない。</p>
 *
 * <h2>既知の限定</h2>
 * <p>ラムダ式の内部からのゲート呼び出しはバイトコード上 synthetic メソッドへ切り出されるため
 * 検出されない（recall より precision を優先）。凍結ストア（{@code FreezingArchRule}）は
 * <b>使わない</b> —— 発足時点で違反 0 件のクリーン発足であり、{@code --tests} 絞り込み実行で
 * 凍結ストアを破壊する事故（{@code ArchUnitFreezeStoreIntegrityTest} が守る領域）を
 * 持ち込まないためである。</p>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class CacheableAuthzEnforcementGuardTest {

    /** 認可判定を担うクラスの単純名（完全一致）。 */
    private static final Set<String> AUTHZ_CLASS_NAMES = Set.of(
        "AccessControlService",
        "ContentVisibilityChecker"
    );

    /** 認可判定を担うクラスの単純名（接尾辞一致）。 */
    private static final Set<String> AUTHZ_CLASS_SUFFIXES = Set.of(
        "AccessGuard",
        "AuthorizationService"
    );

    /** 例外送出型ゲートメソッドの名前接頭辞（拒否時に throw する様式）。 */
    private static final Set<String> ENFORCING_METHOD_PREFIXES = Set.of(
        "check",
        "require",
        "assert"
    );

    @ArchTest
    static final ArchRule cacheable_methods_should_not_enforce_authorization =
        methods().that().areAnnotatedWith(Cacheable.class)
            .should(notCallEnforcingAuthzGate())
            .because("@Cacheable メソッドの内側に置いた認可はキャッシュヒット時に実行されない。"
                + "認可はキャッシュの外側（キャッシュ対象メソッドの呼び出し側）で行うこと（issue #2496）")
            .as("@Cacheable methods should not enforce authorization inside the cached body");

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaMethod> notCallEnforcingAuthzGate() {
        return new ArchCondition<>("not call an exception-throwing authorization gate") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                    String ownerName = call.getTarget().getOwner().getSimpleName();
                    String targetName = call.getTarget().getName();

                    if (!isAuthzClass(ownerName) || !isEnforcingMethod(targetName)) {
                        continue;
                    }

                    events.add(SimpleConditionEvent.violated(method, String.format(
                        "@Cacheable メソッド %s.%s() が認可ゲート %s.%s() を本体内で呼んでいる。"
                            + "キャッシュヒット時にこの認可は実行されないため、呼び出し側"
                            + "（キャッシュの外）へ移動すること。(%s)",
                        method.getOwner().getSimpleName(), method.getName(),
                        ownerName, targetName,
                        call.getSourceCodeLocation())));
                }
            }
        };
    }

    private static boolean isAuthzClass(String simpleName) {
        if (AUTHZ_CLASS_NAMES.contains(simpleName)) {
            return true;
        }
        return AUTHZ_CLASS_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    private static boolean isEnforcingMethod(String methodName) {
        return ENFORCING_METHOD_PREFIXES.stream().anyMatch(methodName::startsWith);
    }
}
