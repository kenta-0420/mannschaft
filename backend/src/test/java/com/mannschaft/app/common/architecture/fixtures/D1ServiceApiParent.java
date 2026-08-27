package com.mannschaft.app.common.architecture.fixtures;

/**
 * fixture: {@code @Service} が<b>付いていない</b>親クラス。public メソッドで
 * {@link DummyD6ExposedEntity} を戻り値に公開する。
 *
 * <p>{@link com.mannschaft.app.common.architecture.ServiceApiEntityBoundaryArchTest} の
 * 自己検証（{@code ServiceApiEntityBoundaryArchTestSelfVerificationTest}）で使う。
 * このクラス自体は {@code @Service} でないため、初版の
 * {@code methods().that().areDeclaredInClassesThat().areAnnotatedWith(Service.class)} という
 * メソッド起点の実装では、このメソッドを継承した {@code @Service} サブクラス
 * （{@link D1ServiceApiChildService}）が検査対象から漏れていた（Codex 検分 P1）。
 */
public class D1ServiceApiParent {

    /** {@code @Service} サブクラスが継承する、Entity を公開する public メソッド。 */
    public DummyD6ExposedEntity findEntityFromParent() {
        return new DummyD6ExposedEntity();
    }
}
