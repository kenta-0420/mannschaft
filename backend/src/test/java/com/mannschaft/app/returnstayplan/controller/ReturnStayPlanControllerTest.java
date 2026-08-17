package com.mannschaft.app.returnstayplan.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** 実Securityフィルタと実Controllerを通すF02.11 HTTP契約試練。 */
@WebMvcTest(ReturnStayPlanController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import(ReturnStayPlanService.class)
class ReturnStayPlanControllerTest {

    private static final UUID PLAN_ID =
            UUID.fromString("0190f3c0-0000-7000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("AC-01 認可: 未認証の本人一覧は実filterで401を返す")
    void ac01_未認証一覧401() throws Exception {
        mockMvc.perform(get("/api/v1/me/return-stay-plans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-03 API正常: 作成は201かつdata wrapperでowner本人の予定を返す")
    void ac03_作成201DataWrapper() throws Exception {
        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerUserId").value(10));
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-04 API入力: 必須項目欠落はServiceへ到達せず400を返す")
    void ac04_必須項目欠落400() throws Exception {
        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-05 API入力: 他は妥当でもunknown fieldがあれば400を返す")
    void ac05_unknownField400() throws Exception {
        Map<String, Object> payload = Map.of(
                "planType", "HOMECOMING",
                "isPublished", false,
                "location", Map.of("countryCode", "JP", "prefectureCode", "13"),
                "startDate", "2026-08-17",
                "endDate", "2026-08-20",
                "teamIds", List.of(),
                "unknown", true);

        mockMvc.perform(post("/api/v1/me/return-stay-plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-17 API競合: stale versionは409を返す")
    void ac17_version競合409() throws Exception {
        mockMvc.perform(put("/api/v1/me/return-stay-plans/{id}", PLAN_ID)
                        .with(csrf())
                        .param("version", "99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-22 APIページング: size=101は400を返す")
    void ac22_size上限400() throws Exception {
        mockMvc.perform(get("/api/v1/me/return-stay-plans").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "10", roles = "MEMBER")
    @DisplayName("AC-02 IDOR: 他人または不存在のplanIdは同じ404を返す")
    void ac02_item秘匿404() throws Exception {
        mockMvc.perform(get("/api/v1/me/return-stay-plans/{id}", PLAN_ID))
                .andExpect(status().isNotFound());
    }

    private ReturnStayPlanCreateRequest validRequest() {
        return new ReturnStayPlanCreateRequest(
                "HOMECOMING",
                true,
                new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 20),
                List.of(30L));
    }
}
