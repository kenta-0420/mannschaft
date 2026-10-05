package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.controller.BlogPostController;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogRanchTransportRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 既入口の実認可・保存成功と初公開資格の欠落を分ける。HTTP/filter/配送成功の試験ではない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties="ranch.source.blog.queue-capacity=0")
class BlogRanchPublicationCoverageIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private BlogPostRepository posts;
    @Autowired private BlogPostController controller;
    @Autowired private BlogScheduledPublishBatchService scheduled;
    @Autowired private BlogRanchTransportRepository transport;
    @Autowired private TeamRepository teams;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository assignments;
    @Autowired private JdbcTemplate jdbc;
    private Long author,editor,team,membership,assignment;
    private final List<Long> ownPosts=new ArrayList<>();
    @BeforeEach void fixture() { author=user();editor=user(); }
    private Long user() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@cms-coverage.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    private Long draft(Long teamId,LocalDateTime due) {
        Long id=posts.saveAndFlush(BlogPostEntity.builder().authorId(author).userId(author).teamId(teamId)
                .title("公開入口検証").slug("coverage-"+UUID.randomUUID()).body("本人の検証本文")
                .publishedAt(due).build()).getId();
        ownPosts.add(id);return id;
    }
    private void actor(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(),null,List.of()));
    }
    private void published(Long id) {
        assertThat(jdbc.queryForObject("SELECT status FROM blog_posts WHERE id=?",String.class,id)).isEqualTo("PUBLISHED");
    }
    private void qualified(Long id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND first_published_at IS NOT NULL "
                +"AND first_published_author_user_id=? AND is_ranch_publication_observed=TRUE",Integer.class,id,author))
                .as("本体保存後に著者の初回資格が同じ行へ固定される").isEqualTo(1);
    }
    @Test void ownerBulkCommitsBothPostsAndCapturesEachAuthorFirstPublication() {
        Long one=draft(null,null),two=draft(null,null);actor(author);
        assertThat(controller.bulkAction(new BulkActionRequest(List.of(one,two),"PUBLISH")).getStatusCode().value()).isEqualTo(200);
        published(one);published(two);
        qualified(one);qualified(two);
    }
    @Test void scheduledSystemPublicationPreservesNativeSuccessAndCapturesAuthor() {
        Long id=draft(null,LocalDateTime.now().minusHours(1));
        assertThat(scheduled.publishScheduledPosts()).isGreaterThanOrEqualTo(1);
        published(id);qualified(id);
    }
    private void teamAdmin() {
        team=teams.saveAndFlush(TeamEntity.builder().slug("coverage-"+UUID.randomUUID().toString().substring(0,20))
                .name("検証チーム").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        membership=memberships.saveAndFlush(MembershipEntity.builder().userId(editor).scopeType(ScopeType.TEAM)
                .scopeId(team).joinedAt(LocalDateTime.now()).build()).getId();
        Long admin=roles.findByName("ADMIN").map(RoleEntity::getId).orElseGet(() -> roles.saveAndFlush(
                RoleEntity.builder().name("ADMIN").displayName("ADMIN").priority(2).isSystem(true).build()).getId());
        assignment=assignments.saveAndFlush(UserRoleEntity.builder().userId(editor).roleId(admin).teamId(team).build()).getId();
    }
    @Test void existingTeamAdminMayPublishDifferentAuthorAndCapturesRecipientAuthor() {
        teamAdmin();Long id=draft(team,null);actor(editor);
        assertThat(controller.changeStatus(id,new PublishRequest("PUBLISHED",null,null),new MockHttpServletRequest())
                .getStatusCode().value()).isEqualTo(200);
        published(id);qualified(id);
    }
    @Test void inactiveAuthorDoesNotNarrowExistingEditorWritePermission() {
        teamAdmin();Long id=draft(team,null);
        var user=users.findById(author).orElseThrow();user.setStatus(UserEntity.UserStatus.FROZEN);users.saveAndFlush(user);
        actor(editor);
        assertThat(controller.changeStatus(id,new PublishRequest("PUBLISHED",null,null),new MockHttpServletRequest())
                .getStatusCode().value()).isEqualTo(200);
        published(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND first_published_at IS NULL "
                +"AND first_published_author_user_id IS NULL",Integer.class,id)).isEqualTo(1);
    }
    @Test void deniedSecondBulkPostRollsBackFirstNativeSaveAndQualification() {
        Long permitted=draft(null,null),forbidden=draft(null,null);
        jdbc.update("UPDATE blog_posts SET author_id=?,user_id=? WHERE id=?",editor,editor,forbidden);
        actor(author);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.bulkAction(new BulkActionRequest(List.of(permitted,forbidden),"PUBLISH")))
                .isInstanceOfSatisfying(com.mannschaft.app.common.BusinessException.class,error ->
                        assertThat(error.getErrorCode()).isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_002));
        for(Long id:List.of(permitted,forbidden)) {
            assertThat(jdbc.queryForObject("SELECT status FROM blog_posts WHERE id=?",String.class,id)).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND first_published_at IS NULL "
                    +"AND first_published_author_user_id IS NULL",Integer.class,id)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_ranch_outboxes WHERE recipient_user_id IN (?,?)",Integer.class,author,editor)).isZero();
    }
    @Test void inactiveAuthorStillReceivesOriginalScheduledPublicationWithoutReward() {
        Long id=draft(null,LocalDateTime.now().minusHours(1));
        var user=users.findById(author).orElseThrow();user.setStatus(UserEntity.UserStatus.FROZEN);users.saveAndFlush(user);
        assertThat(scheduled.publishScheduledPosts()).isGreaterThanOrEqualTo(1);published(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND first_published_at IS NULL "
                +"AND first_published_author_user_id IS NULL",Integer.class,id)).isEqualTo(1);
    }
    @AfterEach void cleanupOwnRows() {
        SecurityContextHolder.clearContext();
        if(author!=null) transport.deleteForUser(author);
        for(Long id:ownPosts) posts.deleteById(id);
        if(assignment!=null) assignments.deleteById(assignment);
        if(membership!=null) memberships.deleteById(membership);
        if(team!=null) teams.deleteById(team);
        if(author!=null) users.deleteById(author);
        if(editor!=null) users.deleteById(editor);
    }
}
