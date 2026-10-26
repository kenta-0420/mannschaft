package com.mannschaft.app.template;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.template.entity.ScopeFeatureDisplayPolicyEntity;
import com.mannschaft.app.template.repository.ScopeFeatureDisplayPolicyRepository;
import com.mannschaft.app.template.service.ScopeFeatureDisplayPolicyService;
import com.mannschaft.app.template.service.ScopeFeatureDisplayPolicyWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** AC-1/2/11の既定合成・独立boolとServiceの一括読取回数を固定する。認可の実証は契約ITが担う。 */
@ExtendWith(MockitoExtension.class)
class ScopeFeatureDisplayPolicyServiceTest {
    @Mock private AccessControlService access;
    @Mock private ScopeFeatureDisplayPolicyRepository repository;
    @Mock private ScopeFeatureDisplayPolicyWriter writer;
    @InjectMocks private ScopeFeatureDisplayPolicyService service;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void AC1_AC11_二つのboolは独立し全キーの取得は一回(boolean memberVisible, boolean supporterVisible) {
        when(access.isSystemAdmin(1L)).thenReturn(true);
        when(repository.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, 2L)).thenReturn(List.of(
                ScopeFeatureDisplayPolicyEntity.builder().scopeType(FeatureDisplayScope.TEAM).scopeId(2L)
                        .featureKey("schedule").memberVisible(memberVisible).supporterVisible(supporterVisible).updatedBy(1L).build()));

        var response = service.getSettings(1L, FeatureDisplayScope.TEAM, 2L);

        assertThat(response.features()).hasSize(FeatureDisplayCatalog.keys(FeatureDisplayScope.TEAM).size());
        assertThat(response.features()).filteredOn(row -> row.featureKey().equals("schedule")).singleElement()
                .satisfies(row -> {
                    assertThat(row.memberVisible()).isEqualTo(memberVisible);
                    assertThat(row.supporterVisible()).isEqualTo(supporterVisible);
                });
        assertThat(response.features()).filteredOn(row -> !row.featureKey().equals("schedule"))
                .allSatisfy(row -> {
                    assertThat(row.memberVisible()).isTrue();
                    assertThat(row.supporterVisible()).isTrue();
                });
        verify(repository).findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, 2L);
        verifyNoMoreInteractions(repository, writer);
    }

    @Test
    void AC2_未保存組織もカタログ全件trueで書き込まない() {
        when(access.isSystemAdmin(1L)).thenReturn(true);
        when(repository.findByScopeTypeAndScopeId(FeatureDisplayScope.ORGANIZATION, 2L)).thenReturn(List.of());
        var response = service.getSettings(1L, FeatureDisplayScope.ORGANIZATION, 2L);
        assertThat(response.features()).extracting(row -> row.featureKey())
                .containsExactlyElementsOf(FeatureDisplayCatalog.keys(FeatureDisplayScope.ORGANIZATION));
        assertThat(response.features()).allSatisfy(row -> {
            assertThat(row.memberVisible()).isTrue();
            assertThat(row.supporterVisible()).isTrue();
        });
        verify(repository).findByScopeTypeAndScopeId(FeatureDisplayScope.ORGANIZATION, 2L);
        verifyNoMoreInteractions(repository, writer);
    }
}
