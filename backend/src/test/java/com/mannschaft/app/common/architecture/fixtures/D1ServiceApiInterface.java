package com.mannschaft.app.common.architecture.fixtures;

/**
 * fixture: default インターフェースメソッドで {@link DummyD6ExposedEntity} を公開するインターフェース。
 * {@code @Service} は付いていない。
 *
 * <p>{@link com.mannschaft.app.common.architecture.ServiceApiEntityBoundaryArchTest} の自己検証で、
 * デフォルトインターフェースメソッド経由の継承も捕捉できることを裏付けるために使う。
 */
public interface D1ServiceApiInterface {

    /** {@code @Service} 実装クラスが継承する、Entity を公開する default メソッド。 */
    default DummyD6ExposedEntity findEntityFromInterface() {
        return new DummyD6ExposedEntity();
    }
}
