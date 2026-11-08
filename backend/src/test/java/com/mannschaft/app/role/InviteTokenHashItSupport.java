package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260826-1457 W1 P1 の試練（系統1: role 招待トークン）で共有する実 DB・HTTP の足場。
 *
 * <p>fixture は<b>平文列にもハッシュ列にも依存しない経路</b>（発行 API・ID 指定の JDBC 更新）で作る。
 * 平文の既知な宛先付き招待が要る試練だけは {@link #insertTargetedInviteWithKnownPlaintext} で
 * {@code token_hash} 列へ直接書く。この列が無い（移行前の）ときは、SQL エラーではなく
 * {@link #assertHashSchema()} の assertion で落ちる。</p>
 *
 * <p>契約（出陣の足軽が合わせる）:</p>
 * <ul>
 *   <li>ID 承諾: {@code POST /api/v1/invite/invitations/{tokenId}/accept}（body 任意 {@code {"folderId": n}}）</li>
 *   <li>ID 辞退: {@code POST /api/v1/invite/invitations/{tokenId}/decline}</li>
 *   <li>QR-PDF: {@code POST /api/v1/teams/{teamId|slug}/invite-tokens/pdf}（body {@code {"token": "<平文>"}}）</li>
 * </ul>
 */
abstract class InviteTokenHashItSupport extends InviteGenerationItSupport {

    /** Base64URL（パディング無し）32 バイト = 43 字（御裁可: {@code SecretTokenVault.issueBase64Url()}）。 */
    protected static final String BASE64URL_43 = "^[A-Za-z0-9_-]{43}$";
    protected static final String SHA256_HEX = "^[0-9a-f]{64}$";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected SecretTokenVault vault;
    @Autowired protected MembershipRepository memberships;
    @Autowired protected ChatChannelRepository channels;
    @Autowired protected ChatChannelMemberRepository channelMembers;

    private final List<Long> ownedChannels = new ArrayList<>();

    protected void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    protected String slug(Scope scope) {
        return jdbc.queryForObject("SELECT slug FROM " + scope.table() + " WHERE id = ?", String.class, scope.id());
    }

    protected String collection(Scope scope) {
        return "TEAM".equals(scope.type()) ? "teams" : "organizations";
    }

    protected String scopeColumn(Scope scope) {
        return "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
    }

    /** 権限ありの発行者（user_roles の ADMIN と memberships の在籍）を作る。 */
    protected Long issuer(Scope scope) {
        Long admin = admin(scope);
        inTx(() -> memberships.saveAndFlush(MembershipEntity.builder().userId(admin)
                .scopeType(ScopeType.valueOf(scope.type())).scopeId(scope.id()).roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build()));
        return admin;
    }

    protected MockHttpServletResponse perform(Long actor, RequestBuilder request) throws Exception {
        setAuth(actor);
        return mvc.perform(request).andReturn().getResponse();
    }

    protected JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    /** 共有リンク型の招待を発行 API で作り、応答の {@code data} を返す。 */
    protected JsonNode issueShared(Scope scope, Long actor) throws Exception {
        MockHttpServletResponse response = perform(actor, post("/api/v1/" + collection(scope) + "/{slug}/invite-tokens", slug(scope))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new CreateInviteTokenRequest(scope.memberRoleId(), "7d", 5))));
        assertThat(response.getStatus()).as("共有招待の発行: %s", response.getContentAsString()).isEqualTo(201);
        return json(response).path("data");
    }

    protected Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    protected Set<String> inviteTokenColumns() {
        return new TreeSet<>(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'invite_tokens'", String.class));
    }

    /** 移行後の形（平文列 token が無く token_hash がある）であること。無ければ assertion で落ちる。 */
    protected void assertHashSchema() {
        assertThat(inviteTokenColumns())
                .as("invite_tokens は token_hash だけを持ち、平文列 token を持たない（AC-1）")
                .contains("token_hash")
                .doesNotContain("token");
    }

    /**
     * 平文が既知の宛先付き招待を {@code token_hash} 列へ直接書いて作る（両入口の同値試験用）。
     *
     * @return 招待 ID
     */
    protected Long insertTargetedInviteWithKnownPlaintext(Scope scope, String plaintext, Long target, Long createdBy,
                                                          LocalDateTime expiresAt, Integer maxUses, int usedCount,
                                                          LocalDateTime revokedAt) {
        assertHashSchema();
        jdbc.update("INSERT INTO invite_tokens (token_hash, " + scopeColumn(scope) + ", role_id, expires_at, max_uses, "
                        + "used_count, revoked_at, target_user_id, created_by, scope_closure_generation, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                vault.hash(plaintext), scope.id(), scope.memberRoleId(), expiresAt, maxUses, usedCount, revokedAt,
                target, createdBy, LocalDateTime.now(java.time.ZoneOffset.UTC));
        return jdbc.queryForObject("SELECT id FROM invite_tokens WHERE token_hash = ?", Long.class, vault.hash(plaintext));
    }

    protected Long createDm(Long userA, Long userB) {
        return inTx(() -> {
            Long channelId = channels.saveAndFlush(ChatChannelEntity.builder().channelType(ChannelType.DM)
                    .name(null).isPrivate(true).createdBy(userA).build()).getId();
            channelMembers.saveAndFlush(ChatChannelMemberEntity.builder().channelId(channelId).userId(userA).build());
            channelMembers.saveAndFlush(ChatChannelMemberEntity.builder().channelId(channelId).userId(userB).build());
            ownedChannels.add(channelId);
            return channelId;
        });
    }

    /** DM から承諾型招待を発行 API で作り、応答の {@code data} を返す。 */
    protected JsonNode issueMembershipInvite(Long channelId, Scope scope, Long actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scope.type());
        body.put("scopeId", scope.id());
        body.put("roleId", scope.memberRoleId());
        body.put("expiresInDays", 7);
        MockHttpServletResponse response = perform(actor, post("/api/v1/chat/channels/{cid}/membership-invite", channelId)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
        assertThat(response.getStatus()).as("承諾型招待の発行: %s", response.getContentAsString()).isEqualTo(201);
        return json(response).path("data");
    }

    protected boolean isMember(Scope scope, Long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND " + scopeColumn(scope)
                + " = ?", Long.class, userId, scope.id());
        return count != null && count > 0;
    }

    protected int usedCount(Long inviteId) {
        Integer used = jdbc.queryForObject("SELECT used_count FROM invite_tokens WHERE id = ?", Integer.class, inviteId);
        return used == null ? -1 : used;
    }

    protected Object revokedAt(Long inviteId) {
        return jdbc.queryForObject("SELECT revoked_at FROM invite_tokens WHERE id = ?", Object.class, inviteId);
    }

    /** 応答のステータスとエラーコードの組（存在オラクル比較用）。 */
    protected record Outcome(int status, String errorCode) {
    }

    protected Outcome outcome(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        String code = body.isBlank() ? null : mapper.readTree(body).path("error").path("code").asText(null);
        return new Outcome(response.getStatus(), code);
    }

    @AfterEach
    void removeOwnedChannelsAndAuthentication() {
        SecurityContextHolder.clearContext();
        for (Long channelId : ownedChannels) {
            jdbc.update("DELETE FROM chat_messages WHERE channel_id = ?", channelId);
            jdbc.update("DELETE FROM chat_channel_members WHERE channel_id = ?", channelId);
            jdbc.update("DELETE FROM chat_channels WHERE id = ?", channelId);
        }
        ownedChannels.clear();
    }
}
