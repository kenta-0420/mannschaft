package com.mannschaft.app;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Spring Boot コンテキスト起動テスト。
 * Testcontainers で MySQL を起動し、全 Bean が正常に生成されることを検証する。
 * Docker が利用不可の場合はスキップされる。
 *
 * <p><b>OOM 対策</b>: {@link AbstractMySqlIntegrationTest} を継承して ApplicationContext と
 * MySQL コンテナを他統合テストと共有する。詳細は親クラスの Javadoc を参照。</p>
 */
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MannschaftApplicationTests extends AbstractMySqlIntegrationTest {

    @Test
    void contextLoads() {
    }
}
