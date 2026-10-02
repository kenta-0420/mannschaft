package com.mannschaft.app.succession.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
import com.mannschaft.app.succession.service.LegalFilingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LegalFilingController} の軽量 MockMvc テスト（F09.15 S6-C）。
 *
 * <p>Spring コンテキスト起動を避けるため StandaloneSetup を用い、Service 層を Mockito で
 * モック化する。認可は Service 内で行われるため、Controller では SecurityContext に
 * Authentication をセットしてユーザー ID 解決のみを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LegalFilingController 軽量結合テスト")
class LegalFilingControllerTest {

    @Mock
    private LegalFilingService legalFilingService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 400L;
    private static final Long ORG_ID = 100L;
    private static final Long RESIDENT_REGISTRY_ID = 300L;
    private static final Long DWELLING_UNIT_ID = 200L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        LegalFilingController controller = new LegalFilingController(legalFilingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET 一覧 正常系: 200 OK でレスポンスを返す")
    void get_list_by_organization_200() throws Exception {
        LegalFilingEntity entity = buildEntity(UUID.randomUUID(), "ABSENTEE_PROPERTY_MANAGER");
        given(legalFilingService.listByOrganization(ORG_ID, USER_ID)).willReturn(List.of(entity));

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ID + "/succession/legal-filings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(entity.getId().toString()))
                .andExpect(jsonPath("$.data[0].filingType").value("ABSENTEE_PROPERTY_MANAGER"));
    }

    @Test
    @DisplayName("GET 詳細 異常系: 存在しない id は 例外（404 相当）")
    void get_by_id_not_found() throws Exception {
        UUID legalFilingId = UUID.randomUUID();
        given(legalFilingService.getById(legalFilingId, ORG_ID, USER_ID))
                .willThrow(new BusinessException(SuccessionErrorCode.LEGAL_FILING_NOT_FOUND));

        // GlobalExceptionHandler 不在の standalone setup では Servlet 例外として伝搬する。
        // BusinessException が投げられたことを通じて Controller の経路が確認できれば十分。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        mockMvc.perform(get("/api/v1/organizations/" + ORG_ID
                                + "/succession/legal-filings/" + legalFilingId)))
                .hasCauseInstanceOf(BusinessException.class);

        verify(legalFilingService).getById(legalFilingId, ORG_ID, USER_ID);
    }

    @Test
    @DisplayName("POST 起票 バリデーション失敗: filingType 空は 400")
    void post_create_validation_failure_400() throws Exception {
        String body = """
                {
                  "residentRegistryId": 300,
                  "dwellingUnitId": 200,
                  "filingType": "",
                  "note": "備考"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations/" + ORG_ID + "/succession/legal-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        // バリデーション失敗時は Service が呼ばれないこと
        verify(legalFilingService, never()).createLegalFiling(
                anyLong(), anyLong(), anyLong(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("POST 起票 正常系: 200 OK でレスポンスを返す")
    void post_create_legal_filing_200() throws Exception {
        UUID id = UUID.randomUUID();
        LegalFilingEntity entity = buildEntity(id, "ABSENTEE_PROPERTY_MANAGER");
        given(legalFilingService.createLegalFiling(
                eq(ORG_ID), eq(RESIDENT_REGISTRY_ID), eq(DWELLING_UNIT_ID),
                eq("ABSENTEE_PROPERTY_MANAGER"), eq("備考テスト"), eq(USER_ID)))
                .willReturn(entity);

        String body = """
                {
                  "residentRegistryId": 300,
                  "dwellingUnitId": 200,
                  "filingType": "ABSENTEE_PROPERTY_MANAGER",
                  "note": "備考テスト"
                }
                """;

        mockMvc.perform(post("/api/v1/organizations/" + ORG_ID + "/succession/legal-filings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.filingType").value("ABSENTEE_PROPERTY_MANAGER"));
    }

    @Test
    @DisplayName("GET 証拠ダウンロード URL 正常系: 200 OK で URL を返す")
    void get_evidence_download_url_200() throws Exception {
        UUID legalFilingId = UUID.randomUUID();
        String url = "https://test.s3/evidence.zip?signed=1";
        given(legalFilingService.generateEvidenceDownloadUrl(legalFilingId, ORG_ID, USER_ID))
                .willReturn(url);

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ID
                        + "/succession/legal-filings/" + legalFilingId
                        + "/evidence-package/download-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").value(url))
                .andExpect(jsonPath("$.data.ttlSeconds").value(3600));
    }

    // ─── ヘルパー ───────────────────────────────────────────────────────

    private LegalFilingEntity buildEntity(UUID id, String filingType) {
        LegalFilingEntity entity = LegalFilingEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_UNIT_ID)
                .residentRegistryId(RESIDENT_REGISTRY_ID)
                .filingType(filingType)
                .templatePdfS3Key("organizations/100/succession/legal-filings/" + id + "/template.pdf")
                .build();
        setField(entity, "id", id);
        setField(entity, "createdAt", LocalDateTime.now());
        setField(entity, "updatedAt", LocalDateTime.now());
        return entity;
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
