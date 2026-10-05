package com.mannschaft.app.social.announcement;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.common.storage.acl.StorageAclEntity;
import com.mannschaft.app.common.storage.acl.StorageAclMode;
import com.mannschaft.app.common.storage.acl.StorageAclScopeType;
import com.mannschaft.app.common.storage.acl.StorageAclStatus;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F02.6 本文プレビューの試練。実 Security Filter・実 F00・実 MySQL を通す。
 * 認可と課金をモックせず、保存された元記事と最新状態をオラクルにする。
 */
@AutoConfigureMockMvc
@Transactional
// 実 S3 SDK の presign は通信を行わない。認可/ACL をモックせず、署名鍵だけを自己完結な fixture にする。
@org.springframework.test.context.TestPropertySource(properties = {
        "mannschaft.storage.endpoint=http://127.0.0.1:19002",
        "mannschaft.storage.access-key=preview-it-key",
        "mannschaft.storage.secret-key=preview-it-signing-secret",
        "mannschaft.storage.bucket=preview-it"
})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F02.6 本文プレビュー API 契約・最新認可")
class AnnouncementPreviewScopeContractIT extends AbstractMySqlIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.mannschaft.app.payment.service.PaymentGateService previewPaymentGateService;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private AnnouncementPreviewSourceService previewSourceService;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"VISIBILITY_002", "VISIBILITY_003"})
    @DisplayName("PREVIEW-09 元 Resolver の不正種別400/内部障害500を不在404にしない")
    void sourceResolver障害を404に丸めない(String code) throws Exception {
        var error = com.mannschaft.app.common.visibility.VisibilityErrorCode.valueOf(code);
        org.mockito.Mockito.doThrow(new com.mannschaft.app.common.BusinessException(error))
                .when(previewSourceService).metadata(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(memberId));
        preview(feed, memberId).andExpect(status().is("VISIBILITY_003".equals(code) ? 500 : 400))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("PREVIEW-06 対象外 source 種別は feed 認可後に400")
    void 対象外source種別は認可後に400とする() throws Exception {
        AnnouncementFeedEntity unsupported = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.TIMELINE_POST, blog.getId());
        preview(unsupported, memberId).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertHidden(unsupported, outsiderId);
    }

    @Test
    @DisplayName("PREVIEW-07 課金評価 null は HIDDEN 404 と区別して500")
    void gateNullは500とする() throws Exception {
        org.mockito.Mockito.doReturn(null).when(previewPaymentGateService).checkAccessForPreview(
                org.mockito.ArgumentMatchers.eq("ANNOUNCEMENT"), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        preview(feed, memberId).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("PREVIEW-07 課金障害は500で本文を返さない")
    void gate例外は500とする() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("試練課金障害"))
                .when(previewPaymentGateService).checkAccessForPreview(
                        org.mockito.ArgumentMatchers.eq("ANNOUNCEMENT"), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        preview(feed, memberId).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"));
    }

    @Test
    @DisplayName("PREVIEW-07 FULL 元本文の再読取で起きた課金障害も lenient 404 にしない")
    void source再評価の障害は500とする() throws Exception {
        org.mockito.Mockito.doReturn(new com.mannschaft.app.payment.dto.GateCheckResponse(true, false, java.util.List.of()))
                .doThrow(new IllegalStateException("試練元記事再読取障害"))
                .when(previewPaymentGateService).checkAccessForPreview(
                        org.mockito.ArgumentMatchers.eq("POST"), org.mockito.ArgumentMatchers.eq(blog.getId()),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        preview(feed, memberId).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_999"));
    }

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;

    // DB の日時列と同じアプリ JST 壁時計を固定し、開始/終了の等号を再現可能にする。
    @TestBean(name = "wallClock", methodName = "fixedWallClock")
    private Clock wallClock;

    static Clock fixedWallClock() {
        return Clock.fixed(Instant.parse("2026-10-04T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    }

    private Long teamId;
    private Long otherTeamId;
    private Long organizationId;
    private Long memberId;
    private Long authorId;
    private Long outsiderId;
    private BlogPostEntity blog;
    private AnnouncementFeedEntity feed;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        teamId = TeamOrgFixtureHelper.insertTeam(em, "プレビューのチーム", "preview-" + key);
        otherTeamId = TeamOrgFixtureHelper.insertTeam(em, "別チーム", "preview-other-" + key);
        organizationId = TeamOrgFixtureHelper.insertOrganization(em, "プレビューの組織", "preview-org-" + key);
        memberId = saveUser("member-" + key);
        authorId = saveUser("author-" + key);
        outsiderId = saveUser("outsider-" + key);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        blog = saveBlog(teamId, null, "最新の本文", Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED, PostType.BLOG);
        feed = saveFeed(AnnouncementScopeType.TEAM, teamId, AnnouncementSourceType.BLOG_POST, blog.getId());
        em.flush();
    }

    @Test
    @DisplayName("PREVIEW-01/08 チームのブログ本文と所有 scope を返す")
    void チームのブログ本文を返す() throws Exception {
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feedId").value(feed.getId()))
                .andExpect(jsonPath("$.data.scopeType").value("TEAM"))
                .andExpect(jsonPath("$.data.scopeId").value(teamId))
                .andExpect(jsonPath("$.data.accessState").value("FULL"))
                .andExpect(jsonPath("$.data.sourceType").value("BLOG_POST"))
                .andExpect(jsonPath("$.data.sourceId").value(blog.getId()))
                .andExpect(jsonPath("$.data.blogPost.content.body").value("最新の本文"))
                .andExpect(jsonPath("$.data.sourceUrl").value("/blog/posts/" + blog.getSlug() + "?teamId=" + teamId))
                .andExpect(jsonPath("$.data.bulletinThread").value(nullValue()))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
    }

    @Test
    @DisplayName("PREVIEW-01 組織所有ブログを組織 EP から返す")
    void 組織のブログ本文を返す() throws Exception {
        BlogPostEntity post = saveBlog(null, organizationId, "組織本文", Visibility.MEMBERS_ONLY,
                PostStatus.PUBLISHED, PostType.BLOG);
        AnnouncementFeedEntity orgFeed = saveFeed(AnnouncementScopeType.ORGANIZATION, organizationId,
                AnnouncementSourceType.BLOG_POST, post.getId());
        preview(orgFeed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeType").value("ORGANIZATION"))
                .andExpect(jsonPath("$.data.blogPost.content.body").value("組織本文"))
                .andExpect(jsonPath("$.data.sourceUrl").value("/blog/posts/" + post.getSlug()
                        + "?organizationId=" + organizationId));
    }

    @Test
    @DisplayName("PREVIEW-01 組織の postType ANNOUNCEMENT を返す")
    void 組織のブログ型お知らせ本文を返す() throws Exception {
        BlogPostEntity post = saveBlog(null, organizationId, "組織お知らせ本文", Visibility.MEMBERS_ONLY,
                PostStatus.PUBLISHED, PostType.ANNOUNCEMENT);
        AnnouncementFeedEntity item = saveFeed(AnnouncementScopeType.ORGANIZATION, organizationId,
                AnnouncementSourceType.BLOG_POST, post.getId());
        preview(item, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blogPost.meta.postType").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.data.blogPost.content.body").value("組織お知らせ本文"));
    }

    @Test
    @DisplayName("PREVIEW-01/08 組織掲示板を実 slug の元ページ URL 付きで返す")
    void 組織掲示板本文を返す() throws Exception {
        BulletinThreadEntity thread = BulletinThreadEntity.builder()
                .scopeType(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION).scopeId(organizationId)
                .authorId(authorId).title("組織掲示板").body("組織掲示板本文").build();
        em.persist(thread);
        AnnouncementFeedEntity item = saveFeed(AnnouncementScopeType.ORGANIZATION, organizationId,
                AnnouncementSourceType.BULLETIN_THREAD, thread.getId());
        preview(item, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeId").value(organizationId))
                .andExpect(jsonPath("$.data.bulletinThread.scopeId").value(organizationId))
                .andExpect(jsonPath("$.data.bulletinThread.body").value("組織掲示板本文"))
                .andExpect(jsonPath("$.data.sourceUrl", containsString("/organizations/preview-org-")))
                .andExpect(jsonPath("$.data.sourceUrl", containsString("/bulletin?threadId=" + thread.getId())));
    }

    @Test
    @DisplayName("PREVIEW-01 postType ANNOUNCEMENT も BLOG_POST として返す")
    void ブログ型お知らせも本文を返す() throws Exception {
        BlogPostEntity post = saveBlog(teamId, null, "お知らせ本文", Visibility.MEMBERS_ONLY,
                PostStatus.PUBLISHED, PostType.ANNOUNCEMENT);
        AnnouncementFeedEntity notice = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BLOG_POST, post.getId());
        preview(notice, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blogPost.meta.postType").value("ANNOUNCEMENT"));
    }

    @Test
    @DisplayName("PREVIEW-01 掲示板は flat ThreadResponse と添付配列を返す")
    void 掲示板本文を返す() throws Exception {
        BulletinThreadEntity thread = BulletinThreadEntity.builder()
                .scopeType(com.mannschaft.app.bulletin.ScopeType.TEAM).scopeId(teamId)
                .authorId(authorId).title("掲示板の最新タイトル").body("掲示板の本文").build();
        em.persist(thread);
        AnnouncementFeedEntity threadFeed = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BULLETIN_THREAD, thread.getId());
        preview(threadFeed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bulletinThread.body").value("掲示板の本文"))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
    }

    @Test
    @DisplayName("PREVIEW-15/16 正常添付を維持し危険 MIME と他 scope ACL を除外")
    void 添付の最新ACLとMIMEを検証する() throws Exception {
        BulletinThreadEntity thread = saveThread();
        BulletinAttachmentEntity safe = saveAttachment(thread, "application/pdf", teamId);
        saveAttachment(thread, "text/html", teamId);
        saveAttachment(thread, "application/pdf", otherTeamId);
        AnnouncementFeedEntity item = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BULLETIN_THREAD, thread.getId());
        preview(item, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attachments.length()").value(1))
                .andExpect(jsonPath("$.data.attachments[0].id").value(safe.getId()))
                .andExpect(jsonPath("$.data.attachments[0].contentType").value("application/pdf"));
    }

    @Test
    @DisplayName("元 detail/global/添付一覧/download は非所属を全て404に隠蔽する")
    void 元掲示板の全読取入口にも最新認可を適用する() throws Exception {
        BulletinThreadEntity thread = saveThread();
        var reply = com.mannschaft.app.bulletin.entity.BulletinReplyEntity.builder()
                .threadId(thread.getId()).authorId(authorId).body("返信の試練").build();
        em.persist(reply);
        BulletinAttachmentEntity attachment = saveAttachment(thread, "application/pdf", teamId);
        em.flush();
        for (String path : java.util.List.of(
                "/api/v1/teams/" + teamId + "/bulletin/threads/" + thread.getId(),
                "/api/v1/bulletin/threads/" + thread.getId(),
                "/api/v1/bulletin/threads/" + thread.getId() + "/attachments",
                "/api/v1/bulletin/replies/" + reply.getId() + "/attachments",
                "/api/v1/bulletin/attachments/" + attachment.getId() + "/download-url")) {
            mockMvc.perform(get(path).servletPath(path).with(user(outsiderId.toString())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
            mockMvc.perform(get(path).servletPath(path).with(user(memberId.toString())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("PREVIEW-07 feed LOCKED でも元 scope 不一致は 404")
    void feedLOCKEDでも所有scopeを検証する() throws Exception {
        BlogPostEntity other = saveBlog(otherTeamId, null, "秘密", Visibility.PUBLIC,
                PostStatus.PUBLISHED, PostType.BLOG);
        AnnouncementFeedEntity item = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BLOG_POST, other.getId());
        addGate("ANNOUNCEMENT", item.getId(), false);
        assertHidden(item, memberId);
    }

    @Test
    @DisplayName("PREVIEW-07 明示 feed HIDDEN は source FULL より優先")
    void feedHIDDENを拒否する() throws Exception {
        addGate("ANNOUNCEMENT", feed.getId(), true);
        assertHidden(feed, memberId);
    }

    private BulletinThreadEntity saveThread() {
        BulletinThreadEntity thread = BulletinThreadEntity.builder()
                .scopeType(com.mannschaft.app.bulletin.ScopeType.TEAM).scopeId(teamId)
                .authorId(authorId).title("添付の試練").body("添付本文").build();
        em.persist(thread);
        return thread;
    }

    private BulletinAttachmentEntity saveAttachment(BulletinThreadEntity thread, String mime, Long aclTeam) {
        String key = "preview/" + UUID.randomUUID();
        BulletinAttachmentEntity attachment = BulletinAttachmentEntity.builder()
                .targetType(TargetType.THREAD).targetId(thread.getId()).fileKey(key)
                .originalFilename("試練.pdf").fileSize(20L).contentType(mime).createdBy(authorId).build();
        em.persist(attachment);
        em.persist(StorageAclEntity.builder().fileKey(key).ownerId(authorId)
                .scopeType(StorageAclScopeType.TEAM).scopeKey(aclTeam.toString())
                .aclMode(StorageAclMode.CONTENT_BOUND).status(StorageAclStatus.CLAIMED)
                .contentType(mime).parentContentReferenceType("BULLETIN_THREAD")
                .parentContentReferenceKey(thread.getId().toString())
                .attachmentBindingType("BULLETIN_ATTACHMENT")
                .attachmentBindingKey(attachment.getId().toString())
                .expiresAt(wallClock.instant().plusSeconds(3600)).build());
        return attachment;
    }

    @Test
    @DisplayName("PREVIEW-03 空本文も FULL として返す")
    void 空本文をLOCKEDにしない() throws Exception {
        blog.update(blog.getTitle(), blog.getSlug(), "", null, null, Visibility.MEMBERS_ONLY,
                blog.getPriority(), (short) 0);
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("FULL"))
                .andExpect(jsonPath("$.data.blogPost.content.body").value(""));
    }

    @Test
    @DisplayName("PREVIEW-05 元の論理削除は feed の同期前でも 404")
    void 元記事の削除を最新状態で拒否する() throws Exception {
        blog.softDelete();
        em.flush();
        em.clear();
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-05 元の下書き化は古い feed でも 404")
    void 元記事の下書き化を最新状態で拒否する() throws Exception {
        blog.unpublish(LocalDateTime.now(wallClock));
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-05 元の PRIVATE 化は古い feed でも 404")
    void 元記事のPRIVATE化を最新状態で拒否する() throws Exception {
        blog.update(blog.getTitle(), blog.getSlug(), blog.getBody(), null, null, Visibility.PRIVATE,
                blog.getPriority(), (short) 0);
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-04 表示期限切れは本文を返さない")
    void 期限切れを拒否する() throws Exception {
        AnnouncementFeedEntity expired = saveFeed(feed.toBuilder().id(null)
                .expiresAt(LocalDateTime.now(wallClock).minusDays(1)).build());
        assertHidden(expired, memberId);
    }

    @Test
    @DisplayName("PREVIEW-04 表示開始前は本文を返さない")
    void 表示開始前を拒否する() throws Exception {
        AnnouncementFeedEntity future = saveFeed(feed.toBuilder().id(null)
                .startsAt(LocalDateTime.now(wallClock).plusDays(1)).build());
        assertHidden(future, memberId);
    }

    @Test
    @DisplayName("PREVIEW-06 未認証は実 Security Filter で 401")
    void 未認証は401() throws Exception {
        String path = path(feed);
        mockMvc.perform(get(path).servletPath(path)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PREVIEW-05 非公開 feed の部外者は同一 404")
    void 部外者の非公開閲覧を拒否する() throws Exception {
        assertHidden(feed, outsiderId);
    }

    @Test
    @DisplayName("PREVIEW-05/06 保存済み feed でも最新の所属解除を 404")
    void 最新の所属解除を反映する() throws Exception {
        em.createQuery("SELECT m FROM MembershipEntity m WHERE m.userId = :userId "
                        + "AND m.scopeType = :scopeType AND m.scopeId = :scopeId", MembershipEntity.class)
                .setParameter("userId", memberId).setParameter("scopeType", ScopeType.TEAM)
                .setParameter("scopeId", teamId).getResultList().forEach(em::remove);
        em.flush();
        em.clear();
        assertHidden(feed, memberId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOG_POST", "BULLETIN_THREAD"})
    @DisplayName("PREVIEW-06 準備中の所有チームはブログ/掲示板とも 404")
    void 準備中チームの本文を拒否する(String sourceType) throws Exception {
        AnnouncementFeedEntity item = feed;
        if ("BULLETIN_THREAD".equals(sourceType)) {
            BulletinThreadEntity thread = saveThread();
            item = saveFeed(AnnouncementScopeType.TEAM, teamId,
                    AnnouncementSourceType.BULLETIN_THREAD, thread.getId());
        }
        TeamEntity team = em.find(TeamEntity.class, teamId);
        em.merge(team.toBuilder().lifecycleStatus(TeamEntity.LifecycleStatus.PROVISIONED).build());
        assertHidden(item, memberId);
    }

    @Test
    @DisplayName("PREVIEW-05 feed の URL scope 差替えは同一 404")
    void 別scopeのURLを拒否する() throws Exception {
        String path = "/api/v1/teams/" + otherTeamId + "/announcements/" + feed.getId() + "/preview";
        mockMvc.perform(get(path).servletPath(path).with(user(memberId.toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
    }

    @Test
    @DisplayName("PREVIEW-05 feed と元記事の所有 scope 不一致は同一 404")
    void 元記事とのscope不一致を拒否する() throws Exception {
        BlogPostEntity other = saveBlog(otherTeamId, null, "他テナント秘密", Visibility.PUBLIC,
                PostStatus.PUBLISHED, PostType.BLOG);
        AnnouncementFeedEntity mismatch = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BLOG_POST, other.getId());
        assertHidden(mismatch, memberId);
    }

    @Test
    @DisplayName("PREVIEW-06 PUBLIC は認証済み部外者にも既存ラダーどおり許可")
    void PUBLICの既存許可を維持する() throws Exception {
        BlogPostEntity post = saveBlog(teamId, null, "公開本文", Visibility.PUBLIC,
                PostStatus.PUBLISHED, PostType.BLOG);
        AnnouncementFeedEntity publicFeed = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BLOG_POST, post.getId());
        publicFeed.updateVisibility(AnnouncementVisibility.PUBLIC);
        preview(publicFeed, outsiderId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blogPost.content.body").value("公開本文"));
    }

    @Test
    @DisplayName("PREVIEW-07 feed titleOnly は LOCKED・参照と本文は明示 null")
    void feedLOCKEDは本文と参照を秘匿する() throws Exception {
        addGate("ANNOUNCEMENT", feed.getId(), false);
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                .andExpect(jsonPath("$.data.sourceType").value(nullValue()))
                .andExpect(jsonPath("$.data.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.data.sourceUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()))
                .andExpect(jsonPath("$.data.bulletinThread").value(nullValue()))
                .andExpect(jsonPath("$.data.attachments").isEmpty());
    }

    @Test
    @DisplayName("PREVIEW-07 元記事 titleOnly も LOCKED")
    void 元記事LOCKEDも本文を秘匿する() throws Exception {
        addGate("POST", blog.getId(), false);
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()));
    }

    @Test
    @DisplayName("PREVIEW-07 feed/source 両 LOCKED も参照秘匿")
    void 両LOCKEDは本文を秘匿する() throws Exception {
        addGate("ANNOUNCEMENT", feed.getId(), false);
        addGate("POST", blog.getId(), false);
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                .andExpect(jsonPath("$.data.sourceId").value(nullValue()))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()));
    }

    @Test
    @DisplayName("PREVIEW-07 source HIDDEN は feed FULL でも 404")
    void 元HIDDENをfeedFULLで迂回しない() throws Exception {
        addGate("POST", blog.getId(), true);
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-07 ADMIN の既存 feed LOCKED 許可を継承")
    void ADMINはfeedLOCKEDを閲覧できる() throws Exception {
        Long admin = saveUser("admin-" + UUID.randomUUID());
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamId, null);
        addGate("ANNOUNCEMENT", feed.getId(), false);
        preview(feed, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("FULL"));
    }

    @Test
    @DisplayName("PREVIEW-07 ADMIN でも元ブログ LOCKED は既存 CMS と同じ")
    void ADMINでも元ブログLOCKEDを迂回しない() throws Exception {
        Long admin = saveUser("admin-" + UUID.randomUUID());
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamId, null);
        addGate("POST", blog.getId(), false);
        preview(feed, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("LOCKED"))
                .andExpect(jsonPath("$.data.blogPost").value(nullValue()));
    }

    @Test
    @DisplayName("PREVIEW-07 SYSTEM_ADMIN でも明示 source HIDDEN は 404")
    void SYSTEM_ADMINでも明示HIDDENを迂回しない() throws Exception {
        Long admin = saveUser("system-" + UUID.randomUUID());
        MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
        addGate("POST", blog.getId(), true);
        assertHidden(feed, admin);
    }

    @Test
    @DisplayName("PREVIEW-07 実支払記録が有効なら source LOCKED から FULL")
    void 支払済み本文を返す() throws Exception {
        PaymentItemEntity item = addGate("POST", blog.getId(), false);
        em.persist(MemberPaymentEntity.builder().userId(memberId).paymentItemId(item.getId())
                .amountPaid(BigDecimal.valueOf(100)).paymentMethod(PaymentMethod.CASH)
                .status(PaymentStatus.PAID).payerUserId(memberId).payerRelationship(PayerRelationship.SELF)
                .validFrom(java.time.LocalDate.now().minusYears(1))
                .validUntil(java.time.LocalDate.now().plusYears(1))
                .paidAt(LocalDateTime.now(wallClock)).recordedBy(authorId).build());
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessState").value("FULL"))
                .andExpect(jsonPath("$.data.blogPost.content.body").value("最新の本文"));
    }

    @Test
    @DisplayName("PREVIEW-07 元記事 HIDDEN が feed LOCKED より優先する")
    void 元HIDDENをfeedLOCKEDで迂回しない() throws Exception {
        addGate("ANNOUNCEMENT", feed.getId(), false);
        addGate("POST", blog.getId(), true);
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-07 feed LOCKED でも元削除を確認する")
    void feedLOCKEDでも元削除を拒否する() throws Exception {
        addGate("ANNOUNCEMENT", feed.getId(), false);
        blog.softDelete();
        em.flush();
        em.clear();
        assertHidden(feed, memberId);
    }

    @Test
    @DisplayName("PREVIEW-11 プレビュー GET は既読と閲覧数を更新しない")
    void GETの業務副作用がない() throws Exception {
        long before = countReads();
        preview(feed, memberId).andExpect(status().isOk());
        em.flush();
        em.clear();
        assertThat(countReads()).isEqualTo(before);
        assertThat(em.find(BlogPostEntity.class, blog.getId()).getViewCount()).isZero();
    }

    @Test
    @DisplayName("PREVIEW-16 本文応答は private no-store")
    void 本文を共有キャッシュさせない() throws Exception {
        preview(feed, memberId).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
                .andExpect(header().string("Cache-Control", containsString("max-age=0")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }

    @Test
    @DisplayName("PREVIEW-04 JST 表示開始と同時刻は FULL")
    void 表示開始の等号を含む() throws Exception {
        AnnouncementFeedEntity boundary = saveFeed(feed.toBuilder().id(null)
                .startsAt(LocalDateTime.now(wallClock)).build());
        preview(boundary, memberId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-04 JST 表示終了と同時刻は 404")
    void 表示終了の等号を含まない() throws Exception {
        AnnouncementFeedEntity boundary = saveFeed(feed.toBuilder().id(null)
                .expiresAt(LocalDateTime.now(wallClock)).build());
        assertHidden(boundary, memberId);
    }

    @Test
    @DisplayName("PREVIEW-05 直属組織メンバーは宛先チーム指定でも対象")
    void 直属組織メンバーを配信対象から除外しない() throws Exception {
        AnnouncementFeedEntity item = audienceFeed("[" + otherTeamId + "]", null, false);
        preview(item, memberId).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN"})
    @DisplayName("PREVIEW-05/06 権限ロール割当だけの直属組織管理者も宛先対象")
    void 管理者の権限ロールから直属所属を補完する(String roleName) throws Exception {
        Long viewer = saveUser("org-role-" + UUID.randomUUID());
        MembershipTestHelper.insertUserRole(em, viewer, roleName, null, organizationId);
        AnnouncementFeedEntity item = audienceFeed("[" + otherTeamId + "]", null, false);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 対象チームの ACTIVE 配下所属を許可")
    void 指定チームのACTIVE所属を許可する() throws Exception {
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed("[" + teamId + "]", null, false);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 宛先から外れた最新 feed は再取得で 404")
    void 最新の配信対象解除を反映する() throws Exception {
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed("[" + teamId + "]", null, false);
        preview(item, viewer).andExpect(status().isOk());
        em.merge(item.toBuilder().targetTeamIds("[" + otherTeamId + "]").build());
        em.flush();
        em.clear();
        assertHidden(item, viewer);
    }

    @Test
    @DisplayName("PREVIEW-05 対象チームでも PENDING 配下所属は対象外")
    void PENDING配下を拒否する() throws Exception {
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.PENDING);
        AnnouncementFeedEntity item = audienceFeed("[" + teamId + "]", null, false);
        assertHidden(item, viewer);
    }

    @Test
    @DisplayName("PREVIEW-05 グループ宛先は現在の ACTIVE 所属で判定")
    void 生存グループの現在所属を許可する() throws Exception {
        UUID group = saveGroup(false);
        Long viewer = childViewer(group, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed(null, "[\"" + group + "\"]", false);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 生存グループで未該当なら古い snapshot でも対象外")
    void 生存グループで古いsnapshotを流用しない() throws Exception {
        UUID group = saveGroup(false);
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed(null, "[\"" + group + "\"]", false);
        saveSnapshot(item, group);
        assertHidden(item, viewer);
    }

    @Test
    @DisplayName("PREVIEW-05 削除グループは送信時 snapshot と現在 ACTIVE の AND")
    void 削除グループのsnapshotを許可する() throws Exception {
        UUID group = saveGroup(true);
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed(null, "[\"" + group + "\"]", false);
        saveSnapshot(item, group);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 削除グループの snapshot だけでは PENDING を許可しない")
    void 削除グループでも現在ACTIVEを要求する() throws Exception {
        UUID group = saveGroup(true);
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.PENDING);
        AnnouncementFeedEntity item = audienceFeed(null, "[\"" + group + "\"]", false);
        saveSnapshot(item, group);
        assertHidden(item, viewer);
    }

    @Test
    @DisplayName("PREVIEW-05 includeUnassigned は未所属グループを対象とする")
    void 未分類チームを許可する() throws Exception {
        Long viewer = childViewer(null, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed(null, "[]", true);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 includeUnassigned は削除グループ残存参照も未分類")
    void 削除グループ参照を未分類と扱う() throws Exception {
        UUID group = saveGroup(true);
        Long viewer = childViewer(group, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed(null, "[]", true);
        preview(item, viewer).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PREVIEW-05 複数宛先はチーム OR グループ OR 未分類")
    void 宛先条件をORで合成する() throws Exception {
        UUID group = saveGroup(false);
        Long viewer = childViewer(group, TeamOrgMembershipEntity.Status.ACTIVE);
        AnnouncementFeedEntity item = audienceFeed("[" + otherTeamId + "]", "[\"" + group + "\"]", false);
        preview(item, viewer).andExpect(status().isOk());
    }

    private AnnouncementFeedEntity audienceFeed(String teams, String groups, boolean unassigned) {
        BlogPostEntity source = saveBlog(null, organizationId, "配信対象の本文", Visibility.PUBLIC,
                PostStatus.PUBLISHED, PostType.BLOG);
        return saveFeed(AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(organizationId).sourceType(AnnouncementSourceType.BLOG_POST).sourceId(source.getId())
                .authorId(authorId).titleCache("配信対象").visibility(AnnouncementVisibility.PUBLIC)
                .targetTeamIds(teams).targetGroupIds(groups).includeUnassigned(unassigned).build());
    }

    private Long childViewer(UUID group, TeamOrgMembershipEntity.Status status) {
        Long viewer = saveUser("child-" + UUID.randomUUID().toString().substring(0, 8));
        MembershipTestHelper.insertMembership(em, viewer, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.persist(TeamOrgMembershipEntity.builder().teamId(teamId).organizationId(organizationId)
                .status(status).groupId(group).invitedAt(LocalDateTime.now(wallClock)).build());
        return viewer;
    }

    private UUID saveGroup(boolean deleted) {
        OrgTeamGroupEntity group = OrgTeamGroupEntity.builder().organizationId(organizationId)
                .name("試練グループ" + UUID.randomUUID().toString().substring(0, 8)).build();
        em.persist(group);
        if (deleted) group.softDelete(wallClock.instant(), authorId);
        return group.getId();
    }

    private void saveSnapshot(AnnouncementFeedEntity item, UUID group) {
        em.persist(AnnouncementFeedGroupSnapshotEntity.builder().feedId(item.getId())
                .groupId(group.toString()).teamId(teamId).build());
    }

    @Test
    @DisplayName("PREVIEW-18 一覧に本文プレビュー可否を追加する")
    void 一覧の対象種別にpreview可否を返す() throws Exception {
        String path = "/api/v1/teams/" + teamId + "/announcements";
        mockMvc.perform(get(path).servletPath(path).with(user(memberId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contentPreviewAvailable").value(true));
    }

    private ResultActions preview(AnnouncementFeedEntity item, Long viewerId) throws Exception {
        em.flush();
        String path = path(item);
        return mockMvc.perform(get(path).servletPath(path).with(user(viewerId.toString())));
    }

    private String path(AnnouncementFeedEntity item) {
        String scope = item.getScopeType() == AnnouncementScopeType.TEAM ? "teams" : "organizations";
        return "/api/v1/" + scope + "/" + item.getScopeId() + "/announcements/" + item.getId() + "/preview";
    }

    private void assertHidden(AnnouncementFeedEntity item, Long viewerId) throws Exception {
        preview(item, viewerId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ANNOUNCE_001"));
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@preview.example.com")
                .lastName("試練").firstName("利用者").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }

    private BlogPostEntity saveBlog(Long team, Long organization, String body, Visibility visibility,
                                    PostStatus status, PostType type) {
        BlogPostEntity post = BlogPostEntity.builder().teamId(team).organizationId(organization)
                .authorId(authorId).title("最新のタイトル").slug("post-" + UUID.randomUUID())
                .body(body).visibility(visibility).status(status).postType(type).build();
        em.persist(post);
        return post;
    }

    private AnnouncementFeedEntity saveFeed(AnnouncementScopeType scope, Long scopeId,
                                            AnnouncementSourceType type, Long sourceId) {
        return saveFeed(AnnouncementFeedEntity.builder().scopeType(scope).scopeId(scopeId)
                .sourceType(type).sourceId(sourceId).authorId(authorId).titleCache("古いキャッシュ")
                .excerptCache("本文ではない古い抜粋").visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build());
    }

    private AnnouncementFeedEntity saveFeed(AnnouncementFeedEntity item) {
        em.persist(item);
        return item;
    }

    private PaymentItemEntity addGate(String type, Long id, boolean titleHidden) {
        PaymentItemEntity item = PaymentItemEntity.builder().teamId(teamId).name("本文購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(item.getId()).contentType(type)
                .contentId(id).isTitleHidden(titleHidden).createdBy(authorId).build());
        return item;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LOCKED", "HIDDEN", "FULL"})
    @DisplayName("PREVIEW-07 CUSTOM 元記事の実課金は LOCKED/HIDDEN/支払済 FULL を区別")
    void CUSTOM元記事の課金状態を合成する(String state) throws Exception {
        BlogPostEntity custom = saveBlog(teamId, null, "購入済み秘密本文", Visibility.CUSTOM,
                PostStatus.PUBLISHED, PostType.BLOG);
        AnnouncementFeedEntity item = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementSourceType.BLOG_POST, custom.getId());
        PaymentItemEntity payment = addGate("POST", custom.getId(), "HIDDEN".equals(state));
        if ("FULL".equals(state)) {
            em.persist(MemberPaymentEntity.builder().userId(memberId).paymentItemId(payment.getId())
                    .amountPaid(BigDecimal.valueOf(100)).paymentMethod(PaymentMethod.CASH)
                    .status(PaymentStatus.PAID).payerUserId(memberId).payerRelationship(PayerRelationship.SELF)
                    .validFrom(java.time.LocalDate.now().minusYears(1))
                    .validUntil(java.time.LocalDate.now().plusYears(1))
                    .paidAt(LocalDateTime.now(wallClock)).recordedBy(authorId).build());
        }
        if ("HIDDEN".equals(state)) assertHidden(item, memberId);
        else {
            ResultActions result = preview(item, memberId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessState").value(state));
            if ("FULL".equals(state)) result.andExpect(jsonPath("$.data.blogPost.content.body").value("購入済み秘密本文"));
            else result.andExpect(jsonPath("$.data.blogPost").value(nullValue()));
        }
    }

    private long countReads() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status").getSingleResult()).longValue();
    }
}
