package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageNicknameResponse;
import com.mannschaft.app.village.dto.VillageNicknameUpdateRequest;
import com.mannschaft.app.village.service.VillageNicknameService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link VillageNicknameController} 軽量結合テスト（F17.1 B4）。
 *
 * <p>StandaloneSetup + Mockito で Service 層をモック化し、Controller の HTTP 入出力と
 * ErrorCode → HttpStatus マッピング（GlobalExceptionHandler）を一気通貫で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNicknameController 軽量結合テスト")
class VillageNicknameControllerTest {

    @Mock
    private VillageNicknameService nicknameService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 400L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();  // キー不在時は ErrorCode.getMessage() にフォールバック
        VillageNicknameController controller = new VillageNicknameController(nicknameService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================================================================
    // GET /api/v1/me/village-nickname
    // ==================================================================

    @Test
    @DisplayName("GET 正常系: 設定あり → 200 OK + data に内容を返す")
    void get_200_withData() throws Exception {
        VillageNicknameResponse response = VillageNicknameResponse.builder()
                .nickname("たまねぎ侍")
                .avatarR2Key("village_user_nicknames/global/abc/avatar.png")
                .bio("玉ねぎが好き")
                .lastChangedAt(LocalDateTime.of(2026, 5, 1, 12, 0))
                .changeCountThisMonth(1L)
                .monthlyLimit(3)
                .build();
        given(nicknameService.getMyNickname(USER_ID)).willReturn(Optional.of(response));

        mockMvc.perform(get("/api/v1/me/village-nickname"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("たまねぎ侍"))
                .andExpect(jsonPath("$.data.changeCountThisMonth").value(1))
                .andExpect(jsonPath("$.data.monthlyLimit").value(3));
    }

    @Test
    @DisplayName("GET 正常系: 未設定 → 200 OK + data:null")
    void get_200_empty() throws Exception {
        given(nicknameService.getMyNickname(USER_ID)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/me/village-nickname"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==================================================================
    // PUT /api/v1/me/village-nickname
    // ==================================================================

    @Test
    @DisplayName("PUT 正常系: 200 OK + 更新後レスポンスを返す")
    void put_200() throws Exception {
        VillageNicknameResponse response = VillageNicknameResponse.builder()
                .nickname("おむすび姫")
                .avatarR2Key(null)
                .bio(null)
                .lastChangedAt(LocalDateTime.now())
                .changeCountThisMonth(1L)
                .monthlyLimit(3)
                .build();
        given(nicknameService.updateMyNickname(eq(USER_ID), any(VillageNicknameUpdateRequest.class)))
                .willReturn(response);

        String body = """
                {"nickname": "おむすび姫"}
                """;

        mockMvc.perform(put("/api/v1/me/village-nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("おむすび姫"))
                .andExpect(jsonPath("$.data.changeCountThisMonth").value(1));
    }

    @Test
    @DisplayName("PUT 409: NICKNAME_TAKEN（グローバル UNIQUE 衝突）")
    void put_409_nicknameTaken() throws Exception {
        given(nicknameService.updateMyNickname(eq(USER_ID), any(VillageNicknameUpdateRequest.class)))
                .willThrow(new BusinessException(VillageErrorCode.NICKNAME_TAKEN));

        String body = """
                {"nickname": "ゆうしゃ"}
                """;

        mockMvc.perform(put("/api/v1/me/village-nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_008"));
    }

    @Test
    @DisplayName("PUT 422: NICKNAME_INVALID（NG ワード / 長さ違反）")
    void put_422_invalid() throws Exception {
        given(nicknameService.updateMyNickname(eq(USER_ID), any(VillageNicknameUpdateRequest.class)))
                .willThrow(new BusinessException(VillageErrorCode.NICKNAME_INVALID));

        String body = """
                {"nickname": "super_admin"}
                """;

        mockMvc.perform(put("/api/v1/me/village-nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_028"));
    }

    @Test
    @DisplayName("PUT 429: NICKNAME_CHANGE_THROTTLED（月3回超過）")
    void put_429_throttled() throws Exception {
        given(nicknameService.updateMyNickname(eq(USER_ID), any(VillageNicknameUpdateRequest.class)))
                .willThrow(new BusinessException(VillageErrorCode.NICKNAME_CHANGE_THROTTLED));

        String body = """
                {"nickname": "なまえ4"}
                """;

        mockMvc.perform(put("/api/v1/me/village-nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_011"));
    }

    @Test
    @DisplayName("PUT 400: Bean Validation（nickname 空）")
    void put_400_blankNickname() throws Exception {
        // Service には到達せず BindException で 400
        String body = """
                {"nickname": ""}
                """;

        mockMvc.perform(put("/api/v1/me/village-nickname")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_001.getCode()));
    }
}
