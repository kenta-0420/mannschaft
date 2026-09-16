package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.stereotype.Service;

/**
 * fixture: {@code @Service} クラス。{@link D1ServiceApiParent}（Entity を公開する
 * {@code findEntityFromParent()} を宣言、{@code @Service} 無し）と
 * {@link D1ServiceApiInterface}（Entity を公開する default メソッド {@code findEntityFromInterface()}）
 * の両方を<b>オーバーライドせずに継承</b>する。
 *
 * <p>{@link com.mannschaft.app.common.architecture.ServiceApiEntityBoundaryArchTest} の
 * 自己検証本体。番人が {@code JavaClass#getAllMethods()} を使うクラス起点の実装であれば、
 * このクラス自身に {@code findEntityFromParent}/{@code findEntityFromInterface} の宣言が
 * 無くても、継承経由で Entity を公開していることを検出できるはずである。
 *
 * <p>あわせて {@code safeMethod()} という DTO のみを返す固有メソッドも持ち、
 * 偽陽性が発生しないことも確認する。
 */
@Service
public class D1ServiceApiChildService extends D1ServiceApiParent implements D1ServiceApiInterface {

    /** Entity を公開しない固有メソッド（偽陽性ゼロの確認用）。 */
    public String safeMethod() {
        return "safe";
    }
}
