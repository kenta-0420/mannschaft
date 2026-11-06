package com.mannschaft.app.common.testing;

import com.mannschaft.app.common.storage.R2StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.BootstrapUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 既存 IT の実 metadata で起動した context と外部 R2 mock の identity を検査する。
 * 専用 Spring 構成を作らない。Docker 不在時は規約どおり skip し、共有の確認には
 * 本 case の実実行・skip 0 を要求する。起動失敗はそのまま失敗として扱う。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class R2MockContextSharingIT {

    @Test
    void 代表4ITはfiltersごとの2実contextと唯一のR2mockを共有する() throws ClassNotFoundException {
        ApplicationContext withoutFilters = assertShared("circulation.CirculationExportScopeContractIT",
                "circulation.CirculationWriteAclScopeContractIT");
        ApplicationContext withFilters = assertShared("receipt.ReceiptIssuerSettingsContractIT",
                "filesharing.PublicFileLinkContractIT");
        assertThat(withFilters).isNotSameAs(withoutFilters);
    }

    private static ApplicationContext assertShared(String first, String second) throws ClassNotFoundException {
        ApplicationContext firstContext = context(first);
        ApplicationContext secondContext = context(second);
        assertThat(firstContext).isSameAs(secondContext);
        assertThat(firstContext.getBeansOfType(R2StorageService.class)).hasSize(1);
        R2StorageService firstMock = firstContext.getBean(R2StorageService.class);
        assertThat(Mockito.mockingDetails(firstMock).isMock()).isTrue();
        assertThat(secondContext.getBean(R2StorageService.class)).isSameAs(firstMock);
        return firstContext;
    }

    private static ApplicationContext context(String name) throws ClassNotFoundException {
        return BootstrapUtils.resolveTestContextBootstrapper(R2MockContextConfigurationTest.existingClass(name))
                .buildTestContext().getApplicationContext();
    }
}
