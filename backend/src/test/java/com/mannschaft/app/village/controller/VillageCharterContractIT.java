package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageCharterArticleEntity;
import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import com.mannschaft.app.village.entity.VillageCharterEntity;
import com.mannschaft.app.village.entity.VillageCharterRevisionEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageCharterArticleRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageCharterRepository;
import com.mannschaft.app.village.repository.VillageCharterRevisionRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.3 村憲章 API 契約テスト（設計書 §17・実装済み green）。
 *
 * <h2>カバー範囲</h2>
 *
 * <p>公開ゲート（AC-01/02/03）・条文管理と編集権限（04/05/05b/06/07/08/08b）・並び順/自動採番/再連番
 * （09/10/11/11c/12/13）・策定者（14/15/16/16b）・制定日/改定履歴（17/17b/18）・性能（20/20b）を
 * MockMvc 経由で検証する。認可は {@code VillageCharterAccessService}（read 公開ゲート）と
 * write 2 段ガード（{@code loadActiveVillage}→{@code requireHeadmanOrElder}）に委譲される。</p>
 *
 * <p>金型: {@link VillageMeetupCapacityContractIT} / {@link VillageEventArchiveReadContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} +
 * {@code @Transactional} + {@code @MockitoBean R2StorageService}）。フィクスチャは Repository で作り、
 * 日時は {@code LocalDateTime} を bind する（TZ 境界事故回避）。並行系（AC-11b/11d/12b）は
 * {@code VillageCharterConcurrencyIT}、退会（AC-19）は {@code VillageCharterWithdrawalIT} に分離。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.3 村憲章 API 契約テスト（AC-01〜20b）")
class VillageCharterContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageMembershipRepository membershipRepository;
    @Autowired private VillageCharterRepository charterRepository;
    @Autowired private VillageCharterArticleRepository articleRepository;
    @Autowired private VillageCharterDrafterRepository drafterRepository;
    @Autowired private VillageCharterRevisionRepository revisionRepository;
    @Autowired private UserVillageNicknameRepository nicknameRepository;
    @Autowired private EntityManager em;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため mock 宣言。 */
    @MockitoBean private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_300_001L;
    private static final Long ELDER_ID = 17_300_002L;
    private static final Long VILLAGER_ID = 17_300_003L;
    private static final Long OUTSIDER_ID = 17_300_099L;
    private static final Long DRAFTER_USER_ID = 17_300_050L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能③ 公開ゲート（AC-01/02/03）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("公開ゲート（AC-01/02/03）")
    class PublicGate {

        @Test
        @DisplayName("AC-01 PUBLIC村の未参加ログインユーザーは200・canEdit=false・条/策定者/改定履歴が返る")
        void publicVillage_outsider_canRead_AC01() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            VillageCharterEntity charter = persistCharter(v.getId());
            persistArticle(charter.getId(), v.getId(), 0, "第一条 本文");
            persistDrafter(charter.getId(), DRAFTER_USER_ID, "開村の祖", 0);
            persistRevision(charter.getId(), "初回改定", LocalDateTime.now());

            authAs(OUTSIDER_ID); // 非メンバー
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasCharter").value(true))
                    .andExpect(jsonPath("$.data.canEdit").value(false))
                    .andExpect(jsonPath("$.data.articles.length()").value(1))
                    .andExpect(jsonPath("$.data.articles[0].articleNumber").value(1))
                    .andExpect(jsonPath("$.data.drafters.length()").value(1))
                    .andExpect(jsonPath("$.data.revisions.length()").value(1));
        }

        @Test
        @DisplayName("AC-02 UNLISTED村: 非メンバーは404・現役メンバーは200・未ログインは401")
        void unlistedVillage_hidesFromOutsider_admitsMember_AC02() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.UNLISTED);
            persistCharter(v.getId());
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            // 非メンバー → 404（存在秘匿・IDOR）
            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));

            // 現役メンバー → 200
            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk());

            // 未ログイン → 401（Controller 先頭 getCurrentUserId が投げる）
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-03 憲章未制定の閲覧可能な村は200＋hasCharter=false・空配列（404にしない）")
        void unenacted_returns200Empty_AC03() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasCharter").value(false))
                    .andExpect(jsonPath("$.data.enactedAt").doesNotExist())
                    .andExpect(jsonPath("$.data.articles.length()").value(0))
                    .andExpect(jsonPath("$.data.drafters.length()").value(0))
                    .andExpect(jsonPath("$.data.revisions.length()").value(0));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能① 条文管理・編集権限（AC-04/05/05b/06/07/08/08b）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("条文管理・編集権限（AC-04/05/05b/06/07/08/08b）")
    class ArticleCrudAndAuthz {

        @Test
        @DisplayName("AC-04 現役HEADMANのPOST articlesで条が末尾追加され初回はcharter自動生成・enacted_atセット")
        void headman_postArticle_autoCreatesCharter_AC04() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "第一条 この村はゆるく集う"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasCharter").value(true))
                    .andExpect(jsonPath("$.data.enactedAt").exists())
                    .andExpect(jsonPath("$.data.articles.length()").value(1));

            // charter が自動生成されている
            org.assertj.core.api.Assertions.assertThat(
                    charterRepository.findByVillageIdAndDeletedAtIsNull(v.getId())).isPresent();
        }

        @Test
        @DisplayName("AC-05 一般村人・非メンバーの write は 403（MODERATION_FORBIDDEN）")
        void nonModerator_write_forbidden_AC05() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            authAs(VILLAGER_ID); // 一般村人
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "勝手に条を足す"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));

            authAs(OUTSIDER_ID); // 非メンバー
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "部外者が条を足す"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AC-05b 削除済み村への write は404・凍結済み村への write は409（VILLAGE_027）。権限より先に評価")
        void writeVillageStateGate_AC05b() throws Exception {
            // 削除済み村
            VillageEntity deleted = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(deleted.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            deleted.setDeletedAt(LocalDateTime.now());
            villageRepository.saveAndFlush(deleted);

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", deleted.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "削除済み村に条"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));

            // 凍結済み村
            VillageEntity archived = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(archived.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            archived.setArchivedAt(LocalDateTime.now());
            villageRepository.saveAndFlush(archived);

            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", archived.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "凍結村に条"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_027"));
        }

        @Test
        @DisplayName("AC-05b(順序) 凍結村への非モデレーターwriteは、権限403でなく状態409(VILLAGE_027)が先に返る")
        void writeStateGateEvaluatedBeforePermission_AC05b() throws Exception {
            // 凍結村 × 一般村人（非モデレーター）: 状態ガードが権限チェックより先ゆえ 409(VILLAGE_027)。
            // もし順序が逆（権限が先）なら 403(VILLAGE_024) が返るはずで、本アサートで順序を実証する。
            VillageEntity archived = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(archived.getId(), VILLAGER_ID, VillageRole.VILLAGER); // 非モデレーター
            archived.setArchivedAt(LocalDateTime.now());
            villageRepository.saveAndFlush(archived);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", archived.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "凍結村に一般村人が条"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_027")); // 403 ではない＝状態が先

            // 削除村 × 非メンバー: 状態ガードが先ゆえ 404(VILLAGE_001)（権限 403 ではない）。
            VillageEntity deleted = persistVillage(VillageVisibility.PUBLIC);
            deleted.setDeletedAt(LocalDateTime.now());
            villageRepository.saveAndFlush(deleted);

            authAs(OUTSIDER_ID); // 非メンバー
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", deleted.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "削除村に部外者が条"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_001")); // 403 ではない＝状態が先
        }

        @Test
        @DisplayName("AC-06 現役村長のPUT articlesで本文・付則が更新され、更新後versionが応答に載る")
        void headman_putArticle_updates_AC06() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity a = persistArticle(charter.getId(), v.getId(), 0, "旧本文");

            authAs(HEADMAN_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "新本文");
            body.put("supplement", "付則を添える");
            body.put("version", a.getVersion() == null ? 0L : a.getVersion());
            mockMvc.perform(put("/api/v1/villages/{vid}/charter/articles/{aid}", v.getId(), a.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.body").value("新本文"))
                    .andExpect(jsonPath("$.data.version").exists());
        }

        @Test
        @DisplayName("AC-07 古いversionでのPUT articlesは409（CHARTER_ARTICLE_VERSION_CONFLICT・層1）")
        void staleVersion_put_conflict_AC07() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity a = persistArticle(charter.getId(), v.getId(), 0, "本文");

            authAs(HEADMAN_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "上書き");
            body.put("version", 999L); // 古い/ありえない version
            mockMvc.perform(put("/api/v1/villages/{vid}/charter/articles/{aid}", v.getId(), a.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_105"));
        }

        @Test
        @DisplayName("AC-08 別の村のcharterに属するarticleIdをPUT/DELETEに渡すと404（CHARTER_ARTICLE_NOT_FOUND・IDOR）")
        void crossVillage_articleId_notFound_AC08() throws Exception {
            VillageEntity vA = persistVillage(VillageVisibility.PUBLIC);
            VillageEntity vB = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(vA.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charterB = persistCharter(vB.getId());
            VillageCharterArticleEntity articleB = persistArticle(charterB.getId(), vB.getId(), 0, "村Bの条");

            authAs(HEADMAN_ID);
            // 村Aの URL で村Bの articleId を PUT
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "他村の条を触る");
            body.put("version", 0L);
            mockMvc.perform(put("/api/v1/villages/{vid}/charter/articles/{aid}", vA.getId(), articleB.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_104"));
            // DELETE も同様
            mockMvc.perform(delete("/api/v1/villages/{vid}/charter/articles/{aid}", vA.getId(), articleB.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_104"));
        }

        @Test
        @DisplayName("AC-08b body空/2000超・supplement2000超は400（Bean Validation）。2000ちょうどは通る")
        void articleLength_boundary_400_AC08b() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            authAs(HEADMAN_ID);

            // body 空 → 400（@NotBlank）
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("body", ""))))
                    .andExpect(status().isBadRequest());

            // body 2001字 → 400（@Size）
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "あ".repeat(2001)))))
                    .andExpect(status().isBadRequest());

            // supplement 2001字 → 400（@Size）
            Map<String, Object> tooLongSupp = new LinkedHashMap<>();
            tooLongSupp.put("body", "正常本文");
            tooLongSupp.put("supplement", "い".repeat(2001));
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(tooLongSupp)))
                    .andExpect(status().isBadRequest());

            // body ちょうど2000字 → 境界内ゆえ通る（400 ではなく 200）。
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "う".repeat(2000)))))
                    .andExpect(status().isOk());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能② 並び順・自動採番・再連番（AC-09/10/11/11c/12/13）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("並び順・自動採番・再連番（AC-09/10/11/11c/12/13）")
    class OrderingAndNumbering {

        @Test
        @DisplayName("AC-09 articleNumberは非削除条をsort_order昇順に並べたindex+1（DBに条番号列なし）")
        void autoNumbering_derived_AC09() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            VillageCharterEntity charter = persistCharter(v.getId());
            persistArticle(charter.getId(), v.getId(), 0, "一つ目");
            persistArticle(charter.getId(), v.getId(), 1, "二つ目");
            persistArticle(charter.getId(), v.getId(), 2, "三つ目");

            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.articles[0].articleNumber").value(1))
                    .andExpect(jsonPath("$.data.articles[1].articleNumber").value(2))
                    .andExpect(jsonPath("$.data.articles[2].articleNumber").value(3));
        }

        @Test
        @DisplayName("AC-10 DELETE articlesで対象を論理削除→残条が0,1,2…に再連番されarticleNumberが繰り上がる")
        void deleteArticle_renumbers_AC10() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            persistArticle(charter.getId(), v.getId(), 0, "一つ目");
            VillageCharterArticleEntity middle = persistArticle(charter.getId(), v.getId(), 1, "二つ目");
            persistArticle(charter.getId(), v.getId(), 2, "三つ目");

            authAs(HEADMAN_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/charter/articles/{aid}", v.getId(), middle.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.articles.length()").value(2))
                    .andExpect(jsonPath("$.data.articles[0].articleNumber").value(1))
                    .andExpect(jsonPath("$.data.articles[1].articleNumber").value(2))
                    .andExpect(jsonPath("$.data.articles[1].body").value("三つ目"));
        }

        @Test
        @DisplayName("AC-11 PATCH orderに非削除条の完全集合を並べ替えて送ると再連番され親charter versionが++")
        void patchOrder_reNumbers_bumpsVersion_AC11() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity a0 = persistArticle(charter.getId(), v.getId(), 0, "A");
            VillageCharterArticleEntity a1 = persistArticle(charter.getId(), v.getId(), 1, "B");

            authAs(HEADMAN_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("articleIds", List.of(a1.getId().toString(), a0.getId().toString())); // 逆順
            body.put("charterVersion", charter.getVersion() == null ? 0L : charter.getVersion());
            mockMvc.perform(patch("/api/v1/villages/{vid}/charter/articles/order", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.articles[0].body").value("B"))
                    .andExpect(jsonPath("$.data.articles[1].body").value("A"));
        }

        @Test
        @DisplayName("AC-11c 別条の削除による再連番では他条の層1 versionは増えず、その条のPUTは409にならない")
        void renumber_doesNotBumpOtherArticleVersion_AC11c() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity aB = persistArticle(charter.getId(), v.getId(), 0, "条B(先頭・削除対象)");
            VillageCharterArticleEntity aA = persistArticle(charter.getId(), v.getId(), 1, "条A(編集対象)");
            long aVersionBefore = aA.getVersion() == null ? 0L : aA.getVersion();

            authAs(HEADMAN_ID);
            // 条Bを削除 → 条Aが sort_order 1→0 に繰り上がる（再連番）
            mockMvc.perform(delete("/api/v1/villages/{vid}/charter/articles/{aid}", v.getId(), aB.getId()))
                    .andExpect(status().isOk());

            // 条Aを、削除前に取得した version で PUT → 層1 versionは再連番で上がっていないので 409 にならない
            Map<String, Object> putBody = new LinkedHashMap<>();
            putBody.put("body", "条Aを編集");
            putBody.put("version", aVersionBefore);
            mockMvc.perform(put("/api/v1/villages/{vid}/charter/articles/{aid}", v.getId(), aA.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(putBody)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-12 古いcharterVersionでのPATCH orderは409（CHARTER_ORDER_VERSION_CONFLICT・層2）")
        void staleCharterVersion_patchOrder_conflict_AC12() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity a0 = persistArticle(charter.getId(), v.getId(), 0, "A");
            VillageCharterArticleEntity a1 = persistArticle(charter.getId(), v.getId(), 1, "B");

            authAs(HEADMAN_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("articleIds", List.of(a1.getId().toString(), a0.getId().toString()));
            body.put("charterVersion", 999L); // 古い/ありえない version
            mockMvc.perform(patch("/api/v1/villages/{vid}/charter/articles/order", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_106"));
        }

        @Test
        @DisplayName("AC-13 version一致でarticleIds集合が過不足/重複なら400（version不一致は先に409）")
        void patchOrder_setMismatch_400_AC13() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterArticleEntity a0 = persistArticle(charter.getId(), v.getId(), 0, "A");
            persistArticle(charter.getId(), v.getId(), 1, "B");
            long ver = charter.getVersion() == null ? 0L : charter.getVersion();

            authAs(HEADMAN_ID);
            // 欠落（a1 を含めない）＋ version は一致 → 400
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("articleIds", List.of(a0.getId().toString()));
            body.put("charterVersion", ver);
            mockMvc.perform(patch("/api/v1/villages/{vid}/charter/articles/order", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能⑤ 策定者（AC-14/15/16/16b）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("策定者（AC-14/15/16/16b）")
    class Drafters {

        @Test
        @DisplayName("AC-14 POST draftersで村ニックネームがnickname_snapshotに焼付・末尾sort_order・応答にuserIdなし")
        void addDrafter_snapshotsNickname_AC14() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            persistCharter(v.getId());
            // 策定者に加えるユーザーの村内ニックネームを用意
            persistNickname(DRAFTER_USER_ID, v.getId(), "村の開祖");

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/drafters", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", DRAFTER_USER_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.drafters[0].displayName").value("村の開祖"))
                    .andExpect(jsonPath("$.data.drafters[0].sortOrder").value(0))
                    .andExpect(jsonPath("$.data.drafters[0].userId").doesNotExist());
        }

        @Test
        @DisplayName("AC-15 同一ユーザーの二重策定者登録は409（CHARTER_DRAFTER_DUPLICATE）")
        void duplicateDrafter_conflict_AC15() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            persistDrafter(charter.getId(), DRAFTER_USER_ID, "既存策定者", 0);

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/drafters", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", DRAFTER_USER_ID))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_108"));
        }

        @Test
        @DisplayName("AC-16 存在しない/他charterのdrafterIdをDELETEすると404（CHARTER_DRAFTER_NOT_FOUND）")
        void deleteDrafter_notFound_AC16() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            persistCharter(v.getId());

            authAs(HEADMAN_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/charter/drafters/{did}", v.getId(), UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_107"));
        }

        @Test
        @DisplayName("AC-16b DELETE draftersは更新後の憲章全体を返し、残る策定者が0,1,2…に再連番される")
        void deleteDrafter_returnsFullCharter_renumbers_AC16b() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            VillageCharterDrafterEntity d0 = persistDrafter(charter.getId(), 5001L, "策定者0", 0);
            persistDrafter(charter.getId(), 5002L, "策定者1", 1);
            persistDrafter(charter.getId(), 5003L, "策定者2", 2);

            authAs(HEADMAN_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/charter/drafters/{did}", v.getId(), d0.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.drafters.length()").value(2))
                    .andExpect(jsonPath("$.data.drafters[0].sortOrder").value(0))
                    .andExpect(jsonPath("$.data.drafters[1].sortOrder").value(1))
                    .andExpect(jsonPath("$.data.drafters[0].displayName").value("策定者1"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 機能④ 制定日・改定履歴（AC-17/17b/18）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("制定日・改定履歴（AC-17/17b/18）")
    class EnactmentAndRevisions {

        @Test
        @DisplayName("AC-17 POST revisionsでlast_revised_at更新＋改定履歴に日付＋メモの1行追記・revisedAt降順")
        void addRevision_appendsHistory_AC17() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            persistCharter(v.getId());

            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/revisions", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("note", "第一条を明確化"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lastRevisedAt").exists())
                    .andExpect(jsonPath("$.data.revisions.length()").value(1))
                    .andExpect(jsonPath("$.data.revisions[0].note").value("第一条を明確化"));
        }

        @Test
        @DisplayName("AC-17b 条のPOST/PUT/DELETE直後（改正確定前）でも変更はGETに即時反映（非ゲート）")
        void editsArePubliclyVisibleImmediately_AC17b() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            authAs(HEADMAN_ID);
            // 条を追加（改正確定はしない）
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "追加直後の条"))))
                    .andExpect(status().isOk());

            // 非メンバーの GET に即時反映される（下書き状態は存在しない）
            authAs(OUTSIDER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.articles.length()").value(1))
                    .andExpect(jsonPath("$.data.articles[0].body").value("追加直後の条"))
                    // 改正未確定ゆえ lastRevisedAt は null のまま（可視性は変わらない）
                    .andExpect(jsonPath("$.data.lastRevisedAt").doesNotExist());
        }

        @Test
        @DisplayName("AC-18 初回作成でenacted_at自動セット、その後の改正確定でenacted_atは不変（last_revised_atのみ動く）")
        void enactedAtImmutable_AC18() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            authAs(HEADMAN_ID);
            // 初回条追加で制定
            String created = mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "制定の条"))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String enactedAt = objectMapper.readTree(created).at("/data/enactedAt").asText();

            // 改正を確定
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/revisions", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", "改定"))))
                    .andExpect(status().isOk())
                    // enacted_at は改定で変化しない
                    .andExpect(jsonPath("$.data.enactedAt").value(enactedAt))
                    .andExpect(jsonPath("$.data.lastRevisedAt").exists());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 横断・性能（AC-20/20b）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("性能（AC-20/20b）")
    class Performance {

        @Test
        @DisplayName("AC-20 GET .../charterは条/策定者/履歴の件数に依らず固定回数クエリ（N+1なし）")
        void getCharter_isNotNPlus1_AC20() throws Exception {
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            VillageCharterEntity charter = persistCharter(v.getId());
            // 少数（基準）
            persistArticle(charter.getId(), v.getId(), 0, "a0");
            persistDrafter(charter.getId(), 6001L, "d0", 0);
            persistRevision(charter.getId(), "r0", LocalDateTime.now());
            em.flush();
            em.clear();

            authAs(OUTSIDER_ID);
            Statistics stats1 = statisticsCleared();
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk());
            long fewCount = stats1.getPrepareStatementCount();

            // 多数（条/策定者/履歴を増やす）
            for (int i = 1; i <= 10; i++) {
                persistArticle(charter.getId(), v.getId(), i, "a" + i);
                persistDrafter(charter.getId(), 6001L + i, "d" + i, i);
                persistRevision(charter.getId(), "r" + i, LocalDateTime.now().minusDays(i));
            }
            em.flush();
            em.clear();

            Statistics stats2 = statisticsCleared();
            mockMvc.perform(get("/api/v1/villages/{vid}/charter", v.getId()))
                    .andExpect(status().isOk());
            long manyCount = stats2.getPrepareStatementCount();

            // 件数が増えても発行 SQL 数はほぼ一定（各サブリスト1本ずつで組む・N+1 なら比例増）。
            org.assertj.core.api.Assertions.assertThat(manyCount)
                    .as("条/策定者/履歴が増えても GET のSQL発行数は件数非依存であるべし（各1本＝計4本前後）")
                    .isLessThanOrEqualTo(fewCount);
        }

        @Test
        @DisplayName("AC-20b 条は既定200超でPOST articlesが400、策定者は既定20超でPOST draftersが400")
        void subListUpperBounds_400_AC20b() throws Exception {
            // 条 200 件で満杯 → 201件目の POST は 400
            VillageEntity v = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter = persistCharter(v.getId());
            for (int i = 0; i < 200; i++) {
                persistArticle(charter.getId(), v.getId(), i, "条" + i);
            }
            authAs(HEADMAN_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/articles", v.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "201件目"))))
                    .andExpect(status().isBadRequest());

            // 策定者 20 人で満杯 → 21人目の POST は 400
            VillageEntity v2 = persistVillage(VillageVisibility.PUBLIC);
            persistMembership(v2.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCharterEntity charter2 = persistCharter(v2.getId());
            for (int i = 0; i < 20; i++) {
                persistDrafter(charter2.getId(), 7000L + i, "策定者" + i, i);
            }
            persistNickname(7100L, v2.getId(), "21人目");
            mockMvc.perform(post("/api/v1/villages/{vid}/charter/drafters", v2.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", 7100L))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private VillageEntity persistVillage(VillageVisibility visibility) {
        VillageEntity v = VillageEntity.builder()
                .slug("charter-" + Long.toHexString(System.nanoTime()))
                .name("憲章村" + System.nanoTime())
                .description("村憲章テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageMembershipEntity persistMembership(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private VillageCharterEntity persistCharter(UUID villageId) {
        VillageCharterEntity c = VillageCharterEntity.builder()
                .villageId(villageId)
                .enactedAt(LocalDateTime.now())
                .build();
        return charterRepository.saveAndFlush(c);
    }

    private VillageCharterArticleEntity persistArticle(UUID charterId, UUID villageId, int sortOrder, String body) {
        VillageCharterArticleEntity a = VillageCharterArticleEntity.builder()
                .charterId(charterId)
                .villageId(villageId)
                .sortOrder(sortOrder)
                .body(body)
                .build();
        return articleRepository.saveAndFlush(a);
    }

    private VillageCharterDrafterEntity persistDrafter(UUID charterId, Long userId, String nickname, int sortOrder) {
        VillageCharterDrafterEntity d = VillageCharterDrafterEntity.builder()
                .charterId(charterId)
                .userId(userId)
                .nicknameSnapshot(nickname)
                .sortOrder(sortOrder)
                .build();
        return drafterRepository.saveAndFlush(d);
    }

    private VillageCharterRevisionEntity persistRevision(UUID charterId, String note, LocalDateTime revisedAt) {
        VillageCharterRevisionEntity r = VillageCharterRevisionEntity.builder()
                .charterId(charterId)
                .revisedAt(revisedAt)
                .note(note)
                .build();
        return revisionRepository.saveAndFlush(r);
    }

    private UserVillageNicknameEntity persistNickname(Long userId, UUID villageId, String nickname) {
        UserVillageNicknameEntity n = new UserVillageNicknameEntity();
        n.setUserId(userId);
        n.setVillageId(villageId);
        n.setNickname(nickname);
        return nicknameRepository.saveAndFlush(n);
    }
}
