package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.disclosure.dto.DisclosureCustomTemplateRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureFormTemplateEditService} 単体テスト（F09.14 Phase 3-C）。
 *
 * <p>件数上限・楽観ロック・システムテンプレ保護・論理削除・クロステナント遮断の
 * 5 つの不変条件を網羅的に検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureFormTemplateEditService 単体テスト")
class DisclosureFormTemplateEditServiceTest {

    private static final Long ORG_ID = 100L;
    private static final Long OTHER_ORG_ID = 999L;

    @Mock
    private DisclosureFormTemplateRepository repository;

    @Mock
    private AccessControlService accessControlService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisclosureFormTemplateEditService service;
    private DisclosureFormTemplateValidator validator;

    /** Validator がエラーを投げないよう、autoFillFrom に使うキーを stub 登録する。 */
    private static class StubSource implements AutoFillSource {
        private final String key;
        StubSource(String key) { this.key = key; }
        @Override public String key() { return key; }
        @Override public Object resolve(AutoFillContext ctx) { return null; }
    }

    @BeforeEach
    void setUp() {
        DisclosureAutoFillService autoFillService = new DisclosureAutoFillService(
                List.of(new StubSource("organization.name")));
        autoFillService.init();
        validator = new DisclosureFormTemplateValidator(objectMapper, autoFillService);
        service = new DisclosureFormTemplateEditService(repository, validator, objectMapper, accessControlService);
    }

    /** 妥当な最小 schema を返す。 */
    private JsonNode minimalSchema() {
        try {
            return objectMapper.readTree("""
                { "sections": [
                  { "id": "s1", "title": "S", "fields": [
                    { "id": "f1", "label": "L", "type": "TEXT" }
                  ]}
                ]}
                """);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 標準的な作成リクエスト。 */
    private DisclosureCustomTemplateRequest createRequest(String code, String version) {
        return new DisclosureCustomTemplateRequest(
                code, "テスト様式", null, version,
                minimalSchema(), null, null, null, null, true, null);
    }

    /** 標準的な更新リクエスト（versionLock 指定）。 */
    private DisclosureCustomTemplateRequest updateRequest(String code, String version, Long versionLock) {
        return new DisclosureCustomTemplateRequest(
                code, "更新後の名前", null, version,
                minimalSchema(), null, null, null, null, true, versionLock);
    }

    /** id / versionLock を Reflection でセットしたカスタムテンプレ Entity を返す。 */
    private DisclosureFormTemplateEntity buildCustom(Long id, Long scopeId, String code, String version,
                                                     Long versionLock) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("既存名")
                .version(version)
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType("ORGANIZATION")
                .scopeId(scopeId)
                .formSchema("{\"sections\":[]}")
                .isActive(true)
                .versionLock(versionLock)
                .build();
        setField(entity, com.mannschaft.app.common.BaseEntity.class, "id", id);
        return entity;
    }

    private DisclosureFormTemplateEntity buildSystem(Long id, String code, String version) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("システム標準")
                .version(version)
                .isStandard(true)
                .isSystemTemplate(true)
                .scopeType(null)
                .scopeId(null)
                .formSchema("{\"sections\":[]}")
                .isActive(true)
                .versionLock(0L)
                .build();
        setField(entity, com.mannschaft.app.common.BaseEntity.class, "id", id);
        return entity;
    }

    private static void setField(Object target, Class<?> declaringClass, String fieldName, Object value) {
        try {
            Field f = declaringClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // create
    // =========================================================================

    @Nested
    @DisplayName("createCustomTemplate()")
    class Create {

        @Test
        @DisplayName("正常系: 件数 0 件で新規作成成功 / scopeType=ORGANIZATION/isSystemTemplate=false で永続化される")
        void create_ok() {
            when(repository.countByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", ORG_ID))
                    .thenReturn(0L);
            when(repository.findByCodeAndVersionAndDeletedAtIsNull(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(repository.save(any(DisclosureFormTemplateEntity.class)))
                    .thenAnswer(inv -> {
                        DisclosureFormTemplateEntity e = inv.getArgument(0);
                        setField(e, com.mannschaft.app.common.BaseEntity.class, "id", 5L);
                        return e;
                    });

            DisclosureFormTemplateResponse res = service.createCustomTemplate(
                    ORG_ID, 1L, createRequest("ORG_CUSTOM_A", "1.0"));

            assertThat(res.id()).isEqualTo(5L);
            ArgumentCaptor<DisclosureFormTemplateEntity> captor =
                    ArgumentCaptor.forClass(DisclosureFormTemplateEntity.class);
            verify(repository).save(captor.capture());
            DisclosureFormTemplateEntity saved = captor.getValue();
            assertThat(saved.getIsSystemTemplate()).isFalse();
            assertThat(saved.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(saved.getScopeId()).isEqualTo(ORG_ID);
            assertThat(saved.getCode()).isEqualTo("ORG_CUSTOM_A");
            assertThat(saved.getCreatedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("件数上限超過: 既に 10 件あれば DISCLOSURE_013")
        void create_exceedsMaxCount() {
            when(repository.countByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", ORG_ID))
                    .thenReturn(10L);

            assertThatThrownBy(() ->
                    service.createCustomTemplate(ORG_ID, 1L, createRequest("ORG_CUSTOM_X", "1.0")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_013);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("(code, version) 重複: DISCLOSURE_004")
        void create_duplicateCodeVersion() {
            when(repository.countByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", ORG_ID))
                    .thenReturn(2L);
            when(repository.findByCodeAndVersionAndDeletedAtIsNull("ORG_CUSTOM_A", "1.0"))
                    .thenReturn(Optional.of(buildCustom(99L, ORG_ID, "ORG_CUSTOM_A", "1.0", 0L)));

            assertThatThrownBy(() ->
                    service.createCustomTemplate(ORG_ID, 1L, createRequest("ORG_CUSTOM_A", "1.0")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }

        @Test
        @DisplayName("organizationId が null: DISCLOSURE_004")
        void create_nullOrgId() {
            assertThatThrownBy(() ->
                    service.createCustomTemplate(null, 1L, createRequest("X", "1.0")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }
    }

    // =========================================================================
    // update
    // =========================================================================

    @Nested
    @DisplayName("updateCustomTemplate()")
    class Update {

        @Test
        @DisplayName("正常系: version を 1.0 → 2.0 に変更してインクリメントされる（既存ドラフトは別 snapshot で保護）")
        void update_versionIncrement() {
            DisclosureFormTemplateEntity existing = buildCustom(10L, ORG_ID, "ORG_CUSTOM_A", "1.0", 3L);
            when(repository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(existing));
            when(repository.findByCodeAndVersionAndDeletedAtIsNull("ORG_CUSTOM_A", "2.0"))
                    .thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(DisclosureFormTemplateEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            DisclosureFormTemplateResponse res = service.updateCustomTemplate(
                    ORG_ID, 10L, 1L, updateRequest("ORG_CUSTOM_A", "2.0", 3L));

            assertThat(res.version()).isEqualTo("2.0");
            assertThat(res.name()).isEqualTo("更新後の名前");
        }

        @Test
        @DisplayName("システムテンプレは編集不可: DISCLOSURE_014")
        void update_systemTemplateForbidden() {
            when(repository.findByIdAndDeletedAtIsNull(1L))
                    .thenReturn(Optional.of(buildSystem(1L, "MLIT_STANDARD_2024", "2024.1")));

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 1L, 1L,
                            updateRequest("MLIT_STANDARD_2024", "2024.2", 0L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_014);
        }

        @Test
        @DisplayName("別組織のテンプレは更新不可: DISCLOSURE_002")
        void update_crossTenant() {
            when(repository.findByIdAndDeletedAtIsNull(20L))
                    .thenReturn(Optional.of(buildCustom(20L, OTHER_ORG_ID, "ORG_CUSTOM_X", "1.0", 0L)));

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 20L, 1L,
                            updateRequest("ORG_CUSTOM_X", "2.0", 0L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
        }

        @Test
        @DisplayName("楽観ロック: versionLock 不一致で DISCLOSURE_003")
        void update_optimisticLockMismatch() {
            when(repository.findByIdAndDeletedAtIsNull(10L))
                    .thenReturn(Optional.of(buildCustom(10L, ORG_ID, "ORG_CUSTOM_A", "1.0", 5L)));

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 10L, 1L,
                            updateRequest("ORG_CUSTOM_A", "2.0", 1L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);
        }

        @Test
        @DisplayName("versionLock 未指定: DISCLOSURE_004")
        void update_missingVersionLock() {
            when(repository.findByIdAndDeletedAtIsNull(10L))
                    .thenReturn(Optional.of(buildCustom(10L, ORG_ID, "ORG_CUSTOM_A", "1.0", 0L)));

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 10L, 1L,
                            updateRequest("ORG_CUSTOM_A", "2.0", null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }

        @Test
        @DisplayName("code 変更は許容しない: DISCLOSURE_004")
        void update_codeChangeRejected() {
            when(repository.findByIdAndDeletedAtIsNull(10L))
                    .thenReturn(Optional.of(buildCustom(10L, ORG_ID, "ORG_CUSTOM_A", "1.0", 0L)));

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 10L, 1L,
                            updateRequest("ORG_CUSTOM_RENAMED", "2.0", 0L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }

        @Test
        @DisplayName("対象が存在しない: DISCLOSURE_001")
        void update_notFound() {
            when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.updateCustomTemplate(ORG_ID, 99L, 1L,
                            updateRequest("X", "1.0", 0L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
        }
    }

    // =========================================================================
    // delete
    // =========================================================================

    @Nested
    @DisplayName("deleteCustomTemplate()")
    class Delete {

        @Test
        @DisplayName("正常系: deletedAt がセットされて save される（物理削除しない）")
        void delete_ok() {
            DisclosureFormTemplateEntity entity = buildCustom(10L, ORG_ID, "ORG_CUSTOM_A", "1.0", 0L);
            when(repository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));
            when(repository.save(any(DisclosureFormTemplateEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.deleteCustomTemplate(ORG_ID, 1L, 10L);

            assertThat(entity.getDeletedAt()).isNotNull();
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("システムテンプレ削除拒否: DISCLOSURE_014")
        void delete_systemTemplateForbidden() {
            when(repository.findByIdAndDeletedAtIsNull(1L))
                    .thenReturn(Optional.of(buildSystem(1L, "MLIT_STANDARD_2024", "2024.1")));

            assertThatThrownBy(() -> service.deleteCustomTemplate(ORG_ID, 1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_014);
        }

        @Test
        @DisplayName("別組織のテンプレ削除拒否: DISCLOSURE_002")
        void delete_crossTenant() {
            when(repository.findByIdAndDeletedAtIsNull(20L))
                    .thenReturn(Optional.of(buildCustom(20L, OTHER_ORG_ID, "X", "1.0", 0L)));

            assertThatThrownBy(() -> service.deleteCustomTemplate(ORG_ID, 1L, 20L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
        }

        @Test
        @DisplayName("対象が存在しない: DISCLOSURE_001")
        void delete_notFound() {
            when(repository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteCustomTemplate(ORG_ID, 1L, 99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
        }
    }
}
