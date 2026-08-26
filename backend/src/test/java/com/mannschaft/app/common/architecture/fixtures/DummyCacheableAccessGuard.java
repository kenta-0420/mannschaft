package com.mannschaft.app.common.architecture.fixtures;

/**
 * {@code CacheableAuthzEnforcementGuardTest} の偽陰性ゼロ証明メタテスト用のダミー認可クラス。
 *
 * <p>単純クラス名が {@code *AccessGuard} で終わるため、番人の {@code isAuthzClass} 判定で
 * 「認可クラス」として認識される。既存メタテスト（{@code AuthzControllerGuardConditionTest}）が
 * 使う {@link DummyAccessGuard} とは別クラスにしてあり、そちらの判定に影響を与えない。</p>
 *
 * <p>本番プロダクションコードではなく test 配下の fixture であり、番人本体の
 * {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} により
 * 本番の走査対象には一切混入しない。</p>
 */
public class DummyCacheableAccessGuard {

    /**
     * <b>例外送出型</b>ゲート（{@code check*}）。拒否時に例外を投げる様式を模す。
     * {@code @Cacheable} の内側から到達したら違反として検出されなければならない。
     */
    public void checkAccess(Long scopeId) {
        // 認可判定を行う想定（テストでは副作用なし）
    }

    /**
     * <b>照会系</b>メソッド（{@code is*}・boolean 返却）。値を返すだけで例外を投げない。
     * {@code RoleResolver#resolveViewerRole} のような正当形を模しており、
     * {@code @Cacheable} の内側から呼ばれても<b>違反にしてはならない</b>。
     */
    public boolean isAdmin(Long scopeId) {
        return true;
    }
}
