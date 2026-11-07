package com.mannschaft.app.timeline;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.timeline.entity.TimelinePollEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.repository.TimelinePollOptionRepository;
import com.mannschaft.app.timeline.repository.TimelinePollRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.service.TimelinePostVisibilityAccessGuard;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260929-1517: 実 Security・実 Bean・MySQL で非公開投稿の全参照入口を検証する。
 * TimelinePostController#getPost/getReplies/createPost、TimelinePollController#getPoll/vote、
 * TimelineReactionController#addReaction/removeReaction、TimelineBookmarkController#addBookmark が対象。
 * 返信作成は REQUIRES_NEW のため、その試験だけ fixture を実 commit し、所有行を後始末する。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelinePostStatusVisibilityContractIT extends AbstractMySqlIntegrationTest {
    private static final long OWNER = 9_715_101L;
    private static final long PEER = 9_715_102L;
    private static final long OUTSIDER = 9_715_103L;
    private static final long ADMIN = 9_715_104L;
    private static final long SCOPE = 9_715_100L;
    private static final String CONTENT = "CMP1517-非公開内容";
    private static final String NOT_FOUND = TimelineErrorCode.POST_NOT_FOUND.getCode();
    private static final List<PostStatus> PRIVATE_STATUSES =
            List.of(PostStatus.DRAFT, PostStatus.SCHEDULED, PostStatus.HIDDEN);
    private static final List<PostScopeType> SCOPES =
            List.of(PostScopeType.PUBLIC, PostScopeType.TEAM, PostScopeType.ORGANIZATION, PostScopeType.VILLAGE);

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TimelinePostRepository posts;
    @Autowired private TimelinePollRepository polls;
    @Autowired private TimelinePollOptionRepository options;
    @Autowired private TimelinePostReactionRepository reactions;
    @Autowired private VillageRepository villages;
    @Autowired private VillageMembershipRepository villageMemberships;
    @Autowired private TimelinePostVisibilityAccessGuard visibility;
    private UUID villageId;

    @BeforeEach
    void 検証主体と実所属を作る() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            for (long actor : List.of(OWNER, PEER, OUTSIDER, ADMIN)) {
                MembershipTestHelper.insertActiveUser(em, actor);
            }
            for (long actor : List.of(OWNER, PEER, ADMIN)) {
                MembershipTestHelper.insertMembership(em, actor, ScopeType.TEAM, SCOPE, RoleKind.MEMBER);
                MembershipTestHelper.insertMembership(em, actor, ScopeType.ORGANIZATION, SCOPE, RoleKind.MEMBER);
            }
            MembershipTestHelper.insertUserRole(em, ADMIN, "SYSTEM_ADMIN", null, null);
            VillageEntity village = villages.save(VillageEntity.builder()
                    .slug("cmp1517-" + UUID.randomUUID().toString().substring(0, 12))
                    .name("公開状態契約の村").type(VillageType.COMMUNITY)
                    .joinPolicy(VillageJoinPolicy.FREE).visibility(VillageVisibility.PUBLIC)
                    .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY).build());
            villageId = village.getId();
            for (long actor : List.of(OWNER, PEER, ADMIN)) {
                villageMemberships.save(VillageMembershipEntity.builder().villageId(villageId)
                        .subjectType(VillageSubjectType.USER).subjectId(actor).role(VillageRole.VILLAGER)
                        .joinedAt(LocalDateTime.now()).build());
            }
            em.flush();
        });
    }

    @AfterEach
    void 実コミットした試験だけ所有行を後始末する() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                jdbc.update("DELETE FROM timeline_posts WHERE content LIKE 'CMP1517-%'");
                jdbc.update("DELETE FROM village_memberships WHERE village_id IN "
                        + "(SELECT id FROM villages WHERE slug LIKE 'cmp1517-%')");
                jdbc.update("DELETE FROM villages WHERE slug LIKE 'cmp1517-%'");
                jdbc.update("DELETE FROM memberships WHERE user_id IN (?, ?, ?, ?)", OWNER, PEER, OUTSIDER, ADMIN);
                jdbc.update("DELETE FROM user_roles WHERE user_id IN (?, ?, ?, ?)", OWNER, PEER, OUTSIDER, ADMIN);
                jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?) "
                        + "AND email LIKE 'auth-contract-%@example.com'", OWNER, PEER, OUTSIDER, ADMIN);
            });
        }
    }

    static Stream<Arguments> 非公開入口条件() {
        return PRIVATE_STATUSES.stream().flatMap(state -> SCOPES.stream().flatMap(scope ->
                Stream.of(OWNER, PEER, OUTSIDER, ADMIN).flatMap(actor -> Arrays.stream(Entry.values())
                        .map(entry -> Arguments.of(state, scope, actor, entry)))));
    }

    @ParameterizedTest(name = "{0} {1} actor={2} {3}")
    @MethodSource("非公開入口条件")
    void 非公開投稿は既存scope内の本人だけが全入口を使える(
            PostStatus state, PostScopeType scope, long actor, Entry entry) throws Exception {
        TimelinePostEntity target = savePost(state, scope, OWNER, null);
        TimelinePollOptionEntity option = poll(target.getId());
        if (entry == Entry.REMOVE_REACTION) {
            reactions.save(TimelinePostReactionEntity.builder().timelinePostId(target.getId()).userId(actor).build());
            target.incrementReactionCount();
        }
        em.flush();
        List<Long> before = snapshot(target.getId(), option.getId());

        var result = mvc.perform(request(entry, target.getId(), option.getId())
                .with(user(Long.toString(actor)).roles(actor == ADMIN ? "SYSTEM_ADMIN" : "USER")));

        if (actor == OWNER) {
            result.andExpect(status().is(entry.successStatus));
        } else {
            result.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertThat(snapshot(target.getId(), option.getId())).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PostStatus.class, names = {"DRAFT", "SCHEDULED", "HIDDEN"})
    void 投稿者本人も所属を失えば非公開投稿を読めない(PostStatus state) throws Exception {
        TimelinePostEntity target = savePost(state, PostScopeType.TEAM, OUTSIDER, null);
        mvc.perform(get("/api/v1/timeline/posts/{id}", target.getId()).with(user(Long.toString(OUTSIDER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
    }

    @ParameterizedTest
    @EnumSource(value = PostStatus.class, names = {"DRAFT", "SCHEDULED", "HIDDEN", "DELETED"})
    void 投稿者不在の非公開投稿を本人不在として拒否する(PostStatus state) throws Exception {
        TimelinePostEntity target = savePost(state, PostScopeType.PUBLIC, null, null);
        mvc.perform(get("/api/v1/timeline/posts/{id}", target.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void 予約時刻を過ぎても状態が予約の間は他人に公開しない(int offset) throws Exception {
        TimelinePostEntity target = posts.save(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC)
                .userId(OWNER).content(CONTENT).status(PostStatus.SCHEDULED)
                .scheduledAt(LocalDateTime.now().plusDays(offset)).build());
        mvc.perform(get("/api/v1/timeline/posts/{id}", target.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
        jdbc.update("UPDATE timeline_posts SET status = 'PUBLISHED' WHERE id = ?", target.getId());
        em.clear();
        mvc.perform(get("/api/v1/timeline/posts/{id}", target.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.status").value("PUBLISHED"));
    }

    @Test
    void 状態不明と論理削除は本人にも不可視で公開システム投稿は維持する() throws Exception {
        assertThat(visibility.isVisible(null, OWNER)).isFalse();
        assertThat(visibility.isVisible(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC)
                .userId(OWNER).status(null).build(), OWNER)).isFalse();
        assertThat(visibility.isVisible(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC)
                .userId(OWNER).status(PostStatus.DRAFT).build(), null)).isFalse();
        TimelinePostEntity deleted = savePost(PostStatus.PUBLISHED, PostScopeType.PUBLIC, OWNER, null);
        deleted.softDelete();
        assertThat(visibility.isVisible(deleted, OWNER)).isFalse();
        em.flush();
        em.clear();
        mvc.perform(get("/api/v1/timeline/posts/{id}", deleted.getId()).with(user(Long.toString(OWNER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
        TimelinePostEntity system = savePost(PostStatus.PUBLISHED, PostScopeType.VILLAGE, null, null);
        mvc.perform(get("/api/v1/timeline/posts/{id}", system.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isOk());
    }

    @Test
    void 非公開親は返信入口も拒否し公開済み返信自身の独立性は維持する() throws Exception {
        TimelinePostEntity parent = savePost(PostStatus.HIDDEN, PostScopeType.TEAM, OWNER, null);
        TimelinePostEntity reply = savePost(PostStatus.PUBLISHED, PostScopeType.TEAM, PEER, parent.getId());
        mvc.perform(get("/api/v1/timeline/posts/{id}/replies", parent.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
        mvc.perform(get("/api/v1/timeline/posts/{id}", reply.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content.parentId").value(parent.getId()))
                .andExpect(jsonPath("$.data.parent").doesNotExist());
        mvc.perform(get("/api/v1/timeline/posts/{id}/replies", reply.getId()).with(user(Long.toString(PEER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.posts").isEmpty());
    }

    static Stream<Arguments> 返信条件() {
        return PRIVATE_STATUSES.stream().flatMap(state -> SCOPES.stream()
                .flatMap(scope -> Stream.of(OWNER, PEER, OUTSIDER, ADMIN)
                        .map(actor -> Arguments.of(state, scope, actor))));
    }

    @ParameterizedTest
    @MethodSource("返信条件")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 返信作成も非公開の対象親を他人から秘匿する(PostStatus state, PostScopeType scope, long actor)
            throws Exception {
        TimelinePostEntity parent = savePost(state, scope, OWNER, null);
        long before = posts.count();
        var result = mvc.perform(post("/api/v1/timeline/posts").with(user(Long.toString(actor)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"CMP1517-返信\","
                        + "\"scopeType\":\"PUBLIC\",\"postedAsType\":\"USER\",\"status\":\"DRAFT\","
                        + "\"parentId\":" + parent.getId() + "}"));
        if (actor == OWNER) {
            result.andExpect(status().isCreated());
            assertThat(posts.count()).isEqualTo(before + 1);
            assertThat(posts.findById(parent.getId()).orElseThrow().getReplyCount()).isEqualTo(1);
        } else {
            result.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));
            assertThat(posts.count()).isEqualTo(before);
            assertThat(posts.findById(parent.getId()).orElseThrow().getReplyCount()).isZero();
        }
    }

    private TimelinePostEntity savePost(PostStatus state, PostScopeType scope, Long author, Long parentId) {
        return posts.saveAndFlush(TimelinePostEntity.builder().scopeType(scope).scopeId(SCOPE)
                .scopeVillageId(scope == PostScopeType.VILLAGE ? villageId : null)
                .userId(author).content(CONTENT).status(state).parentId(parentId).build());
    }

    @ParameterizedTest
    @EnumSource(value = PostStatus.class, names = {"DRAFT", "SCHEDULED", "HIDDEN"})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 他人の非公開投稿はリポストも作成せず元のカウンタを変えない(PostStatus state) throws Exception {
        TimelinePostEntity original = savePost(state, PostScopeType.PUBLIC, OWNER, null);
        long before = posts.count();

        mvc.perform(post("/api/v1/timeline/posts").with(user(Long.toString(PEER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"CMP1517-リポスト\","
                                + "\"scopeType\":\"PUBLIC\",\"postedAsType\":\"USER\",\"status\":\"DRAFT\","
                                + "\"repostOfId\":" + original.getId() + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));

        assertThat(posts.count()).isEqualTo(before);
        assertThat(posts.findById(original.getId()).orElseThrow().getRepostCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 不在と論理削除済みの元投稿への新規リポストは同じ404で保存しない(boolean deleted) throws Exception {
        long originalId;
        if (deleted) {
            originalId = savePost(PostStatus.PUBLISHED, PostScopeType.PUBLIC, OWNER, null).getId();
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                posts.findById(originalId).orElseThrow().softDelete();
                em.flush();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE id = ? AND deleted_at IS NOT NULL",
                    Long.class, originalId)).isEqualTo(1L);
        } else {
            originalId = Long.MAX_VALUE;
            assertThat(posts.findById(originalId)).isEmpty();
        }
        long before = posts.count();

        mvc.perform(post("/api/v1/timeline/posts").with(user(Long.toString(PEER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"CMP1517-消失元リポスト\","
                                + "\"scopeType\":\"PUBLIC\",\"postedAsType\":\"USER\",\"status\":\"DRAFT\","
                                + "\"repostOfId\":" + originalId + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(NOT_FOUND));

        assertThat(posts.count()).isEqualTo(before);
        if (deleted) {
            assertThat(jdbc.queryForObject("SELECT repost_count FROM timeline_posts WHERE id = ?",
                    Integer.class, originalId)).isZero();
        }
    }

    private TimelinePollOptionEntity poll(Long postId) {
        TimelinePollEntity poll = polls.save(TimelinePollEntity.builder().timelinePostId(postId)
                .question("非公開の質問").build());
        return options.save(TimelinePollOptionEntity.builder().timelinePollId(poll.getId()).optionText("選択肢").build());
    }

    private List<Long> snapshot(Long postId, Long optionId) {
        return List.of(count("SELECT COUNT(*) FROM timeline_posts"), count("SELECT COUNT(*) FROM timeline_poll_votes"),
                count("SELECT COUNT(*) FROM timeline_post_reactions"), count("SELECT COUNT(*) FROM timeline_bookmarks"),
                jdbc.queryForObject("SELECT reaction_count + reply_count + repost_count FROM timeline_posts WHERE id = ?",
                        Long.class, postId),
                jdbc.queryForObject("SELECT vote_count FROM timeline_poll_options WHERE id = ?", Long.class, optionId),
                jdbc.queryForObject("SELECT total_vote_count FROM timeline_polls WHERE timeline_post_id = ?",
                        Long.class, postId));
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private MockHttpServletRequestBuilder request(Entry entry, Long postId, Long optionId) {
        String path = "/api/v1/timeline/posts/" + postId;
        return switch (entry) {
            case DETAIL -> get(path);
            case REPLIES -> get(path + "/replies");
            case POLL -> get(path + "/poll");
            case VOTE -> post(path + "/poll/vote").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"optionId\":" + optionId + "}");
            case ADD_REACTION -> post(path + "/reactions");
            case REMOVE_REACTION -> delete(path + "/reactions");
            case BOOKMARK -> post("/api/v1/timeline/bookmarks/" + postId);
        };
    }

    private enum Entry {
        DETAIL(200), REPLIES(200), POLL(200), VOTE(200), ADD_REACTION(201), REMOVE_REACTION(200), BOOKMARK(201);

        private final int successStatus;

        Entry(int successStatus) {
            this.successStatus = successStatus;
        }
    }
}
