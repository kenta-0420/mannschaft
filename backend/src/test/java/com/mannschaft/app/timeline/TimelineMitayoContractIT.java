package com.mannschaft.app.timeline;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 実DBの集計からHTTPレスポンスまで、閲覧者固有のみたよ契約を検証する。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261026-1912 タイムラインみたよ契約")
class TimelineMitayoContractIT extends AbstractMySqlIntegrationTest {
    private static final long VIEWER = 96_811L;
    private static final long AUTHOR = 96_812L;
    private static final long OUTSIDER = 96_813L;
    private static final long TEAM = 76_811L;

    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private TimelinePostRepository posts;
    @Autowired private TimelinePostReactionRepository reactions;
    @Autowired private MembershipRepository memberships;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, VIEWER);
        MembershipTestHelper.insertActiveUser(em, AUTHOR);
        MembershipTestHelper.insertActiveUser(em, OUTSIDER);
        memberships.save(MembershipEntity.builder().userId(VIEWER).scopeType(ScopeType.TEAM)
                .scopeId(TEAM).roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
    }

    @Test
    void 実集計は対象投稿と閲覧者に限定し反応なしと匿名も扱う() {
        TimelinePostEntity first = savePost(null);
        TimelinePostEntity second = savePost(null);
        TimelinePostEntity excluded = savePost(null);
        react(first.getId(), VIEWER);
        react(first.getId(), AUTHOR);
        react(second.getId(), AUTHOR);
        react(excluded.getId(), VIEWER);
        em.flush();
        var result = reactions.summarizeByPostIds(List.of(first.getId(), second.getId()), VIEWER);
        assertThat(result).hasSize(2);
        var byId = result.stream().collect(java.util.stream.Collectors.toMap(
                TimelinePostReactionRepository.PostReactionSummary::getPostId, summary -> summary));
        assertThat(byId.get(first.getId()).getReactionCount()).isEqualTo(2);
        assertThat(byId.get(first.getId()).getViewerReactionCount()).isEqualTo(1);
        assertThat(byId.get(second.getId()).getReactionCount()).isEqualTo(1);
        assertThat(byId.get(second.getId()).getViewerReactionCount()).isZero();
        assertThat(reactions.summarizeByPostIds(List.of(first.getId()), null).get(0)
                .getViewerReactionCount()).isZero();
    }

    @Test
    void 全読取レスポンスで閲覧者のみたよ状態と実件数を返す() throws Exception {
        TimelinePostEntity parent = savePost(null);
        TimelinePostEntity reply = savePost(parent.getId());
        react(parent.getId(), VIEWER);
        react(parent.getId(), AUTHOR);
        react(reply.getId(), VIEWER);
        em.flush();

        mvc.perform(get("/api/v1/timeline/feed").param("scopeType", "TEAM").param("scopeId", Long.toString(TEAM))
                        .with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.posts[0].mitayo").value(true))
                .andExpect(jsonPath("$.data.posts[0].mitayoCount").value(2));
        mvc.perform(get("/api/v1/timeline/my").with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.posts[0].mitayo").value(true))
                .andExpect(jsonPath("$.data.posts[0].mitayoCount").value(2));
        mvc.perform(get("/api/v1/timeline/users/{id}/posts", AUTHOR).with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].mitayo").value(true));
        mvc.perform(get("/api/v1/timeline/posts/{id}/replies", parent.getId()).with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.posts[0].mitayo").value(true))
                .andExpect(jsonPath("$.data.posts[0].mitayoCount").value(1));
        mvc.perform(get("/api/v1/timeline/posts/{id}", parent.getId()).with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recentReplies[0].mitayo").value(true));
    }

    @Test
    void 未反応はゼロを返し非メンバーには反応情報を漏らさない() throws Exception {
        savePost(null);
        em.flush();
        mvc.perform(get("/api/v1/timeline/feed").param("scopeType", "TEAM").param("scopeId", Long.toString(TEAM))
                        .with(user(Long.toString(VIEWER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.posts[0].mitayo").value(false))
                .andExpect(jsonPath("$.data.posts[0].mitayoCount").value(0));
        mvc.perform(get("/api/v1/timeline/feed").param("scopeType", "TEAM").param("scopeId", Long.toString(TEAM))
                        .with(user(Long.toString(OUTSIDER))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data.posts").doesNotExist());
    }

    private TimelinePostEntity savePost(Long parentId) {
        return posts.save(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(TEAM)
                .userId(AUTHOR).content("みたよ契約投稿").status(PostStatus.PUBLISHED).parentId(parentId).build());
    }

    private void react(Long postId, long userId) {
        reactions.save(TimelinePostReactionEntity.builder().timelinePostId(postId).userId(userId).build());
    }
}