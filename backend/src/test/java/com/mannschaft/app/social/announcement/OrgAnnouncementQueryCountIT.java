package com.mannschaft.app.social.announcement;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261011-0035 ALIC-1 §2.7 の SQL 本数（AC-ALIC1-G01・G02・I03。試練 red）。
 *
 * <p>計測は {@link SqlIntentCounter}（application-test.yml で statement_inspector に登録済み）で、
 * API 1回（MockMvc・実 Security Filter）の全 SQL を数える。各計測の前に別組織でウォームアップを1回行い、
 * 初回だけの読み込み（roles の seed 等）を本数から外す。比較は「件数・チーム数を変えても本数差0」が主、
 * 絶対値の上限は陣立て §2.7 の表から算出した値（下記定数のコメント）で固定する。</p>
 *
 * <p>現状（red の理由）: 組織ロールを持たないチームメンバーへの MEMBERS 告知の本文プレビューは 404
 * （§1.1 ①）、read-batch API は未実装（404/405）、GUEST はチームダッシュボードで会員限定元の告知が出る
 * （M11 代案未実装）ため、前提の検査で落ちる。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ALIC-1 G/I03 宛先資格判定の SQL 本数がチーム数・件数に比例しない")
class OrgAnnouncementQueryCountIT extends AbstractMySqlIntegrationTest {

    /**
     * プレビュー1回の上限。§2.7: feed 1 + 組織ロール snapshot（定数・2）+ 閲覧者のチーム 2 + 宛先照合 3
     * + チームロール snapshot 2 + 課金 3 + 元ブログ（委任経路の metadata・本文・F00 評価 ≒ 6）= 19。
     * 認証主体の確認など共通フィルタの分として +11 を見込み 30。
     */
    static final int PREVIEW_MAX_STATEMENTS = 30;

    /**
     * read-batch 1回の上限。§2.7: feed（findAllById）1 + 組織ロール 2 + 閲覧者のチーム 2 + 宛先照合 3
     * + チームロール 2 + 課金一括 3 + 既読 ID 取得 1 + UPSERT 1 = 15。共通フィルタ分 +10 で 25。
     */
    static final int READ_BATCH_MAX_STATEMENTS = 25;

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;

    // ════════════════════════════════════ G01 ════════════════════════════════════

    @Test
    @DisplayName("AC-ALIC1-G01 プレビュー: 所属チーム1件と20件で SQL 本数差0（グループ宛て・課金ゲートあり）、上限以内")
    void g01_プレビューの本数はチーム数に比例しない() throws Exception {
        Scenario warm = scenario(1, 1);
        preview(warm, warm.feedIds().get(0));

        Scenario one = scenario(1, 1);
        Scenario twenty = scenario(20, 1);
        int small = countPreview(one);
        int large = countPreview(twenty);

        assertThat(large).as("所属1チーム: %d 本 / 20チーム: %d 本", small, large).isEqualTo(small);
        assertThat(small).as("§2.7 から算出した上限").isLessThanOrEqualTo(PREVIEW_MAX_STATEMENTS);
    }

    private int countPreview(Scenario s) throws Exception {
        flushAndClear();
        SqlIntentCounter.reset();
        String body = preview(s, s.feedIds().get(0));
        int count = SqlIntentCounter.totalCount();
        assertThat(body).as("前提: 組織ロールなしのチーム MEMBER が MEMBERS 告知の本文を委任経路で読める（A05）")
                .contains("\"accessState\":\"FULL\"").contains(BODY);
        return count;
    }

    private String preview(Scenario s, Long feedId) throws Exception {
        flushAndClear();
        String path = "/api/v1/organizations/" + s.orgId() + "/announcements/" + feedId + "/preview";
        MvcResult result = mockMvc.perform(get(path).servletPath(path).with(user(s.viewerId().toString())))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════ G02 ════════════════════════════════════

    @Test
    @DisplayName("AC-ALIC1-G02 read-batch: 1件と50件、1チームと20チームで SQL 本数差0（課金ゲートあり・グループ宛て・既読済み混在）、上限以内")
    void g02_readBatchの本数は件数とチーム数に比例しない() throws Exception {
        Scenario warm = scenario(1, 1);
        readBatch(warm, 1);

        int oneFeedOneTeam = countReadBatch(scenario(1, 1), 1);
        int fiftyFeedsOneTeam = countReadBatch(scenario(1, 48), 48);
        int oneFeedTwentyTeams = countReadBatch(scenario(20, 1), 1);

        assertThat(fiftyFeedsOneTeam).as("1件: %d 本 / 50件: %d 本", oneFeedOneTeam, fiftyFeedsOneTeam)
                .isEqualTo(oneFeedOneTeam);
        assertThat(oneFeedTwentyTeams).as("1チーム: %d 本 / 20チーム: %d 本", oneFeedOneTeam, oneFeedTwentyTeams)
                .isEqualTo(oneFeedOneTeam);
        assertThat(oneFeedOneTeam).as("§2.7 から算出した上限").isLessThanOrEqualTo(READ_BATCH_MAX_STATEMENTS);
    }

    /** 宛先内の未読 visible 件と、既読済み1件・課金 HIDDEN 1件を混ぜて送る（送信件数は visible+2）。 */
    private int countReadBatch(Scenario s, int visible) throws Exception {
        flushAndClear();
        SqlIntentCounter.reset();
        String body = readBatch(s, visible);
        int count = SqlIntentCounter.totalCount();
        assertThat(body).as("前提: 宛先内の未読だけが既読になる（既読済み・HIDDEN は数えない）")
                .contains("\"markedCount\":" + visible);
        return count;
    }

    private String readBatch(Scenario s, int visible) throws Exception {
        flushAndClear();
        List<Long> ids = new ArrayList<>(s.feedIds());
        ids.add(s.alreadyReadFeedId());
        ids.add(s.hiddenFeedId());
        assertThat(ids).hasSize(visible + 2);
        String path = "/api/v1/organizations/" + s.orgId() + "/announcements/read-batch";
        String json = "{\"feedIds\":[" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
        MvcResult result = mockMvc.perform(post(path).servletPath(path).with(user(s.viewerId().toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.markedCount").exists()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════ I03 ════════════════════════════════════

    @Test
    @DisplayName("AC-ALIC1-I03 GUEST の一覧（チームダッシュボード）: 会員限定元の告知1件と20件で SQL 本数差0、会員限定元は出ず PUBLIC 元は出る")
    void i03_GUESTの一覧の本数は件数に比例しない() throws Exception {
        GuestScenario warm = guestScenario(1);
        guestDashboard(warm);

        GuestScenario one = guestScenario(1);
        GuestScenario twenty = guestScenario(20);
        int small = countGuestDashboard(one);
        int large = countGuestDashboard(twenty);

        assertThat(large).as("会員限定元1件: %d 本 / 20件: %d 本", small, large).isEqualTo(small);
    }

    private int countGuestDashboard(GuestScenario s) throws Exception {
        flushAndClear();
        SqlIntentCounter.reset();
        String body = guestDashboard(s);
        int count = SqlIntentCounter.totalCount();
        assertThat(body).as("前提（I02）: PUBLIC 告知×PUBLIC ブログは G の一覧に出る").contains(s.publicTitle());
        assertThat(body).as("I01: PUBLIC 告知×掲示板（会員限定元）は G の一覧に出ない").doesNotContain(s.memberOnlyTitle());
        return count;
    }

    private String guestDashboard(GuestScenario s) throws Exception {
        flushAndClear();
        String path = "/api/v1/dashboard/team/" + s.teamSlug();
        MvcResult result = mockMvc.perform(get(path).servletPath(path).with(user(s.guestId().toString())))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("前提: G（チーム GUEST）が自チームのダッシュボードの一覧を開ける。"
                        + "現行は checkMembership が memberships のみを見るため 403 になる（未解決事項として上奏）")
                .isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════ fixtures ════════════════════════════════════

    private static final String BODY = "宛先資格の本数計測本文";

    record Scenario(Long orgId, Long viewerId, List<Long> feedIds, Long alreadyReadFeedId, Long hiddenFeedId) {
    }

    record GuestScenario(String teamSlug, Long guestId, String publicTitle, String memberOnlyTitle) {
    }

    /**
     * 組織 X・グループ G・X に ACTIVE 加盟し G に属するチーム teamCount 件（閲覧者はすべての MEMBER、組織ロールなし）。
     * G 宛ての MEMBERS 告知（元は組織の MEMBERS ブログ・告知に課金ゲートあり・閲覧者は支払済み）を feedCount 件、
     * 既読済みの同条件の告知1件、課金 HIDDEN の告知1件を作る。
     */
    private Scenario scenario(int teamCount, int feedCount) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Long orgId = TeamOrgFixtureHelper.insertOrganization(em, "本数計測組織", "qc-org-" + key);
        Long author = saveUser("qc-author-" + key);
        Long viewer = saveUser("qc-viewer-" + key);
        MembershipTestHelper.insertMembership(em, author, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        OrgTeamGroupEntity group = OrgTeamGroupEntity.builder().organizationId(orgId).name("東" + key).build();
        em.persist(group);
        for (int i = 0; i < teamCount; i++) {
            Long team = TeamOrgFixtureHelper.insertTeam(em, "本数計測チーム" + i, "qc-team-" + key + "-" + i);
            MembershipTestHelper.insertMembership(em, viewer, ScopeType.TEAM, team, RoleKind.MEMBER);
            em.persist(TeamOrgMembershipEntity.builder().teamId(team).organizationId(orgId)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE).groupId(group.getId())
                    .invitedAt(LocalDateTime.now().minusDays(2)).respondedAt(LocalDateTime.now().minusDays(1)).build());
        }
        BlogPostEntity blog = BlogPostEntity.builder().organizationId(orgId).authorId(author)
                .title("本数計測").slug("qc-post-" + key).body(BODY).visibility(Visibility.MEMBERS_ONLY)
                .status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(blog);
        PaymentItemEntity item = PaymentItemEntity.builder().organizationId(orgId).name("本数計測の購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        em.persist(MemberPaymentEntity.builder().userId(viewer).paymentItemId(item.getId())
                .amountPaid(BigDecimal.valueOf(100)).paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID)
                .payerUserId(viewer).payerRelationship(PayerRelationship.SELF)
                .validFrom(LocalDate.now().minusYears(1)).validUntil(LocalDate.now().plusYears(1))
                .paidAt(LocalDateTime.now()).recordedBy(author).build());

        List<Long> feeds = new ArrayList<>();
        for (int k = 0; k < feedCount; k++) {
            Long feed = groupFeed(orgId, author, blog.getId(), group.getId());
            em.persist(ContentPaymentGateEntity.builder().paymentItemId(item.getId()).contentType("ANNOUNCEMENT")
                    .contentId(feed).isTitleHidden(false).createdBy(author).build());
            feeds.add(feed);
        }
        Long alreadyRead = groupFeed(orgId, author, blog.getId(), group.getId());
        em.persist(AnnouncementReadStatusEntity.builder().announcementFeedId(alreadyRead).userId(viewer)
                .readAt(LocalDateTime.now().minusHours(1)).build());
        Long hidden = groupFeed(orgId, author, blog.getId(), group.getId());
        PaymentItemEntity hiddenItem = PaymentItemEntity.builder().organizationId(orgId).name("非表示の購読")
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(hiddenItem);
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(hiddenItem.getId()).contentType("ANNOUNCEMENT")
                .contentId(hidden).isTitleHidden(true).createdBy(author).build());
        flushAndClear();
        return new Scenario(orgId, viewer, feeds, alreadyRead, hidden);
    }

    private Long groupFeed(Long orgId, Long author, Long blogId, UUID groupId) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(orgId).sourceType(AnnouncementSourceType.BLOG_POST).sourceId(blogId).authorId(author)
                .titleCache("グループ宛て計測").excerptCache("抜粋").visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE)
                .targetGroupIds("[\"" + groupId + "\"]").includeUnassigned(false).build();
        em.persist(feed);
        return feed.getId();
    }

    /**
     * 組織 X に ACTIVE 加盟するチーム T1 と、その GUEST（user_roles の GUEST・memberships なし）。
     * T1 宛ての PUBLIC 告知×組織掲示板（会員限定元）を memberOnlyCount 件、PUBLIC 告知×PUBLIC ブログを1件。
     */
    private GuestScenario guestScenario(int memberOnlyCount) {
        String key = UUID.randomUUID().toString().substring(0, 8);
        Long orgId = TeamOrgFixtureHelper.insertOrganization(em, "GUEST計測組織", "qc-gorg-" + key);
        String teamSlug = "qc-gteam-" + key;
        Long team = TeamOrgFixtureHelper.insertTeam(em, "GUEST計測チーム", teamSlug);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, team, orgId, "ACTIVE", LocalDateTime.now().minusDays(1));
        Long author = saveUser("qc-gauthor-" + key);
        Long guest = saveUser("qc-guest-" + key);
        MembershipTestHelper.insertMembership(em, author, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, guest, "GUEST", team, null);
        String memberOnlyTitle = "会員限定元の告知" + key;
        for (int k = 0; k < memberOnlyCount; k++) {
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION).scopeId(orgId)
                    .authorId(author).title("組織掲示板" + k).body("組織掲示板本文").build();
            em.persist(thread);
            em.persist(AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgId)
                    .sourceType(AnnouncementSourceType.BULLETIN_THREAD).sourceId(thread.getId()).authorId(author)
                    .titleCache(memberOnlyTitle).excerptCache("抜粋").visibility(AnnouncementVisibility.PUBLIC)
                    .targetTeamIds("[" + team + "]").build());
        }
        String publicTitle = "公開元の告知" + key;
        BlogPostEntity blog = BlogPostEntity.builder().organizationId(orgId).authorId(author)
                .title("公開記事").slug("qc-gpost-" + key).body("公開本文").visibility(Visibility.PUBLIC)
                .status(PostStatus.PUBLISHED).postType(PostType.BLOG).build();
        em.persist(blog);
        em.persist(AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.ORGANIZATION).scopeId(orgId)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(blog.getId()).authorId(author)
                .titleCache(publicTitle).excerptCache("抜粋").visibility(AnnouncementVisibility.PUBLIC)
                .targetTeamIds("[" + team + "]").build());
        flushAndClear();
        return new GuestScenario(teamSlug, guest, publicTitle, memberOnlyTitle);
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@qc.example.com")
                .lastName("試練").firstName("本数").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
