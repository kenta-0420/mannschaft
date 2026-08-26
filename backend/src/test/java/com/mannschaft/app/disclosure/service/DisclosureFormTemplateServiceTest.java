package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureFormTemplateService} 単体テスト（F09.14 Phase 2-β-4）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureFormTemplateService 単体テスト")
class DisclosureFormTemplateServiceTest {

    @Mock
    private DisclosureFormTemplateRepository repository;

    @Mock
    private AccessControlService accessControlService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisclosureFormTemplateService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureFormTemplateService(repository, objectMapper, accessControlService);
    }

    @Test
    @DisplayName("get(): 論理削除済テンプレートは DISCLOSURE_001")
    void get_notFound() {
        when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ORGANIZATION", 1L, 1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    @Test
    @DisplayName("get(): 別組織のカスタムテンプレートは DISCLOSURE_002")
    void get_crossTenant() {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code("CUSTOM_A")
                .name("カスタム様式")
                .version("1")
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType("ORGANIZATION")
                .scopeId(99L) // 別組織
                .formSchema("{\"sections\":[]}")
                .isActive(true)
                .build();
        when(repository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.get("ORGANIZATION", 1L, 1L, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("get(): システム提供テンプレートはどの組織からも閲覧可")
    void get_systemTemplateOk() {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code("MLIT_STANDARD_2024")
                .name("国交省標準書式")
                .version("2024.1")
                .isStandard(true)
                .isSystemTemplate(true)
                .formSchema("{\"sections\":[]}")
                .isActive(true)
                .build();
        when(repository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(entity));

        assertThat(service.get("ORGANIZATION", 1L, 1L, 1L).code()).isEqualTo("MLIT_STANDARD_2024");
        assertThat(service.get(null, null, 1L, 1L).code()).isEqualTo("MLIT_STANDARD_2024");
    }

    @Test
    @DisplayName("listAvailable(): scopeType=ORGANIZATION 指定時は当該組織のカスタムを統合する")
    void listAvailable_mergesScopeCustom() throws Exception {
        DisclosureFormTemplateEntity systemTpl = DisclosureFormTemplateEntity.builder()
                .code("MLIT_STANDARD_2024").name("国交省標準").version("2024.1")
                .isStandard(true).isSystemTemplate(true)
                .formSchema("{\"sections\":[]}").isActive(true).build();
        setBaseEntityId(systemTpl, 1L);
        DisclosureFormTemplateEntity orgCustom = DisclosureFormTemplateEntity.builder()
                .code("ORG_CUSTOM").name("当組織カスタム").version("1")
                .isStandard(false).isSystemTemplate(false)
                .scopeType("ORGANIZATION").scopeId(1L)
                .formSchema("{\"sections\":[]}").isActive(true).build();
        setBaseEntityId(orgCustom, 2L);

        when(repository.findActiveByPrefecture(any())).thenReturn(List.of(systemTpl));
        lenient().when(repository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                anyString(), any(), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(List.of(orgCustom)));

        var result = service.listAvailable("ORGANIZATION", 1L, 1L, null);
        assertThat(result).hasSize(2)
                .extracting("code")
                .containsExactlyInAnyOrder("MLIT_STANDARD_2024", "ORG_CUSTOM");
    }

    /** BaseEntity の private id を Reflection でセット（テスト専用）。 */
    private static void setBaseEntityId(Object entity, Long id) throws Exception {
        java.lang.reflect.Field f =
                com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    @Test
    @DisplayName("listAvailable(): 不正な scopeType は DISCLOSURE_004")
    void listAvailable_invalidScope() {
        assertThatThrownBy(() -> service.listAvailable("TEAM", 1L, 1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }
}
