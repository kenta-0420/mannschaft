package com.mannschaft.app.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB11 — dashboard（チャットフォルダ）ドメイン
 * item eviction BOLA 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code ChatFolderService#assignItem}/{@code bulkAssignItems} は「1アイテム1フォルダ」制約の
 * 実装として、割り当て前に既存の割り当てを {@code ChatContactFolderItemRepository#findByItemTypeAndItemId}/
 * {@code deleteByItemTypeAndItemId} で検索・削除していたが、この 2 メソッドは owner（フォルダ所有者）を
 * 一切見ずグローバルに 1 件を対象にする。フォルダ操作自体（update/delete/getItems）は
 * {@code findByIdAndUserId} で owner 確認済みだが、この「既存割り当ての退避」処理だけは
 * owner スコープが漏れていた。そのため攻撃者が任意の {@code itemId} を自分のフォルダへ割り当てると、
 * 同一 {@code itemType}/{@code itemId} を持つ<b>他人のフォルダ行が黙って削除（eviction）</b>される
 * BOLA が成立していた。</p>
 *
 * <p>手本: {@code ChatContactFolderItemRepository#existsByFolderOwnerAndItemTypeAndItemId}
 * （{@code JOIN ChatContactFolderEntity f WHERE f.userId=:userId} で owner 突合）に倣い、
 * 新設した owner-scoped の {@code findByFolderOwnerAndItemTypeAndItemId}/
 * {@code deleteByFolderOwnerAndItemTypeAndItemId} に置き換えて根治した。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）。本ドメインはチーム/組織スコープを持たない個人所有リソースの
 * ため {@code MembershipTestHelper} は不要。</p>
 *
 * <p><b>検証内容</b>: userA・userB がそれぞれ自分のフォルダに同一 {@code itemId} を割り当て済みの状態で、
 * userA が自分のフォルダ間でその item を移動（再割り当て）しても、userB のフォルダ行が
 * 消えない（残存する）ことを assert する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("dashboard（チャットフォルダ）item eviction BOLA 認可契約テスト（試練・Wave3-B11）")
class ChatFolderItemEvictionScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatContactFolderRepository folderRepository;

    @Autowired
    private ChatContactFolderItemRepository folderItemRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SHARED_ITEM_ID = 999L; // userA・userB 双方が各自フォルダに持つ共有アイテムID

    private Long userAId;
    private Long userBId;

    private Long folderA1Id; // userA 所有・SHARED_ITEM_ID を既に保持
    private Long folderA2Id; // userA 所有・移動先
    private Long folderBId;  // userB 所有・SHARED_ITEM_ID を保持（eviction 被害者候補）

    @BeforeEach
    void setUp() {
        userAId = insertUser("chatfolderauthz-user-a@example.com");
        userBId = insertUser("chatfolderauthz-user-b@example.com");

        ChatContactFolderEntity folderA1 = folderRepository.save(ChatContactFolderEntity.builder()
                .userId(userAId).name("CHATFOLDERAUTHZ A-1").sortOrder(0).build());
        folderA1Id = folderA1.getId();

        ChatContactFolderEntity folderA2 = folderRepository.save(ChatContactFolderEntity.builder()
                .userId(userAId).name("CHATFOLDERAUTHZ A-2").sortOrder(1).build());
        folderA2Id = folderA2.getId();

        ChatContactFolderEntity folderB = folderRepository.save(ChatContactFolderEntity.builder()
                .userId(userBId).name("CHATFOLDERAUTHZ B").sortOrder(0).build());
        folderBId = folderB.getId();

        // userA・userB 双方が同一 itemType/itemId（CONTACT/SHARED_ITEM_ID）を
        // 各自のフォルダに割り当て済みの状態を再現する。
        folderItemRepository.save(ChatContactFolderItemEntity.builder()
                .folderId(folderA1Id).itemType(FolderItemType.CONTACT).itemId(SHARED_ITEM_ID).build());
        folderItemRepository.save(ChatContactFolderItemEntity.builder()
                .folderId(folderBId).itemType(FolderItemType.CONTACT).itemId(SHARED_ITEM_ID).build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PUT /chat-folders/{id}/items（単発割り当て・移動）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PUT /chat-folders/{id}/items（単発割り当て・移動）")
    class AssignItem {

        @Test
        @DisplayName("userAが自分のフォルダ間でitemを移動しても、userBのフォルダ行は消えない（eviction根治）")
        void 他人のフォルダ行は消えない() throws Exception {
            setAuth(userAId);

            mockMvc.perform(put("/api/v1/chat-folders/{id}/items", folderA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody())))
                    .andExpect(status().isOk());

            // userB の割り当て行が生存していること（BOLA 根治の核心アサーション）
            Optional<ChatContactFolderItemEntity> survivingB =
                    folderItemRepository.findByFolderOwnerAndItemTypeAndItemId(
                            userBId, FolderItemType.CONTACT, SHARED_ITEM_ID);
            assertThat(survivingB).isPresent();
            assertThat(survivingB.get().getFolderId()).isEqualTo(folderBId);
        }

        @Test
        @DisplayName("userA自身の既存割り当ては正しくfolderA2へ移動する（1アイテム1フォルダの正当挙動は維持）")
        void 自分の既存割り当ては移動する() throws Exception {
            setAuth(userAId);

            mockMvc.perform(put("/api/v1/chat-folders/{id}/items", folderA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody())))
                    .andExpect(status().isOk());

            Optional<ChatContactFolderItemEntity> movedA =
                    folderItemRepository.findByFolderOwnerAndItemTypeAndItemId(
                            userAId, FolderItemType.CONTACT, SHARED_ITEM_ID);
            assertThat(movedA).isPresent();
            assertThat(movedA.get().getFolderId()).isEqualTo(folderA2Id);

            // 旧フォルダ（folderA1）の行は削除されている
            List<ChatContactFolderItemEntity> folderA1Items =
                    folderItemRepository.findByFolderId(folderA1Id);
            assertThat(folderA1Items).isEmpty();
        }

        private Map<String, Object> assignBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("itemType", "CONTACT");
            body.put("itemId", SHARED_ITEM_ID);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PUT /chat-folders/{id}/items/bulk（一括割り当て）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PUT /chat-folders/{id}/items/bulk（一括割り当て）")
    class BulkAssignItems {

        @Test
        @DisplayName("一括割り当てでも、userBのフォルダ行は消えない（eviction根治）")
        void 他人のフォルダ行は消えない() throws Exception {
            setAuth(userAId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemType", "CONTACT");
            item.put("itemId", SHARED_ITEM_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", List.of(item));

            mockMvc.perform(put("/api/v1/chat-folders/{id}/items/bulk", folderA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            Optional<ChatContactFolderItemEntity> survivingB =
                    folderItemRepository.findByFolderOwnerAndItemTypeAndItemId(
                            userBId, FolderItemType.CONTACT, SHARED_ITEM_ID);
            assertThat(survivingB).isPresent();
            assertThat(survivingB.get().getFolderId()).isEqualTo(folderBId);
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
                                + "VALUES (:email, 'CHATFOLDERAUTHZ', 'テスト', 'CHATFOLDERAUTHZ テスト', 'ACTIVE', "
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
