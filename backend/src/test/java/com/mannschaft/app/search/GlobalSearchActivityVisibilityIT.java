package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索 ACTIVITY（活動記録 {@code activity_results}）種別の受け入れテスト（CMP-261116-0722 PR-7 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性（台帳 D4 / 設計書 §7 ACTIVITY）</b>: 次の<b>すべて</b>を満たす活動記録だけが出る。</p>
 * <ol>
 *   <li>{@code deleted_at IS NULL} かつ {@code status = 'PUBLISHED'}。{@link ActivityStatus#DRAFT} は作成者本人・
 *       ADMIN を含め誰にも出さない（既存一覧 {@code findVisibleByScopeTypeAndScopeId} の「DRAFT は作成者本人可視」も
 *       検索では使わない）。</li>
 *   <li>{@code scope_type} が TEAM / ORGANIZATION。{@link ActivityScopeType#COMMITTEE} は対象外
 *       （{@code ActivityScopeAccessGuard} がロール基盤の解決対象外として fail-closed にしている。scope_id が
 *       閲覧者の所属チーム・組織の ID と一致しても出さない）。{@link ActivityScopeType} はこの 3 値のみ。</li>
 *   <li><b>所属経路</b>: 記録のスコープが集約側 {@link SearchViewer} の母集団（GUEST のみ除外・削除/アーカイブ/未稼働除外・
 *       SYSTEM_ADMIN は母集団なし）にあり、既存一覧の入口 {@code ActivityScopeAccessGuard#checkMembership}
 *       （{@code memberships} の在籍行。SUPPORTER も通る）を満たす。{@code user_roles} の ADMIN だけで在籍行の
 *       無い利用者には出さない。<b>公開経路は無い</b>（公開チームの PUBLIC 記録でも非所属者には出さない。
 *       台帳 AC-A7 の「公開データがある種別」に ACTIVITY は入っていない）。</li>
 *   <li><b>可視レベル</b>: そのスコープでの閲覧者ロールについて {@code MembershipBatchQueryService#resolveVisibleLevels}
 *       を {@code ActivityVisibilityMapper#toFunctional} で逆写像した集合に記録の visibility が入る
 *       （既存一覧 {@code ActivityResultService#listActivities} と同じ）。<b>スコープごとに</b>判定する（AC-X1）:
 *     <ul>
 *       <li>{@link ActivityVisibility#PUBLIC} → SUPPORTER 以上（ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER）</li>
 *       <li>{@link ActivityVisibility#MEMBERS_ONLY}（MEMBERS_AND_ABOVE に写像）→ MEMBER 以上（SUPPORTER に出ない）</li>
 *     </ul></li>
 *   <li><b>予定から自動生成された記録</b>（{@code is_auto_generated_from_schedule = TRUE}）: 上記に加えて
 *       {@code AutomaticActivityActualPolicy} / {@code AutomaticActivityListService#list} と同じく、元予定
 *       （{@code schedule_id}）が閲覧者に可視（{@code ScheduleActivityVisibilityService#visibleSources}）で、かつ
 *       元予定のスコープが記録のスコープと一致すること。元予定が不可視（min_view_role 不足）・論理削除済み・
 *       schedule_id 無し・別スコープの予定のときは、可視レベルを満たすメンバーにも結果・件数ともに出さない。</li>
 * </ol>
 *
 * <p><b>SYSTEM_ADMIN</b> はバイパスしない（D5）。<b>スコープ指定時</b>はそのスコープ（所属確認済み）の記録だけを返す
 * （AC-A3 の存在オラクル封じ）。</p>
 *
 * <p><b>一致列</b>: 設計書 §3 のとおり {@code title} の LIKE（全語 AND、{@code SearchLikeConditions} /
 * {@code SearchQueryText#containsPattern} のエスケープ）。{@code description} だけの一致はヒットしない。
 * FULLTEXT 索引は新設しない（本番 migration 不要）。</p>
 *
 * <p><b>遷移先</b>: FE の現役ルート {@code frontend/app/pages/activities/[id].vue}（認証済み詳細。
 * {@code ActivityRecordList} / {@code ActivitySchedulePanel} / {@code ActivityCreateDialog} がここへ遷移する）に合わせ
 * {@code /activities/{id}}。匿名公開ページ {@code /activity/{id}} は PUBLIC 記録しか開けないため使わない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 ACTIVITY 種別の認可境界（CMP-261116-0722 PR-7 試練）")
class GlobalSearchActivityVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String ACTIVITY = "ACTIVITY";
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdActivityIds = new ArrayList<>();
    private final List<Long> createdScheduleIds = new ArrayList<>();
    private final List<Long> anchoredTeamIds = new ArrayList<>();

    @BeforeEach
    void prepare() {
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUpActivities() {
        inTx(() -> {
            for (Long id : createdActivityIds) {
                em.createNativeQuery("DELETE FROM activity_results WHERE id = :id").setParameter("id", id)
                        .executeUpdate();
            }
            for (Long id : createdScheduleIds) {
                em.createNativeQuery("DELETE FROM schedules WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long teamId : anchoredTeamIds) {
                em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id")
                        .setParameter("id", teamId).executeUpdate();
            }
            return null;
        });
        createdActivityIds.clear();
        createdScheduleIds.clear();
        anchoredTeamIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2: スコープの境界（ACTIVITY に公開経路は無い）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界（A1 / A2）・SYSTEM_ADMIN・authorId・在籍行")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(ACTIVITY): チーム X の活動記録は X 非所属者の結果にも typeCounts.ACTIVITY にも meta.total にも出ない（X の所属者には出る）")
        void AC_A1_ACTIVITY_非所属チームの活動記録は出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long record = teamActivity(teamX, insider, kw + " チームXの練習記録", ActivityVisibility.MEMBERS_ONLY);

            assertNothingVisible(search(outsider, kw), ACTIVITY);
            JsonNode outsiderDetail = search(outsider, kw, ACTIVITY);
            assertNothingVisible(outsiderDetail, ACTIVITY);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), ACTIVITY, record, 1);
            JsonNode insiderDetail = search(insider, kw, ACTIVITY);
            assertVisible(insiderDetail, ACTIVITY, record, 1);
            assertThat(insiderDetail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A1(ACTIVITY): 公開チーム・公開組織の PUBLIC 活動記録でも非所属者には出ない（検索に公開経路は無い）。所属者には出る")
        void AC_A1_ACTIVITY_公開スコープのPUBLIC記録でも非所属者に出ない() throws Exception {
            Long team = publicTeam("XP-" + kw);
            Long org = publicOrg("OP-" + kw);
            Long insider = viewer("a1p-insider");
            teamMember(insider, team);
            orgMember(insider, org);
            Long outsider = viewer("a1p-outsider");
            Long teamPublic = teamActivity(team, insider, kw + " 公開チームの公開記録", ActivityVisibility.PUBLIC);
            Long orgPublic = orgActivity(org, insider, kw + " 公開組織の公開記録", ActivityVisibility.PUBLIC);

            assertNothingVisible(search(outsider, kw), ACTIVITY);
            JsonNode outsiderDetail = search(outsider, kw, ACTIVITY);
            assertNothingVisible(outsiderDetail, ACTIVITY);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            JsonNode own = search(insider, kw, ACTIVITY);
            assertThat(resultIds(own, ACTIVITY)).containsExactlyInAnyOrder(teamPublic, orgPublic);
            assertThat(typeCount(own, ACTIVITY)).isEqualTo(2);
            assertThat(own.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A2(ACTIVITY): 組織 O の活動記録は別組織ユーザーにも、O 配下チームだけの所属者にも出ない（O の所属者には出る）")
        void AC_A2_ACTIVITY_別組織の活動記録は出ない() throws Exception {
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
            Long r1 = orgActivity(orgO, insider, kw + " 組織の活動1", ActivityVisibility.MEMBERS_ONLY);
            Long r2 = orgActivity(orgO, insider, kw + " 組織の活動2", ActivityVisibility.PUBLIC);

            for (Long viewer : List.of(outsider, childOnly)) {
                assertNothingVisible(search(viewer, kw), ACTIVITY);
                JsonNode detail = search(viewer, kw, ACTIVITY);
                assertNothingVisible(detail, ACTIVITY);
                assertThat(detail.path("meta").path("total").asLong()).isZero();
            }

            JsonNode insiderBody = search(insider, kw, ACTIVITY);
            assertThat(resultIds(insiderBody, ACTIVITY)).containsExactlyInAnyOrder(r1, r2);
            assertThat(typeCount(insiderBody, ACTIVITY)).isEqualTo(2);
            assertThat(insiderBody.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームの記録も、他人の DRAFT も出ない（既存一覧の viewerIsSystemAdmin 貫通を検索では使わない）")
        void SYSTEM_ADMINはバイパスしない() throws Exception {
            Long foreign = privateTeam("SAF-" + kw);
            Long own = privateTeam("SAO-" + kw);
            Long author = viewer("sa-author");
            teamMember(author, foreign);
            teamMember(author, own);
            Long admin = viewer("sysadmin");
            teamMember(admin, own);
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            Set<Long> invisible = new HashSet<>();
            invisible.add(teamActivity(foreign, author, kw + " 他チーム", ActivityVisibility.PUBLIC));
            invisible.add(teamActivity(foreign, author, kw + " 他チーム内輪", ActivityVisibility.MEMBERS_ONLY));
            invisible.add(activity(ActivityScopeType.TEAM, own, author, kw + " 下書き",
                    ActivityVisibility.MEMBERS_ONLY, ActivityStatus.DRAFT));

            JsonNode detail = search(admin, Map.of("q", kw, "type", ACTIVITY, "size", "50"));
            assertThat(resultIds(detail, ACTIVITY)).doesNotContainAnyElementsOf(invisible);
            assertThat(typeCount(detail, ACTIVITY)).as("件数にも数えない").isEqualTo(resultIds(detail, ACTIVITY).size());
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(resultIds(detail, ACTIVITY).size());
            assertThat(typeCount(search(admin, kw), ACTIVITY)).isEqualTo(resultIds(detail, ACTIVITY).size());

            // 対照: 同じ記録群で、通常の所属者には他チーム・下書き以外の自チームの公開済み記録だけが出る
            // （SYSTEM_ADMIN は集約側の母集団を持たないため、所属者であっても対照には使わない）
            Long member = viewer("sa-member");
            teamMember(member, own);
            Long visible = teamActivity(own, author, kw + " 自チーム", ActivityVisibility.MEMBERS_ONLY);
            JsonNode memberBody = search(member, kw, ACTIVITY);
            assertThat(resultIds(memberBody, ACTIVITY)).containsExactly(visible);
            assertThat(typeCount(memberBody, ACTIVITY)).isEqualTo(1);
        }

        @Test
        @DisplayName("authorId（作成者）指定でも不可視の記録は出ない（可視な同一作成者の記録だけが出る）")
        void authorId指定でも不可視は出ない() throws Exception {
            Long foreign = privateTeam("AF-" + kw);
            Long mine = privateTeam("AM-" + kw);
            Long author = viewer("au-author");
            teamMember(author, foreign);
            teamMember(author, mine);
            Long me = viewer("au-me");
            teamSupporter(me, mine);
            teamActivity(foreign, author, kw + " 他チーム", ActivityVisibility.PUBLIC);
            teamActivity(mine, author, kw + " 自チーム内輪", ActivityVisibility.MEMBERS_ONLY);
            activity(ActivityScopeType.TEAM, mine, author, kw + " 自チーム下書き", ActivityVisibility.PUBLIC,
                    ActivityStatus.DRAFT);
            Long visible = teamActivity(mine, author, kw + " 自チーム公開", ActivityVisibility.PUBLIC);
            Long other = viewer("au-other");
            teamMember(other, mine);
            teamActivity(mine, other, kw + " 別作成者", ActivityVisibility.PUBLIC);

            JsonNode body = search(me, Map.of("q", kw, "type", ACTIVITY, "authorId", author.toString()));
            assertThat(resultIds(body, ACTIVITY)).containsExactly(visible);
            assertThat(typeCount(body, ACTIVITY)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("既存一覧の入口（checkMembership＝memberships の在籍行）と揃える: user_roles の ADMIN だけで在籍行の無い利用者には、そのチームの記録が出ない（在籍者には出る）")
        void 在籍行の無いuser_roles_ADMINには出ない() throws Exception {
            Long team = privateTeam("NM-" + kw);
            Long roleOnly = viewer("nm-role-only");
            teamAdmin(roleOnly, team);
            Long member = viewer("nm-member");
            teamMember(member, team);
            Long record = teamActivity(team, member, kw + " 在籍者向け", ActivityVisibility.PUBLIC);

            assertNothingVisible(search(roleOnly, kw), ACTIVITY);
            JsonNode detail = search(roleOnly, kw, ACTIVITY);
            assertNothingVisible(detail, ACTIVITY);
            assertThat(detail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(member, kw, ACTIVITY), ACTIVITY, record, 1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-X1 / AC-V1: 可視レベル（スコープ単位）・DRAFT・COMMITTEE
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("可視レベル・DRAFT・COMMITTEE（X1 / V1）")
    class VisibilityLevel {

        @Test
        @DisplayName("AC-X1(ACTIVITY): X で ADMIN・Y で SUPPORTER の利用者に、Y の MEMBERS_ONLY 記録は出ず、X の同等記録は出る（スコープごとのロールで判定し和集合で判定しない）")
        void AC_X1_ACTIVITY_スコープごとのロールで判定() throws Exception {
            Long teamX = privateTeam("X1X-" + kw);
            Long teamY = privateTeam("X1Y-" + kw);
            Long me = viewer("x1-me");
            teamMember(me, teamX);
            teamAdmin(me, teamX);
            teamSupporter(me, teamY);
            Long author = viewer("x1-author");
            teamMember(author, teamX);
            teamMember(author, teamY);

            Long xMembers = teamActivity(teamX, author, kw + " X内輪", ActivityVisibility.MEMBERS_ONLY);
            Long xPublic = teamActivity(teamX, author, kw + " X公開", ActivityVisibility.PUBLIC);
            Set<Long> yInvisible = new HashSet<>();
            yInvisible.add(teamActivity(teamY, author, kw + " Y内輪1", ActivityVisibility.MEMBERS_ONLY));
            yInvisible.add(teamActivity(teamY, author, kw + " Y内輪2", ActivityVisibility.MEMBERS_ONLY));
            Long yPublic = teamActivity(teamY, author, kw + " Y公開", ActivityVisibility.PUBLIC);

            assertExactlyVisible(me, yInvisible, xMembers, xPublic, yPublic);
        }

        @Test
        @DisplayName("AC-V1: 可視レベルの全段（ActivityVisibility の列挙すべて）をロール別に固定する。PUBLIC は ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER、MEMBERS_ONLY は SUPPORTER を除く。GUEST のみ・非所属には何も出ない")
        void AC_V1_可視レベルの全段をロール別に固定() throws Exception {
            Long team = privateTeam("V1-" + kw);
            Roles r = rolesIn(team);
            Long guest = viewer("v1-guest");
            teamGuest(guest, team);
            Long outsider = viewer("v1-outsider");
            teamMember(outsider, privateTeam("V1O-" + kw));

            Map<ActivityVisibility, Long> records = new EnumMap<>(ActivityVisibility.class);
            for (ActivityVisibility v : ActivityVisibility.values()) {
                records.put(v, teamActivity(team, r.admin(), kw + " 段 " + v.name(), v));
            }
            // 列挙が増えたら本テストを更新させる（新しい段の期待値を必ず決める）
            assertThat(records.keySet()).as("ActivityVisibility の全値を期待値に持つこと")
                    .containsExactlyInAnyOrder(ActivityVisibility.PUBLIC, ActivityVisibility.MEMBERS_ONLY);
            Long pub = records.get(ActivityVisibility.PUBLIC);
            Long members = records.get(ActivityVisibility.MEMBERS_ONLY);

            assertExactlyVisible(r.admin(), Set.of(), pub, members);
            assertExactlyVisible(r.deputy(), Set.of(), pub, members);
            assertExactlyVisible(r.member(), Set.of(), pub, members);
            assertExactlyVisible(r.supporter(), Set.of(members), pub);
            assertExactlyVisible(guest, Set.of(pub, members));
            assertExactlyVisible(outsider, Set.of(pub, members));
        }

        @Test
        @DisplayName("AC-V1: 組織でも同じ。MEMBERS_ONLY は組織 SUPPORTER に出ず組織 MEMBER / ADMIN に出る。PUBLIC は組織 SUPPORTER にも出る")
        void AC_V1_組織の可視レベル() throws Exception {
            Long org = privateOrg("V1G-" + kw);
            Long admin = viewer("v1g-admin");
            orgMember(admin, org);
            orgAdmin(admin, org);
            Long member = viewer("v1g-member");
            orgMember(member, org);
            Long supporter = viewer("v1g-supporter");
            inTx(() -> {
                MembershipTestHelper.insertMembership(em, supporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER);
                return null;
            });
            Long members = orgActivity(org, member, kw + " 組織内輪", ActivityVisibility.MEMBERS_ONLY);
            Long pub = orgActivity(org, member, kw + " 組織公開", ActivityVisibility.PUBLIC);

            assertExactlyVisible(admin, Set.of(), members, pub);
            assertExactlyVisible(member, Set.of(), members, pub);
            assertExactlyVisible(supporter, Set.of(members), pub);
        }

        @Test
        @DisplayName("AC-V1: DRAFT は作成者本人・ADMIN・MEMBER の誰にも結果・件数ともに出ない（PUBLIC / MEMBERS_ONLY のどちらの DRAFT も）。同じスコープの公開済みは出る")
        void AC_V1_DRAFTは誰にも出ない() throws Exception {
            Long team = privateTeam("DR-" + kw);
            Roles r = rolesIn(team);
            Set<Long> drafts = new HashSet<>();
            for (ActivityVisibility v : ActivityVisibility.values()) {
                drafts.add(activity(ActivityScopeType.TEAM, team, r.member(), kw + " 下書き " + v.name(), v,
                        ActivityStatus.DRAFT));
                drafts.add(activity(ActivityScopeType.TEAM, team, r.admin(), kw + " 管理者の下書き " + v.name(), v,
                        ActivityStatus.DRAFT));
            }
            Long published = teamActivity(team, r.member(), kw + " 公開済み", ActivityVisibility.PUBLIC);

            for (Long viewer : List.of(r.member(), r.admin(), r.deputy(), r.supporter())) {
                assertExactlyVisible(viewer, drafts, published);
            }
        }

        @Test
        @DisplayName("AC-V1: COMMITTEE scope の記録は、scope_id が閲覧者の所属チーム・所属組織の ID と同じでも、所属者・ADMIN・作成者本人に結果・件数ともに出ない")
        void AC_V1_COMMITTEEは出ない() throws Exception {
            Long team = privateTeam("CM-" + kw);
            Long org = privateOrg("CMO-" + kw);
            Long me = viewer("cm-me");
            teamMember(me, team);
            teamAdmin(me, team);
            orgMember(me, org);
            Set<Long> committee = new HashSet<>();
            for (Long scopeId : List.of(team, org)) {
                for (ActivityVisibility v : ActivityVisibility.values()) {
                    committee.add(activity(ActivityScopeType.COMMITTEE, scopeId, me, kw + " 委員会 " + v.name(), v,
                            ActivityStatus.PUBLISHED));
                }
            }
            Long teamRecord = teamActivity(team, me, kw + " チーム", ActivityVisibility.MEMBERS_ONLY);
            Long orgRecord = orgActivity(org, me, kw + " 組織", ActivityVisibility.MEMBERS_ONLY);

            assertExactlyVisible(me, committee, teamRecord, orgRecord);
        }

        @Test
        @DisplayName("ActivityScopeType は TEAM / ORGANIZATION / COMMITTEE の 3 値（検索対象は TEAM / ORGANIZATION のみ。値が増えたら対象外の判断を更新させる）")
        void スコープ種別の列挙を固定() {
            assertThat(ActivityScopeType.values()).containsExactlyInAnyOrder(
                    ActivityScopeType.TEAM, ActivityScopeType.ORGANIZATION, ActivityScopeType.COMMITTEE);
            assertThat(ActivityStatus.values()).containsExactlyInAnyOrder(
                    ActivityStatus.DRAFT, ActivityStatus.PUBLISHED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-V2: 予定から自動生成された活動記録（元予定の可視性）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("予定から自動生成された記録（V2）")
    class AutomaticFromSchedule {

        @Test
        @DisplayName("AC-V2: 可視レベルを満たす MEMBER でも、元予定が不可視（ADMIN_ONLY）・論理削除済み・schedule_id 無し・別スコープの予定なら結果にも件数にも出ない。元予定が可視（MEMBER_PLUS）なら出る")
        void AC_V2_元予定が不可視なら出ない() throws Exception {
            Long team = privateTeam("V2-" + kw);
            Long otherTeam = privateTeam("V2U-" + kw);
            Long author = viewer("v2-author");
            teamMember(author, team);
            teamAdmin(author, team);
            teamMember(author, otherTeam);
            Long member = viewer("v2-member");
            teamMember(member, team);
            teamMember(member, otherTeam);

            Long visibleSource = teamSchedule(team, author, MinViewRole.MEMBER_PLUS);
            Long shown = autoActivity(team, visibleSource, author, kw + " 可視予定から", ActivityVisibility.MEMBERS_ONLY);

            Set<Long> hidden = new HashSet<>();
            Long adminOnly = teamSchedule(team, author, MinViewRole.ADMIN_ONLY);
            hidden.add(autoActivity(team, adminOnly, author, kw + " 管理者限定予定から", ActivityVisibility.MEMBERS_ONLY));
            Long deleted = teamSchedule(team, author, MinViewRole.MEMBER_PLUS);
            hidden.add(autoActivity(team, deleted, author, kw + " 削除予定から", ActivityVisibility.MEMBERS_ONLY));
            nativeUpdate("UPDATE schedules SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deleted);
            hidden.add(autoActivity(team, null, author, kw + " 予定IDなし", ActivityVisibility.PUBLIC));
            // 元予定は member に可視（otherTeam の MEMBER_PLUS）だが、記録のスコープ（team）と一致しない
            Long foreignSource = teamSchedule(otherTeam, author, MinViewRole.MEMBER_PLUS);
            hidden.add(autoActivity(team, foreignSource, author, kw + " 別スコープ予定から", ActivityVisibility.PUBLIC));

            Long manual = teamActivity(team, author, kw + " 手動記録", ActivityVisibility.MEMBERS_ONLY);

            assertExactlyVisible(member, hidden, shown, manual);
        }

        @Test
        @DisplayName("AC-V2: SUPPORTER には、PUBLIC の自動生成記録でも元予定が MEMBER_PLUS なら出ず、元予定が SUPPORTER_PLUS なら出る（記録の可視レベルと元予定の可視性の両方を満たす）")
        void AC_V2_SUPPORTERは元予定の閲覧ロールにも従う() throws Exception {
            Long team = privateTeam("V2S-" + kw);
            Long author = viewer("v2s-author");
            teamMember(author, team);
            teamAdmin(author, team);
            Long supporter = viewer("v2s-supporter");
            teamSupporter(supporter, team);

            Long forSupporters = teamSchedule(team, author, MinViewRole.SUPPORTER_PLUS);
            Long shown = autoActivity(team, forSupporters, author, kw + " 応援者可の予定", ActivityVisibility.PUBLIC);
            Set<Long> hidden = new HashSet<>();
            Long forMembers = teamSchedule(team, author, MinViewRole.MEMBER_PLUS);
            hidden.add(autoActivity(team, forMembers, author, kw + " メンバー以上の予定", ActivityVisibility.PUBLIC));
            // 元予定は可視でも記録自体が MEMBERS_ONLY なら SUPPORTER に出ない
            hidden.add(autoActivity(team, forSupporters, author, kw + " 内輪の記録", ActivityVisibility.MEMBERS_ONLY));

            assertExactlyVisible(supporter, hidden, shown);
        }

        @Test
        @DisplayName("AC-V2: 自動生成の DRAFT（実績未公開）は、元予定が可視でも作成者本人・ADMIN・MEMBER の誰にも出ない")
        void AC_V2_自動生成のDRAFTは出ない() throws Exception {
            Long team = privateTeam("V2D-" + kw);
            Roles r = rolesIn(team);
            Long source = teamSchedule(team, r.admin(), MinViewRole.ANYONE);
            Long draft = inTxActivity(ActivityResultEntity.builder().scopeType(ActivityScopeType.TEAM).scopeId(team)
                    .scheduleId(source).createdBy(r.admin()).autoGeneratedFromSchedule(true).planned(true)
                    .title(kw + " 実績未公開").activityDate(LocalDate.of(2026, 10, 15))
                    .status(ActivityStatus.DRAFT).visibility(ActivityVisibility.PUBLIC).build());
            Long published = autoActivity(team, source, r.admin(), kw + " 実績公開", ActivityVisibility.PUBLIC);

            for (Long viewer : List.of(r.admin(), r.member())) {
                assertExactlyVisible(viewer, Set.of(draft), published);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(ACTIVITY): 非所属スコープ（記録あり）指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0、data/meta/error）。自スコープ指定ではそのスコープの記録だけが出る")
    void AC_A3_ACTIVITY_存在オラクルなし() throws Exception {
        Long foreign = publicTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        teamActivity(foreign, foreignAuthor, kw + " 他所の公開記録", ActivityVisibility.PUBLIC);
        Long mine = privateTeam("M-" + kw);
        Long myOrg = privateOrg("MO-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        orgMember(me, myOrg);
        Long mineRecord = teamActivity(mine, me, kw + " 自チームの記録", ActivityVisibility.MEMBERS_ONLY);
        Long orgRecord = orgActivity(myOrg, me, kw + " 自組織の記録", ActivityVisibility.MEMBERS_ONLY);

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", ACTIVITY, "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", ACTIVITY, "scopeType", "TEAM",
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
        assertThat(typeCount(foreignDetail, ACTIVITY)).isZero();
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", ACTIVITY, "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, ACTIVITY)).as("スコープ指定時はそのスコープの記録だけ").containsExactly(mineRecord);
        assertThat(typeCount(ownScope, ACTIVITY)).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);

        JsonNode orgScope = search(me, Map.of("q", kw, "type", ACTIVITY, "scopeType", "ORGANIZATION",
                "scopeId", myOrg.toString()));
        assertThat(resultIds(orgScope, ACTIVITY)).containsExactly(orgRecord);
        assertThat(typeCount(orgScope, ACTIVITY)).isEqualTo(1);

        JsonNode unscoped = search(me, kw, ACTIVITY);
        assertThat(resultIds(unscoped, ACTIVITY)).as("スコープ指定なしでも他所の公開記録は出ない（公開経路なし）")
                .containsExactlyInAnyOrder(mineRecord, orgRecord);
        assertThat(typeCount(unscoped, ACTIVITY)).isEqualTo(2);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(ACTIVITY): GUEST ロールしか持たないスコープ（公開チーム・非公開組織）の記録は PUBLIC でも出ない。同じチームの MEMBER には出る")
    void AC_A4_ACTIVITY_GUESTのみのスコープは母集団外() throws Exception {
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
        Long pub = teamActivity(team, author, kw + " 公開記録", ActivityVisibility.PUBLIC);
        Long members = teamActivity(team, author, kw + " 内輪記録", ActivityVisibility.MEMBERS_ONLY);
        orgActivity(org, author, kw + " 組織の公開記録", ActivityVisibility.PUBLIC);

        assertNothingVisible(search(guest, kw), ACTIVITY);
        JsonNode guestDetail = search(guest, kw, ACTIVITY);
        assertNothingVisible(guestDetail, ACTIVITY);
        assertThat(guestDetail.path("meta").path("total").asLong()).isZero();

        JsonNode memberDetail = search(member, kw, ACTIVITY);
        assertThat(resultIds(memberDetail, ACTIVITY)).containsExactlyInAnyOrder(pub, members);
        assertThat(typeCount(memberDetail, ACTIVITY)).isEqualTo(2);
    }

    @Nested
    @DisplayName("論理削除・アーカイブ（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(ACTIVITY): 論理削除済みの記録は所属者・ADMIN・作成者本人にも出ない（生存記録は出る）")
        void AC_A5_ACTIVITY_削除済み記録は出ない() throws Exception {
            Long team = privateTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            teamAdmin(me, team);
            Long member = viewer("a5-member");
            teamMember(member, team);
            Long alive = teamActivity(team, me, kw + " 生存", ActivityVisibility.MEMBERS_ONLY);
            Set<Long> gone = new HashSet<>();
            for (ActivityVisibility v : ActivityVisibility.values()) {
                Long deleted = teamActivity(team, me, kw + " 削除済み " + v.name(), v);
                nativeUpdate("UPDATE activity_results SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deleted);
                gone.add(deleted);
            }

            for (Long viewer : List.of(me, member)) {
                assertExactlyVisible(viewer, gone, alive);
            }
        }

        @Test
        @DisplayName("AC-A5(ACTIVITY): 論理削除・アーカイブ済み・未稼働（PROVISIONED）のチーム、論理削除・アーカイブ済みの組織の記録は所属者に出ない")
        void AC_A5_ACTIVITY_削除済みスコープは出ない() throws Exception {
            Long deletedTeam = privateTeam("DT-" + kw);
            Long archivedTeam = privateTeam("AT-" + kw);
            Long provisionedTeam = privateTeam("PT-" + kw);
            Long deletedOrg = privateOrg("DO-" + kw);
            Long archivedOrg = privateOrg("AO-" + kw);
            Long liveTeam = privateTeam("LT-" + kw);
            Long me = viewer("a5s-me");
            for (Long t : List.of(deletedTeam, archivedTeam, provisionedTeam, liveTeam)) {
                teamMember(me, t);
            }
            orgMember(me, deletedOrg);
            orgMember(me, archivedOrg);
            Set<Long> gone = new HashSet<>();
            for (Long t : List.of(deletedTeam, archivedTeam, provisionedTeam)) {
                gone.add(teamActivity(t, me, kw + " チーム " + t, ActivityVisibility.PUBLIC));
            }
            for (Long o : List.of(deletedOrg, archivedOrg)) {
                gone.add(orgActivity(o, me, kw + " 組織 " + o, ActivityVisibility.PUBLIC));
            }
            Long live = teamActivity(liveTeam, me, kw + " 生存チーム", ActivityVisibility.PUBLIC);
            softDeleteTeam(deletedTeam);
            archiveTeam(archivedTeam);
            provisionTeam(provisionedTeam);
            softDeleteOrg(deletedOrg);
            archiveOrg(archivedOrg);

            assertExactlyVisible(me, gone, live);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A6 / AC-C8: 件数
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数（A6 / C8）")
    class Counting {

        @Test
        @DisplayName("AC-A6(ACTIVITY): 不可視の記録（他チーム・内輪(SUPPORTER視点)・DRAFT・削除・COMMITTEE・元予定不可視の自動生成）を足しても件数は変わらず、meta.total と typeCounts.ACTIVITY が一致する")
        void AC_A6_ACTIVITY_不可視を足しても件数不変() throws Exception {
            Long foreign = privateTeam("A6F-" + kw);
            Long mine = privateTeam("A6M-" + kw);
            Long myOrg = privateOrg("A6O-" + kw);
            Long me = viewer("a6-me");
            teamSupporter(me, mine);
            orgMember(me, myOrg);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            teamAdmin(stranger, mine);
            teamActivity(mine, stranger, kw + " 1", ActivityVisibility.PUBLIC);
            orgActivity(myOrg, me, kw + " 2", ActivityVisibility.MEMBERS_ONLY);

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, ACTIVITY)).isEqualTo(2);

            teamActivity(foreign, stranger, kw + " 他チーム", ActivityVisibility.PUBLIC);
            teamActivity(mine, stranger, kw + " 内輪", ActivityVisibility.MEMBERS_ONLY);
            activity(ActivityScopeType.ORGANIZATION, myOrg, me, kw + " 自分の下書き", ActivityVisibility.PUBLIC,
                    ActivityStatus.DRAFT);
            nativeUpdate("UPDATE activity_results SET deleted_at = UTC_TIMESTAMP() WHERE id = :id",
                    teamActivity(mine, stranger, kw + " 削除", ActivityVisibility.PUBLIC));
            activity(ActivityScopeType.COMMITTEE, myOrg, me, kw + " 委員会", ActivityVisibility.PUBLIC,
                    ActivityStatus.PUBLISHED);
            autoActivity(mine, teamSchedule(mine, stranger, MinViewRole.ADMIN_ONLY), stranger, kw + " 管理者予定から",
                    ActivityVisibility.PUBLIC);

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, ACTIVITY);
            assertThat(typeCount(after, ACTIVITY)).isEqualTo(typeCount(before, ACTIVITY));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, ACTIVITY));
            assertThat(typeCount(detail, ACTIVITY)).isEqualTo(typeCount(after, ACTIVITY));
            assertThat(resultIds(detail, ACTIVITY)).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(ACTIVITY): 可視11件・不可視3件 → typeCounts.ACTIVITY=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を重複・欠落なく返す")
        void AC_C8_ACTIVITY_真の件数とページ送り() throws Exception {
            Long mine = privateTeam("C8M-" + kw);
            Long myOrg = privateOrg("C8O-" + kw);
            Long foreign = privateTeam("C8F-" + kw);
            Long me = viewer("c8-me");
            teamMember(me, mine);
            orgMember(me, myOrg);
            Long stranger = viewer("c8-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            Set<Long> visible = new HashSet<>();
            for (int i = 0; i < 7; i++) {
                visible.add(teamActivity(mine, i % 2 == 0 ? me : stranger, kw + " チーム" + i,
                        i % 3 == 0 ? ActivityVisibility.PUBLIC : ActivityVisibility.MEMBERS_ONLY));
            }
            Long source = teamSchedule(mine, stranger, MinViewRole.MEMBER_PLUS);
            for (int i = 0; i < 2; i++) {
                visible.add(autoActivity(mine, source, stranger, kw + " 予定から" + i, ActivityVisibility.MEMBERS_ONLY));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(orgActivity(myOrg, me, kw + " 組織" + i, ActivityVisibility.MEMBERS_ONLY));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(teamActivity(foreign, stranger, kw + " 他チーム", ActivityVisibility.PUBLIC));
            invisible.add(activity(ActivityScopeType.TEAM, mine, me, kw + " 下書き", ActivityVisibility.PUBLIC,
                    ActivityStatus.DRAFT));
            invisible.add(autoActivity(mine, teamSchedule(mine, stranger, MinViewRole.ADMIN_ONLY), stranger,
                    kw + " 管理者予定から", ActivityVisibility.MEMBERS_ONLY));

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, ACTIVITY)).isEqualTo(11);
            assertThat(resultIds(overview, ACTIVITY)).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", ACTIVITY, "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, ACTIVITY)).isEqualTo(11);
                all.addAll(resultIds(detail, ACTIVITY));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", ACTIVITY, "size", "50"));
            assertThat(resultIds(big, ACTIVITY)).hasSize(11).doesNotContainAnyElementsOf(invisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / 一致列 / URL / createdAt
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープ・一致列・遷移先・作成日時")
    class EscapingUrlAndTime {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視の記録は混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(ACTIVITY): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならず、不可視は混ざらない")
        void AC_C11_ACTIVITY_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long mine = privateTeam("C11M-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            Long me = viewer("c11-me");
            teamSupporter(me, mine);
            Long invisible = teamActivity(foreign, stranger, op + kw + op + " 他チーム", ActivityVisibility.PUBLIC);
            Long membersOnly = teamActivity(mine, stranger, op + kw + op + " 内輪", ActivityVisibility.MEMBERS_ONLY);
            Long visible = teamActivity(mine, stranger, op + kw + op + " 自チーム", ActivityVisibility.PUBLIC);

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", ACTIVITY));
                assertThat(resultIds(body, ACTIVITY)).doesNotContain(invisible, membersOnly);
                assertThat(typeCount(body, ACTIVITY)).isLessThanOrEqualTo(1);
                assertThat(body.path("data").path("timedOutTypes").toString()).doesNotContain(ACTIVITY);
            }
            assertVisible(search(me, kw), ACTIVITY, visible, 1);
        }

        @Test
        @DisplayName("AC-C11(ACTIVITY): % と _ はワイルドカードとして解釈されない（q=kw%x は「kwAx」に一致しない）")
        void ワイルドカードは文字として検索される() throws Exception {
            Long team = privateTeam("WC-" + kw);
            Long me = viewer("wc-me");
            teamMember(me, team);
            Long literal = teamActivity(team, me, kw + "%x_y 文字どおり", ActivityVisibility.MEMBERS_ONLY);
            teamActivity(team, me, kw + "AxBy 別物", ActivityVisibility.MEMBERS_ONLY);

            JsonNode body = search(me, Map.of("q", kw + "%x_y", "type", ACTIVITY));
            assertThat(resultIds(body, ACTIVITY)).containsExactly(literal);
            assertThat(typeCount(body, ACTIVITY)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("一致列: title の一致でヒットする（設計書 §3 は title の LIKE）。description だけの一致はヒットしない")
        void titleで一致しdescriptionだけでは一致しない() throws Exception {
            Long team = privateTeam("TL-" + kw);
            Long me = viewer("tl-me");
            teamMember(me, team);
            Long byTitle = teamActivity(team, me, kw + " 題名で一致", ActivityVisibility.MEMBERS_ONLY);
            Long both = inTxActivity(base(ActivityScopeType.TEAM, team, me, kw + " 両方",
                    ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED).description("説明にも " + kw).build());
            inTxActivity(base(ActivityScopeType.TEAM, team, me, "題名は無関係",
                    ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED).description("説明だけに " + kw).build());

            JsonNode detail = search(me, kw, ACTIVITY);
            assertThat(resultIds(detail, ACTIVITY)).containsExactlyInAnyOrder(byTitle, both);
            assertThat(typeCount(detail, ACTIVITY)).isEqualTo(2);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(typeCount(search(me, kw), ACTIVITY)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-C12(ACTIVITY): title はプレーン（<mark> なし・生のまま）で highlights.title が一致位置を指す。snippet を返すなら HTML エスケープ済みで生のタグを含まず 200 文字以下")
        void AC_C12_ACTIVITY_titleとsnippet() throws Exception {
            Long team = privateTeam("C12-" + kw);
            Long me = viewer("c12-me");
            teamMember(me, team);
            String title = "<i>" + kw + "</i> 練習";
            String description = "<script>alert(1)</script> 前文 " + kw + " 説明<b>太字</b>" + "あ".repeat(300);
            Long id = inTxActivity(base(ActivityScopeType.TEAM, team, me, title, ActivityVisibility.MEMBERS_ONLY,
                    ActivityStatus.PUBLISHED).description(description).build());

            JsonNode hit = hitOf(search(me, kw, ACTIVITY), id);
            assertThat(hit.path("title").asText()).isEqualTo(title).doesNotContain("<mark>");
            JsonNode range = hit.path("highlights").path("title");
            assertThat(range.isArray()).as("highlights.title に [start, end]").isTrue();
            assertThat(title.substring(range.get(0).asInt(), range.get(1).asInt())).isEqualToIgnoringCase(kw);

            String snippet = hit.path("snippet").isNull() ? "" : hit.path("snippet").asText();
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>").doesNotContain("<i>");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<")
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(hit.path("relevance").isNull()).as("LIKE 種別の relevance は null（設計書 §4）").isTrue();
        }

        @Test
        @DisplayName("ACTIVITY の結果（チーム）は type=ACTIVITY・url=/activities/{id}（FE 現役の認証済み詳細ルート）、scope はチーム、author は作成者")
        void URLは活動記録の詳細() throws Exception {
            Long team = privateTeam("URL-" + kw);
            Long me = viewer("url-me");
            teamMember(me, team);
            Long id = teamActivity(team, me, kw + " 遷移先", ActivityVisibility.MEMBERS_ONLY);

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo(ACTIVITY);
            assertThat(hit.path("url").asText()).isEqualTo("/activities/" + id);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("TEAM");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(team);
            assertThat(hit.path("author").path("id").asLong()).isEqualTo(me);
            assertThat(typeCount(overview, ACTIVITY)).as("ACTIVITY の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }

        @Test
        @DisplayName("ACTIVITY の結果（組織）は url=/activities/{id}、scope は組織")
        void URLは組織でも活動記録の詳細() throws Exception {
            Long org = privateOrg("URLO-" + kw);
            Long me = viewer("urlo-me");
            orgMember(me, org);
            Long id = orgActivity(org, me, kw + " 組織遷移先", ActivityVisibility.PUBLIC);

            JsonNode hit = hitOf(search(me, kw, ACTIVITY), id);
            assertThat(hit.path("url").asText()).isEqualTo("/activities/" + id);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("ORGANIZATION");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(org);
        }

        @Test
        @DisplayName("createdAt: DB の UTC 格納値 00:00 の記録の createdAt は同一瞬間（JST 09:00+09:00）")
        void createdAtは同一瞬間() throws Exception {
            Long team = privateTeam("CA-" + kw);
            Long me = viewer("ca-me");
            teamMember(me, team);
            Long id = teamActivity(team, me, kw + " 作成日時", ActivityVisibility.MEMBERS_ONLY);
            nativeUpdate("UPDATE activity_results SET created_at = '2026-03-10 00:00:00' WHERE id = :id", id);

            JsonNode hit = hitOf(search(me, kw, ACTIVITY), id);
            String text = hit.path("createdAt").asText();
            assertThat(text).as("createdAt が出ていること（%s）", hit).isNotBlank();
            assertThat(OffsetDateTime.parse(text).toInstant()).isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------
    // 判定ヘルパ
    // ------------------------------------------------------------------

    /** 詳細ビュー（結果・typeCounts・meta.total）と概要ビュー（typeCounts）で、可視が expected ちょうどであること。 */
    private void assertExactlyVisible(Long viewer, Set<Long> neverVisible, Long... expected) throws Exception {
        JsonNode detail = search(viewer, Map.of("q", kw, "type", ACTIVITY, "size", "50"));
        assertThat(resultIds(detail, ACTIVITY)).as("viewer=%s の結果", viewer).containsExactlyInAnyOrder(expected);
        // AssertJ の doesNotContainAnyElementsOf は空集合を渡すと IllegalArgumentException になるため、
        // 不可視の指定があるときだけ確かめる（containsExactlyInAnyOrder が既に余分な ID を許さない）
        if (!neverVisible.isEmpty()) {
            assertThat(resultIds(detail, ACTIVITY)).as("viewer=%s の結果（不可視）", viewer)
                    .doesNotContainAnyElementsOf(neverVisible);
        }
        assertThat(typeCount(detail, ACTIVITY)).as("viewer=%s の typeCounts", viewer).isEqualTo(expected.length);
        assertThat(detail.path("meta").path("total").asLong()).as("viewer=%s の meta.total", viewer)
                .isEqualTo(expected.length);
        assertThat(typeCount(search(viewer, kw), ACTIVITY)).as("viewer=%s の概要 typeCounts", viewer)
                .isEqualTo(expected.length);
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (ACTIVITY.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("ACTIVITY id=" + id + " が結果に無い（応答: " + body + "）");
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    /** 1 チーム内の ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER（いずれも memberships の在籍行あり）。 */
    private record Roles(Long admin, Long deputy, Long member, Long supporter) {
    }

    private Roles rolesIn(Long teamId) {
        Long admin = viewer("admin");
        teamMember(admin, teamId);
        teamAdmin(admin, teamId);
        Long deputy = viewer("deputy");
        teamMember(deputy, teamId);
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, deputy, "DEPUTY_ADMIN", teamId, null);
            return null;
        });
        Long member = viewer("member");
        teamMember(member, teamId);
        Long supporter = viewer("supporter");
        teamSupporter(supporter, teamId);
        return new Roles(admin, deputy, member, supporter);
    }

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

    private ActivityResultEntity.ActivityResultEntityBuilder<?, ?> base(ActivityScopeType scopeType, Long scopeId,
                                                                        Long authorId, String title,
                                                                        ActivityVisibility visibility,
                                                                        ActivityStatus status) {
        return ActivityResultEntity.builder().scopeType(scopeType).scopeId(scopeId).createdBy(authorId)
                .title(title).activityDate(LocalDate.of(2026, 10, 15)).visibility(visibility).status(status);
    }

    /** 活動記録を commit する（テンプレートなし・手動作成）。 */
    private Long activity(ActivityScopeType scopeType, Long scopeId, Long authorId, String title,
                          ActivityVisibility visibility, ActivityStatus status) {
        return inTxActivity(base(scopeType, scopeId, authorId, title, visibility, status).build());
    }

    private Long inTxActivity(ActivityResultEntity entity) {
        Long id = inTx(() -> {
            em.persist(entity);
            em.flush();
            return entity.getId();
        });
        createdActivityIds.add(id);
        return id;
    }

    /** チームの公開済み活動記録。 */
    private Long teamActivity(Long teamId, Long authorId, String title, ActivityVisibility visibility) {
        return activity(ActivityScopeType.TEAM, teamId, authorId, title, visibility, ActivityStatus.PUBLISHED);
    }

    /** 組織の公開済み活動記録。 */
    private Long orgActivity(Long orgId, Long authorId, String title, ActivityVisibility visibility) {
        return activity(ActivityScopeType.ORGANIZATION, orgId, authorId, title, visibility, ActivityStatus.PUBLISHED);
    }

    /** 予定から自動生成された（実績公開済みの）チーム活動記録。scheduleId は null 可。 */
    private Long autoActivity(Long teamId, Long scheduleId, Long authorId, String title,
                              ActivityVisibility visibility) {
        return inTxActivity(base(ActivityScopeType.TEAM, teamId, authorId, title, visibility,
                ActivityStatus.PUBLISHED).scheduleId(scheduleId).autoGeneratedFromSchedule(true).build());
    }

    /** チームの予定（MEMBERS_ONLY・指定の最小閲覧ロール）を commit する。題名は検索語を含めない。 */
    private Long teamSchedule(Long teamId, Long authorId, MinViewRole minViewRole) {
        Long id = inTx(() -> {
            ScheduleEntity schedule = ScheduleEntity.builder().title("元予定").teamId(teamId).createdBy(authorId)
                    .status(ScheduleStatus.SCHEDULED).eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(minViewRole)
                    .startAt(LocalDateTime.of(2026, 10, 15, 12, 0)).endAt(LocalDateTime.of(2026, 10, 15, 13, 0))
                    .build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
        createdScheduleIds.add(id);
        return id;
    }
}
