package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261105-1507: チャットの存在オラクル封鎖とメッセージ削除権限（F04.2 §5）の契約テスト。
 *
 * <p>基準: docs/security/01_authorization_baseline.md §3.3.2。チャンネル ID・メッセージ ID を指定する EP で、
 * そのチャンネルの存在を知り得ない利用者への応答を、不在 ID への応答と status・{@code error.code}・
 * {@code error.message} まで完全に一致させる。一致だけでは両方壊れたとき偽 green になるため、
 * 主要経路は絶対値（404 + CHAT_001 / CHAT_002）も固定する。</p>
 *
 * <p>EP 別許可主体表（越境 = 部外者 / 不在 = 存在しない ID）:</p>
 * <ul>
 *   <li>チャンネル ID 指定系: 越境・不在とも 404 CHAT_001。存在を知り得る利用者
 *       （公開チャンネルのスコープ所属者・スコープ管理者・チャンネルメンバー）の権限不足は 403。</li>
 *   <li>メッセージ ID 指定系: 越境・不在とも 404 CHAT_002（子リソースの不在コード）。</li>
 *   <li>メッセージ削除: 投稿者本人 / チャンネル OWNER・ADMIN / スコープ ADMIN / SYSTEM_ADMIN は許可、
 *       チャンネルの一般メンバーは 403 CHAT_007。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("chat 存在秘匿・メッセージ削除権限 契約テスト（CMP-261105-1507）")
class ChatExistenceConcealmentContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;

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

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    /** TEAM_PUBLIC / TEAM_PRIVATE / DM の OWNER。 */
    private Long ownerId;
    /** TEAM_PUBLIC の一般メンバー（チームメンバー）。 */
    private Long plainMemberId;
    /** DM 相手。 */
    private Long dmPartnerId;
    /** チームの ADMIN（どのチャンネルにも未参加）。 */
    private Long teamAdminId;
    /** どこにも属さない部外者。 */
    private Long outsiderId;

    private Long teamPublicChannelId;
    private Long teamPrivateChannelId;
    private Long dmChannelId;
    /** DM 内の OWNER の投稿。 */
    private Long dmMessageId;
    /** TEAM_PUBLIC 内の plainMember の投稿。 */
    private Long memberMessageId;
    /** TEAM_PUBLIC 内の OWNER の投稿。 */
    private Long ownerMessageId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("存在秘匿チーム-" + System.nanoTime());
        ownerId = insertUser("conceal-owner@example.com");
        plainMemberId = insertUser("conceal-member@example.com");
        dmPartnerId = insertUser("conceal-partner@example.com");
        teamAdminId = insertUser("conceal-admin@example.com");
        outsiderId = insertUser("conceal-outsider@example.com");

        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, plainMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);

        teamPublicChannelId = saveChannel(ChannelType.TEAM_PUBLIC, teamId, "秘匿 公開", false);
        teamPrivateChannelId = saveChannel(ChannelType.TEAM_PRIVATE, teamId, "秘匿 非公開", true);
        dmChannelId = saveChannel(ChannelType.DM, null, null, false);

        saveMember(teamPublicChannelId, ownerId, ChannelMemberRole.OWNER);
        saveMember(teamPublicChannelId, plainMemberId, ChannelMemberRole.MEMBER);
        saveMember(teamPrivateChannelId, ownerId, ChannelMemberRole.OWNER);
        saveMember(dmChannelId, ownerId, ChannelMemberRole.OWNER);
        saveMember(dmChannelId, dmPartnerId, ChannelMemberRole.MEMBER);

        dmMessageId = saveMessage(dmChannelId, ownerId, "DM の本文");
        memberMessageId = saveMessage(teamPublicChannelId, plainMemberId, "一般メンバーの投稿");
        ownerMessageId = saveMessage(teamPublicChannelId, ownerId, "OWNER の投稿");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. チャンネル ID 指定系: 部外者の応答 ＝ 不在 ID の応答
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チャンネルID指定系: 部外者への応答は不在IDと完全一致（404 CHAT_001）")
    class ChannelIdEndpoints {

        @Test
        @DisplayName("GET /channels/{id}/messages（他人のDM）")
        void メッセージ一覧() throws Exception {
            assertConcealedAsChannelNotFound(id -> get("/api/v1/chat/channels/{id}/messages", id), dmChannelId);
        }

        @Test
        @DisplayName("GET /channels/{id}/messages（他チームの非公開チャンネル）")
        void 非公開チャンネルのメッセージ一覧() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> get("/api/v1/chat/channels/{id}/messages", id), teamPrivateChannelId);
        }

        @Test
        @DisplayName("GET /channels/{id}/messages（他チームの公開チャンネルも部外者には秘匿）")
        void 他チームの公開チャンネルのメッセージ一覧() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> get("/api/v1/chat/channels/{id}/messages", id), teamPublicChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/messages")
        void メッセージ投稿() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/messages", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("body", "侵入"))), dmChannelId);
        }

        @Test
        @DisplayName("GET /channels/{id}/messages/search")
        void メッセージ検索() throws Exception {
            assertConcealedAsChannelNotFound(id -> get("/api/v1/chat/channels/{id}/messages/search", id)
                    .param("keyword", "本文"), dmChannelId);
        }

        @Test
        @DisplayName("GET /channels/{id}")
        void チャンネル詳細() throws Exception {
            assertConcealedAsChannelNotFound(id -> get("/api/v1/chat/channels/{id}", id), dmChannelId);
        }

        @Test
        @DisplayName("GET /channels/{id}/threads")
        void スレッド一覧() throws Exception {
            assertConcealedAsChannelNotFound(id -> get("/api/v1/chat/channels/{id}/threads", id), dmChannelId);
        }

        @Test
        @DisplayName("PATCH /channels/{id}（チームチャンネル）")
        void チャンネル更新() throws Exception {
            assertConcealedAsChannelNotFound(id -> patch("/api/v1/chat/channels/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "改名"))), teamPrivateChannelId);
        }

        @Test
        @DisplayName("PATCH /channels/{id}（DM）")
        void DM更新() throws Exception {
            assertConcealedAsChannelNotFound(id -> patch("/api/v1/chat/channels/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("name", "改名"))), dmChannelId);
        }

        @Test
        @DisplayName("DELETE /channels/{id}")
        void チャンネル削除() throws Exception {
            assertConcealedAsChannelNotFound(id -> delete("/api/v1/chat/channels/{id}", id), teamPrivateChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/archive")
        void アーカイブ() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> post("/api/v1/chat/channels/{id}/archive", id), teamPrivateChannelId);
        }

        @Test
        @DisplayName("DELETE /channels/{id}/archive")
        void アーカイブ解除() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> delete("/api/v1/chat/channels/{id}/archive", id), teamPrivateChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/convert-to-group")
        void グループ変換() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> post("/api/v1/chat/channels/{id}/convert-to-group", id), dmChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/members")
        void メンバー追加() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/members", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("userIds", List.of(outsiderId)))), teamPrivateChannelId);
        }

        @Test
        @DisplayName("DELETE /channels/{id}/members/{userId}（他人の除外）")
        void メンバー除外() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> delete("/api/v1/chat/channels/{id}/members/{uid}", id, dmPartnerId), dmChannelId);
        }

        @Test
        @DisplayName("DELETE /channels/{id}/members/{userId}（自分の退出）")
        void 自分の退出() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> delete("/api/v1/chat/channels/{id}/members/{uid}", id, outsiderId), dmChannelId);
        }

        @Test
        @DisplayName("PATCH /channels/{id}/members/{userId}/role")
        void ロール変更() throws Exception {
            assertConcealedAsChannelNotFound(id -> patch("/api/v1/chat/channels/{id}/members/{uid}/role", id, dmPartnerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("role", "ADMIN"))), dmChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/join（他チームの公開チャンネル）")
        void 自己参加() throws Exception {
            assertConcealedAsChannelNotFound(
                    id -> post("/api/v1/chat/channels/{id}/join", id), teamPublicChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/join（DM）")
        void DMへの自己参加() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/join", id), dmChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/invite-to-zimmer")
        void Zimmer招待() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/invite-to-zimmer", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("userIds", List.of(dmPartnerId)))), dmChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/invite-to-zimmer（DM以外の種別も漏らさない）")
        void DM以外へのZimmer招待() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/invite-to-zimmer", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("userIds", List.of(dmPartnerId)))), teamPrivateChannelId);
        }

        @Test
        @DisplayName("PATCH /channels/{id}/inquiry")
        void 問い合わせ設定() throws Exception {
            assertConcealedAsChannelNotFound(id -> patch("/api/v1/chat/channels/{id}/inquiry", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("is_inquiry_channel", true))), teamPrivateChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/icon/upload-url")
        void アイコンURL発行() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/icon/upload-url", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("file_name", "a.png", "content_type", "image/png", "file_size", 100))),
                    teamPrivateChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/membership-invite")
        void 承諾型招待() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/channels/{id}/membership-invite", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("scopeType", "TEAM", "scopeId", teamId))), dmChannelId);
        }

        @Test
        @DisplayName("POST /files/upload-url（本文の channelId）")
        void 添付URL発行() throws Exception {
            assertConcealedAsChannelNotFound(id -> post("/api/v1/chat/files/upload-url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("channelId", id, "fileName", "a.txt",
                            "contentType", "text/plain", "fileSize", 10))), dmChannelId);
        }

        @Test
        @DisplayName("POST /channels/{id}/read（既読）・PATCH settings・PATCH members/me は不在と同一応答")
        void 個人設定系() throws Exception {
            assertSameAsMissing(id -> post("/api/v1/chat/channels/{id}/read", id), dmChannelId);
            assertSameAsMissing(id -> patch("/api/v1/chat/channels/{id}/settings", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("isMuted", true))), dmChannelId);
            assertSameAsMissing(id -> patch("/api/v1/chat/channels/{id}/members/me", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("isMuted", true))), dmChannelId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. メッセージ ID 指定系: 部外者の応答 ＝ 不在メッセージ ID の応答
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージID指定系: 部外者への応答は不在IDと完全一致（404 CHAT_002）")
    class MessageIdEndpoints {

        @Test
        @DisplayName("PATCH /messages/{id}（編集）")
        void 編集() throws Exception {
            assertConcealedAsMessageNotFound(id -> patch("/api/v1/chat/messages/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("body", "改ざん"))));
        }

        @Test
        @DisplayName("DELETE /messages/{id}（削除）")
        void 削除() throws Exception {
            assertConcealedAsMessageNotFound(id -> delete("/api/v1/chat/messages/{id}", id));
        }

        @Test
        @DisplayName("GET /messages/{id}/thread")
        void スレッド() throws Exception {
            assertConcealedAsMessageNotFound(id -> get("/api/v1/chat/messages/{id}/thread", id));
        }

        @Test
        @DisplayName("POST /messages/{id}/pin")
        void ピン() throws Exception {
            assertConcealedAsMessageNotFound(id -> post("/api/v1/chat/messages/{id}/pin", id)
                    .param("pinned", "true"));
        }

        @Test
        @DisplayName("POST /messages/{id}/forward（転送先が不在でも転送元の秘匿が先）")
        void 転送() throws Exception {
            assertConcealedAsMessageNotFound(id -> post("/api/v1/chat/messages/{id}/forward", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("targetChannelId", MISSING_ID))));
        }

        @Test
        @DisplayName("POST /messages/{id}/reactions")
        void リアクション追加() throws Exception {
            assertConcealedAsMessageNotFound(id -> post("/api/v1/chat/messages/{id}/reactions", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("emoji", "👍"))));
        }

        @Test
        @DisplayName("DELETE /messages/{id}/reactions")
        void リアクション削除() throws Exception {
            assertConcealedAsMessageNotFound(id -> delete("/api/v1/chat/messages/{id}/reactions", id)
                    .param("emoji", "👍"));
        }

        @Test
        @DisplayName("POST /bookmarks（本文の messageId）")
        void ブックマーク() throws Exception {
            assertConcealedAsMessageNotFound(id -> post("/api/v1/chat/bookmarks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("messageId", id))));
        }

        @Test
        @DisplayName("POST /messages/{id}/migrate-to-board")
        void 掲示板移行() throws Exception {
            assertConcealedAsMessageNotFound(id -> post("/api/v1/chat/messages/{id}/migrate-to-board", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("boardScopeId", teamId, "scopeType", "TEAM",
                            "categoryId", 1, "title", "移行"))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 存在を知り得る利用者の権限不足は 403 のまま（404 に化けない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("存在を知り得る利用者の権限不足は403のまま")
    class AwareUserStillForbidden {

        @Test
        @DisplayName("チャンネルの一般メンバーが他人のメッセージを編集すると403 CHAT_006")
        void 他人の編集は403() throws Exception {
            setAuthentication(plainMemberId);
            mockMvc.perform(patch("/api/v1/chat/messages/{id}", ownerMessageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_006"));
        }

        @Test
        @DisplayName("チャンネルの一般メンバーが他人のメッセージを削除すると403 CHAT_007")
        void 他人の削除は403() throws Exception {
            setAuthentication(plainMemberId);
            mockMvc.perform(delete("/api/v1/chat/messages/{id}", ownerMessageId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_007"));
        }

        @Test
        @DisplayName("一般メンバーがメンバー追加すると403 CHAT_005（存在は既知）")
        void 一般メンバーのメンバー追加は403() throws Exception {
            setAuthentication(plainMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/members", teamPublicChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userIds", List.of(teamAdminId)))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("同チームの未参加者が公開チャンネルの本文を読むと403 CHAT_005（公開チャンネルの存在は既知）")
        void 公開チャンネルの未参加チームメンバーは403() throws Exception {
            setAuthentication(teamAdminId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. メッセージ削除権限（F04.2 §5: 投稿者本人 or チーム/組織 ADMIN or チャンネル OWNER/ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージ削除権限（F04.2 §5）")
    class DeletePermission {

        @Test
        @DisplayName("投稿者本人は自分のメッセージを削除できる（204）")
        void 本人は削除できる() throws Exception {
            setAuthentication(plainMemberId);
            mockMvc.perform(delete("/api/v1/chat/messages/{id}", memberMessageId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("チャンネル OWNER は他人のメッセージを削除できる（204）")
        void チャンネルOWNERは削除できる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/chat/messages/{id}", memberMessageId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("チーム ADMIN はチャンネル未参加でもチームチャンネルの他人のメッセージを削除できる（204）")
        void チームADMINは削除できる() throws Exception {
            setAuthentication(teamAdminId);
            mockMvc.perform(delete("/api/v1/chat/messages/{id}", memberMessageId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("チーム ADMIN でも他人の DM のメッセージは削除できず、不在と同一の404")
        void チームADMINでもDMは秘匿() throws Exception {
            setAuthentication(teamAdminId);
            mockMvc.perform(delete("/api/v1/chat/messages/{id}", dmMessageId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertConcealedAsChannelNotFound(
            LongFunction<MockHttpServletRequestBuilder> request, Long hiddenChannelId) throws Exception {
        JsonNode hidden = assertSameAsMissing(request, hiddenChannelId);
        assertThat(hidden.path("status").asInt()).as("越境の status").isEqualTo(404);
        assertThat(hidden.path("code").asText()).as("越境の error.code").isEqualTo("CHAT_001");
    }

    private void assertConcealedAsMessageNotFound(
            LongFunction<MockHttpServletRequestBuilder> request) throws Exception {
        JsonNode hidden = assertSameAsMissing(request, dmMessageId);
        assertThat(hidden.path("status").asInt()).as("越境の status").isEqualTo(404);
        assertThat(hidden.path("code").asText()).as("越境の error.code").isEqualTo("CHAT_002");
    }

    /**
     * 部外者が実在 ID を叩いた応答と不在 ID を叩いた応答の status・error.code・error.message が
     * 一致することを検証し、実在 ID 側の要約を返す。
     */
    private JsonNode assertSameAsMissing(
            LongFunction<MockHttpServletRequestBuilder> request, Long hiddenId) throws Exception {
        setAuthentication(outsiderId);
        JsonNode hidden = summarize(mockMvc.perform(request.apply(hiddenId)).andReturn().getResponse());
        setAuthentication(outsiderId);
        JsonNode missing = summarize(mockMvc.perform(request.apply(MISSING_ID)).andReturn().getResponse());
        assertThat(hidden).as("越境（実在ID）と不在IDの応答は status・code・message まで一致すること")
                .isEqualTo(missing);
        assertThat(hidden.path("status").asInt()).as("越境が成功してはならない").isGreaterThanOrEqualTo(400);
        return hidden;
    }

    private JsonNode summarize(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        var node = objectMapper.createObjectNode();
        node.put("status", response.getStatus());
        node.put("code", root.path("error").path("code").asText(null));
        node.put("message", root.path("error").path("message").asText(null));
        return node;
    }

    /** リクエスト本文を JSON 化する（ラムダ内で使うため検査例外を投げない）。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("リクエスト本文の JSON 化に失敗", e);
        }
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long saveChannel(ChannelType type, Long scopeTeamId, String name, boolean isPrivate) {
        return channelRepository.save(ChatChannelEntity.builder()
                .channelType(type)
                .teamId(scopeTeamId)
                .name(name)
                .isPrivate(isPrivate)
                .createdBy(ownerId)
                .build()).getId();
    }

    private void saveMember(Long channelId, Long userId, ChannelMemberRole role) {
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).role(role).build());
    }

    private Long saveMessage(Long channelId, Long senderId, String body) {
        return messageRepository.save(ChatMessageEntity.builder()
                .channelId(channelId).senderId(senderId).body(body).build()).getId();
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
                                + "VALUES (:email, '存在秘匿', 'テスト', '存在秘匿テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
