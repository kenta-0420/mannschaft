package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 6 — chat ドメイン「チャンネル閲覧・参加・投稿」認可契約テスト（試練 / red 先行）。
 *
 * <p><b>是正対象の欠陥</b>: {@code ChatMessageService.checkChannelViewAccess} は Javadoc に自ら
 * 「通常チャンネルでは no-op」と明記されているとおり {@code TOURNAMENT_CHAT}/
 * {@code TOURNAMENT_DIVISION_CHAT} でしか認可せず、通常チャンネル（チーム／組織／DM／グループDM）は
 * 認可ゼロで素通ししていた。加えて {@code ChatMemberService.joinChannel} は
 * {@code channelType}/{@code isPrivate}/スコープ所属を一切見ず、
 * {@code ChatChannelService.getChannel} は非メンバーにも DM 相手を返し、
 * {@code convertToGroup} は呼出者 ID すら受け取っていなかった。</p>
 *
 * <p><b>採用した意味論の根拠（憶測ではない）</b>: WebSocket 購読認可の正準実装
 * {@code ChatChannelSubscriptionInterceptor.MEMBERSHIP_GATED_TYPES}（:84-90）が
 * 「{@code chat_channel_members} 行でメンバーシップが定義される種別」として
 * TEAM_PUBLIC / TEAM_PRIVATE / ORG_PUBLIC / ORG_PRIVATE / DM / GROUP_DM を列挙し、
 * VILLAGE_LOBBY / EVENT_CHAT / TOURNAMENT_* は「別ドメインのアクセスモデルで管理されるため素通し」と
 * 規定している。REST 側も同じ境界に揃える（{@code ChatMessageService#getActiveThreads} :385-389 の
 * 既存判定とも一致）。よって <b>TEAM_PUBLIC であってもチャンネルメンバーであることを要する</b>
 * （「公開」はチームメンバーが自分で参加できることを意味し、未参加での本文閲覧は許さない）。</p>
 *
 * <p>金型: {@code PhotoAlbumListPhotosScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}
 * + 実 MySQL Testcontainers）。ID-only エンドポイントのため越境は 403（CHAT_005）に畳み込む。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("chat ドメイン チャンネル閲覧・参加・投稿 認可契約テスト（試練・Wave6）")
class ChatChannelAccessScopeContractIT extends AbstractMySqlIntegrationTest {

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
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository villageMembershipRepository;

    @Autowired
    private EventRepository eventRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;

    /** チャンネルメンバー かつ チームAメンバー。 */
    private Long channelMemberId;
    /** チームAメンバーだが「チャンネル未参加」。TEAM_PUBLIC のメンバーシップゲートを実証する主役。 */
    private Long teamOnlyMemberId;
    /** DM の相手（channelMemberId と 1:1 DM を持つ）。 */
    private Long dmPartnerId;
    /** チームにもチャンネルにも属さない完全な部外者。 */
    private Long outsiderId;

    private Long teamPublicChannelId;
    private Long teamPrivateChannelId;
    private Long dmChannelId;

    // 裏目付A: VILLAGE_LOBBY / EVENT_CHAT の素通し根治用データ
    /** 対象村の現役 USER メンバー（VILLAGE_LOBBY を閲覧できるべき主役）。 */
    private Long villageMemberId;
    private UUID villageId;
    private Long villageLobbyChannelId;
    /** EVENT_CHAT のイベントスコープ（teamA）メンバー（チャンネルメンバー行は持たない）。 */
    private Long eventScopeMemberId;
    private Long eventId;
    private Long eventChatChannelId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("認可契約チャットチームA");

        channelMemberId = insertUser("chat-authz-channel-member@example.com");
        teamOnlyMemberId = insertUser("chat-authz-team-only@example.com");
        dmPartnerId = insertUser("chat-authz-dm-partner@example.com");
        outsiderId = insertUser("chat-authz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, channelMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, teamOnlyMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // dmPartnerId / outsiderId はチームAに所属しない。

        teamPublicChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(teamAId)
                .name("認可契約 公開チャンネル")
                .isPrivate(false)
                .createdBy(channelMemberId)
                .build()).getId();

        teamPrivateChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PRIVATE)
                .teamId(teamAId)
                .name("認可契約 非公開チャンネル")
                .isPrivate(true)
                .createdBy(channelMemberId)
                .build()).getId();

        dmChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.DM)
                .createdBy(channelMemberId)
                .build()).getId();

        // チャンネルメンバー行: channelMemberId のみ（teamOnlyMemberId は意図的に未参加）
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(teamPublicChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(teamPrivateChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(dmChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(dmChannelId).userId(dmPartnerId)
                .role(ChannelMemberRole.MEMBER).build());

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(dmChannelId).senderId(channelMemberId)
                .body("DM の秘密の本文").build());
        messageRepository.save(ChatMessageEntity.builder()
                .channelId(teamPublicChannelId).senderId(channelMemberId)
                .body("公開チャンネルの本文").build());

        setUpVillageLobby();
        setUpEventChat();

        em.flush();
        em.clear();
    }

    /**
     * VILLAGE_LOBBY 認可用データ。村・村メンバー（USER 主体）・村ロビーチャンネルを用意する。
     * outsiderId は村メンバーではない（非メンバーの主役）。
     */
    private void setUpVillageLobby() {
        villageMemberId = insertUser("chat-authz-village-member@example.com");

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("chat-authz-village-" + System.nanoTime())
                .name("認可契約テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .build());
        villageId = village.getId();

        villageMembershipRepository.save(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(villageMemberId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build());

        villageLobbyChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY)
                .villageId(villageId)
                .name("認可契約 村ロビー")
                .isPrivate(false)
                .createdBy(villageMemberId)
                .build()).getId();

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(villageLobbyChannelId).senderId(villageMemberId)
                .body("村ロビーの本文").build());
    }

    /**
     * EVENT_CHAT 認可用データ。teamA スコープのイベントと EVENT_CHAT チャンネルを用意する。
     * イベントスコープのメンバー = teamA メンバー（{@code eventScopeMemberId}）。
     * outsiderId は teamA 非所属のためイベント非参加者（非メンバーの主役）。
     */
    private void setUpEventChat() {
        eventScopeMemberId = insertUser("chat-authz-event-member@example.com");
        MembershipTestHelper.insertMembership(em, eventScopeMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        EventEntity event = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamAId)
                .slug("chat-authz-event-" + System.nanoTime())
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .build());
        eventId = event.getId();

        eventChatChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.EVENT_CHAT)
                .teamId(teamAId)
                .name("認可契約 イベントチャット")
                .isPrivate(false)
                .sourceType("EVENT")
                .sourceId(eventId)
                .createdBy(eventScopeMemberId)
                .build()).getId();

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(eventChatChannelId).senderId(eventScopeMemberId)
                .body("イベントチャットの本文").build());
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ一覧（GET /chat/channels/{id}/messages）— 最重要
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージ一覧(listMessages)")
    class ListMessages {

        @Test
        @DisplayName("部外者による他人のDMメッセージ一覧取得は403（CHAT_005・DM本文の総当り閲覧を閉塞）")
        void 部外者のDMメッセージ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チームメンバーでもチャンネル未参加ならTEAM_PUBLICのメッセージ一覧は403（メンバーシップゲート）")
        void チャンネル未参加のチームメンバーは403() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("部外者によるTEAM_PRIVATEのメッセージ一覧取得は403")
        void 部外者の非公開チャンネルメッセージ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPrivateChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チャンネルメンバーのメッセージ一覧取得は200（非回帰）")
        void チャンネルメンバーのメッセージ一覧は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPublicChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DM相手のメッセージ一覧取得は200（非回帰）")
        void DM相手のメッセージ一覧は200() throws Exception {
            setAuthentication(dmPartnerId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", dmChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("不在チャンネルのメッセージ一覧取得は404（CHAT_001・従来はWARN既定の400だった）")
        void 不在チャンネルのメッセージ一覧は404() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ検索・アクティブスレッド（同じ閲覧認可を通す派生経路）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージ検索・スレッド一覧")
    class SearchAndThreads {

        @Test
        @DisplayName("部外者による他人のDMメッセージ検索は403（本文漏洩の裏口を閉塞）")
        void 部外者のDMメッセージ検索は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", dmChannelId)
                            .param("keyword", "秘密"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("部外者によるアクティブスレッド一覧取得は403")
        void 部外者のスレッド一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チャンネルメンバーのスレッド一覧取得は200（非回帰）")
        void メンバーのスレッド一覧は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", teamPublicChannelId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チャンネル詳細（GET /chat/channels/{id}）— DM 相手の関係グラフ漏洩
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チャンネル詳細(getChannel)")
    class GetChannel {

        @Test
        @DisplayName("部外者による他人のDM詳細取得は403（DM相手のuserId・表示名・アバターの漏洩を閉塞）")
        void 部外者のDM詳細は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チャンネル未参加のチームメンバーによるTEAM_PUBLIC詳細取得は403")
        void 未参加チームメンバーのチャンネル詳細は403() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("DM当事者の詳細取得は200で相手情報を返す（非回帰）")
        void DM当事者の詳細は200() throws Exception {
            setAuthentication(dmPartnerId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", dmChannelId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dmPartner.userId").value(channelMemberId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チャンネル参加（POST /chat/channels/{id}/join）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チャンネル参加(joinChannel)")
    class JoinChannel {

        @Test
        @DisplayName("他人のDMへの自己参加は403（DM/GROUP_DMは自己参加不可）")
        void 他人のDMへの自己参加は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チームメンバーであっても非公開チャンネルへの自己参加は403（招待制）")
        void 非公開チャンネルへの自己参加は403() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPrivateChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("非チームメンバーによる公開チャンネルへの自己参加は403（COMMON_002・スコープ所属を要求）")
        void 非チームメンバーの公開チャンネル参加は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("チームメンバーによる公開チャンネルへの自己参加は201（非回帰・公開の本来の意味）")
        void チームメンバーの公開チャンネル参加は201() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPublicChannelId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ送信・DM→グループDM変換（書き込み系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("書き込み系(sendMessage / convertToGroup)")
    class WriteOperations {

        @Test
        @DisplayName("部外者による他人のDMへのメッセージ送信は403（投稿バイパスを閉塞）")
        void 部外者のDMへの送信は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", dmChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("チャンネルメンバーのメッセージ送信は201（非回帰）")
        void メンバーの送信は201() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", teamPublicChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("部外者による他人のDMのグループDM変換は403（callerId未受領の欠陥を閉塞）")
        void 部外者のグループDM変換は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/convert-to-group", dmChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("DMのOWNERによるグループDM変換は200（非回帰）")
        void OWNERのグループDM変換は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/convert-to-group", dmChannelId))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> sendBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "認可契約テストの投稿");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 裏目付A: VILLAGE_LOBBY / EVENT_CHAT の閲覧素通し根治
    //   旧実装は isMembershipGated() でない種別を無言 return で素通しし、
    //   村の非メンバー・イベント非参加者でも本文一覧・スレッド・検索・投稿が通っていた。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("村ロビー・イベントチャット認可(VILLAGE_LOBBY / EVENT_CHAT)")
    class VillageAndEventChat {

        // ---- AC-1a: 村の非メンバーは VILLAGE_LOBBY を閲覧できない ----

        @Test
        @DisplayName("AC-1a: 村の非メンバーによる村ロビーのメッセージ一覧は403（CHAT_005・素通し根治）")
        void 村非メンバーの村ロビーメッセージ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        // ---- AC-1b: イベント非参加者は EVENT_CHAT を閲覧できない ----

        @Test
        @DisplayName("AC-1b: イベント非参加者によるイベントチャットのメッセージ一覧は403（CHAT_005・素通し根治）")
        void イベント非参加者のイベントチャットメッセージ一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", eventChatChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        // ---- AC-1c: 正当な村メンバー / イベントスコープメンバーは従来どおり200（回帰防止） ----

        @Test
        @DisplayName("AC-1c: 村の現役メンバーによる村ロビーのメッセージ一覧は200（回帰防止）")
        void 村メンバーの村ロビーメッセージ一覧は200() throws Exception {
            setAuthentication(villageMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-1c: イベントスコープのメンバーによるイベントチャットのメッセージ一覧は200（回帰防止）")
        void イベントスコープメンバーのイベントチャットメッセージ一覧は200() throws Exception {
            setAuthentication(eventScopeMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", eventChatChannelId))
                    .andExpect(status().isOk());
        }

        // ---- AC-1d: list / thread / search の全読取経路で非メンバー403（経路漏れ防止） ----

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーのスレッド一覧は403（経路漏れ防止）")
        void 村非メンバーの村ロビースレッド一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", villageLobbyChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーのメッセージ検索は403（経路漏れ防止）")
        void 村非メンバーの村ロビーメッセージ検索は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", villageLobbyChannelId)
                            .param("keyword", "本文"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットのスレッド一覧は403（経路漏れ防止）")
        void イベント非参加者のイベントチャットスレッド一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", eventChatChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットのメッセージ検索は403（経路漏れ防止）")
        void イベント非参加者のイベントチャットメッセージ検索は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", eventChatChannelId)
                            .param("keyword", "本文"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        // ---- 投稿経路も同じゲートを通す（非メンバーの書き込みバイパス閉塞） ----

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーへのメッセージ送信は403（投稿バイパス閉塞）")
        void 村非メンバーの村ロビー投稿は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("body", "侵入投稿"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットへのメッセージ送信は403（投稿バイパス閉塞）")
        void イベント非参加者のイベントチャット投稿は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", eventChatChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("body", "侵入投稿"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
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
                                + "VALUES (:email, 'チャット認可', 'テスト', 'チャット認可テスト', 'ACTIVE', "
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
                                + "CONCAT('chat-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
