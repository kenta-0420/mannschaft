package com.mannschaft.app.common.testing;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実 Spring metadata で R2 mock の共有と必要な分離を固定する。
 * Context を load せず、既存 MySQL 基底の static 初期化も行わない。
 */
class R2MockContextConfigurationTest {

    private static boolean fixtureInitialized;

    @Test
    void metadata構築では対象クラスのstatic初期化を実行しない() {
        configuration(InitializationProbe.class);
        assertThat(fixtureInitialized).isFalse();
    }

    @Test
    void filters無効の同じR2mock構成を共有する() throws ClassNotFoundException {
        assertThat(configuration("circulation.CirculationExportScopeContractIT"))
                .isEqualTo(configuration("circulation.CirculationWriteAclScopeContractIT"));
    }

    @Test
    void filters有効の同じR2mock構成を共有する() throws ClassNotFoundException {
        assertThat(configuration("receipt.ReceiptIssuerSettingsContractIT"))
                .isEqualTo(configuration("filesharing.PublicFileLinkContractIT"));
    }

    @Test
    void filters有効と無効は分離する() throws ClassNotFoundException {
        assertThat(configuration("circulation.CirculationWriteAclScopeContractIT"))
                .isNotEqualTo(configuration("filesharing.PublicFileLinkContractIT"));
    }

    @Test
    void 代表4ITの構成keyはfiltersごとの2種類になる() throws ClassNotFoundException {
        Set<MergedContextConfiguration> configurations = new HashSet<>();
        configurations.add(configuration("circulation.CirculationExportScopeContractIT"));
        configurations.add(configuration("circulation.CirculationWriteAclScopeContractIT"));
        configurations.add(configuration("receipt.ReceiptIssuerSettingsContractIT"));
        configurations.add(configuration("filesharing.PublicFileLinkContractIT"));
        assertThat(configurations.size()).isEqualTo(2);
    }

    @Test
    void name未指定の同型mockでもfield別名は異なるkeyになる() {
        assertThat(configuration(Canonical.class)).isNotEqualTo(configuration(Alias.class));
    }

    @Test
    void 追加mockを持つ構成は分離する() {
        assertThat(configuration(Canonical.class)).isNotEqualTo(configuration(AdditionalMock.class));
    }

    @Test
    void spyはmockと分離する() {
        assertThat(configuration(Canonical.class)).isNotEqualTo(configuration(Spy.class));
    }

    @Test
    void qualifier差は分離する() {
        assertThat(configuration(Canonical.class)).isNotEqualTo(configuration(Qualified.class));
    }

    @Test
    void profile差は分離する() {
        assertThat(configuration(Canonical.class)).isNotEqualTo(configuration(Profile.class));
    }

    static Class<?> existingClass(String name) throws ClassNotFoundException {
        return Class.forName("com.mannschaft.app." + name, false,
                R2MockContextConfigurationTest.class.getClassLoader());
    }

    private static MergedContextConfiguration configuration(String name) throws ClassNotFoundException {
        return configuration(existingClass(name));
    }

    private static MergedContextConfiguration configuration(Class<?> type) {
        return BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
    }

    /** Context load を伴わない metadata 専用の共通設定。 */
    static class EmptyConfiguration {
    }

    /** 正規 field 名の検体。 */
    @ContextConfiguration(classes = EmptyConfiguration.class)
    static class Canonical {
        @MockitoBean
        R2StorageService r2StorageService;
    }

    /** name 未指定の field fallback を確認する検体。 */
    @ContextConfiguration(classes = EmptyConfiguration.class)
    static class Alias {
        @MockitoBean
        R2StorageService storageService;
    }

    /** 外部依存 mock 追加の検体。 */
    static class AdditionalMock extends Canonical {
        @MockitoBean
        S3Client s3Client;
    }

    /** 同型 spy の検体。 */
    @ContextConfiguration(classes = EmptyConfiguration.class)
    static class Spy {
        @MockitoSpyBean
        R2StorageService r2StorageService;
    }

    /** 明示 qualifier の検体。 */
    @ContextConfiguration(classes = EmptyConfiguration.class)
    static class Qualified {
        @MockitoBean
        @Qualifier("qualifiedStorage")
        R2StorageService r2StorageService;
    }

    /** profile 差の検体。 */
    @ActiveProfiles("separate")
    static class Profile extends Canonical {
    }

    /** 既存 IT と同じ Spring Boot 共通基底で static 初期化を検出する検体。 */
    static class InitializationProbe extends AbstractMySqlIntegrationTest {
        static {
            fixtureInitialized = true;
        }
    }
}
