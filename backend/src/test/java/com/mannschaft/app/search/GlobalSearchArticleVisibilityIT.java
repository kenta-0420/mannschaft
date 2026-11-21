package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索 ARTICLE（ブログ記事 {@code blog_posts}）種別の受け入れテスト（CMP-261116-0722 PR-5 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性（台帳 D4 / 設計書 §7 ARTICLE）</b>: 次の<b>すべて</b>を満たす記事だけが出る。</p>
 * <ol>
 *   <li>{@code deleted_at IS NULL} かつ {@code status = 'PUBLISHED'} かつ {@code published_at <= 現在}
 *       （{@code PostStatus} の DRAFT / PENDING_REVIEW / PENDING_SELF_REVIEW / REJECTED / ARCHIVED と予約中は
 *       作成者本人にも出ない）。</li>
 *   <li>チーム記事（{@code team_id}）または組織記事（{@code organization_id}）。個人ブログ
 *       （{@code user_id} / {@code social_profile_id}）は公開でも対象外。</li>
 *   <li><b>所属経路</b>: 記事のスコープが集約側 {@link SearchViewer} の母集団（SUPPORTER 以上・GUEST のみ除外・
 *       削除/アーカイブ除外）にあり、そのスコープでの閲覧者ロールについて
 *       {@code MembershipBatchQueryService#resolveVisibleLevels} と同じ段（{@code CmsVisibilityMapper} の写像）
 *       に記事の visibility が入る。<b>スコープごとに</b>判定し、全所属先ロールの和集合では判定しない（AC-X1）:
 *     <ul>
 *       <li>PUBLIC / SCOPE_AFFILIATED / SUPPORTERS_AND_ABOVE → SUPPORTER 以上</li>
 *       <li>MEMBERS_ONLY / MEMBERS_AND_ABOVE（どちらも MEMBERS_AND_ABOVE）→ MEMBER 以上</li>
 *       <li>ADMINS_AND_ABOVE → ADMIN のみ（DEPUTY_ADMIN は不可。F00 の hasRoleOrAbove(ADMIN) と同じ）</li>
 *       <li>PRIVATE / FOLLOWERS_ONLY / CUSTOM_TEMPLATE / CUSTOM → 作成者・ADMIN を含め誰にも出さない
 *           （行ごとの判定が要り件数と結果の一致を保てないため今回対象外）</li>
 *     </ul></li>
 *   <li><b>公開経路（非所属者）</b>: visibility = PUBLIC かつ {@code public_visible = TRUE} かつ記事のスコープ
 *       （チーム／組織）が visibility = PUBLIC・lifecycle ACTIVE・未アーカイブ・未削除。既存の未ログイン公開ページ
 *       （{@code PublicPostQueryService} → {@code TeamRepository#findPublicTeamById} /
 *       {@code OrganizationRepository#findPublicOrganizationById} + {@code BlogPostRepository#findPublicPostsByTeamId}）
 *       と sitemap（{@code findAllPublicPostsByTeam}: 非公開チーム配下の PUBLIC 記事を載せない理由を明記）と同じ
 *       条件に揃える。所属者には {@code public_visible} に関係なく出る（通常ビューは変わらない、という列の定義）。</li>
 *   <li><b>ペイウォール</b>: {@code content_payment_gates}（content_type = {@code ContentGateType.POST}）が付いた記事は、
 *       記事を開くときと同じ正準判定（{@code PaymentGateService#checkAccessBatch} の scope 束縛版。受益者キー・全ゲート
 *       AND・PAID かつ {@code valid_until + grace_period_days >= 今日}・記事と同スコープの有効な非 DONATION 項目）を満たす
 *       閲覧者にだけ、結果にも件数にも出す。満たさない閲覧者には、{@code is_title_hidden=FALSE} の「鍵付きタイトル」も
 *       含めて件数ごと出さない（本文由来の snippet を返すため）。SYSTEM_ADMIN もバイパスしない。</li>
 * </ol>
 *
 * <p><b>SYSTEM_ADMIN</b> はバイパスしない（D5）。<b>スコープ指定時</b>はそのスコープ（所属確認済み）の記事だけを返し、
 * 他スコープの公開記事は混ぜない（AC-A3 の存在オラクル封じ）。</p>
 *
 * <p><b>FULLTEXT</b>: 本番 V6.002 の {@code ft_bp_title_body (title, body) WITH PARSER ngram} をテストスキーマにも補う
 * （{@code test-schema-fulltext-indexes.sql} と本クラスの {@link #ensureFulltextIndex()}）。InnoDB の FULLTEXT は
 * コミット済みの行だけを拾うため、fixture は基底の {@link #inTx} で commit する。キーワードは ngram の
 * ストップワードを含まない子音だけで作る。</p>
 *
 * <p><b>遷移先</b>: FE {@code frontend/app/utils/blogPostRoute.ts} の {@code blogPostReadRoute} と同じ
 * {@code /blog/posts/{encodeURIComponent(slug)}?teamId={id}} / {@code ?organizationId={id}}。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 ARTICLE 種別の認可境界（CMP-261116-0722 PR-5 試練）")
class GlobalSearchArticleVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String ARTICLE = "ARTICLE";
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdArticleIds = new ArrayList<>();
    private final List<Long> createdPaymentItemIds = new ArrayList<>();
    private final List<Long> anchoredTeamIds = new ArrayList<>();

    @BeforeEach
    void prepare() {
        ensureFulltextIndex();
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUpArticles() {
        inTx(() -> {
            for (Long id : createdArticleIds) {
                em.createNativeQuery("DELETE FROM content_payment_gates WHERE content_type = :t AND content_id = :id")
                        .setParameter("t", ContentGateType.POST).setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM blog_posts WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : createdPaymentItemIds) {
                em.createNativeQuery("DELETE FROM member_payments WHERE payment_item_id = :id").setParameter("id", id)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM content_payment_gates WHERE payment_item_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM payment_items WHERE id = :id").setParameter("id", id)
                        .executeUpdate();
            }
            for (Long teamId : anchoredTeamIds) {
                em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id")
                        .setParameter("id", teamId).executeUpdate();
            }
            return null;
        });
        createdArticleIds.clear();
        createdPaymentItemIds.clear();
        anchoredTeamIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2 / A7: スコープの境界と公開記事
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界・公開記事（A1 / A2 / A7）")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(ARTICLE): チーム X のメンバー限定記事は X 非所属者の結果にも typeCounts.ARTICLE にも meta.total にも出ない（X の所属者には出る）")
        void AC_A1_ARTICLE_非所属チームのメンバー限定記事は出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long article = teamArticle(teamX, insider, Visibility.MEMBERS_ONLY, kw + " チームXの記事", "本文");

            assertNothingVisible(search(outsider, kw), ARTICLE);
            JsonNode outsiderDetail = search(outsider, kw, ARTICLE);
            assertNothingVisible(outsiderDetail, ARTICLE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), ARTICLE, article, 1);
            JsonNode insiderDetail = search(insider, kw, ARTICLE);
            assertVisible(insiderDetail, ARTICLE, article, 1);
            assertThat(insiderDetail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A1(ARTICLE): 公開チームでも PUBLIC 以外の記事（SCOPE_AFFILIATED / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE）は非所属者に出ない。本文だけの一致も同じ境界")
        void AC_A1_ARTICLE_公開チームの非公開記事は出ない() throws Exception {
            Long team = publicTeam("XP-" + kw);
            Long insider = viewer("a1p-insider");
            teamMember(insider, team);
            Long outsider = viewer("a1p-outsider");
            Long affiliated = teamArticle(team, insider, Visibility.SCOPE_AFFILIATED, "無関係な題", "本文に " + kw);
            Long supporters = teamArticle(team, insider, Visibility.SUPPORTERS_AND_ABOVE, kw + " 応援者以上", "本文");
            Long members = teamArticle(team, insider, Visibility.MEMBERS_AND_ABOVE, kw + " メンバー以上", "本文");

            assertNothingVisible(search(outsider, kw), ARTICLE);
            assertNothingVisible(search(outsider, kw, ARTICLE), ARTICLE);

            JsonNode own = search(insider, kw, ARTICLE);
            assertThat(resultIds(own, ARTICLE)).containsExactlyInAnyOrder(affiliated, supporters, members);
            assertThat(typeCount(own, ARTICLE)).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-A2(ARTICLE): 組織 O の非公開記事は別組織ユーザーにも、O 配下チームだけの所属者にも出ない（O の所属者には出る）")
        void AC_A2_ARTICLE_別組織の記事は出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long orgP = privateOrg("P-" + kw);
            Long childTeam = privateTeam("OC-" + kw);
            anchorTeam(childTeam, orgO);
            Long insider = viewer("a2-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("a2-outsider");
            orgMember(outsider, orgP);
            Long childOnly = viewer("a2-child");
            teamMember(childOnly, childTeam);
            Long a1 = orgArticle(orgO, insider, Visibility.MEMBERS_ONLY, kw + " 組織の記事1", "本文");
            Long a2 = orgArticle(orgO, insider, Visibility.SCOPE_AFFILIATED, kw + " 組織の記事2", "本文");

            for (Long viewer : List.of(outsider, childOnly)) {
                assertNothingVisible(search(viewer, kw), ARTICLE);
                JsonNode detail = search(viewer, kw, ARTICLE);
                assertNothingVisible(detail, ARTICLE);
                assertThat(detail.path("meta").path("total").asLong()).isZero();
            }

            JsonNode insiderBody = search(insider, kw, ARTICLE);
            assertThat(resultIds(insiderBody, ARTICLE)).containsExactlyInAnyOrder(a1, a2);
            assertThat(typeCount(insiderBody, ARTICLE)).isEqualTo(2);
            assertThat(insiderBody.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A7(ARTICLE): 公開チーム・公開組織の PUBLIC 記事は、どこにも所属しないユーザーにも結果・件数ともに出る")
        void AC_A7_ARTICLE_公開記事は非所属者にも出る() throws Exception {
            Long team = publicTeam("A7T-" + kw);
            Long org = publicOrg("A7O-" + kw);
            Long author = viewer("a7-author");
            teamMember(author, team);
            orgMember(author, org);
            Long nobody = viewer("a7-nobody");
            Long teamPublic = teamArticle(team, author, Visibility.PUBLIC, kw + " チーム公開記事", "本文");
            Long orgPublic = orgArticle(org, author, Visibility.PUBLIC, kw + " 組織公開記事", "本文");

            JsonNode overview = search(nobody, kw);
            assertThat(resultIds(overview, ARTICLE)).containsExactlyInAnyOrder(teamPublic, orgPublic);
            assertThat(typeCount(overview, ARTICLE)).isEqualTo(2);
            JsonNode detail = search(nobody, kw, ARTICLE);
            assertThat(resultIds(detail, ARTICLE)).containsExactlyInAnyOrder(teamPublic, orgPublic);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A7(ARTICLE): 非公開チーム／非公開組織配下の PUBLIC 記事は非所属者に出ない（公開ページ・sitemap と同じく親スコープの公開性を見る）。所属者には出る")
        void AC_A7_ARTICLE_非公開スコープ配下のPUBLIC記事は非所属者に出ない() throws Exception {
            Long team = privateTeam("A7PT-" + kw);
            Long org = privateOrg("A7PO-" + kw);
            Long member = viewer("a7p-member");
            teamMember(member, team);
            orgMember(member, org);
            Long outsider = viewer("a7p-outsider");
            Long t = teamArticle(team, member, Visibility.PUBLIC, kw + " 非公開チームのPUBLIC", "本文");
            Long o = orgArticle(org, member, Visibility.PUBLIC, kw + " 非公開組織のPUBLIC", "本文");

            assertNothingVisible(search(outsider, kw), ARTICLE);
            JsonNode outsiderDetail = search(outsider, kw, ARTICLE);
            assertNothingVisible(outsiderDetail, ARTICLE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            JsonNode own = search(member, kw, ARTICLE);
            assertThat(resultIds(own, ARTICLE)).containsExactlyInAnyOrder(t, o);
            assertThat(typeCount(own, ARTICLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A7(ARTICLE): public_visible=false の PUBLIC 記事は非所属者に出ない（公開ページから外す列）。所属者の通常ビューには出る")
        void AC_A7_ARTICLE_公開表示オフは非所属者に出ない() throws Exception {
            Long team = publicTeam("A7V-" + kw);
            Long member = viewer("a7v-member");
            teamMember(member, team);
            Long outsider = viewer("a7v-outsider");
            Long hidden = teamArticle(team, member, Visibility.PUBLIC, kw + " 公開表示オフ", "本文");
            nativeUpdate("UPDATE blog_posts SET public_visible = FALSE WHERE id = :id", hidden);
            Long shown = teamArticle(team, member, Visibility.PUBLIC, kw + " 公開表示オン", "本文");

            JsonNode outsiderBody = search(outsider, kw, ARTICLE);
            assertThat(resultIds(outsiderBody, ARTICLE)).containsExactly(shown);
            assertThat(typeCount(outsiderBody, ARTICLE)).isEqualTo(1);
            assertThat(outsiderBody.path("meta").path("total").asLong()).isEqualTo(1);

            JsonNode own = search(member, kw, ARTICLE);
            assertThat(resultIds(own, ARTICLE)).containsExactlyInAnyOrder(hidden, shown);
            assertThat(typeCount(own, ARTICLE)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A7(ARTICLE): 公開記事は重複しない（所属者が自チームの PUBLIC 記事を検索しても所属経路と公開経路で二重に数えない）")
        void AC_A7_ARTICLE_所属者の公開記事は二重計上しない() throws Exception {
            Long team = publicTeam("A7D-" + kw);
            Long member = viewer("a7d-member");
            teamMember(member, team);
            Long a = teamArticle(team, member, Visibility.PUBLIC, kw + " 自チーム公開", "本文");

            JsonNode overview = search(member, kw);
            assertThat(resultIds(overview, ARTICLE)).containsExactly(a);
            assertThat(typeCount(overview, ARTICLE)).isEqualTo(1);
            JsonNode detail = search(member, kw, ARTICLE);
            assertThat(resultIds(detail, ARTICLE)).containsExactly(a);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームのメンバー限定・管理者限定・非公開記事は出ない（公開チームの PUBLIC 記事は一般利用者と同じく出る）")
        void SYSTEM_ADMINはバイパスしない() throws Exception {
            Long team = privateTeam("SA-" + kw);
            Long pubTeam = publicTeam("SAP-" + kw);
            Long author = viewer("sa-author");
            teamMember(author, team);
            teamMember(author, pubTeam);
            Long admin = viewer("sysadmin");
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " メンバー限定", "本文");
            teamArticle(team, author, Visibility.ADMINS_AND_ABOVE, kw + " 管理者限定", "本文");
            teamArticle(team, author, Visibility.PRIVATE, kw + " 非公開", "本文");
            teamArticle(team, author, Visibility.PUBLIC, kw + " 非公開チームのPUBLIC", "本文");
            Long open = teamArticle(pubTeam, author, Visibility.PUBLIC, kw + " 公開", "本文");

            JsonNode detail = search(admin, kw, ARTICLE);
            assertThat(resultIds(detail, ARTICLE)).containsExactly(open);
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
            assertThat(typeCount(search(admin, kw), ARTICLE)).isEqualTo(1);
        }

        @Test
        @DisplayName("authorId 指定でも不可視の記事は出ない（可視な同一著者の記事だけが出る）")
        void authorId指定でも不可視は出ない() throws Exception {
            Long foreign = privateTeam("AF-" + kw);
            Long mine = privateTeam("AM-" + kw);
            Long author = viewer("au-author");
            teamMember(author, foreign);
            teamMember(author, mine);
            Long me = viewer("au-me");
            teamMember(me, mine);
            teamArticle(foreign, author, Visibility.MEMBERS_ONLY, kw + " 他チーム", "本文");
            teamArticle(mine, author, Visibility.ADMINS_AND_ABOVE, kw + " 自チーム管理者限定", "本文");
            teamArticle(mine, author, Visibility.PRIVATE, kw + " 自チーム非公開", "本文");
            Long visible = teamArticle(mine, author, Visibility.MEMBERS_ONLY, kw + " 自チーム", "本文");
            Long other = viewer("au-other");
            teamMember(other, mine);
            teamArticle(mine, other, Visibility.MEMBERS_ONLY, kw + " 別著者", "本文");

            JsonNode body = search(me, Map.of("q", kw, "type", ARTICLE, "authorId", author.toString()));
            assertThat(resultIds(body, ARTICLE)).containsExactly(visible);
            assertThat(typeCount(body, ARTICLE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-X1 / AC-R2: 可視レベルはスコープごとのロールで判定する
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("可視レベル（X1 / R2）")
    class VisibilityLevels {

        @Test
        @DisplayName("AC-X1: スコープ X で ADMIN・スコープ Y で SUPPORTER のユーザーに、Y の ADMINS_AND_ABOVE / MEMBERS_ONLY / MEMBERS_AND_ABOVE 記事は結果にも件数にも出ない（X の同等記事と Y の SUPPORTERS_AND_ABOVE 記事は出る）")
        void AC_X1_ロールは全所属先の和集合で判定しない() throws Exception {
            Long teamX = privateTeam("X1X-" + kw);
            Long teamY = privateTeam("X1Y-" + kw);
            Long author = viewer("x1-author");
            teamMember(author, teamX);
            teamMember(author, teamY);
            Long me = viewer("x1-me");
            teamMember(me, teamX);
            teamAdmin(me, teamX);
            teamSupporter(me, teamY);
            Long xAdmins = teamArticle(teamX, author, Visibility.ADMINS_AND_ABOVE, kw + " X管理者", "本文");
            Long xMembers = teamArticle(teamX, author, Visibility.MEMBERS_ONLY, kw + " Xメンバー", "本文");
            Long xMembersNew = teamArticle(teamX, author, Visibility.MEMBERS_AND_ABOVE, kw + " Xメンバー新", "本文");
            Long yAdmins = teamArticle(teamY, author, Visibility.ADMINS_AND_ABOVE, kw + " Y管理者", "本文");
            Long yMembers = teamArticle(teamY, author, Visibility.MEMBERS_ONLY, kw + " Yメンバー", "本文");
            Long yMembersNew = teamArticle(teamY, author, Visibility.MEMBERS_AND_ABOVE, kw + " Yメンバー新", "本文");
            Long ySupporters = teamArticle(teamY, author, Visibility.SUPPORTERS_AND_ABOVE, kw + " Y応援者", "本文");

            JsonNode detail = search(me, Map.of("q", kw, "type", ARTICLE, "size", "50"));
            assertThat(resultIds(detail, ARTICLE))
                    .containsExactlyInAnyOrder(xAdmins, xMembers, xMembersNew, ySupporters)
                    .doesNotContain(yAdmins, yMembers, yMembersNew);
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(4);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(4);
            assertThat(typeCount(search(me, kw), ARTICLE)).isEqualTo(4);
        }

        @Test
        @DisplayName("AC-X1: 組織 X で ADMIN・チーム Y で SUPPORTER のユーザーにも、Y の管理者限定・メンバー限定記事は出ない（種別をまたいでも和集合にしない）")
        void AC_X1_組織ADMINとチームSUPPORTER() throws Exception {
            Long orgX = privateOrg("X1OX-" + kw);
            Long teamY = privateTeam("X1TY-" + kw);
            Long author = viewer("x1o-author");
            orgMember(author, orgX);
            teamMember(author, teamY);
            Long me = viewer("x1o-me");
            orgMember(me, orgX);
            orgAdmin(me, orgX);
            teamSupporter(me, teamY);
            Long xAdmins = orgArticle(orgX, author, Visibility.ADMINS_AND_ABOVE, kw + " 組織X管理者", "本文");
            teamArticle(teamY, author, Visibility.ADMINS_AND_ABOVE, kw + " チームY管理者", "本文");
            teamArticle(teamY, author, Visibility.MEMBERS_ONLY, kw + " チームYメンバー", "本文");

            JsonNode detail = search(me, kw, ARTICLE);
            assertThat(resultIds(detail, ARTICLE)).containsExactly(xAdmins);
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-R2: ロール別の可視範囲（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER）。ADMINS_AND_ABOVE は ADMIN だけ、MEMBERS_ONLY は SUPPORTER に出ない。PRIVATE / FOLLOWERS_ONLY / CUSTOM_TEMPLATE / CUSTOM は作成者・ADMIN にも出ない")
        void AC_R2_ロール別の可視範囲() throws Exception {
            Long team = privateTeam("R2-" + kw);
            Long admin = viewer("r2-admin");
            teamMember(admin, team);
            teamAdmin(admin, team);
            Long deputy = viewer("r2-deputy");
            teamMember(deputy, team);
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, deputy, "DEPUTY_ADMIN", team, null);
                return null;
            });
            Long member = viewer("r2-member");
            teamMember(member, team);
            Long supporter = viewer("r2-supporter");
            teamSupporter(supporter, team);

            // 作成者は ADMIN 本人（PRIVATE 等が作成者にも出ないことを同時に固定する）
            Long pub = teamArticle(team, admin, Visibility.PUBLIC, kw + " PUBLIC", "本文");
            Long affiliated = teamArticle(team, admin, Visibility.SCOPE_AFFILIATED, kw + " 所属者", "本文");
            Long supporters = teamArticle(team, admin, Visibility.SUPPORTERS_AND_ABOVE, kw + " 応援者以上", "本文");
            Long membersOnly = teamArticle(team, admin, Visibility.MEMBERS_ONLY, kw + " メンバー限定", "本文");
            Long membersAbove = teamArticle(team, admin, Visibility.MEMBERS_AND_ABOVE, kw + " メンバー以上", "本文");
            Long admins = teamArticle(team, admin, Visibility.ADMINS_AND_ABOVE, kw + " 管理者限定", "本文");
            Set<Long> never = new HashSet<>();
            never.add(teamArticle(team, admin, Visibility.PRIVATE, kw + " 非公開", "本文"));
            never.add(teamArticle(team, admin, Visibility.FOLLOWERS_ONLY, kw + " フォロワー", "本文"));
            never.add(teamArticle(team, admin, Visibility.CUSTOM_TEMPLATE, kw + " テンプレート", "本文"));
            never.add(teamArticle(team, admin, Visibility.CUSTOM, kw + " ペイウォール", "本文"));

            assertExactlyVisible(admin, never, pub, affiliated, supporters, membersOnly, membersAbove, admins);
            assertExactlyVisible(deputy, never, pub, affiliated, supporters, membersOnly, membersAbove);
            assertExactlyVisible(member, never, pub, affiliated, supporters, membersOnly, membersAbove);
            assertExactlyVisible(supporter, never, pub, affiliated, supporters);
        }

        @Test
        @DisplayName("AC-R2: 組織記事でも同じ。ADMINS_AND_ABOVE は組織 MEMBER に出ず組織 ADMIN に出る。MEMBERS_ONLY は組織 SUPPORTER に出ない")
        void AC_R2_組織記事のロール別可視範囲() throws Exception {
            Long org = privateOrg("R2O-" + kw);
            Long admin = viewer("r2o-admin");
            orgMember(admin, org);
            orgAdmin(admin, org);
            Long member = viewer("r2o-member");
            orgMember(member, org);
            Long supporter = viewer("r2o-supporter");
            inTx(() -> {
                MembershipTestHelper.insertMembership(em, supporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER);
                return null;
            });
            Long admins = orgArticle(org, member, Visibility.ADMINS_AND_ABOVE, kw + " 組織管理者限定", "本文");
            Long membersOnly = orgArticle(org, member, Visibility.MEMBERS_ONLY, kw + " 組織メンバー限定", "本文");
            Long supporters = orgArticle(org, member, Visibility.SUPPORTERS_AND_ABOVE, kw + " 組織応援者以上", "本文");

            assertExactlyVisible(admin, Set.of(), admins, membersOnly, supporters);
            assertExactlyVisible(member, Set.of(admins), membersOnly, supporters);
            assertExactlyVisible(supporter, Set.of(admins, membersOnly), supporters);
        }

        @Test
        @DisplayName("AC-R2: 個人ブログの記事（user_id / social_profile_id）は PUBLIC・PUBLISHED でも作成者本人にも他人にも出ない")
        void AC_R2_個人ブログは出ない() throws Exception {
            Long team = privateTeam("R2P-" + kw);
            Long me = viewer("r2p-me");
            teamMember(me, team);
            Long other = viewer("r2p-other");
            teamMember(other, team);
            articleOf(null, null, me, null, me, Visibility.PUBLIC, PostStatus.PUBLISHED, past(),
                    kw + " 個人ブログ", "本文", uniqueSlug());
            articleOf(null, null, me, 1L, me, Visibility.PUBLIC, PostStatus.PUBLISHED, past(),
                    kw + " ソーシャルプロフィールのブログ", "本文", uniqueSlug());
            Long visible = teamArticle(team, me, Visibility.MEMBERS_ONLY, kw + " チーム", "本文");

            for (Long viewer : List.of(me, other)) {
                JsonNode detail = search(viewer, kw, ARTICLE);
                assertThat(resultIds(detail, ARTICLE)).containsExactly(visible);
                assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("ペイウォール: 課金ゲート付き記事（タイトル秘匿・タイトル可視とも）は未払いのメンバーに結果・件数ともに出ない（ゲートなしは出る。鍵付きタイトルだけの表示も検索では行わない）")
        void ペイウォール付き記事は未払い者に出ない() throws Exception {
            Long team = privateTeam("PW-" + kw);
            Long author = viewer("pw-author");
            teamMember(author, team);
            Long me = viewer("pw-me");
            teamMember(me, team);
            Long hiddenTitle = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 有料タイトル秘匿", "有料本文");
            Long visibleTitle = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 有料タイトル可視", "有料本文");
            Long free = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 無料", "本文");
            gate(team, hiddenTitle, true);
            gate(team, visibleTitle, false);

            JsonNode detail = search(me, kw, ARTICLE);
            assertThat(resultIds(detail, ARTICLE)).containsExactly(free);
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
            assertThat(typeCount(search(me, kw), ARTICLE)).isEqualTo(1);
        }

        private void assertExactlyVisible(Long viewer, Set<Long> neverVisible, Long... expected) throws Exception {
            JsonNode detail = search(viewer, Map.of("q", kw, "type", ARTICLE, "size", "50"));
            assertThat(resultIds(detail, ARTICLE)).as("viewer=%s の結果", viewer)
                    .containsExactlyInAnyOrder(expected);
            // AssertJ の doesNotContainAnyElementsOf は空集合を渡すと IllegalArgumentException になるため、
            // 不可視の指定があるときだけ確かめる（containsExactlyInAnyOrder が既に余分な ID を許さない）
            if (!neverVisible.isEmpty()) {
                assertThat(resultIds(detail, ARTICLE)).as("viewer=%s の結果（不可視）", viewer)
                        .doesNotContainAnyElementsOf(neverVisible);
            }
            assertThat(typeCount(detail, ARTICLE)).as("viewer=%s の typeCounts", viewer).isEqualTo(expected.length);
            assertThat(detail.path("meta").path("total").asLong()).as("viewer=%s の meta.total", viewer)
                    .isEqualTo(expected.length);
            assertThat(typeCount(search(viewer, kw), ARTICLE)).as("viewer=%s の概要 typeCounts", viewer)
                    .isEqualTo(expected.length);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ペイウォール: 支払い条件を満たした閲覧者にだけ出す（CMP-261116-0722 PR-5 改: マスター裁定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ペイウォール解錠（支払い条件を満たした閲覧者にだけ出す）")
    class PaywallUnlock {

        @Test
        @DisplayName("有効な支払い（本人・PAID・期限内）のあるメンバーには、課金ゲート付き記事（タイトル秘匿・可視とも）が結果にも typeCounts にも meta.total にも出る")
        void 支払い済みメンバーには出る() throws Exception {
            Long team = privateTeam("PU-" + kw);
            Long author = viewer("pu-author");
            teamMember(author, team);
            Long paid = viewer("pu-paid");
            teamMember(paid, team);
            Long unpaid = viewer("pu-unpaid");
            teamMember(unpaid, team);
            Long hiddenTitle = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 有料タイトル秘匿", "有料本文");
            Long visibleTitle = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 有料タイトル可視", "有料本文");
            Long free = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 無料", "本文");
            Long item1 = gate(team, hiddenTitle, true);
            Long item2 = gate(team, visibleTitle, false);
            payment(paid, item1, "PAID", null, null);
            payment(paid, item2, "PAID", 30, null);

            assertExactly(paid, free, hiddenTitle, visibleTitle);
            assertExactly(unpaid, free);
            assertExactly(author, free);
        }

        @Test
        @DisplayName("期限切れ（valid_until + 猶予日数 < 今日）・PENDING・REFUNDED・CANCELLED の支払いでは出ない。猶予期間内は出る")
        void 期限切れや未確定の支払いでは出ない() throws Exception {
            Long team = privateTeam("PE-" + kw);
            Long author = viewer("pe-author");
            teamMember(author, team);
            Long expired = viewer("pe-expired");
            teamMember(expired, team);
            Long pending = viewer("pe-pending");
            teamMember(pending, team);
            Long refunded = viewer("pe-refunded");
            teamMember(refunded, team);
            Long cancelled = viewer("pe-cancelled");
            teamMember(cancelled, team);
            Long inGrace = viewer("pe-grace");
            teamMember(inGrace, team);
            Long article = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 期限付き有料", "有料本文");
            Long free = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 無料", "本文");
            Long item = gate(team, article, false);
            jdbc.update("UPDATE payment_items SET grace_period_days = 3 WHERE id = ?", item);
            payment(expired, item, "PAID", -4, null);
            payment(pending, item, "PENDING", 30, null);
            payment(refunded, item, "REFUNDED", 30, null);
            payment(cancelled, item, "CANCELLED", 30, null);
            payment(inGrace, item, "PAID", -2, null);

            for (Long v : List.of(expired, pending, refunded, cancelled)) {
                assertExactly(v, free);
            }
            assertExactly(inGrace, free, article);
        }

        @Test
        @DisplayName("支払い項目が複数のゲートはすべて支払って初めて出る（AND）。1つだけの支払いでは出ない")
        void 複数ゲートはすべて支払いが必要() throws Exception {
            Long team = privateTeam("PM-" + kw);
            Long author = viewer("pm-author");
            teamMember(author, team);
            Long both = viewer("pm-both");
            teamMember(both, team);
            Long one = viewer("pm-one");
            teamMember(one, team);
            Long article = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 二重ゲート", "有料本文");
            Long item1 = gate(team, article, false);
            Long item2 = gate(team, article, false);
            payment(both, item1, "PAID", null, null);
            payment(both, item2, "PAID", null, null);
            payment(one, item1, "PAID", null, null);

            assertExactly(both, article);
            assertExactly(one);
        }

        @Test
        @DisplayName("他スコープの支払い項目だけを支払った閲覧者には出ない（記事と別チームの項目で組んだ不整合ゲートは、その項目を支払っても解錠しない）")
        void 他スコープの支払い項目では出ない() throws Exception {
            Long team = privateTeam("PS-" + kw);
            Long other = privateTeam("PSO-" + kw);
            Long author = viewer("ps-author");
            teamMember(author, team);
            Long me = viewer("ps-me");
            teamMember(me, team);
            teamMember(me, other);
            Long proper = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 自チーム項目のゲート", "有料本文");
            Long mismatched = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 他チーム項目のゲート", "有料本文");
            gate(team, proper, false);
            Long otherItem = gate(other, mismatched, false);
            // 他チーム Y の項目を支払っても、自チームの項目のゲートは解錠しない
            Long otherItem2 = item(other);
            payment(me, otherItem2, "PAID", null, null);
            // 記事と別スコープの項目で組まれたゲートは、その項目を支払っても解錠しない
            payment(me, otherItem, "PAID", null, null);

            assertExactly(me);
        }

        @Test
        @DisplayName("受益者キー: 他人が受益者の支払い（自分が払い手）では出ない。自分が受益者なら払い手が別人でも出る")
        void 受益者キーで判定する() throws Exception {
            Long team = privateTeam("PB-" + kw);
            Long author = viewer("pb-author");
            teamMember(author, team);
            Long payer = viewer("pb-payer");
            teamMember(payer, team);
            Long beneficiary = viewer("pb-beneficiary");
            teamMember(beneficiary, team);
            Long article = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 受益者キー", "有料本文");
            Long item = gate(team, article, false);
            payment(beneficiary, item, "PAID", null, payer);

            assertExactly(beneficiary, article);
            assertExactly(payer);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN はバイパスしない: 公開チームの PUBLIC な有料記事も、未払いなら出ない（支払えば一般利用者と同じく出る）")
        void SYSTEM_ADMINも未払いなら出ない() throws Exception {
            Long team = publicTeam("PA-" + kw);
            Long author = viewer("pa-author");
            teamMember(author, team);
            Long admin = viewer("pa-sysadmin");
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            Long nobodyPaid = viewer("pa-nobody-paid");
            Long gated = teamArticle(team, author, Visibility.PUBLIC, kw + " 公開の有料記事", "有料本文");
            Long open = teamArticle(team, author, Visibility.PUBLIC, kw + " 公開の無料記事", "本文");
            Long item = gate(team, gated, false);
            payment(nobodyPaid, item, "PAID", null, null);

            assertExactly(admin, open);
            assertExactly(nobodyPaid, open, gated);
        }

        private void assertExactly(Long viewer, Long... expected) throws Exception {
            JsonNode detail = search(viewer, Map.of("q", kw, "type", ARTICLE, "size", "50"));
            assertThat(resultIds(detail, ARTICLE)).as("viewer=%s の結果", viewer).containsExactlyInAnyOrder(expected);
            assertThat(typeCount(detail, ARTICLE)).as("viewer=%s の typeCounts", viewer).isEqualTo(expected.length);
            assertThat(detail.path("meta").path("total").asLong()).as("viewer=%s の meta.total", viewer)
                    .isEqualTo(expected.length);
            JsonNode overview = search(viewer, kw);
            assertThat(resultIds(overview, ARTICLE)).as("viewer=%s の概要結果", viewer)
                    .containsExactlyInAnyOrder(expected);
            assertThat(typeCount(overview, ARTICLE)).as("viewer=%s の概要 typeCounts", viewer)
                    .isEqualTo(expected.length);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-R1: 公開状態
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("公開状態（R1）")
    class PublicationStatus {

        @ParameterizedTest(name = "status={0} の記事は作成者本人（ADMIN）にも他のメンバーにも、公開チームの PUBLIC でも非所属者にも出ない")
        @EnumSource(value = PostStatus.class, names = {"DRAFT", "PENDING_REVIEW", "PENDING_SELF_REVIEW", "REJECTED", "ARCHIVED"})
        @DisplayName("AC-R1: PUBLISHED 以外は誰にも出ない")
        void AC_R1_非PUBLISHEDは誰にも出ない(PostStatus status) throws Exception {
            Long team = publicTeam("R1-" + kw);
            Long author = viewer("r1-author");
            teamMember(author, team);
            teamAdmin(author, team);
            Long member = viewer("r1-member");
            teamMember(member, team);
            Long outsider = viewer("r1-outsider");
            articleOf(team, null, null, null, author, Visibility.MEMBERS_ONLY, status, past(),
                    kw + " メンバー限定 " + status, "本文", uniqueSlug());
            articleOf(team, null, null, null, author, Visibility.PUBLIC, status, past(),
                    kw + " 公開 " + status, "本文", uniqueSlug());
            Long published = teamArticle(team, author, Visibility.MEMBERS_ONLY, kw + " 公開済み", "本文");

            for (Long viewer : List.of(author, member)) {
                JsonNode detail = search(viewer, kw, ARTICLE);
                assertThat(resultIds(detail, ARTICLE)).as("viewer=%s", viewer).containsExactly(published);
                assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
            }
            assertNothingVisible(search(outsider, kw), ARTICLE);
            assertNothingVisible(search(outsider, kw, ARTICLE), ARTICLE);
        }

        @Test
        @DisplayName("AC-R1: 公開日時前（予約中の DRAFT・published_at が未来の PUBLISHED）は作成者本人にも出ない。2時間後でも出ない（時差9時間の取り違えを検出）、2時間前の公開記事は出る")
        void AC_R1_公開日時前は出ない() throws Exception {
            Long team = publicTeam("R1F-" + kw);
            Long author = viewer("r1f-author");
            teamMember(author, team);
            teamAdmin(author, team);
            Long outsider = viewer("r1f-outsider");
            LocalDateTime now = LocalDateTime.now(SERVER_ZONE);
            articleOf(team, null, null, null, author, Visibility.MEMBERS_ONLY, PostStatus.DRAFT, now.plusDays(1),
                    kw + " 予約中", "本文", uniqueSlug());
            articleOf(team, null, null, null, author, Visibility.PUBLIC, PostStatus.PUBLISHED, now.plusDays(1),
                    kw + " 未来公開 翌日", "本文", uniqueSlug());
            articleOf(team, null, null, null, author, Visibility.PUBLIC, PostStatus.PUBLISHED, now.plusHours(2),
                    kw + " 未来公開 2時間後", "本文", uniqueSlug());
            Long recent = articleOf(team, null, null, null, author, Visibility.PUBLIC, PostStatus.PUBLISHED,
                    now.minusHours(2), kw + " 2時間前に公開", "本文", uniqueSlug());

            for (Long viewer : List.of(author, outsider)) {
                JsonNode detail = search(viewer, kw, ARTICLE);
                assertThat(resultIds(detail, ARTICLE)).as("viewer=%s", viewer).containsExactly(recent);
                assertThat(typeCount(detail, ARTICLE)).isEqualTo(1);
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(ARTICLE): 非所属スコープ（公開チームで PUBLIC 記事あり）指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）。自スコープ指定ではそのスコープの記事だけが出る")
    void AC_A3_ARTICLE_存在オラクルなし() throws Exception {
        Long foreign = publicTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        teamArticle(foreign, foreignAuthor, Visibility.PUBLIC, kw + " 他所の公開記事", "本文");
        teamArticle(foreign, foreignAuthor, Visibility.MEMBERS_ONLY, kw + " 他所の限定記事", "本文");
        Long mine = privateTeam("M-" + kw);
        Long myOrg = privateOrg("MO-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        orgMember(me, myOrg);
        Long mineArticle = teamArticle(mine, me, Visibility.MEMBERS_ONLY, kw + " 自チームの記事", "本文");
        Long orgArticle = orgArticle(myOrg, me, Visibility.MEMBERS_ONLY, kw + " 自組織の記事", "本文");

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", ARTICLE, "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", ARTICLE, "scopeType", "TEAM",
                "scopeId", "987654321987"));

        for (String t : ALL_TYPES) {
            assertThat(typeCount(foreignScope, t)).as("非所属スコープ %s", t).isZero();
        }
        assertThat(foreignScope.path("data").path("results")).isEmpty();
        assertThat(foreignScope.path("data")).isEqualTo(missingScope.path("data"));
        assertThat(foreignScope.path("meta")).isEqualTo(missingScope.path("meta"));
        assertThat(foreignScope.path("error")).isEqualTo(missingScope.path("error"));
        assertThat(foreignDetail.path("data")).isEqualTo(missingDetail.path("data"));
        assertThat(foreignDetail.path("meta")).isEqualTo(missingDetail.path("meta"));
        assertThat(foreignDetail.path("error")).isEqualTo(missingDetail.path("error"));
        assertThat(typeCount(foreignDetail, ARTICLE)).isZero();
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", ARTICLE, "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, ARTICLE)).as("スコープ指定時はそのスコープの記事だけ（他所の公開記事も混ぜない）")
                .containsExactly(mineArticle);
        assertThat(resultIds(ownScope, ARTICLE)).doesNotContain(orgArticle);
        assertThat(typeCount(ownScope, ARTICLE)).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);

        JsonNode unscoped = search(me, kw, ARTICLE);
        assertThat(typeCount(unscoped, ARTICLE)).as("スコープ指定なしなら自所属2件＋他所の公開1件").isEqualTo(3);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(ARTICLE): GUEST ロールしか持たないスコープの記事（SCOPE_AFFILIATED を含む）は出ない。公開チームの PUBLIC 記事だけは非所属者と同じ扱いで出る（AC-A7 と整合）")
    void AC_A4_ARTICLE_GUESTのみのスコープは母集団外() throws Exception {
        Long team = publicTeam("G-" + kw);
        Long org = privateOrg("GO-" + kw);
        Long author = viewer("a4-author");
        teamMember(author, team);
        orgMember(author, org);
        Long guest = viewer("a4-guest");
        teamGuest(guest, team);
        orgGuest(guest, org);
        Long member = viewer("a4-member");
        teamMember(member, team);
        Long affiliated = teamArticle(team, author, Visibility.SCOPE_AFFILIATED, kw + " 所属者向け", "本文");
        Long supporters = teamArticle(team, author, Visibility.SUPPORTERS_AND_ABOVE, kw + " 応援者以上", "本文");
        orgArticle(org, author, Visibility.SCOPE_AFFILIATED, kw + " 組織の所属者向け", "本文");
        orgArticle(org, author, Visibility.PUBLIC, kw + " 非公開組織のPUBLIC", "本文");
        Long open = teamArticle(team, author, Visibility.PUBLIC, kw + " 公開記事", "本文");

        JsonNode guestDetail = search(guest, kw, ARTICLE);
        assertThat(resultIds(guestDetail, ARTICLE)).containsExactly(open);
        assertThat(typeCount(guestDetail, ARTICLE)).isEqualTo(1);
        assertThat(guestDetail.path("meta").path("total").asLong()).isEqualTo(1);
        assertThat(typeCount(search(guest, kw), ARTICLE)).isEqualTo(1);

        JsonNode memberDetail = search(member, kw, ARTICLE);
        assertThat(resultIds(memberDetail, ARTICLE)).containsExactlyInAnyOrder(affiliated, supporters, open);
        assertThat(typeCount(memberDetail, ARTICLE)).isEqualTo(3);
    }

    @Nested
    @DisplayName("論理削除・アーカイブ（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(ARTICLE): 論理削除済み記事は所属者にも作成者本人にも、公開記事でも非所属者にも出ない")
        void AC_A5_ARTICLE_削除済み記事は出ない() throws Exception {
            Long team = publicTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            Long outsider = viewer("a5-outsider");
            Long alive = teamArticle(team, me, Visibility.PUBLIC, kw + " 生存", "本文");
            Long deleted = teamArticle(team, me, Visibility.PUBLIC, kw + " 削除済み", "本文");
            Long deletedMembers = teamArticle(team, me, Visibility.MEMBERS_ONLY, kw + " 削除済み限定", "本文");
            nativeUpdate("UPDATE blog_posts SET deleted_at = NOW() WHERE id = :id", deleted);
            nativeUpdate("UPDATE blog_posts SET deleted_at = NOW() WHERE id = :id", deletedMembers);

            for (Long viewer : List.of(me, outsider)) {
                JsonNode body = search(viewer, kw, ARTICLE);
                assertThat(resultIds(body, ARTICLE)).as("viewer=%s", viewer).containsExactly(alive);
                assertThat(typeCount(body, ARTICLE)).isEqualTo(1);
                assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
                assertThat(typeCount(search(viewer, kw), ARTICLE)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("AC-A5(ARTICLE): 論理削除・アーカイブ済み・未稼働（PROVISIONED）の公開チーム／公開組織の記事は、所属者にも非所属者にも（PUBLIC 記事でも）出ない")
        void AC_A5_ARTICLE_削除済みスコープは出ない() throws Exception {
            Long deletedTeam = publicTeam("DT-" + kw);
            Long archivedTeam = publicTeam("AT-" + kw);
            Long provisionedTeam = publicTeam("PT-" + kw);
            Long deletedOrg = publicOrg("DO-" + kw);
            Long archivedOrg = publicOrg("AO-" + kw);
            Long liveTeam = publicTeam("LT-" + kw);
            Long me = viewer("a5s-me");
            teamMember(me, deletedTeam);
            teamMember(me, archivedTeam);
            teamMember(me, provisionedTeam);
            orgMember(me, deletedOrg);
            orgMember(me, archivedOrg);
            teamMember(me, liveTeam);
            Long outsider = viewer("a5s-outsider");
            for (Long t : List.of(deletedTeam, archivedTeam, provisionedTeam)) {
                teamArticle(t, me, Visibility.PUBLIC, kw + " 公開 " + t, "本文");
                teamArticle(t, me, Visibility.MEMBERS_ONLY, kw + " 限定 " + t, "本文");
            }
            for (Long o : List.of(deletedOrg, archivedOrg)) {
                orgArticle(o, me, Visibility.PUBLIC, kw + " 組織公開 " + o, "本文");
                orgArticle(o, me, Visibility.MEMBERS_ONLY, kw + " 組織限定 " + o, "本文");
            }
            Long live = teamArticle(liveTeam, me, Visibility.PUBLIC, kw + " 生存チーム", "本文");
            softDeleteTeam(deletedTeam);
            archiveTeam(archivedTeam);
            provisionTeam(provisionedTeam);
            softDeleteOrg(deletedOrg);
            archiveOrg(archivedOrg);

            for (Long viewer : List.of(me, outsider)) {
                JsonNode body = search(viewer, Map.of("q", kw, "type", ARTICLE, "size", "50"));
                assertThat(resultIds(body, ARTICLE)).as("viewer=%s", viewer).containsExactly(live);
                assertThat(typeCount(body, ARTICLE)).isEqualTo(1);
                assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
                assertThat(typeCount(search(viewer, kw), ARTICLE)).isEqualTo(1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A6 / AC-C8: 件数
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数（A6 / C8）")
    class Counting {

        @Test
        @DisplayName("AC-A6(ARTICLE): 不可視の記事（他チーム・非公開・管理者限定・下書き・予約・削除・個人ブログ・非公開チームの PUBLIC）を足しても件数は変わらず、meta.total と typeCounts.ARTICLE が一致する")
        void AC_A6_ARTICLE_不可視を足しても件数不変() throws Exception {
            Long foreign = privateTeam("A6F-" + kw);
            Long mine = privateTeam("A6M-" + kw);
            Long me = viewer("a6-me");
            teamMember(me, mine);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            teamArticle(mine, me, Visibility.MEMBERS_ONLY, kw + " 1", "本文");
            teamArticle(mine, stranger, Visibility.SUPPORTERS_AND_ABOVE, kw + " 2", "本文");

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, ARTICLE)).isEqualTo(2);

            teamArticle(foreign, stranger, Visibility.MEMBERS_ONLY, kw + " 他チーム", "本文");
            teamArticle(foreign, stranger, Visibility.PUBLIC, kw + " 非公開チームのPUBLIC", "本文");
            teamArticle(mine, stranger, Visibility.PRIVATE, kw + " 非公開", "本文");
            teamArticle(mine, stranger, Visibility.ADMINS_AND_ABOVE, kw + " 管理者限定", "本文");
            articleOf(mine, null, null, null, stranger, Visibility.MEMBERS_ONLY, PostStatus.DRAFT, null,
                    kw + " 下書き", "本文", uniqueSlug());
            articleOf(mine, null, null, null, stranger, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED,
                    LocalDateTime.now(SERVER_ZONE).plusDays(1), kw + " 予約", "本文", uniqueSlug());
            nativeUpdate("UPDATE blog_posts SET deleted_at = NOW() WHERE id = :id",
                    teamArticle(mine, stranger, Visibility.MEMBERS_ONLY, kw + " 削除", "本文"));
            articleOf(null, null, stranger, null, stranger, Visibility.PUBLIC, PostStatus.PUBLISHED, past(),
                    kw + " 個人", "本文", uniqueSlug());

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, ARTICLE);
            assertThat(typeCount(after, ARTICLE)).isEqualTo(typeCount(before, ARTICLE));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, ARTICLE));
            assertThat(typeCount(detail, ARTICLE)).isEqualTo(typeCount(after, ARTICLE));
            assertThat(resultIds(detail, ARTICLE)).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(ARTICLE): 可視11件・不可視3件 → typeCounts.ARTICLE=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を過不足なく返す")
        void AC_C8_ARTICLE_真の件数とページ送り() throws Exception {
            Long mine = privateTeam("C8M-" + kw);
            Long myOrg = privateOrg("C8O-" + kw);
            Long foreign = privateTeam("C8F-" + kw);
            Long pub = publicTeam("C8P-" + kw);
            Long me = viewer("c8-me");
            teamMember(me, mine);
            orgMember(me, myOrg);
            Long stranger = viewer("c8-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            teamMember(stranger, pub);
            Set<Long> visible = new HashSet<>();
            for (int i = 0; i < 7; i++) {
                visible.add(teamArticle(mine, i % 2 == 0 ? me : stranger, Visibility.MEMBERS_ONLY,
                        kw + " チーム" + i, "本文"));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(orgArticle(myOrg, me, Visibility.SCOPE_AFFILIATED, kw + " 組織" + i, "本文"));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(teamArticle(pub, stranger, Visibility.PUBLIC, kw + " 公開" + i, "本文"));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(teamArticle(foreign, stranger, Visibility.MEMBERS_ONLY, kw + " 他チーム", "本文"));
            invisible.add(teamArticle(mine, stranger, Visibility.ADMINS_AND_ABOVE, kw + " 管理者限定", "本文"));
            invisible.add(teamArticle(pub, stranger, Visibility.MEMBERS_ONLY, kw + " 公開チームの限定", "本文"));

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, ARTICLE)).isEqualTo(11);
            assertThat(resultIds(overview, ARTICLE)).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", ARTICLE, "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, ARTICLE)).isEqualTo(11);
                all.addAll(resultIds(detail, ARTICLE));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", ARTICLE, "size", "50"));
            assertThat(resultIds(big, ARTICLE)).hasSize(11).doesNotContainAnyElementsOf(invisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / URL / createdAt
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープ・遷移先・作成日時")
    class EscapingUrlAndTime {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視の記事は混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(ARTICLE): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならない")
        void AC_C11_ARTICLE_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long mine = privateTeam("C11M-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            Long me = viewer("c11-me");
            teamMember(me, mine);
            Long invisible = teamArticle(foreign, stranger, Visibility.MEMBERS_ONLY, op + kw + op + " 他チーム",
                    op + kw + op);
            Long visible = teamArticle(mine, me, Visibility.MEMBERS_ONLY, op + kw + op + " 自チーム", op + kw + op);

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", ARTICLE));
                assertThat(resultIds(body, ARTICLE)).doesNotContain(invisible);
                assertThat(typeCount(body, ARTICLE)).isLessThanOrEqualTo(1);
                assertThat(body.path("data").path("timedOutTypes").toString()).doesNotContain(ARTICLE);
            }
            assertVisible(search(me, kw), ARTICLE, visible, 1);
        }

        @Test
        @DisplayName("AC-C12(ARTICLE): title はプレーン（<mark> なし・生のまま）で highlights.title が一致位置を指す。snippet は本文由来で HTML エスケープされ一致箇所だけが <mark>、200文字以下")
        void AC_C12_ARTICLE_titleとsnippet() throws Exception {
            Long team = privateTeam("C12-" + kw);
            Long me = viewer("c12-me");
            teamMember(me, team);
            String title = "<i>" + kw + "</i> 記事";
            String body = "<script>alert(1)</script> 前文 " + kw + " 本文<b>太字</b>" + "あ".repeat(300);
            Long id = teamArticle(team, me, Visibility.MEMBERS_ONLY, title, body);

            JsonNode hit = hitOf(search(me, kw, ARTICLE), id);
            assertThat(hit.path("title").asText()).isEqualTo(title).doesNotContain("<mark>");
            JsonNode range = hit.path("highlights").path("title");
            assertThat(range.isArray()).as("highlights.title に [start, end]").isTrue();
            assertThat(title.substring(range.get(0).asInt(), range.get(1).asInt())).isEqualToIgnoringCase(kw);

            String snippet = hit.path("snippet").asText();
            assertThat(snippet).contains("<mark>" + kw + "</mark>");
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<")
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(snippet.split("<mark>", -1)).as("<mark> は一致箇所の1か所だけ").hasSize(2);
        }

        @Test
        @DisplayName("ARTICLE の結果（チーム）は type=ARTICLE・url=/blog/posts/{slug}?teamId={id}（blogPostRoute.ts 準拠）、scope はチーム")
        void URLはチーム記事の閲覧ルート() throws Exception {
            Long team = privateTeam("URL-" + kw);
            Long me = viewer("url-me");
            teamMember(me, team);
            String slug = "gs-" + kw + "-" + UUID.randomUUID().toString().substring(0, 6);
            Long id = articleOf(team, null, null, null, me, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED, past(),
                    kw + " 遷移先", "本文", slug);

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo(ARTICLE);
            assertThat(hit.path("url").asText()).isEqualTo("/blog/posts/" + slug + "?teamId=" + team);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("TEAM");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(team);
            assertThat(typeCount(overview, ARTICLE)).as("ARTICLE の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }

        @Test
        @DisplayName("ARTICLE の結果（組織・日本語 slug）は url=/blog/posts/{encodeURIComponent(slug)}?organizationId={id}")
        void URLは組織記事の閲覧ルート() throws Exception {
            Long org = privateOrg("URLO-" + kw);
            Long me = viewer("urlo-me");
            orgMember(me, org);
            String slug = "組織の記事-" + kw;
            Long id = articleOf(null, org, null, null, me, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED, past(),
                    kw + " 組織遷移先", "本文", slug);
            String encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");

            JsonNode hit = hitOf(search(me, kw, ARTICLE), id);
            assertThat(hit.path("url").asText()).isEqualTo("/blog/posts/" + encoded + "?organizationId=" + org);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("ORGANIZATION");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(org);
        }

        @Test
        @DisplayName("createdAt: DB の UTC 格納値 00:00 の記事の createdAt は同一瞬間（JST 09:00+09:00）")
        void createdAtは同一瞬間() throws Exception {
            Long team = privateTeam("CA-" + kw);
            Long me = viewer("ca-me");
            teamMember(me, team);
            Long id = teamArticle(team, me, Visibility.MEMBERS_ONLY, kw + " 作成日時", "本文");
            jdbc.update("UPDATE blog_posts SET created_at = ? WHERE id = ?", "2026-03-10 00:00:00", id);

            JsonNode hit = hitOf(search(me, kw, ARTICLE), id);
            String text = hit.path("createdAt").asText();
            assertThat(text).as("createdAt が出ていること（%s）", hit).isNotBlank();
            assertThat(OffsetDateTime.parse(text).toInstant()).isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private void teamAdmin(Long userId, Long teamId) {
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
            return null;
        });
    }

    private void orgAdmin(Long userId, Long orgId) {
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, orgId);
            return null;
        });
    }

    private void teamSupporter(Long userId, Long teamId) {
        inTx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            return null;
        });
    }

    /** チーム T を組織 O に ACTIVE で所属させる（T の所属者は O の直接所属者ではない）。 */
    private void anchorTeam(Long teamId, Long orgId) {
        inTx(() -> {
            em.persist(TeamOrgMembershipEntity.builder().teamId(teamId).organizationId(orgId)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedAt(LocalDateTime.now(SERVER_ZONE)).build());
            return null;
        });
        anchoredTeamIds.add(teamId);
    }

    /** 記事に課金ゲートを付ける（チームの支払い項目を新規作成）。作成した支払い項目 ID を返す。 */
    private Long gate(Long teamId, Long articleId, boolean titleHidden) {
        Long itemId = item(teamId);
        inTx(() -> {
            em.persist(ContentPaymentGateEntity.builder().paymentItemId(itemId)
                    .contentType(ContentGateType.POST).contentId(articleId).isTitleHidden(titleHidden).build());
            em.flush();
            return null;
        });
        return itemId;
    }

    /** チームの支払い項目（ITEM 型）を作る。 */
    private Long item(Long teamId) {
        Long itemId = inTx(() -> {
            PaymentItemEntity item = PaymentItemEntity.builder().teamId(teamId).name("有料記事-" + kw)
                    .type(PaymentItemType.ITEM).amount(new BigDecimal("500")).build();
            em.persist(item);
            em.flush();
            return item.getId();
        });
        createdPaymentItemIds.add(itemId);
        return itemId;
    }

    /**
     * 支払い記録を作る。{@code validUntilDaysFromToday} は DB の今日からの相対日数（null は無期限）。
     * 日付は DB 側で計算し、Java の時刻を束縛しない。{@code payerUserId} は払い手（受益者と別人のとき）。
     */
    private void payment(Long beneficiaryUserId, Long itemId, String status, Integer validUntilDaysFromToday,
                         Long payerUserId) {
        String validUntil = validUntilDaysFromToday == null ? "NULL"
                : "DATE_ADD(CURRENT_DATE, INTERVAL " + validUntilDaysFromToday.intValue() + " DAY)";
        boolean paid = "PAID".equals(status);
        jdbc.update("INSERT INTO member_payments (user_id, payment_item_id, amount_paid, currency, payment_method,"
                + " status, valid_from, valid_until, paid_at, payer_user_id) VALUES (?, ?, 500, 'JPY', 'MANUAL', ?, "
                + (paid ? "CURRENT_DATE" : "NULL") + ", " + validUntil + ", " + (paid ? "UTC_TIMESTAMP()" : "NULL")
                + ", ?)", beneficiaryUserId, itemId, status, payerUserId);
    }

    private static LocalDateTime past() {
        return LocalDateTime.now(SERVER_ZONE).minusDays(1);
    }

    private static String uniqueSlug() {
        return "gs-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** 公開済み（PUBLISHED・公開日時は前日）のチーム記事。 */
    private Long teamArticle(Long teamId, Long authorId, Visibility visibility, String title, String body) {
        return articleOf(teamId, null, null, null, authorId, visibility, PostStatus.PUBLISHED, past(), title, body,
                uniqueSlug());
    }

    /** 公開済み（PUBLISHED・公開日時は前日）の組織記事。 */
    private Long orgArticle(Long orgId, Long authorId, Visibility visibility, String title, String body) {
        return articleOf(null, orgId, null, null, authorId, visibility, PostStatus.PUBLISHED, past(), title, body,
                uniqueSlug());
    }

    /** 任意のスコープ・公開状態の記事を commit する。 */
    private Long articleOf(Long teamId, Long orgId, Long userId, Long socialProfileId, Long authorId,
                           Visibility visibility, PostStatus status, LocalDateTime publishedAt, String title,
                           String body, String slug) {
        Long id = inTx(() -> {
            BlogPostEntity post = BlogPostEntity.builder().teamId(teamId).organizationId(orgId).userId(userId)
                    .socialProfileId(socialProfileId).authorId(authorId).title(title).slug(slug).body(body)
                    .postType(PostType.BLOG).visibility(visibility).status(status).publishedAt(publishedAt)
                    .build();
            em.persist(post);
            em.flush();
            return post.getId();
        });
        createdArticleIds.add(id);
        return id;
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (ARTICLE.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("ARTICLE id=" + id + " が結果に無い（応答: " + body + "）");
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'blog_posts' "
                        + "AND index_name = 'ft_bp_title_body'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE blog_posts ADD FULLTEXT INDEX ft_bp_title_body (title, body) WITH PARSER ngram");
        }
    }
}
