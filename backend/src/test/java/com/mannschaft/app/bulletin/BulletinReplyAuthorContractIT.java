package com.mannschaft.app.bulletin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #3431: 返信author契約を実Controller・実Securityフィルタ・実MySQLで固定する。
 * 修正前は正常応答のauthor欠落でred。既存authorId/parentIdの互換性も検査する。
 * DB・認可・名前解決は実Beanを通し、外部Redisのみ共通基底のモックを使う。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BulletinReplyAuthorContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager em;
    private Long teamId;
    private Long threadId;
    private Long authorId;
    private Long childAuthorId;
    private Long replyId;

    @BeforeEach
    void フィクスチャを用意する() {
        UserEntity author = createUser("親投稿者");
        authorId = author.getId();
        childAuthorId = createUser("子投稿者").getId();
        TeamEntity team = TeamEntity.builder().name("掲示板契約")
                .slug("board-" + UUID.randomUUID().toString().substring(0, 20))
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
        em.persist(team);
        teamId = team.getId();
        MembershipTestHelper.insertMembership(em, authorId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamId, RoleKind.MEMBER);
        BulletinThreadEntity thread = BulletinThreadEntity.builder().scopeType(ScopeType.TEAM)
                .scopeId(teamId).authorId(authorId).title("表示契約").body("本文\n二行目").build();
        em.persist(thread);
        threadId = thread.getId();
        replyId = saveReply(null, authorId, "親返信").getId();
        saveReply(replyId, childAuthorId, "子返信");
        em.flush();
        em.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 一覧は親と同梱子返信に投稿者と既存IDを返す(boolean global) throws Exception {
        JsonNode reply = data(get(listUrl(global))).get(0);
        assertAuthor(reply, authorId, "親投稿者");
        assertThat(reply.path("body").asText()).isEqualTo("親返信");
        JsonNode child = reply.path("children").get(0);
        assertAuthor(child, childAuthorId, "子投稿者");
        assertThat(child.path("parentId").asLong()).isEqualTo(replyId);
        assertThat(child.path("body").asText()).isEqualTo("子返信");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 作成直後と更新直後に投稿者を返す(boolean global) throws Exception {
        JsonNode created = data(post(listUrl(global)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"作成本文\"}"));
        assertAuthor(created, authorId, "親投稿者");
        assertThat(created.path("body").asText()).isEqualTo("作成本文");
        long id = created.path("id").asLong();
        String url = global ? "/api/v1/bulletin/replies/" + id : listUrl(false) + "/" + id;
        JsonNode updated = data(put(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"更新本文\"}"));
        assertAuthor(updated, authorId, "親投稿者");
        assertThat(updated.path("body").asText()).isEqualTo("更新本文");
        assertThat(updated.path("isEdited").asBoolean()).isTrue();
    }

    @Test
    void ネスト作成直後にも投稿者と親IDを返す() throws Exception {
        JsonNode child = data(post("/api/v1/bulletin/replies/" + replyId + "/replies")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"直後の子返信\"}"));
        assertAuthor(child, authorId, "親投稿者");
        assertThat(child.path("parentId").asLong()).isEqualTo(replyId);
        assertThat(child.path("body").asText()).isEqualTo("直後の子返信");
    }

    @Test
    void 不明と匿名投稿者にも表示可能なauthorを返す() throws Exception {
        saveReply(null, 999999991L, "不明返信");
        saveReply(null, null, "匿名返信");
        em.flush();
        JsonNode replies = data(get(listUrl(true)));
        assertThat(replies.size()).isEqualTo(3);
        assertAuthor(replies.get(1), 999999991L, "不明なユーザー");
        assertThat(replies.get(2).path("author").path("displayName").asText()).isEqualTo("不明なユーザー");
        assertThat(replies.get(2).path("author").path("id").isNull()).isTrue();
    }

    @Test
    void 空返信一覧は正常な空配列を返す() throws Exception {
        em.createQuery("delete from BulletinReplyEntity r where r.threadId = :threadId")
                .setParameter("threadId", threadId).executeUpdate();
        assertThat(data(get(listUrl(true))).isEmpty()).isTrue();
    }

    @Test
    void 非所属と未認証は投稿者情報を取得できない() throws Exception {
        Long outsider = createUser("部外者").getId();
        String url = listUrl(true);
        String denied = mvc.perform(get(url).servletPath(url).with(user(outsider.toString())))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(denied).doesNotContain("親投稿者", "子投稿者", "親返信");
        mvc.perform(get(url).servletPath(url)).andExpect(status().isUnauthorized());
    }

    private String listUrl(boolean global) {
        return global ? "/api/v1/bulletin/threads/" + threadId + "/replies"
                : "/api/v1/teams/" + teamId + "/bulletin/threads/" + threadId + "/replies";
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String url = request.buildRequest(null).getRequestURI();
        var result = mvc.perform(request.servletPath(url).with(user(authorId.toString()))).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private void assertAuthor(JsonNode reply, Long id, String name) {
        assertThat(reply.path("authorId").asLong()).isEqualTo(id);
        assertThat(reply.path("author").path("id").asLong()).isEqualTo(id);
        assertThat(reply.path("author").path("displayName").asText()).isEqualTo(name);
    }

    private UserEntity createUser(String displayName) {
        UserEntity entity = UserEntity.builder().email("board-" + UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("投稿者").displayName(displayName)
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false).locale("ja").timezone("Asia/Tokyo").build();
        em.persist(entity);
        return entity;
    }

    private BulletinReplyEntity saveReply(Long parentId, Long writerId, String body) {
        BulletinReplyEntity reply = BulletinReplyEntity.builder().threadId(threadId).parentId(parentId)
                .authorId(writerId).body(body).depth(parentId == null ? 0 : 1).build();
        em.persist(reply);
        return reply;
    }
}
