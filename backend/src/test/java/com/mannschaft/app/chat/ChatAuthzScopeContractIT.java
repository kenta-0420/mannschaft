package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageBookmarkEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageBookmarkRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.2 チャットドメインの認可契約テスト。
 *
 * <p>本 IT は「他人のリソースに到達できないこと」を固定する。判定はすべて
 * {@code ChatChannelAccessGuard}（チャンネル・メンバー・メッセージ・添付の認可を一元化）に集約されており、
 * 本テストはその保証を EP 単位で外形から固定する。</p>
 *
 * <p>対象エンドポイント（{@code Controller#method} 形式・18 本）:</p>
 * <ul>
 *   <li>{@code ChatBookmarkController#listBookmarks} — 自己スコープ。他ユーザーのブックマークは混入しない。</li>
 *   <li>{@code ChatBookmarkController#removeBookmark} — 自己スコープ。他ユーザーのブックマーク行は削除されない。</li>
 *   <li>{@code ChatChannelController#listChannels} — 自己スコープ。参加していないチャンネルは混入しない。</li>
 *   <li>{@code ChatChannelController#createChannel} — スコープ所属を要求し、非公開はスコープ ADMIN 以上を要求する。</li>
 *   <li>{@code ChatChannelController#getChannel} — 現役メンバーであることを要求する（DM 相手情報を含め非メンバーには返さない）。</li>
 *   <li>{@code ChatChannelController#addMembers} — チャンネル OWNER / ADMIN であることを要求する。</li>
 *   <li>{@code ChatChannelController#removeMember} — 他人の除外は OWNER / ADMIN のみ。自分自身の退出はメンバーであれば可能。</li>
 *   <li>{@code ChatChannelController#changeRole} — チャンネル OWNER / ADMIN であることを要求する（自己昇格を封じる）。</li>
 *   <li>{@code ChatChannelController#startConversation} — 相手のブロック設定・DM 受信範囲設定を、参加人数によらず同一判定で保証する。</li>
 *   <li>{@code ChatChannelController#inviteToZimmer} — 招待元 Kabine の現役メンバーであることを要求する。</li>
 *   <li>{@code ChatChannelController#updateSettings} — 自己スコープ。自分のメンバー行のみを更新する。</li>
 *   <li>{@code ChatChannelController#updateMySettings} — 自己スコープ。自分のメンバー行のみを更新する。</li>
 *   <li>{@code ChatChannelController#generateIconUploadUrl} — チャンネル OWNER / ADMIN であることを要求する。</li>
 *   <li>{@code ChatMessageController#editMessage} — 送信者本人であることを要求する。</li>
 *   <li>{@code ChatMessageController#deleteMessage} — 送信者本人であることを要求する。</li>
 *   <li>{@code ChatReadController#markAsRead} — 自己スコープ。自分のメンバー行の未読のみを消す。</li>
 *   <li>{@code ChatUploadController#generateUploadUrl} — 本文投稿と同一判定（チャンネルメンバー）を経てから署名 URL を発行する。</li>
 *   <li>{@code ChatUploadController#generateDownloadUrl} — 対象キーの属するチャンネルの閲覧認可を経てから署名 URL を発行し、
 *       チャットが管理しないキーは fail-closed で拒否する。</li>
 * </ul>
 *
 * <p>拒否時のステータスは実装のエラーコード（{@link ChatErrorCode}）と
 * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} の対応に従う:</p>
 * <ul>
 *   <li>{@code CHAT_005}（CHANNEL_ACCESS_DENIED）/ {@code CHAT_006}（MESSAGE_EDIT_DENIED）/
 *       {@code CHAT_023}（CHANNEL_ICON_PERMISSION_DENIED）/ {@code COMMON_002}（スコープ権限不足）→ 403</li>
 *   <li>{@code CHAT_003}（MEMBER_NOT_FOUND）/ {@code CHAT_017}（DM_RECEIVE_RESTRICTED）→ 400（Severity.WARN 既定）</li>
 * </ul>
 *
 * <p>金型: {@code TodoPersonalScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL +
 * 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は {@code SecurityUtils} の
 * {@code COMMON_000} → 401。</p>
 *
 * <p><b>テスト環境の前提</b>: {@code test} プロファイルはスキーマを Entity 定義から生成し Flyway を通さないため、
 * マスタデータである {@code storage_plans} のデフォルトプランは本テストが自前で用意する
 * （{@link #ensureDefaultStoragePlans()}。値は本番シード {@code V9.069} と同一）。
 * 認可通過後に走る署名 URL 発行はオブジェクトストレージへの外部依存であるため
 * {@link StorageService} をモックに差し替え、認可判定の成否だけがステータスに現れるようにする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F04.2 チャット 認可契約テスト（他人のリソースへ到達しないこと）")
class ChatAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatChannelRepository channelRepository;

    @Autowired
    private ChatChannelMemberRepository memberRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ChatMessageBookmarkRepository bookmarkRepository;

    @Autowired
    private ChatMessageAttachmentRepository attachmentRepository;

    @Autowired
    private StoragePlanRepository storagePlanRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * オブジェクトストレージへの署名 URL 発行はテスト環境の外にあるため、決定的な値を返すモックに差し替える。
     * 認可は署名 URL 発行より前段で完結しており、本モックは認可判定に一切関与しない。
     */
    @MockitoBean
    private StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    /** チャンネル OWNER 兼メッセージ送信者。チームの一般メンバー。 */
    private Long ownerId;
    /** チャンネルの一般 MEMBER。チームの一般メンバー。 */
    private Long memberId;
    /** どのチャンネル・どのチームにも属さない無関係な他ユーザー（越境元）。 */
    private Long outsiderId;
    /** チームの ADMIN（memberships + user_roles の両方を持つ）。 */
    private Long teamAdminId;
    /** owner をブロックしているユーザー。 */
    private Long blockerId;
    /** DM 受信範囲を TEAM_MEMBERS_ONLY に設定しているユーザー（owner と共通チーム無し）。 */
    private Long dmRestrictedId;

    private Long teamId;

    /** TEAM_PUBLIC チャンネル。メンバーは owner(OWNER) / member(MEMBER)。 */
    private Long teamChannelId;
    /** DM チャンネル（Kabine）。メンバーは owner(OWNER) / member(MEMBER)。 */
    private Long dmChannelId;

    /** teamChannel 内の owner のメッセージ。 */
    private Long ownerMessageId;
    private static final String OWNER_MESSAGE_BODY = "CHATAUTHZ owner の本文";

    /** owner が付けたブックマーク。 */
    private Long ownerBookmarkId;

    /** teamChannel の添付ファイルキー（path variable で扱うためスラッシュ・ドットを含めない）。 */
    private String attachmentFileKey;

    /** 存在しない ID を指すための十分大きい値。 */
    private static final long ABSENT_ID = 9_999_999L;

    @BeforeEach
    void setUp() {
        ensureDefaultStoragePlans();
        stubStorageService();

        String uniq = Long.toString(System.nanoTime(), 36);

        teamId = insertTeam("CHATAUTHZ チーム", "ct-" + uniq);

        ownerId = insertUser("chatauthz-owner-" + uniq + "@example.com", "ANYONE");
        memberId = insertUser("chatauthz-member-" + uniq + "@example.com", "ANYONE");
        outsiderId = insertUser("chatauthz-outsider-" + uniq + "@example.com", "ANYONE");
        teamAdminId = insertUser("chatauthz-admin-" + uniq + "@example.com", "ANYONE");
        blockerId = insertUser("chatauthz-blocker-" + uniq + "@example.com", "ANYONE");
        dmRestrictedId = insertUser("chatauthz-restricted-" + uniq + "@example.com", "TEAM_MEMBERS_ONLY");

        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        // ADMIN 判定は user_roles 側の role_name を見るため、memberships と user_roles の両方を入れる。
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);
        // outsider / blocker / dmRestricted はどのスコープにも所属させない。

        // blocker が owner をブロックしている（owner から blocker への会話開始は成立しない）。
        insertUserBlock(blockerId, ownerId);

        teamChannelId = saveChannel(ChannelType.TEAM_PUBLIC, teamId, "CHATAUTHZ チームチャンネル " + uniq, false);
        saveMember(teamChannelId, ownerId, ChannelMemberRole.OWNER, 0);
        saveMember(teamChannelId, memberId, ChannelMemberRole.MEMBER, 5);

        dmChannelId = saveChannel(ChannelType.DM, null, null, false);
        saveMember(dmChannelId, ownerId, ChannelMemberRole.OWNER, 0);
        saveMember(dmChannelId, memberId, ChannelMemberRole.MEMBER, 0);

        ownerMessageId = messageRepository.save(ChatMessageEntity.builder()
                .channelId(teamChannelId)
                .senderId(ownerId)
                .body(OWNER_MESSAGE_BODY)
                .build()).getId();

        ownerBookmarkId = bookmarkRepository.save(ChatMessageBookmarkEntity.builder()
                .messageId(ownerMessageId)
                .userId(ownerId)
                .note("CHATAUTHZ owner のブックマーク")
                .build()).getId();

        attachmentFileKey = "chatauthz-attachment-" + uniq;
        attachmentRepository.save(ChatMessageAttachmentEntity.builder()
                .messageId(ownerMessageId)
                .fileKey(attachmentFileKey)
                .fileName("shiryou.pdf")
                .fileSize(1024L)
                .contentType("application/pdf")
                .build());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. ChatBookmarkController#listBookmarks（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. ChatBookmarkController#listBookmarks（ブックマーク一覧・自己スコープ）")
    class ListBookmarks {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/chat/bookmarks"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのブックマークは混入しない")
        void 他人のブックマークは混入しない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/chat/bookmarks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(ownerBookmarkId.intValue()))));
        }

        @Test
        @DisplayName("正常系: 本人には自分のブックマークが返る")
        void 本人には自分のブックマークが返る() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/chat/bookmarks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(ownerBookmarkId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. ChatBookmarkController#removeBookmark（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. ChatBookmarkController#removeBookmark（ブックマーク削除・自己スコープ）")
    class RemoveBookmark {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/chat/bookmarks/{messageId}", ownerMessageId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーが同じ messageId を指定しても、他人のブックマーク行は消えない")
        void 他人のブックマーク行は消えない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/chat/bookmarks/{messageId}", ownerMessageId))
                    .andExpect(status().isNoContent());

            assertThat(bookmarkRepository.findByUserIdAndMessageId(ownerId, ownerMessageId)).isPresent();
        }

        @Test
        @DisplayName("正常系: 本人は自分のブックマークを削除できる")
        void 本人は自分のブックマークを削除できる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/chat/bookmarks/{messageId}", ownerMessageId))
                    .andExpect(status().isNoContent());

            assertThat(bookmarkRepository.findByUserIdAndMessageId(ownerId, ownerMessageId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. ChatChannelController#listChannels（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. ChatChannelController#listChannels（チャンネル一覧・自己スコープ）")
    class ListChannels {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/chat/channels"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("参加していないチャンネルは混入しない")
        void 非参加チャンネルは混入しない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(teamChannelId.intValue()))))
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(dmChannelId.intValue()))));
        }

        @Test
        @DisplayName("正常系: 参加中のチャンネルが返る")
        void 参加中チャンネルが返る() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/chat/channels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(teamChannelId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. ChatChannelController#createChannel（スコープ所属・非公開は ADMIN 以上）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. ChatChannelController#createChannel（チャンネル作成・スコープ所属を要求）")
    class CreateChannel {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PUBLIC",
                                    "teamId", teamId,
                                    "name", "CHATAUTHZ 未認証作成"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非所属ユーザーが他チームの teamId を指定した TEAM_PUBLIC 作成は403（チャンネルも作られない）")
        void 非所属の越境作成は403() throws Exception {
            setAuth(outsiderId);
            String name = "CHATAUTHZ 越境作成チャンネル";
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PUBLIC",
                                    "teamId", teamId,
                                    "name", name))))
                    .andExpect(status().isForbidden());

            assertThat(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(teamId, name)).isFalse();
        }

        @Test
        @DisplayName("チーム所属の一般メンバーによる TEAM_PRIVATE 作成は403（非公開は ADMIN 以上）")
        void 一般メンバーの非公開作成は403() throws Exception {
            setAuth(memberId);
            String name = "CHATAUTHZ 非公開チャンネル";
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PRIVATE",
                                    "teamId", teamId,
                                    "name", name))))
                    .andExpect(status().isForbidden());

            assertThat(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(teamId, name)).isFalse();
        }

        @Test
        @DisplayName("チーム所属の一般メンバーによる isPrivate=true の作成は403（非公開は ADMIN 以上）")
        void 一般メンバーのisPrivate作成は403() throws Exception {
            setAuth(memberId);
            String name = "CHATAUTHZ isPrivate チャンネル";
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PUBLIC",
                                    "teamId", teamId,
                                    "name", name,
                                    "isPrivate", true))))
                    .andExpect(status().isForbidden());

            assertThat(channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(teamId, name)).isFalse();
        }

        @Test
        @DisplayName("正常系: チーム所属メンバーは TEAM_PUBLIC を201で作成できる")
        void 所属メンバーの公開作成は201() throws Exception {
            setAuth(memberId);
            String name = "CHATAUTHZ 所属メンバーの公開チャンネル";
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PUBLIC",
                                    "teamId", teamId,
                                    "name", name))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("正常系: チーム ADMIN は TEAM_PRIVATE を201で作成できる")
        void ADMINの非公開作成は201() throws Exception {
            setAuth(teamAdminId);
            String name = "CHATAUTHZ ADMIN の非公開チャンネル";
            mockMvc.perform(post("/api/v1/chat/channels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "channelType", "TEAM_PRIVATE",
                                    "teamId", teamId,
                                    "name", name))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. ChatChannelController#getChannel（現役メンバー限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. ChatChannelController#getChannel（チャンネル詳細・現役メンバー限定）")
    class GetChannel {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/chat/channels/{channelId}", dmChannelId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーが他人の DM を指定→403（DM 相手の情報を返さない）")
        void 非メンバーのDM閲覧は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{channelId}", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("非メンバーがチームチャンネルを指定→403")
        void 非メンバーのチームチャンネル閲覧は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{channelId}", teamChannelId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: メンバーは200で詳細を取得できる")
        void メンバーは200() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/chat/channels/{channelId}", dmChannelId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(dmChannelId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. ChatChannelController#addMembers（OWNER / ADMIN 限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. ChatChannelController#addMembers（メンバー追加・OWNER/ADMIN 限定）")
    class AddMembers {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/members", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(outsiderId)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一般 MEMBER による追加は403（メンバー行も増えない）")
        void 一般メンバーの追加は403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/members", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(outsiderId)))))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, outsiderId)).isFalse();
        }

        @Test
        @DisplayName("非メンバーによる追加は403")
        void 非メンバーの追加は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/members", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(outsiderId)))))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, outsiderId)).isFalse();
        }

        @Test
        @DisplayName("正常系: OWNER は201でメンバーを追加できる")
        void OWNERの追加は201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/members", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(outsiderId)))))
                    .andExpect(status().isCreated());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, outsiderId)).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. ChatChannelController#removeMember（他人の除外は OWNER/ADMIN 限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. ChatChannelController#removeMember（メンバー除外・他人の除外は OWNER/ADMIN 限定）")
    class RemoveMember {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/chat/channels/{channelId}/members/{userId}",
                            teamChannelId, memberId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一般 MEMBER が他人をキック→403（対象のメンバー行は残る）")
        void 一般メンバーのキックは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete("/api/v1/chat/channels/{channelId}/members/{userId}",
                            teamChannelId, ownerId))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, ownerId)).isTrue();
        }

        @Test
        @DisplayName("非メンバーが他人をキック→403")
        void 非メンバーのキックは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/chat/channels/{channelId}/members/{userId}",
                            teamChannelId, memberId))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, memberId)).isTrue();
        }

        @Test
        @DisplayName("正常系: 自分自身の退出は204で成立する")
        void 自分自身の退出は204() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete("/api/v1/chat/channels/{channelId}/members/{userId}",
                            teamChannelId, memberId))
                    .andExpect(status().isNoContent());

            assertThat(memberRepository.existsByChannelIdAndUserId(teamChannelId, memberId)).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. ChatChannelController#changeRole（OWNER / ADMIN 限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. ChatChannelController#changeRole（ロール変更・OWNER/ADMIN 限定）")
    class ChangeRole {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/{userId}/role",
                            teamChannelId, memberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OWNER\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一般 MEMBER が自分を OWNER に昇格→403（ロールは MEMBER のまま）")
        void 一般メンバーの自己昇格は403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/{userId}/role",
                            teamChannelId, memberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"OWNER\"}"))
                    .andExpect(status().isForbidden());

            ChatChannelMemberEntity intact = memberRepository
                    .findByChannelIdAndUserId(teamChannelId, memberId).orElseThrow();
            assertThat(intact.getRole()).isEqualTo(ChannelMemberRole.MEMBER);
        }

        @Test
        @DisplayName("正常系: OWNER は他メンバーのロールを200で変更できる")
        void OWNERのロール変更は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/{userId}/role",
                            teamChannelId, memberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk());

            ChatChannelMemberEntity changed = memberRepository
                    .findByChannelIdAndUserId(teamChannelId, memberId).orElseThrow();
            assertThat(changed.getRole()).isEqualTo(ChannelMemberRole.ADMIN);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. ChatChannelController#startConversation（相手の受信可否を人数によらず同一判定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. ChatChannelController#startConversation（会話開始・相手の受信設定を保証）")
    class StartConversation {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels/conversations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(memberId)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("相手が自分をブロックしている場合は403（チャンネルも作られない）")
        void ブロックされている相手への会話開始は403() throws Exception {
            setAuth(ownerId);
            int before = memberRepository.findByUserId(blockerId).size();

            mockMvc.perform(post("/api/v1/chat/channels/conversations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(blockerId)))))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.findByUserId(blockerId)).hasSize(before);
        }

        @Test
        @DisplayName("Zimmer（2名以上）でも相手の DM 受信範囲設定で拒否される（1対1と同一判定）")
        void Zimmerでも受信範囲設定で拒否される() throws Exception {
            setAuth(ownerId);
            int before = memberRepository.findByUserId(dmRestrictedId).size();

            mockMvc.perform(post("/api/v1/chat/channels/conversations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(dmRestrictedId, teamAdminId)))))
                    .andExpect(status().isBadRequest());

            assertThat(memberRepository.findByUserId(dmRestrictedId)).hasSize(before);
        }

        @Test
        @DisplayName("正常系: 受信可能な相手との会話は成立する")
        void 受信可能な相手との会話は成立する() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/chat/channels/conversations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(teamAdminId)))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. ChatChannelController#inviteToZimmer（招待元 Kabine のメンバー限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. ChatChannelController#inviteToZimmer（Kabine からの招待・招待元メンバー限定）")
    class InviteToZimmer {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/invite-to-zimmer", dmChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(teamAdminId)))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("対象 Kabine の非メンバーによる招待は403（Zimmer も作られない）")
        void 非メンバーの招待は403() throws Exception {
            setAuth(outsiderId);
            int before = memberRepository.findByUserId(teamAdminId).size();

            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/invite-to-zimmer", dmChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(teamAdminId)))))
                    .andExpect(status().isForbidden());

            assertThat(memberRepository.findByUserId(teamAdminId)).hasSize(before);
        }

        @Test
        @DisplayName("正常系: Kabine のメンバーは201で Zimmer を作成できる")
        void Kabineメンバーの招待は201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/invite-to-zimmer", dmChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(teamAdminId)))))
                    .andExpect(status().isCreated());

            assertThat(memberRepository.findByUserId(teamAdminId)).isNotEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. ChatChannelController#updateSettings（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. ChatChannelController#updateSettings（チャンネル個人設定・自己スコープ）")
    class UpdateSettings {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/settings", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isMuted\":true}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーは弾かれ、他人の設定行は書き換わらない")
        void 非メンバーは弾かれ他人の設定は不変() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/settings", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isMuted\":true,\"isPinned\":true}"))
                    .andExpect(status().isBadRequest());

            ChatChannelMemberEntity intact = memberRepository
                    .findByChannelIdAndUserId(teamChannelId, memberId).orElseThrow();
            assertThat(intact.getIsMuted()).isFalse();
            assertThat(intact.getIsPinned()).isFalse();
        }

        @Test
        @DisplayName("正常系: メンバーは自分の設定行のみ200で更新できる")
        void メンバーは自分の設定のみ更新できる() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/settings", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isMuted\":true}"))
                    .andExpect(status().isOk());

            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, memberId)
                    .orElseThrow().getIsMuted()).isTrue();
            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, ownerId)
                    .orElseThrow().getIsMuted()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. ChatChannelController#updateMySettings（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. ChatChannelController#updateMySettings（自分の個人設定更新・自己スコープ）")
    class UpdateMySettings {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/me", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"is_muted\":true}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーは弾かれ、他人の設定行は書き換わらない")
        void 非メンバーは弾かれ他人の設定は不変() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/me", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"is_muted\":true,\"is_pinned\":true}"))
                    .andExpect(status().isBadRequest());

            ChatChannelMemberEntity intact = memberRepository
                    .findByChannelIdAndUserId(teamChannelId, memberId).orElseThrow();
            assertThat(intact.getIsMuted()).isFalse();
            assertThat(intact.getIsPinned()).isFalse();
        }

        @Test
        @DisplayName("正常系: メンバーは自分の設定行のみ200で更新できる")
        void メンバーは自分の設定のみ更新できる() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch("/api/v1/chat/channels/{channelId}/members/me", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"is_pinned\":true}"))
                    .andExpect(status().isOk());

            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, memberId)
                    .orElseThrow().getIsPinned()).isTrue();
            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, ownerId)
                    .orElseThrow().getIsPinned()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. ChatChannelController#generateIconUploadUrl（OWNER / ADMIN 限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. ChatChannelController#generateIconUploadUrl（アイコン署名URL・OWNER/ADMIN 限定）")
    class GenerateIconUploadUrl {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/icon/upload-url", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(iconRequestJson()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一般 MEMBER は403")
        void 一般メンバーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/icon/upload-url", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(iconRequestJson()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/icon/upload-url", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(iconRequestJson()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: OWNER は200で署名URLを取得できる")
        void OWNERは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/icon/upload-url", teamChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(iconRequestJson()))
                    .andExpect(status().isOk());
        }

        private String iconRequestJson() {
            return "{\"file_name\":\"icon.png\",\"content_type\":\"image/png\",\"file_size\":1024}";
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. ChatMessageController#editMessage（送信者本人限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. ChatMessageController#editMessage（メッセージ編集・送信者本人限定）")
    class EditMessage {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/chat/messages/{messageId}", ownerMessageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"改竄\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("同一チャンネルの他メンバーによる編集は403（本文は書き換わらない）")
        void 他メンバーの編集は403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch("/api/v1/chat/messages/{messageId}", ownerMessageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"改竄\"}"))
                    .andExpect(status().isForbidden());

            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getBody())
                    .isEqualTo(OWNER_MESSAGE_BODY);
        }

        @Test
        @DisplayName("非メンバーによる編集は403（本文は書き換わらない）")
        void 非メンバーの編集は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/chat/messages/{messageId}", ownerMessageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"改竄\"}"))
                    .andExpect(status().isForbidden());

            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getBody())
                    .isEqualTo(OWNER_MESSAGE_BODY);
        }

        @Test
        @DisplayName("正常系: 送信者本人は200で編集できる")
        void 送信者本人は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/chat/messages/{messageId}", ownerMessageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"CHATAUTHZ 本人の編集後\"}"))
                    .andExpect(status().isOk());

            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getBody())
                    .isEqualTo("CHATAUTHZ 本人の編集後");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. ChatMessageController#deleteMessage（送信者本人限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. ChatMessageController#deleteMessage（メッセージ削除・送信者本人限定）")
    class DeleteMessage {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/chat/messages/{messageId}", ownerMessageId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("同一チャンネルの他メンバーによる削除は403（論理削除もされない）")
        void 他メンバーの削除は403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete("/api/v1/chat/messages/{messageId}", ownerMessageId))
                    .andExpect(status().isForbidden());

            // @Transactional 内では findById が 1 次キャッシュに当たるため entity の状態を見る。
            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("非メンバーによる削除は403（論理削除もされない）")
        void 非メンバーの削除は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/chat/messages/{messageId}", ownerMessageId))
                    .andExpect(status().isForbidden());

            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 送信者本人は204で論理削除できる")
        void 送信者本人は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/chat/messages/{messageId}", ownerMessageId))
                    .andExpect(status().isNoContent());

            assertThat(messageRepository.findById(ownerMessageId).orElseThrow().getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. ChatReadController#markAsRead（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. ChatReadController#markAsRead（既読・自己スコープ）")
    class MarkAsRead {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/read", teamChannelId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーは弾かれ、他人の未読カウントは変わらない")
        void 非メンバーは弾かれ他人の未読は不変() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/read", teamChannelId))
                    .andExpect(status().isBadRequest());

            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, memberId)
                    .orElseThrow().getUnreadCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系: メンバーは自分の未読のみ204でリセットできる")
        void メンバーは自分の未読のみリセットできる() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/read", teamChannelId))
                    .andExpect(status().isNoContent());

            assertThat(memberRepository.findByChannelIdAndUserId(teamChannelId, memberId)
                    .orElseThrow().getUnreadCount()).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. ChatUploadController#generateUploadUrl（投稿認可を経てから署名）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("17. ChatUploadController#generateUploadUrl（アップロード署名URL・投稿認可を要求）")
    class GenerateUploadUrl {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/chat/files/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(uploadRequestJson(teamChannelId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーが他人のチャンネル ID を指定→403（署名 URL を返さない）")
        void 非メンバーの署名URL取得は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/chat/files/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(uploadRequestJson(teamChannelId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("非メンバーが他人の DM チャンネル ID を指定→403")
        void 非メンバーのDM指定は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/chat/files/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(uploadRequestJson(dmChannelId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: メンバーは200で署名URLを取得できる")
        void メンバーは200() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/chat/files/upload-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(uploadRequestJson(teamChannelId)))
                    .andExpect(status().isOk());
        }

        private String uploadRequestJson(Long channelId) {
            return "{\"channelId\":" + channelId
                    + ",\"fileName\":\"shiryou.pdf\""
                    + ",\"contentType\":\"application/pdf\""
                    + ",\"fileSize\":1024}";
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 18. ChatUploadController#generateDownloadUrl（閲覧認可を経てから署名）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("18. ChatUploadController#generateDownloadUrl（ダウンロード署名URL・閲覧認可を要求）")
    class GenerateDownloadUrl {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/chat/files/{fileKey}/download-url", attachmentFileKey))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーが他人のチャンネルの添付キーを指定→403（署名 URL を返さない）")
        void 非メンバーの添付ダウンロードは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/chat/files/{fileKey}/download-url", attachmentFileKey))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("チャットが管理していない任意のキーは403（fail-closed）")
        void 管理外キーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/chat/files/{fileKey}/download-url",
                            "chatauthz-unmanaged-key-" + ABSENT_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: 当該チャンネルのメンバーは200で署名URLを取得できる")
        void メンバーは200() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get("/api/v1/chat/files/{fileKey}/download-url", attachmentFileKey))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoPersonalScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /**
     * {@code storage_plans} のスコープ別デフォルトプランを用意する（値は本番シード {@code V9.069} と同一）。
     *
     * <p>ストレージサブスクリプションの自動払い出しは {@code REQUIRES_NEW} の独立トランザクションで走り、
     * テストメソッドのトランザクション内の未コミット行を参照できない。そのため本 seed も
     * {@code REQUIRES_NEW} でコミットして、払い出しから確実に見えるようにする。
     * 既存行があるときは作らないため、テスト間で重複しない。</p>
     */
    private void ensureDefaultStoragePlans() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            ensureDefaultStoragePlan("ORGANIZATION", "フリー（組織）", 53_687_091_200L);
            ensureDefaultStoragePlan("TEAM", "フリー（チーム）", 5_368_709_120L);
            ensureDefaultStoragePlan("PERSONAL", "フリー（個人）", 1_073_741_824L);
        });
    }

    private void ensureDefaultStoragePlan(String scopeLevel, String name, long includedBytes) {
        if (storagePlanRepository
                .findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(scopeLevel).isPresent()) {
            return;
        }
        storagePlanRepository.save(StoragePlanEntity.builder()
                .name(name)
                .scopeLevel(scopeLevel)
                .includedBytes(includedBytes)
                .maxBytes(includedBytes)
                .priceMonthly(BigDecimal.ZERO)
                .isDefault(true)
                .sortOrder((short) 1)
                .build());
    }

    /** 署名 URL 発行が決定的な値を返すようにする（認可通過後に外部依存でステータスが揺れないようにする）。 */
    private void stubStorageService() {
        given(storageService.generateUploadUrl(any(), any(), any()))
                .willAnswer(invocation -> new PresignedUploadResult(
                        "https://storage.test.invalid/upload/" + invocation.getArgument(0),
                        invocation.getArgument(0), 900L));
        given(storageService.generateDownloadUrl(any(), any()))
                .willAnswer(invocation ->
                        "https://storage.test.invalid/download/" + invocation.getArgument(0));
    }

    private Long saveChannel(ChannelType channelType, Long teamIdOrNull, String name, boolean isPrivate) {
        return channelRepository.save(ChatChannelEntity.builder()
                .channelType(channelType)
                .teamId(teamIdOrNull)
                .name(name)
                .isPrivate(isPrivate)
                .createdBy(ownerId)
                .build()).getId();
    }

    private void saveMember(Long channelId, Long userId, ChannelMemberRole role, int unreadCount) {
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId)
                .userId(userId)
                .role(role)
                .unreadCount(unreadCount)
                .build());
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void insertUserBlock(Long blockerUserId, Long blockedUserId) {
        em.createNativeQuery(
                        "INSERT INTO user_blocks (blocker_id, blocked_id, created_at) "
                                + "VALUES (:blocker, :blocked, NOW())")
                .setParameter("blocker", blockerUserId)
                .setParameter("blocked", blockedUserId)
                .executeUpdate();
    }

    private Long insertUser(String email, String dmReceiveFrom) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CHATAUTHZ', 'テスト', 'CHATAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', :dm, 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("dm", dmReceiveFrom)
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
