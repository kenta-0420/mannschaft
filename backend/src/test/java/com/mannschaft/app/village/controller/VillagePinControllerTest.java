package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PinListResponse;
import com.mannschaft.app.village.dto.PinOrderUpdateRequest;
import com.mannschaft.app.village.dto.PinResponse;
import com.mannschaft.app.village.service.VillagePinService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link VillagePinController} 軽量結合テスト（F17.1 B8）。
 *
 * <p>StandaloneSetup + Mockito で Service 層をモック化し、HTTP 入出力と
 * ErrorCode → HttpStatus マッピング（GlobalExceptionHandler）を一気通貫で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillagePinController 軽量結合テスト")
class VillagePinControllerTest {

    @Mock
    private VillagePinService pinService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 800L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        VillagePinController controller = new VillagePinController(pinService);
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
    // GET /api/v1/me/village-pins
    // ==================================================================

    @Test
    @DisplayName("GET 正常系: 200 OK + items / count / maxLimit を返す")
    void list_200() throws Exception {
        PinResponse item = PinResponse.builder()
                .id(UUID.randomUUID())
                .villageId(UUID.randomUUID())
                .villageName("東村")
                .villageIconUrl("icon")
                .sortOrder(0L)
                .pinnedAt(LocalDateTime.now())
                .build();
        given(pinService.listMyPins(USER_ID)).willReturn(
                PinListResponse.builder()
                        .items(List.of(item))
                        .count(1)
                        .maxLimit(30)
                        .build());

        mockMvc.perform(get("/api/v1/me/village-pins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.maxLimit").value(30))
                .andExpect(jsonPath("$.data.items[0].villageName").value("東村"));
    }

    @Test
    @DisplayName("GET 正常系: ピン 0 件でも 200 + 空配列を返す")
    void list_200_empty() throws Exception {
        given(pinService.listMyPins(USER_ID)).willReturn(
                PinListResponse.builder().items(List.of()).count(0).maxLimit(30).build());

        mockMvc.perform(get("/api/v1/me/village-pins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    // ==================================================================
    // POST /api/v1/me/village-pins/{villageId}
    // ==================================================================

    @Test
    @DisplayName("POST 正常系: 201 Created + data を返す")
    void post_201() throws Exception {
        UUID vid = UUID.randomUUID();
        PinResponse response = PinResponse.builder()
                .id(UUID.randomUUID())
                .villageId(vid)
                .villageName("新村")
                .villageIconUrl(null)
                .sortOrder(0L)
                .pinnedAt(LocalDateTime.now())
                .build();
        given(pinService.pin(eq(USER_ID), eq(vid))).willReturn(response);

        mockMvc.perform(post("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.villageName").value("新村"))
                .andExpect(jsonPath("$.data.sortOrder").value(0));
    }

    @Test
    @DisplayName("POST 422: VILLAGE_PIN_LIMIT_EXCEEDED（30件超過）")
    void post_422_limit() throws Exception {
        UUID vid = UUID.randomUUID();
        given(pinService.pin(eq(USER_ID), eq(vid)))
                .willThrow(new BusinessException(VillageErrorCode.VILLAGE_PIN_LIMIT_EXCEEDED));

        mockMvc.perform(post("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_013"));
    }

    @Test
    @DisplayName("POST 409: VILLAGE_PIN_ALREADY_EXISTS（重複）")
    void post_409_duplicate() throws Exception {
        UUID vid = UUID.randomUUID();
        given(pinService.pin(eq(USER_ID), eq(vid)))
                .willThrow(new BusinessException(VillageErrorCode.VILLAGE_PIN_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_039"));
    }

    @Test
    @DisplayName("POST 404: VILLAGE_NOT_FOUND（削除/凍結/不在）")
    void post_404_villageNotFound() throws Exception {
        UUID vid = UUID.randomUUID();
        given(pinService.pin(eq(USER_ID), eq(vid)))
                .willThrow(new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));

        mockMvc.perform(post("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));
    }

    // ==================================================================
    // DELETE /api/v1/me/village-pins/{villageId}
    // ==================================================================

    @Test
    @DisplayName("DELETE 正常系: 204 No Content")
    void delete_204() throws Exception {
        UUID vid = UUID.randomUUID();
        doNothing().when(pinService).unpin(USER_ID, vid);

        mockMvc.perform(delete("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isNoContent());

        verify(pinService).unpin(USER_ID, vid);
    }

    @Test
    @DisplayName("DELETE 404: VILLAGE_PIN_NOT_FOUND（未登録）")
    void delete_404() throws Exception {
        UUID vid = UUID.randomUUID();
        doThrow(new BusinessException(VillageErrorCode.VILLAGE_PIN_NOT_FOUND))
                .when(pinService).unpin(USER_ID, vid);

        mockMvc.perform(delete("/api/v1/me/village-pins/{vid}", vid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_038"));
    }

    // ==================================================================
    // PATCH /api/v1/me/village-pins/order
    // ==================================================================

    @Test
    @DisplayName("PATCH 正常系: 200 OK + 並び替え後一覧を返す")
    void patch_200() throws Exception {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        PinResponse r1 = PinResponse.builder()
                .id(UUID.randomUUID()).villageId(v1).villageName("一").villageIconUrl(null)
                .sortOrder(0L).pinnedAt(LocalDateTime.now()).build();
        PinResponse r2 = PinResponse.builder()
                .id(UUID.randomUUID()).villageId(v2).villageName("二").villageIconUrl(null)
                .sortOrder(1L).pinnedAt(LocalDateTime.now()).build();
        given(pinService.reorder(eq(USER_ID), any(PinOrderUpdateRequest.class)))
                .willReturn(PinListResponse.builder()
                        .items(List.of(r1, r2)).count(2).maxLimit(30).build());

        String body = String.format(
                "{\"orderedVillageIds\":[\"%s\",\"%s\"]}", v1, v2);

        mockMvc.perform(patch("/api/v1/me/village-pins/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.items[0].villageName").value("一"));
    }

    @Test
    @DisplayName("PATCH 422: VILLAGE_PIN_ORDER_MISMATCH（集合不一致）")
    void patch_422_mismatch() throws Exception {
        UUID v1 = UUID.randomUUID();
        given(pinService.reorder(eq(USER_ID), any(PinOrderUpdateRequest.class)))
                .willThrow(new BusinessException(VillageErrorCode.VILLAGE_PIN_ORDER_MISMATCH));

        String body = String.format("{\"orderedVillageIds\":[\"%s\"]}", v1);

        mockMvc.perform(patch("/api/v1/me/village-pins/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_040"));
    }
}
