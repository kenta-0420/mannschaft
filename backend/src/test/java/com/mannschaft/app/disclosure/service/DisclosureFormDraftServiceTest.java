package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DraftStatus;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftRequest;
import com.mannschaft.app.disclosure.dto.DisclosureFormDraftResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormDraftEntity;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureFormDraftService} 単体テスト（F09.14 Phase 2-β-4）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureFormDraftService 単体テスト")
class DisclosureFormDraftServiceTest {

    @Mock private DisclosureFormDraftRepository draftRepository;
    @Mock private DisclosureFormTemplateService templateService;
    @Mock private DisclosureAutoFillService autoFillService;
    @Mock private AccessControlService accessControlService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisclosureFormDraftService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureFormDraftService(
                draftRepository, templateService, autoFillService, objectMapper, accessControlService);
    }

    @Test
    @DisplayName("create(): 通常作成で template_version_snapshot に最新版が記録される")
    void create_recordsTemplateVersionSnapshot() {
        DisclosureFormTemplateEntity tpl = systemTemplate("MLIT_STANDARD_2024", "2024.1");
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);
        when(draftRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", 100L))
                .thenReturn(0L);
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                1L, "新規ドラフト", null, null, null);
        DisclosureFormDraftResponse res = service.create(100L, 200L, req);

        assertThat(res.templateId()).isEqualTo(1L);
        assertThat(res.templateVersionSnapshot()).isEqualTo("2024.1");
        assertThat(res.status()).isEqualTo(DraftStatus.DRAFT);
    }

    @Test
    @DisplayName("create(): templateId 欠落は DISCLOSURE_004")
    void create_missingTemplate() {
        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                null, "title", null, null, null);
        assertThatThrownBy(() -> service.create(1L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("create(): 別組織のカスタムテンプレ参照は DISCLOSURE_002")
    void create_crossTenantTemplate() {
        DisclosureFormTemplateEntity tpl = DisclosureFormTemplateEntity.builder()
                .code("OTHER_ORG_CUSTOM").name("別組織カスタム").version("1")
                .isSystemTemplate(false).isStandard(false)
                .scopeType("ORGANIZATION").scopeId(999L) // 別組織
                .formSchema("{\"sections\":[]}").isActive(true).build();
        when(templateService.getEntityOrThrow(7L)).thenReturn(tpl);

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                7L, "title", null, null, null);
        assertThatThrownBy(() -> service.create(100L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("create(): effective_until 経過済テンプレは DISCLOSURE_006")
    void create_expiredTemplate() {
        DisclosureFormTemplateEntity tpl = DisclosureFormTemplateEntity.builder()
                .code("EXPIRED").name("期限切").version("1")
                .isSystemTemplate(true).isStandard(true)
                .formSchema("{\"sections\":[]}").isActive(true)
                .effectiveUntil(LocalDate.now().minusDays(1))
                .build();
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                1L, "title", null, null, null);
        assertThatThrownBy(() -> service.create(100L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_006);
    }

    @Test
    @DisplayName("update(): version 不一致は DISCLOSURE_003")
    void update_versionConflict() throws Exception {
        DisclosureFormDraftEntity entity = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(1L)
                .templateId(1L).templateVersionSnapshot("2024.1")
                .title("既存").formData("{}")
                .status(DraftStatus.DRAFT)
                .createdBy(1L).build();
        setVersion(entity, 5L);
        when(draftRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                null, "更新", null, null, 4L); // 古い version
        assertThatThrownBy(() -> service.update(1L, 10L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);
    }

    @Test
    @DisplayName("update(): EXPORTED 済みは更新不可（DISCLOSURE_004）")
    void update_exportedNotMutable() {
        DisclosureFormDraftEntity entity = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(1L)
                .templateId(1L).templateVersionSnapshot("2024.1")
                .title("既存").formData("{}")
                .status(DraftStatus.EXPORTED)
                .createdBy(1L).build();
        when(draftRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                null, "更新", null, null, 0L);
        assertThatThrownBy(() -> service.update(1L, 10L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("update(): スコープ不一致は DISCLOSURE_002")
    void update_scopeMismatch() {
        DisclosureFormDraftEntity entity = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(999L)
                .templateId(1L).templateVersionSnapshot("v1")
                .title("既存").formData("{}")
                .status(DraftStatus.DRAFT)
                .createdBy(1L).build();
        when(draftRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                null, "T", null, null, 0L);
        assertThatThrownBy(() -> service.update(100L, 10L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("create(): 50件上限到達時は最古ドラフトを論理削除する")
    void create_enforcesMaxLimit() {
        DisclosureFormTemplateEntity tpl = systemTemplate("MLIT_STANDARD_2024", "2024.1");
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);
        when(draftRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull("ORGANIZATION", 100L))
                .thenReturn((long) DisclosureFormDraftService.MAX_DRAFTS_PER_SCOPE);

        DisclosureFormDraftEntity oldest = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateVersionSnapshot("v1")
                .title("古い").formData("{}")
                .status(DraftStatus.DRAFT).createdBy(1L).build();
        lenient().when(draftRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByUpdatedAtAsc(
                any(), any(), any()))
                .thenReturn(java.util.List.of(oldest));
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisclosureFormDraftRequest req = new DisclosureFormDraftRequest(
                1L, "新規", null, null, null);
        service.create(100L, 200L, req);

        ArgumentCaptor<DisclosureFormDraftEntity> captor =
                ArgumentCaptor.forClass(DisclosureFormDraftEntity.class);
        org.mockito.Mockito.verify(draftRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        // 最古ドラフトに softDelete が呼ばれて deletedAt が立っている
        assertThat(captor.getAllValues())
                .anyMatch(e -> e.getDeletedAt() != null && "古い".equals(e.getTitle()));
    }

    @Test
    @DisplayName("refreshAutoFill(): 自動引用結果が空フィールドにマージされる")
    void refreshAutoFill_mergesEmptyOnly() {
        DisclosureFormDraftEntity entity = DisclosureFormDraftEntity.builder()
                .scopeType("ORGANIZATION").scopeId(100L)
                .templateId(1L).templateVersionSnapshot("v1")
                .title("draft")
                .formData("{\"orgName\":\"既存値\",\"unitNum\":\"\"}")
                .status(DraftStatus.DRAFT).createdBy(1L).build();
        when(draftRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(entity));

        DisclosureFormTemplateEntity tpl = systemTemplate("MLIT", "v1");
        when(templateService.getEntityOrThrow(1L)).thenReturn(tpl);

        java.util.Map<String, Object> autoFilled = new java.util.LinkedHashMap<>();
        autoFilled.put("orgName", "新しい組織名");
        autoFilled.put("unitNum", "301");
        when(autoFillService.autoFillAll(any(), any())).thenReturn(autoFilled);
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisclosureFormDraftResponse res = service.refreshAutoFill(100L, 10L, 1L, false);
        // 既存値「既存値」は維持、空文字「」は autoFill 値で上書き
        ObjectNode formData = (ObjectNode) res.formData();
        assertThat(formData.get("orgName").asText()).isEqualTo("既存値");
        assertThat(formData.get("unitNum").asText()).isEqualTo("301");
    }

    // ----- ヘルパー -----

    private DisclosureFormTemplateEntity systemTemplate(String code, String version) {
        DisclosureFormTemplateEntity e = DisclosureFormTemplateEntity.builder()
                .code(code).name("テスト様式").version(version)
                .isSystemTemplate(true).isStandard(true)
                .formSchema("{\"sections\":[]}").isActive(true).build();
        try {
            setBaseEntityId(e, 1L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    /** Reflection で version を強制設定する（テスト専用）。 */
    private static void setVersion(DisclosureFormDraftEntity entity, Long version) throws Exception {
        Field f = DisclosureFormDraftEntity.class.getDeclaredField("version");
        f.setAccessible(true);
        f.set(entity, version);
    }

    private static void setBaseEntityId(Object entity, Long id) throws Exception {
        Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
