package com.mannschaft.app.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SYSTEM_ADMIN モジュール トグル API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code SystemAdminModuleController} の新規 2 本
 * {@code PATCH /api/v1/system-admin/modules/{id}/paid-plan}（有料要否）と
 * {@code PATCH /api/v1/system-admin/modules/{id}/active}（有効/無効）。
 * 更新結果が一覧 {@code GET /api/v1/system-admin/modules}（getModuleCatalog）に反映されること、
 * 存在しない module id で 404（TMPL_002）を返すことを検証する。</p>
 *
 * <p>認可（AC-5）は {@code /api/v1/system-admin/**} のパスベース一括ルール（SYSTEM_ADMIN）で
 * 構造的に担保されるため、本テストは {@code addFilters=false} で機能面（AC-1/2/3）に集中する。</p>
 *
 * <p>金型: {@code TeamModuleScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + repository シード）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("SYSTEM_ADMIN モジュール トグル API 契約テスト（試練）")
class SystemAdminModuleToggleContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModuleDefinitionRepository moduleDefinitionRepository;

    private Long moduleId;

    @BeforeEach
    void setUp() {
        // OPTIONAL かつ active・無料のモジュールを 1 件シード（getModuleCatalog の母集合に入る条件）。
        ModuleDefinitionEntity module = moduleDefinitionRepository.save(ModuleDefinitionEntity.builder()
                .name("SYSADMIN トグル対象モジュール")
                .slug("sysadmin-toggle-module-" + System.nanoTime())
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .isActive(true)
                .build());
        moduleId = module.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1: PATCH /{id}/paid-plan → 一覧に反映
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1: PATCH /{id}/paid-plan")
    class UpdatePaidPlan {

        @Test
        @DisplayName("requiresPaidPlan=true に更新すると 200 かつ一覧に反映される")
        void 有料要否更新が一覧に反映される() throws Exception {
            mockMvc.perform(patch("/api/v1/system-admin/modules/{id}/paid-plan", moduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("requiresPaidPlan", true))))
                    .andExpect(status().isOk());

            JsonNode module = findModuleInCatalog(moduleId);
            assertThat(module).as("更新後のモジュールが一覧に存在する").isNotNull();
            assertThat(module.get("requiresPaidPlan").asBoolean()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2: PATCH /{id}/active → 一覧に反映
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2: PATCH /{id}/active")
    class UpdateActive {

        @Test
        @DisplayName("isActive=false に更新すると 200 かつ一覧（active のみ）から外れる")
        void 有効状態更新が一覧に反映される() throws Exception {
            // 前提: 更新前は一覧に存在する。
            assertThat(findModuleInCatalog(moduleId)).as("更新前は一覧に存在").isNotNull();

            mockMvc.perform(patch("/api/v1/system-admin/modules/{id}/active", moduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isActive", false))))
                    .andExpect(status().isOk());

            // getModuleCatalog は is_active=true のみ返すため、無効化後は一覧から消える。
            assertThat(findModuleInCatalog(moduleId)).as("無効化後は一覧から外れる").isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-3: 存在しない module id → 404（TMPL_002）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-3: 存在しない module id → 404")
    class NotFound {

        private static final long UNKNOWN_ID = 999_999_999L;

        @Test
        @DisplayName("paid-plan: 存在しない id は 404")
        void 有料要否_不在id_404() throws Exception {
            mockMvc.perform(patch("/api/v1/system-admin/modules/{id}/paid-plan", UNKNOWN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("requiresPaidPlan", true))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("active: 存在しない id は 404")
        void 有効状態_不在id_404() throws Exception {
            mockMvc.perform(patch("/api/v1/system-admin/modules/{id}/active", UNKNOWN_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isActive", false))))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC バリデーション: NotNull ボディ欠落は 400
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("requiresPaidPlan 欠落（@NotNull 違反）は 400")
    void 有料要否_ボディ欠落_400() throws Exception {
        mockMvc.perform(patch("/api/v1/system-admin/modules/{id}/paid-plan", moduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LinkedHashMap<String, Object>())))
                .andExpect(status().isBadRequest());
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * {@code GET /api/v1/system-admin/modules}（getModuleCatalog）を叩き、指定 id の
     * モジュールノードを返す。存在しなければ null。
     */
    private JsonNode findModuleInCatalog(Long targetId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/system-admin/modules"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        if (data == null || !data.isArray()) {
            return null;
        }
        for (JsonNode node : data) {
            JsonNode idNode = node.get("id");
            if (idNode != null && idNode.asLong() == targetId) {
                return node;
            }
        }
        return null;
    }
}
