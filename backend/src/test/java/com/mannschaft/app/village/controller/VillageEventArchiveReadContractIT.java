package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageEventArchiveEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageEventArchiveRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 Wave2 ⑦ 村史（行事アーカイブ）読み出し EP — API 契約テスト（Wave2 追補・試練先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p>編纂（書き込み）側の {@code VillageEventArchiveService} は F17.2 Wave2 骨格 PR（#2427）で
 * main 済みだが、村史タブ（村人向け read）が読み込む
 * {@code GET /api/v1/villages/{villageId}/event-archives} が未実装のまま欠落していた
 * （殿が実証・軍議AC漏れの追補）。本テストは試練（red 先行）として作成し、出陣
 * （{@code VillageEventArchiveController}/{@code VillageEventArchiveResponse}/
 * {@code VillageEventArchiveService#listArchives} の実装）で green 化する。</p>
 *
 * <p>金型は姉妹クラス {@link VillageFestivalParticipationContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} +
 * {@code @MockitoBean R2StorageService}）と完全同一構成にして TestContext キャッシュを共有する。</p>
 *
 * <p>受け入れ条件（設計書 §7.4/§13.5）:</p>
 * <ul>
 *   <li>一覧は 200・{@code archived_at} 降順・ページング（既定20・size 上限あり）</li>
 *   <li>認可は村掲示板と同一の閲覧認可（{@code VillageBulletinAccessService}）。
 *       非メンバー×MEMBERS_ONLY 村 → 403（{@code VILLAGE_081}）、
 *       村が存在しない → 404（{@code VILLAGE_001}）</li>
 *   <li>0件でも 200＋空配列</li>
 *   <li>返却フィールドは §7.2 準拠（source_type/source_id/title/summary/archived_at 等）で
 *       実名・identity を含まない（G4）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave2 ⑦村史読み出し API 契約テスト")
class VillageEventArchiveReadContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageEventArchiveRepository archiveRepository;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため宣言する。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_270_001L;
    private static final Long VILLAGER_ID = 17_270_002L;
    private static final Long OUTSIDER_ID = 17_270_004L;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 一覧・ページング・ソート（§7.4/§13.5）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一覧取得（GET .../event-archives）")
    class List_ {

        @Test
        @DisplayName("村人がアクセスすると 200・archived_at 降順で返る")
        void villager_list_200_orderedDesc() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageEventArchiveEntity older = persistArchive(v.getId(), "第1回夏祭り",
                    LocalDateTime.now().minusDays(10));
            VillageEventArchiveEntity newer = persistArchive(v.getId(), "第2回夏祭り",
                    LocalDateTime.now().minusDays(1));

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(newer.getId().toString()))
                    .andExpect(jsonPath("$.data[1].id").value(older.getId().toString()));
        }

        @Test
        @DisplayName("返却フィールドは source_type/source_id/title/summary/archived_at を含み、実名を含まない")
        void villager_list_returnsExpectedFields() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageEventArchiveEntity archive = persistArchive(v.getId(), "第1回夏祭り", LocalDateTime.now());

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(archive.getId().toString()))
                    .andExpect(jsonPath("$.data[0].villageId").value(v.getId().toString()))
                    .andExpect(jsonPath("$.data[0].sourceType").value("FESTIVAL"))
                    .andExpect(jsonPath("$.data[0].sourceId").value(archive.getSourceId().toString()))
                    .andExpect(jsonPath("$.data[0].title").value("第1回夏祭り"))
                    .andExpect(jsonPath("$.data[0].summary").exists())
                    .andExpect(jsonPath("$.data[0].archivedAt").exists());
        }

        @Test
        @DisplayName("size=1 を指定すると 1 件だけ返る（全件一括ではない・§13.5）")
        void list_isPaged_notReturningAll() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistArchive(v.getId(), "第1回夏祭り", LocalDateTime.now().minusDays(2));
            persistArchive(v.getId(), "第2回夏祭り", LocalDateTime.now().minusDays(1));

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId())
                            .param("page", "0")
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("村史が0件でも 200＋空配列")
        void list_empty_returnsEmptyArray() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("sourceType 絞り込みで種別が一致する行のみ返る")
        void list_filterBySourceType() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistArchive(v.getId(), VillageEventArchiveSourceType.FESTIVAL, "夏祭り", LocalDateTime.now());
            persistArchive(v.getId(), VillageEventArchiveSourceType.MEETUP, "定例寄合", LocalDateTime.now());

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId())
                            .param("sourceType", "MEETUP"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].sourceType").value("MEETUP"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 認可（村掲示板と同一の閲覧認可・§7.4）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("認可（掲示板と同一の閲覧認可）")
    class Authorization {

        @Test
        @DisplayName("MEMBERS_ONLY 村への非メンバーは 403 + VILLAGE_081")
        void outsider_membersOnlyVillage_403() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.MEMBERS_ONLY);
            persistArchive(v.getId(), "夏祭り", LocalDateTime.now());

            authAs(OUTSIDER_ID); // membership を作らない
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_081"));
        }

        @Test
        @DisplayName("PUBLIC 村へはログイン済みの非メンバーでも 200")
        void outsider_publicVillage_200() throws Exception {
            VillageEntity v = persistVillage(VillageBulletinVisibility.PUBLIC);
            persistArchive(v.getId(), "夏祭り", LocalDateTime.now());

            authAs(OUTSIDER_ID); // membership を作らない
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("存在しない村IDは 404 + VILLAGE_001（IDOR・存在秘匿）")
        void unknownVillage_404() throws Exception {
            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/event-archives", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private VillageEntity persistVillage(VillageBulletinVisibility bulletinVisibility) {
        VillageEntity v = VillageEntity.builder()
                .slug("evarc-" + Long.toHexString(System.nanoTime()))
                .name("村史読出村" + System.nanoTime())
                .description("村史読み出しテスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(bulletinVisibility)
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

    private VillageEventArchiveEntity persistArchive(UUID villageId, String title, LocalDateTime archivedAt) {
        return persistArchive(villageId, VillageEventArchiveSourceType.FESTIVAL, title, archivedAt);
    }

    private VillageEventArchiveEntity persistArchive(UUID villageId, VillageEventArchiveSourceType sourceType,
                                                       String title, LocalDateTime archivedAt) {
        VillageEventArchiveEntity archive = VillageEventArchiveEntity.builder()
                .villageId(villageId)
                .sourceType(sourceType)
                .sourceId(UUID.randomUUID())
                .title(title)
                .summary("参加表明 合計=3（GOING=2 / MAYBE=1） ／ 実況=1件")
                .archivedAt(archivedAt)
                .build();
        return archiveRepository.saveAndFlush(archive);
    }
}
