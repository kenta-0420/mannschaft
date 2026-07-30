package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.cache.annotation.Cacheable;

import java.util.List;

/**
 * {@code CacheableAuthzEnforcementGuardTest} の偽陰性ゼロ証明メタテスト用 fixture。
 *
 * <p>「{@code @Cacheable} の内側で認可する」意図的な違反と、巻き込んではならない正当形を
 * 同一クラスに並べ、番人の判定ロジックが<b>違反を検出でき、かつ正当形を誤検出しない</b>ことを
 * 証明できるようにしてある。</p>
 *
 * <p>本 fixture は test 配下にあり、番人本体の
 * {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} により
 * 本番の走査対象には混入しない。</p>
 *
 * <table border="1">
 *   <caption>fixture メソッドと期待判定</caption>
 *   <tr><th>メソッド</th><th>形</th><th>期待</th></tr>
 *   <tr><td>{@link #inlineAuthz}</td><td>直接 {@code checkAccess} を呼ぶ</td><td><b>違反</b></td></tr>
 *   <tr><td>{@link #authzViaHelper}</td><td>同クラス private helper 経由</td><td><b>違反</b>（深さ）</td></tr>
 *   <tr><td>{@link #authzViaNestedHelper}</td><td>helper → helper の 2 段</td><td><b>違反</b>（推移）</td></tr>
 *   <tr><td>{@link #lookupOnly}</td><td>照会系 {@code isAdmin} のみ</td><td>合格（緩めすぎ防止）</td></tr>
 *   <tr><td>{@link #noAuthz}</td><td>認可呼びなし</td><td>合格</td></tr>
 * </table>
 */
public class CacheableAuthzFixtureService {

    private final DummyCacheableAccessGuard accessGuard = new DummyCacheableAccessGuard();

    /** 違反: {@code @Cacheable} の本体で直接ゲートを呼ぶ（issue #2496 で実在した形そのもの）。 */
    @Cacheable(value = "fixture:inline", key = "#scopeId")
    public List<String> inlineAuthz(Long scopeId) {
        accessGuard.checkAccess(scopeId);
        return List.of("data");
    }

    /** 違反: 同一クラスの private helper に認可を隠した形（深さ 1 の委譲）。 */
    @Cacheable(value = "fixture:helper", key = "#scopeId")
    public List<String> authzViaHelper(Long scopeId) {
        assertAccess(scopeId);
        return List.of("data");
    }

    /** 違反: helper → helper と 2 段挟んだ形（推移探索が効いていることの確認）。 */
    @Cacheable(value = "fixture:nested", key = "#scopeId")
    public List<String> authzViaNestedHelper(Long scopeId) {
        outerHelper(scopeId);
        return List.of("data");
    }

    /**
     * 合格: 照会系（boolean 返却）のみを呼ぶ。
     * {@code RoleResolver#resolveViewerRole} と同型の正当形であり、違反にしてはならない。
     */
    @Cacheable(value = "fixture:lookup", key = "#scopeId")
    public List<String> lookupOnly(Long scopeId) {
        if (accessGuard.isAdmin(scopeId)) {
            return List.of("admin-data");
        }
        return List.of("data");
    }

    /** 合格: 認可呼びが一切ない素のキャッシュ対象メソッド。 */
    @Cacheable(value = "fixture:clean", key = "#scopeId")
    public List<String> noAuthz(Long scopeId) {
        return List.of("data");
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    /** 認可を隠した private helper（名前は {@code assert*} だが宣言クラスは認可クラスではない）。 */
    private void assertAccess(Long scopeId) {
        accessGuard.checkAccess(scopeId);
    }

    /** 2 段目の helper。 */
    private void outerHelper(Long scopeId) {
        innerHelper(scopeId);
    }

    /** 3 段目の helper。ここで初めてゲートを呼ぶ。 */
    private void innerHelper(Long scopeId) {
        accessGuard.checkAccess(scopeId);
    }
}
