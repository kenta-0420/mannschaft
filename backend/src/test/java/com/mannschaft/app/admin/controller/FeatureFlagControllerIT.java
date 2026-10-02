package com.mannschaft.app.admin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.UpdateFeatureFlagRequest;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一般ユーザー向け公開フィーチャーフラグ読取API {@link FeatureFlagController} の契約テスト
 * （Gate基盤工事①・試練 / 受け入れ条件 AC-1〜AC-4）。
 *
 * <p><b>金型</b>: {@code ActivityPublicContractIT}（{@link AbstractMySqlIntegrationTest} 継承 +
 * {@code @AutoConfigureMockMvc}（{@code addFilters=false} を付けない＝実 Security フィルタチェーンを通す）+
 * {@code @EnabledIf(...isDockerAvailable)}）。</p>
 *
 * <p>test profile は Flyway シード無し（{@code ddl-auto=create}）のため、フィクスチャは
 * {@link FeatureFlagRepository} 経由で都度 save する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("公開フィーチャーフラグ読取API 契約テスト（Gate基盤工事①）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FeatureFlagControllerIT extends AbstractMySqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/feature-flags";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private CacheManager cacheManager;

    /**
     * {@code featureFlagsPublicList} / {@code featureFlags} キャッシュは Spring シングルトン Bean
     * （プロセス内 {@code ConcurrentMapCacheManager}）が保持しており、テストの {@code @Transactional}
     * ロールバックの対象外である。前のテストで evict されずキャッシュに残った値が次のテストへ
     * 漏れる（実際に AC-4 のフラグが AC-2/AC-3 へ混入する形で再現した）ため、
     * 各テスト開始前に明示的にクリアする。
     */
    @BeforeEach
    void cleanUp() {
        featureFlagRepository.deleteAll();
        clearFlagCaches();
    }

    @AfterEach
    void tearDown() {
        featureFlagRepository.deleteAll();
        clearFlagCaches();
    }

    private void clearFlagCaches() {
        if (cacheManager.getCache("featureFlagsPublicList") != null) {
            cacheManager.getCache("featureFlagsPublicList").clear();
        }
        if (cacheManager.getCache("featureFlags") != null) {
            cacheManager.getCache("featureFlags").clear();
        }
    }

    private Long insertFlag(String flagKey, boolean enabled) {
        FeatureFlagEntity entity = featureFlagRepository.save(FeatureFlagEntity.builder()
                .flagKey(flagKey)
                .isEnabled(enabled)
                .description("テスト用フラグ（漏洩したら失格）")
                .build());
        return entity.getId();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-1: 未認証 → 401
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-1) 未認証は401")
    void ac1_未認証は401() throws Exception {
        insertFlag("FEATURE_UNAUTH_" + System.nanoTime(), true);

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-2: 認証済み一般ユーザー → 200、flagKey/enabled のみ（否定アサーション込み）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-2) 認証済み一般ユーザーは200・JSONはflagKey/enabledのみ（description/updatedBy/id を含まない）")
    @WithMockUser(username = "1")
    void ac2_認証済みは200でflagKeyとenabledのみ() throws Exception {
        long nonce = System.nanoTime();
        insertFlag("FEATURE_AC2_" + nonce, true);

        MvcResult result = mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("配列であること").isTrue();
        assertThat(data.size()).as("投入した1件が返る").isEqualTo(1);

        JsonNode item = data.get(0);
        Set<String> expectedKeys = Set.of("flagKey", "enabled");
        Iterator<String> fieldNames = item.fieldNames();
        int count = 0;
        while (fieldNames.hasNext()) {
            String key = fieldNames.next();
            assertThat(expectedKeys).as("想定外のキー: " + key).contains(key);
            count++;
        }
        assertThat(count).as("キー数は2つのみ").isEqualTo(2);

        // 否定アサーション（管理者専用情報が漏れていないこと）
        assertThat(item.has("id")).as("id を含まないこと").isFalse();
        assertThat(item.has("description")).as("description を含まないこと").isFalse();
        assertThat(item.has("updatedBy")).as("updatedBy を含まないこと").isFalse();
        assertThat(item.has("createdAt")).as("createdAt を含まないこと").isFalse();
        assertThat(item.has("updatedAt")).as("updatedAt を含まないこと").isFalse();

        String body = result.getResponse().getContentAsString();
        assertThat(body).as("description の生値が漏れていないこと").doesNotContain("テスト用フラグ（漏洩したら失格）");

        assertThat(item.get("flagKey").asText()).isEqualTo("FEATURE_AC2_" + nonce);
        assertThat(item.get("enabled").asBoolean()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-3: フラグ0件 → 200＋空配列
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-3) フラグ0件は200と空配列")
    @WithMockUser(username = "1")
    void ac3_フラグ0件は200と空配列() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data").isArray())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data").isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════
    // AC-4: updateFlag 後に getPublicFlags が新値を返す（キャッシュ evict 検証）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * (AC-4) {@code updateFlag} 後、{@code getPublicFlags()} が更新後の値を即座に返すこと
     * （{@code featureFlagsPublicList} キャッシュが正しく evict されている証跡）。
     *
     * <p><b>陽性対照（重要）</b>: 本テストが本当に evict を測っているかは、実装側で
     * {@code @CacheEvict(value = "featureFlagsPublicList", allEntries = true)} を一時的に
     * 外した状態で実行し、red になることを確認してから元に戻す運用とする
     * （殿への報告時にそのログを添付する）。</p>
     */
    @Test
    @DisplayName("(AC-4) updateFlag後にgetPublicFlagsが新値を返す（キャッシュevict検証）")
    void ac4_更新後に公開一覧が新値を返す() {
        String flagKey = "FEATURE_AC4_" + System.nanoTime();
        insertFlag(flagKey, false);

        // 1回目の取得でキャッシュに載せる
        var before = featureFlagService.getPublicFlags();
        assertThat(before).anySatisfy(f -> {
            assertThat(f.flagKey()).isEqualTo(flagKey);
            assertThat(f.enabled()).isFalse();
        });

        featureFlagService.updateFlag(flagKey, new UpdateFeatureFlagRequest(true, null), 1L);

        var after = featureFlagService.getPublicFlags();
        assertThat(after).as("evict されていれば新値(true)が返る")
                .anySatisfy(f -> {
                    assertThat(f.flagKey()).isEqualTo(flagKey);
                    assertThat(f.enabled()).isTrue();
                });
    }
}
