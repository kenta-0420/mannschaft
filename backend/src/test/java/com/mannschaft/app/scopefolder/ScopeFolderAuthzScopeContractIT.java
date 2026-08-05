package com.mannschaft.app.scopefolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * マイスコープフォルダ 認可 API 契約テスト（認可根治 Wave4 ロットC）。
 *
 * <p>フォルダは作成した利用者本人の持ち物である。本テストは次を固定する。</p>
 *
 * <h2>フォルダ ID を受け取るエンドポイント（本人所有の検証が要るもの）</h2>
 * <ul>
 *   <li>{@code MyScopeFolderController#updateFolder} / {@code #deleteFolder} /
 *       {@code #addItem} / {@code #removeItem} / {@code #bulkAssign} —
 *       他利用者のフォルダ ID を渡しても {@code SCOPE_FOLDER_NOT_FOUND} に畳まれ、
 *       対象フォルダは一切変化しない。自分のフォルダに対しては正常に成功する。</li>
 * </ul>
 *
 * <h2>フォルダ ID を受け取らないエンドポイント（自己スコープ）</h2>
 * <ul>
 *   <li>{@code MyScopeFolderController#getFolders} / {@code #getDefaultFolder} /
 *       {@code #getNotificationSummary} / {@code #createFolder} / {@code #reorderFolders} —
 *       検索・生成・並び替えの対象がいずれも認証主体に束縛され、他利用者のフォルダには到達しない。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("マイスコープフォルダ 認可 API 契約テスト（認可根治 Wave4 ロットC）")
class ScopeFolderAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MyScopeFolderRepository folderRepository;

    @Autowired
    private MyScopeFolderItemRepository itemRepository;

    @PersistenceContext
    private EntityManager em;

    private static final String BASE = "/api/v1/me/scope-folders";

    private Long ownerId;
    private Long attackerId;
    private Long teamId;
    private Long ownerFolderId;
    private Long attackerFolderId;

    @BeforeEach
    void setUp() {
        String teamSlug = "w4c-folder-team-" + System.nanoTime();
        teamId = insertTeam("W4C フォルダ用チーム", teamSlug);

        ownerId = insertUser("w4c-folder-owner@example.com");
        attackerId = insertUser("w4c-folder-attacker@example.com");

        // 双方ともチームの正規メンバー。差は「フォルダの所有者かどうか」だけにする。
        MembershipTestHelper.insertMembership(
                em, ownerId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(
                em, attackerId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();

        ownerFolderId = folderRepository.save(MyScopeFolderEntity.builder()
                .userId(ownerId)
                .scopeType(ScopeType.TEAM)
                .name("所有者のフォルダ")
                .color("#112233")
                .isDefault(Boolean.FALSE)
                .sortOrder(0)
                .build()).getId();

        attackerFolderId = folderRepository.save(MyScopeFolderEntity.builder()
                .userId(attackerId)
                .scopeType(ScopeType.TEAM)
                .name("別利用者のフォルダ")
                .isDefault(Boolean.FALSE)
                .sortOrder(0)
                .build()).getId();

        itemRepository.save(MyScopeFolderItemEntity.builder()
                .folderId(ownerFolderId)
                .scopeId(teamId)
                .sortOrder(0)
                .assignedVia(com.mannschaft.app.scopefolder.entity.AssignedVia.MANUAL)
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // フォルダ ID を受け取るエンドポイント
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("フォルダ更新(updateFolder)")
    class UpdateFolder {

        @Test
        @DisplayName("他利用者のフォルダ ID を指定しても SCOPE_FOLDER_NOT_FOUND となり、名前は変化しない")
        void 他利用者のフォルダは更新できない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(put(BASE + "/{folderId}", ownerFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "乗っ取り", "color", "#FFFFFF"))))
                    .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));

            // clear すると未確定の変更ごと捨ててしまい「壊れていても緑」になるため、
            // 永続化コンテキストの実体をそのまま見る。
            assertThat(folderRepository.findById(ownerFolderId).orElseThrow().getName())
                    .isEqualTo("所有者のフォルダ");
        }

        @Test
        @DisplayName("自分のフォルダは 200 で更新できる（正常系）")
        void 自分のフォルダは更新できる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(put(BASE + "/{folderId}", ownerFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "改名後", "color", "#445566"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名後"));
        }
    }

    @Nested
    @DisplayName("フォルダ削除(deleteFolder)")
    class DeleteFolder {

        @Test
        @DisplayName("他利用者のフォルダ ID を指定しても削除されない")
        void 他利用者のフォルダは削除できない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(delete(BASE + "/{folderId}", ownerFolderId))
                    .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));

            // フォルダは論理削除（MyScopeFolderService#deleteFolder が softDelete + save）のため、
            // 行の有無ではなく deleted_at を見る。clear は未確定の変更を捨てるので挟まない。
            assertThat(folderRepository.findById(ownerFolderId).orElseThrow().getDeletedAt())
                    .isNull();
        }

        @Test
        @DisplayName("自分のフォルダは 204 で削除できる（正常系）")
        void 自分のフォルダは削除できる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(delete(BASE + "/{folderId}", ownerFolderId))
                    .andExpect(status().isNoContent());

            // 論理削除は UPDATE のため、flush で確定させてから読み直す（flush 無しの clear は変更を捨てる）。
            em.flush();
            em.clear();
            assertThat(folderRepository.findById(ownerFolderId).orElseThrow().getDeletedAt())
                    .isNotNull();
            assertThat(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(ownerFolderId, ownerId))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("アイテム追加(addItem) / 削除(removeItem)")
    class Items {

        @Test
        @DisplayName("他利用者のフォルダへはアイテムを追加できない")
        void 他利用者のフォルダへ追加できない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(post(BASE + "/{folderId}/items", ownerFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("scopeId", teamId))))
                    .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));
        }

        @Test
        @DisplayName("自分のフォルダへは 200 で追加できる（正常系）")
        void 自分のフォルダへ追加できる() throws Exception {
            Long emptyFolderId = folderRepository.save(MyScopeFolderEntity.builder()
                    .userId(ownerId)
                    .scopeType(ScopeType.TEAM)
                    .name("追加先")
                    .isDefault(Boolean.FALSE)
                    .sortOrder(1)
                    .build()).getId();
            em.flush();
            em.clear();

            setAuthentication(ownerId);
            mockMvc.perform(post(BASE + "/{folderId}/items", emptyFolderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("scopeId", teamId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.itemScopeIds[0]").value(teamId));
        }

        @Test
        @DisplayName("他利用者のフォルダからはアイテムを削除できない")
        void 他利用者のフォルダから削除できない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(delete(BASE + "/{folderId}/items/{scopeId}", ownerFolderId, teamId))
                    .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));

            assertThat(itemRepository.findByFolderIdAndScopeId(ownerFolderId, teamId)).isPresent();
        }

        @Test
        @DisplayName("自分のフォルダからは 204 で削除できる（正常系）")
        void 自分のフォルダから削除できる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(delete(BASE + "/{folderId}/items/{scopeId}", ownerFolderId, teamId))
                    .andExpect(status().isNoContent());

            // アイテムは物理削除（MyScopeFolderService#removeItem が itemRepository.delete）。
            // remove は同一トランザクション内では未確定なので flush で DELETE を確定させる。
            em.flush();
            em.clear();
            assertThat(itemRepository.findByFolderIdAndScopeId(ownerFolderId, teamId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("一括振り分け(bulkAssign)")
    class BulkAssign {

        @Test
        @DisplayName("他利用者のフォルダ ID を指定した一括振り分けは 1 件も入らない")
        void 他利用者のフォルダへ一括振り分けできない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(post(BASE + "/items/bulk-assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "folderId", ownerFolderId,
                                    "scopeIds", List.of(teamId),
                                    "scopeType", "TEAM"))))
                    .andExpect(jsonPath("$.error.code").value("SCOPE_FOLDER_NOT_FOUND"));

            assertThat(itemRepository.findByFolderIdOrderBySortOrder(ownerFolderId)).hasSize(1);
        }

        @Test
        @DisplayName("自分のフォルダへは 200 で一括振り分けできる（正常系）")
        void 自分のフォルダへ一括振り分けできる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(post(BASE + "/items/bulk-assign")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "folderId", ownerFolderId,
                                    "scopeIds", List.of(teamId),
                                    "scopeType", "TEAM"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignedCount").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フォルダ ID を受け取らないエンドポイント（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自己スコープの参照系")
    class SelfScopedReads {

        @Test
        @DisplayName("MyScopeFolderController#getFolders は自分のフォルダだけを返す")
        void getFolders_は自分のフォルダだけを返す() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(get(BASE).param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(attackerFolderId))
                    .andExpect(jsonPath("$.data[0].name").value("別利用者のフォルダ"));
        }

        @Test
        @DisplayName("MyScopeFolderController#getDefaultFolder は呼び出し利用者の未分類を返す")
        void getDefaultFolder_は本人の未分類を返す() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(get(BASE + "/default").param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isDefault").value(true));

            em.flush();
            em.clear();
            assertThat(folderRepository
                    .findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(attackerId, ScopeType.TEAM))
                    .isPresent();
            assertThat(folderRepository
                    .findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(ownerId, ScopeType.TEAM))
                    .isEmpty();
        }

        @Test
        @DisplayName("MyScopeFolderController#getNotificationSummary は自分のフォルダ分しか集計しない")
        void getNotificationSummary_は自分の分だけ集計する() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(get(BASE + "/notifications/summary").param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].folderId").value(attackerFolderId));
        }
    }

    @Nested
    @DisplayName("自己スコープの更新系")
    class SelfScopedWrites {

        @Test
        @DisplayName("MyScopeFolderController#createFolder は呼び出し利用者を所有者として作る")
        void createFolder_は本人所有で作られる() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(post(BASE).param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("name", "新規フォルダ", "color", "#010203"))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();
            assertThat(folderRepository
                    .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(attackerId, ScopeType.TEAM))
                    .extracting(MyScopeFolderEntity::getName)
                    .contains("新規フォルダ");
            assertThat(folderRepository
                    .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(ownerId, ScopeType.TEAM))
                    .extracting(MyScopeFolderEntity::getName)
                    .doesNotContain("新規フォルダ");
        }

        @Test
        @DisplayName("MyScopeFolderController#reorderFolders に他利用者のフォルダ ID を混ぜても並び順は変わらない")
        void reorderFolders_は他利用者のフォルダを動かさない() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(put(BASE + "/reorder").param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("orderedIds", List.of(ownerFolderId, attackerFolderId)))))
                    .andExpect(status().isNoContent());

            em.flush();
            em.clear();
            // 他利用者のフォルダは自分のフォルダ集合に含まれないため無視され、sort_order は元のまま。
            assertThat(folderRepository.findById(ownerFolderId).orElseThrow().getSortOrder())
                    .isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
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
                                + "VALUES (:email, 'W4C', 'テスト', 'W4C テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
