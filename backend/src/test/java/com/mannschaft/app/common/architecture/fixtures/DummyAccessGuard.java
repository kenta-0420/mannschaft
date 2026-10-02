package com.mannschaft.app.common.architecture.fixtures;

/**
 * 認可番人の偽陰性ゼロ証明メタテスト用のダミー認可クラス。
 *
 * <p>単純クラス名が {@code *AccessGuard} で終わるため、番人の
 * {@code isAuthorizationClass} 判定で「認可呼び出しクラス」として認識される。
 * 本番プロダクションコードではなく test 配下の fixture であり、
 * {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} により
 * 本番凍結ストアには一切混入しない。
 */
public class DummyAccessGuard {

    /** ダミーの認可チェック。呼ばれること自体が「認可シグナルあり」を意味する。 */
    public void checkAccess(Long scopeId) {
        // 認可判定を行う想定（テストでは副作用なし）
    }
}
