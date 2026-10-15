package com.mannschaft.app.succession.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.succession.dto.SignCovenantRequest;
import com.mannschaft.app.succession.dto.SuccessionCovenantResponse;
import com.mannschaft.app.succession.service.SuccessionCovenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SuccessionCovenantController} の軽量 MockMvc テスト（F09.15 S1 第三陣B）。
 *
 * <p>Spring コンテキスト起動を避けるため StandaloneSetup を用い、Service 層を Mockito で
 * モック化する。認可は Service 内で行われるため、Controller では SecurityContext に
 * Authentication をセットしてユーザー ID 解決のみを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuccessionCovenantController 軽量結合テスト")
class SuccessionCovenantControllerTest {

    @Mock
    private SuccessionCovenantService covenantService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 400L;
    private static final Long ORG_ID = 100L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();  // JSR310 LocalDateTime / Page等
        SuccessionCovenantController controller = new SuccessionCovenantController(covenantService);
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
    @DisplayName("POST /sign 正常系: 201 Created でレスポンスを返す")
    void post_sign_201() throws Exception {
        UUID id = UUID.randomUUID();
        SuccessionCovenantResponse response = SuccessionCovenantResponse.builder()
                .id(id)
                .organizationId(ORG_ID)
                .signerUserId(USER_ID)
                .covenantType("PRIVACY_CONSENT")
                .covenantVersion("v1.0.0")
                .pdfS3Key("organizations/100/succession/covenants/abc_signed.pdf")
                .pdfSha256("hash")
                .internalSignatureToken("token.123")
                .signedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();
        given(covenantService.signCovenant(any(SignCovenantRequest.class), eq(USER_ID)))
                .willReturn(response);

        String body = """
                {
                  "covenant_type": "PRIVACY_CONSENT",
                  "resident_registry_id": 300,
                  "covenant_version": "v1.0.0",
                  "confirmed_items": ["agree_personal_data_collection", "agree_data_retention_10y"]
                }
                """;

        mockMvc.perform(post("/api/v1/succession/covenants/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.covenantType").value("PRIVACY_CONSENT"));
    }

    @Test
    @DisplayName("POST /{id}/revoke 正常系: 200 で revoke 後のレスポンスを返す")
    void post_revoke_200() throws Exception {
        UUID id = UUID.randomUUID();
        SuccessionCovenantResponse response = SuccessionCovenantResponse.builder()
                .id(id)
                .organizationId(ORG_ID)
                .signerUserId(USER_ID)
                .covenantType("PRIVACY_CONSENT")
                .covenantVersion("v1.0.0")
                .pdfS3Key("k")
                .pdfSha256("h")
                .internalSignatureToken("t")
                .signedAt(LocalDateTime.now())
                .revokedAt(LocalDateTime.now())
                .build();
        given(covenantService.revokeCovenant(eq(id), eq(USER_ID))).willReturn(response);

        mockMvc.perform(post("/api/v1/succession/covenants/" + id + "/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").exists());
    }

    @Test
    @DisplayName("GET /organizations/{orgId}/succession/covenants/{id} 正常系")
    void get_covenant_200() throws Exception {
        UUID id = UUID.randomUUID();
        SuccessionCovenantResponse response = SuccessionCovenantResponse.builder()
                .id(id)
                .organizationId(ORG_ID)
                .signerUserId(USER_ID)
                .covenantType("PRIVACY_CONSENT")
                .covenantVersion("v1.0.0")
                .pdfS3Key("k")
                .pdfSha256("h")
                .internalSignatureToken("t")
                .signedAt(LocalDateTime.now())
                .build();
        given(covenantService.getCovenant(eq(id), eq(ORG_ID), eq(USER_ID))).willReturn(response);

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ID + "/succession/covenants/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    @Test
    @DisplayName("GET 一覧 正常系: ページネーション付きで返る")
    void get_org_list_200() throws Exception {
        UUID id = UUID.randomUUID();
        SuccessionCovenantResponse response = SuccessionCovenantResponse.builder()
                .id(id).organizationId(ORG_ID).signerUserId(USER_ID)
                .covenantType("PRIVACY_CONSENT").covenantVersion("v1.0.0")
                .pdfS3Key("k").pdfSha256("h").internalSignatureToken("t")
                .signedAt(LocalDateTime.now()).build();
        Page<SuccessionCovenantResponse> page = new PageImpl<>(
                List.of(response), PageRequest.of(0, 20), 1);
        given(covenantService.listOrgCovenants(eq(ORG_ID), any(Pageable.class), eq(USER_ID)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/organizations/" + ORG_ID + "/succession/covenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(id.toString()));
    }

    @Test
    @DisplayName("GET /me 正常系")
    void get_me_200() throws Exception {
        SuccessionCovenantResponse response = SuccessionCovenantResponse.builder()
                .id(UUID.randomUUID()).organizationId(ORG_ID).signerUserId(USER_ID)
                .covenantType("PRIVACY_CONSENT").covenantVersion("v1.0.0")
                .pdfS3Key("k").pdfSha256("h").internalSignatureToken("t")
                .signedAt(LocalDateTime.now()).build();
        given(covenantService.listMyCovenants(eq(USER_ID))).willReturn(List.of(response));

        mockMvc.perform(get("/api/v1/succession/covenants/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].covenantType").value("PRIVACY_CONSENT"));
    }
}
