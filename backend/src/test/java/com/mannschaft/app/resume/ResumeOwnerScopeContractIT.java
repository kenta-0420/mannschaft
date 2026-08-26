package com.mannschaft.app.resume;

import com.mannschaft.app.resume.entity.ResumeEntity;
import com.mannschaft.app.resume.repository.ResumeRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第2波 — 履歴書・職務経歴書（F01.10）ドメインの認可契約テスト。
 *
 * <p>本テストが固定する防御仕様:</p>
 * <ul>
 *   <li><b>実体由来の所有者照合</b>: 履歴書 ID を受け取る全 EP は
 *       {@code findByIdAndUserId(id, 認証主体の userId)} の複合条件で対象を引き当てる。
 *       所有者以外は、存在しない ID を指定した場合と<b>区別できない 404</b> を受け取る
 *       （履歴書の存在そのものを秘匿する）。</li>
 *   <li><b>副作用の不発生</b>: 拒否された更新・削除・写真操作で DB の実値が動かないことを確認する。</li>
 *   <li><b>自己スコープ</b>: 一覧・新規作成は認証主体の userId のみに束縛される。</li>
 * </ul>
 *
 * <p>本テストは以下の自己スコープ宣言（{@code @SelfScopedEndpoint}）の証跡を兼ねる:
 * {@code ResumeController#listResumes} / {@code ResumeController#createResume}。</p>
 *
 * <p>金型: {@code JobDetailScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("履歴書（F01.10）所有者スコープ 認可契約テスト（第2波）")
class ResumeOwnerScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeRepository resumeRepository;

    @PersistenceContext
    private EntityManager em;

    /** 履歴書の所有者。 */
    private Long ownerId;
    /** 所有者と無関係の第三者（攻撃者役）。 */
    private Long attackerId;

    /** 所有者の履歴書 ID。 */
    private UUID ownerResumeId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("resume-owner@example.com");
        attackerId = insertUser("resume-attacker@example.com");

        ResumeEntity resume = resumeRepository.save(ResumeEntity.builder()
                .userId(ownerId)
                .title("RESUMEAUTHZ 所有者の履歴書")
                .selfPr("RESUMEAUTHZ 自己PR（秘匿対象）")
                .build());
        ownerResumeId = resume.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 参照系 — 所有者以外は 404（存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 参照系（所有者以外は 404 で存在を秘匿）")
    class ReadEndpoints {

        @Test
        @DisplayName("GET /api/v1/resumes/{id}: 第三者は404（ResumeController#getResume）")
        void 第三者の取得は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/resumes/{id}", ownerResumeId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /api/v1/resumes/{id}: 存在しないIDと所有者不一致は同じ404を返す")
        void 不存在と所有者不一致は同じ応答() throws Exception {
            setAuth(attackerId);
            int mismatchStatus = mockMvc.perform(get("/api/v1/resumes/{id}", ownerResumeId))
                    .andReturn().getResponse().getStatus();
            int missingStatus = mockMvc.perform(get("/api/v1/resumes/{id}", UUID.randomUUID()))
                    .andReturn().getResponse().getStatus();
            assertThat(mismatchStatus)
                    .as("所有者不一致と不存在が同一ステータスであること（存在秘匿）")
                    .isEqualTo(missingStatus);
        }

        @Test
        @DisplayName("GET /api/v1/resumes/{id}: 所有者本人は200")
        void 所有者の取得は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/resumes/{id}", ownerResumeId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /api/v1/resumes/{id}/preview: 第三者は404（ResumeController#previewResume）")
        void 第三者のプレビューは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/resumes/{id}/preview", ownerResumeId)
                            .param("type", "rirekisho")
                            .param("format", "pdf"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /api/v1/resumes/{id}/export: 第三者は404（ResumeController#exportResume）")
        void 第三者の出力は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/resumes/{id}/export", ownerResumeId)
                            .param("type", "rirekisho")
                            .param("format", "pdf"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 更新・削除・複製 — 所有者以外は 404 かつ副作用なし
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 更新・削除・複製（所有者以外は 404・副作用なし）")
    class WriteEndpoints {

        @Test
        @DisplayName("PUT /api/v1/resumes/{id}: 第三者は404・本文は書き換わらない（ResumeController#saveResume）")
        void 第三者の一括保存は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/resumes/{id}", ownerResumeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"侵入書き換え\"}"))
                    .andExpect(status().isNotFound());
            assertTitleUnchanged();
        }

        @Test
        @DisplayName("PATCH /api/v1/resumes/{id}: 第三者は404・本文は書き換わらない（ResumeController#patchResume）")
        void 第三者の部分更新は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/resumes/{id}", ownerResumeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"侵入書き換え\"}"))
                    .andExpect(status().isNotFound());
            assertTitleUnchanged();
        }

        @Test
        @DisplayName("DELETE /api/v1/resumes/{id}: 第三者は404・削除されない（ResumeController#deleteResume）")
        void 第三者の削除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/resumes/{id}", ownerResumeId))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();
            assertThat(resumeRepository.findByIdAndUserId(ownerResumeId, ownerId))
                    .as("拒否された削除で履歴書が残っていること")
                    .isPresent();
        }

        @Test
        @DisplayName("POST /api/v1/resumes/{id}/duplicate: 第三者は404・複製されない"
                + "（ResumeController#duplicateResume）")
        void 第三者の複製は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/resumes/{id}/duplicate", ownerResumeId))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();
            assertThat(resumeRepository.findByUserIdOrderByCreatedAtDesc(attackerId))
                    .as("第三者の手元に複製が作られていないこと")
                    .isEmpty();
        }

        /** 拒否された更新で履歴書のタイトルが動いていないことを DB の実値で確認する。 */
        private void assertTitleUnchanged() {
            em.flush();
            em.clear();
            ResumeEntity reloaded = resumeRepository.findByIdAndUserId(ownerResumeId, ownerId).orElseThrow();
            assertThat(reloaded.getTitle())
                    .as("拒否された更新でタイトルが書き換わっていないこと")
                    .isEqualTo("RESUMEAUTHZ 所有者の履歴書");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 証明写真 — 所有者以外は 404 かつ副作用なし
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 証明写真（所有者以外は 404・副作用なし）")
    class PhotoEndpoints {

        @Test
        @DisplayName("POST /api/v1/resumes/{id}/photo: 第三者は404（ResumeController#uploadPhoto）")
        void 第三者のアップロードは404() throws Exception {
            setAuth(attackerId);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "photo.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3, 4});
            mockMvc.perform(multipart("/api/v1/resumes/{id}/photo", ownerResumeId).file(file))
                    .andExpect(status().isNotFound());
            assertPhotoKeyUnchanged();
        }

        @Test
        @DisplayName("DELETE /api/v1/resumes/{id}/photo: 第三者は404（ResumeController#deletePhoto）")
        void 第三者の写真削除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/resumes/{id}/photo", ownerResumeId))
                    .andExpect(status().isNotFound());
            assertPhotoKeyUnchanged();
        }

        /** 拒否された写真操作で photo_key が動いていないことを DB の実値で確認する。 */
        private void assertPhotoKeyUnchanged() {
            em.flush();
            em.clear();
            ResumeEntity reloaded = resumeRepository.findByIdAndUserId(ownerResumeId, ownerId).orElseThrow();
            assertThat(reloaded.getPhotoKey())
                    .as("拒否された写真操作で photo_key が動いていないこと")
                    .isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 自己スコープ EP
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 自己スコープ EP（一覧・新規作成）")
    class SelfScoped {

        @Test
        @DisplayName("GET /api/v1/resumes: 第三者には他人の履歴書が 1 件も返らない"
                + "（ResumeController#listResumes）")
        void 第三者の一覧は空() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/resumes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("GET /api/v1/resumes: 所有者には自分の履歴書のみが返る")
        void 所有者の一覧は自分の履歴書のみ() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/resumes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(ownerResumeId.toString()));
        }

        @Test
        @DisplayName("POST /api/v1/resumes: 作成された履歴書は必ず認証主体に紐づく"
                + "（ResumeController#createResume）")
        void 新規作成は認証主体に紐づく() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/resumes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"第三者の新規履歴書\"}"))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();
            List<ResumeEntity> created = resumeRepository.findByUserIdOrderByCreatedAtDesc(attackerId);
            assertThat(created)
                    .as("作成された履歴書が認証主体（呼び出し元）に紐づくこと")
                    .hasSize(1);
            assertThat(resumeRepository.findByUserIdOrderByCreatedAtDesc(ownerId))
                    .as("他ユーザーの履歴書は増えていないこと")
                    .hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'RESUMEAUTHZ', 'テスト', 'RESUMEAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
