package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileVisibilityRole;
import com.mannschaft.app.filesharing.PermissionTargetType;
import com.mannschaft.app.filesharing.PermissionType;
import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
 * グローバル検索 FILE（共有ファイル {@code shared_files}）種別の受け入れテスト（CMP-261116-0722 PR-6 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性（台帳 D4 / 設計書 §7 FILE）</b>: 次の<b>すべて</b>を満たすファイルだけが出る。FILE には公開経路が無い
 * （公開チームでも非所属者には出ない）。</p>
 * <ol>
 *   <li>ファイル {@code deleted_at IS NULL}。直属フォルダから根までの<b>全祖先フォルダ</b>が未削除。</li>
 *   <li>直属フォルダの {@code scope_type} が TEAM / ORGANIZATION。PERSONAL・TOURNAMENT・TOURNAMENT_DIVISION は
 *       対象外（大会フォルダは {@code organization_id} に主催組織を持つため、組織 ID だけで絞ると主催組織の所属者に
 *       漏れる。scope_type で必ず弾く）。</li>
 *   <li><b>所属経路</b>: フォルダのスコープが集約側 {@link SearchViewer} の母集団（SUPPORTER 以上・GUEST のみ除外・
 *       削除/アーカイブ/未稼働除外・SYSTEM_ADMIN は母集団なし）にあり、かつ既存のファイル閲覧認可の基本認可
 *       （{@code SharedFolderAccessGuard#authorizeBaseView} → {@code AccessControlService#checkMembership}
 *       ＝ {@code memberships} の在籍行）を満たす。{@code user_roles} の ADMIN だけで在籍行の無い利用者には出さない。</li>
 *   <li><b>最低可視ロール</b>（{@code min_visible_role}）: 遷移先（スコープのファイル一覧）でファイルに辿り着く経路と
 *       同じ規則にする。一覧経路は {@code SharedFolderQueryService#getFolderDetail} が
 *       ①フォルダの閲覧認可 {@code authorizeView}（フォルダ自身の min_visible_role）を当てたうえで
 *       ②{@code SharedFileRepository#findVisibleByFolderIdAndLevels}（ファイル値が NULL なら可視、非 NULL なら
 *       閲覧者の満たすレベル集合に入る）で絞る。フォルダ一覧（{@code listFolders(parentId)}）も親フォルダの
 *       {@code authorizeView} を通すため、ファイルに辿り着くには根から直属までの<b>全フォルダ</b>の
 *       min_visible_role を満たす必要がある。したがって検索の条件は
 *       「ファイル値（非 NULL なら）を満たす AND パス上の全フォルダの値（非 NULL なら）を満たす」。
 *       ファイル値がフォルダ値より緩くても、フォルダ値を満たさなければ出さない。
 *       レベルの段は {@code AccessControlService#hasRoleOrAbove}（{@code roles.priority}）と同じで、
 *       SUPPORTERS_AND_ABOVE → SUPPORTER 以上、MEMBERS_AND_ABOVE → MEMBER 以上、ADMINS_AND_ABOVE → ADMIN のみ
 *       （DEPUTY_ADMIN は priority 3 &gt; ADMIN 2 で届かない。{@code FileVisibilityRole} の Javadoc は「ADMIN / 副長」と
 *       書くが、実装の判定は ADMIN のみ）。<b>スコープごと</b>のロールで判定する（AC-X1）。</li>
 *   <li><b>個別権限</b>（{@code file_permissions}）: 既存の閲覧認可（{@code SharedFolderAccessGuard} /
 *       {@code SharedFolderQueryService} / {@code SharedFileService}）は {@code file_permissions} を一切参照しない
 *       （{@code FilePermissionService} は権限行の CRUD と大会スコープのゲートだけ）。一方 F05.5 設計書 §権限評価フロー
 *       は「個別権限はホワイトリスト・暗黙の拒否・ファイル個別がフォルダを置換・祖先へ再帰継承」と定める。
 *       意味が実装と設計で割れており、行ごとの被付与判定（USER / TEAM / ROLE）を件数と同じ述語で確定できないため、
 *       <b>ファイル自身またはパス上のいずれかのフォルダに権限行があるファイルは、作成者・ADMIN・被付与者を含め誰にも
 *       出さない</b>（fail-closed。ARTICLE の CUSTOM / 課金ゲート除外と同じ扱い）。権限行は可視範囲を<b>広げない</b>
 *       （非所属者への付与で出ることは無い）。</li>
 * </ol>
 *
 * <p><b>SYSTEM_ADMIN</b> はバイパスしない（D5。既存の {@code applyMinVisibleRole} は SYSTEM_ADMIN を貫通させるが検索では
 * 使わない）。<b>スコープ指定時</b>はそのスコープ（所属確認済み）のファイルだけを返す（AC-A3 の存在オラクル封じ）。</p>
 *
 * <p><b>FULLTEXT</b>: 本番 migration で {@code shared_files(name, description)} に
 * {@code ft_shared_files_name_description ... WITH PARSER ngram} を新設する（実装担当が採番）。テストスキーマには
 * {@code test-schema-fulltext-indexes.sql} と本クラスの {@link #ensureFulltextIndex()} で同名・同定義を補う。
 * InnoDB の FULLTEXT はコミット済みの行だけを拾うため fixture は基底の {@link #inTx} で commit する。キーワードは
 * ngram のストップワードを含まない子音だけで作る。</p>
 *
 * <p><b>遷移先</b>: FE の実在ルート {@code frontend/app/pages/teams/[slug]/files.vue} /
 * {@code organizations/[slug]/files.vue}（{@code FileBrowser} はフォルダ指定のクエリを持たない）に合わせ、
 * {@code /teams/{slug}/files} / {@code /organizations/{slug}/files}。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 FILE 種別の認可境界（CMP-261116-0722 PR-6 試練）")
class GlobalSearchFileVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String FILE = "FILE";
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");
    /** 本番 migration（実装担当が採番）と同じ索引名。 */
    static final String FULLTEXT_INDEX = "ft_shared_files_name_description";

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdFileIds = new ArrayList<>();
    private final List<Long> createdFolderIds = new ArrayList<>();
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
    void cleanUpFiles() {
        inTx(() -> {
            for (Long id : createdFileIds) {
                em.createNativeQuery("DELETE FROM file_permissions WHERE target_type = 'FILE' AND target_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM shared_files WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            // 子フォルダ（後に作ったもの）から消す（parent_id の外部キー）
            for (int i = createdFolderIds.size() - 1; i >= 0; i--) {
                Long id = createdFolderIds.get(i);
                em.createNativeQuery("DELETE FROM file_permissions WHERE target_type = 'FOLDER' AND target_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM shared_folders WHERE id = :id").setParameter("id", id)
                        .executeUpdate();
            }
            for (Long teamId : anchoredTeamIds) {
                em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id")
                        .setParameter("id", teamId).executeUpdate();
            }
            return null;
        });
        createdFileIds.clear();
        createdFolderIds.clear();
        anchoredTeamIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2: スコープの境界（FILE に公開経路は無い）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界（A1 / A2）・SYSTEM_ADMIN・authorId・在籍行")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(FILE): チーム X のファイルは X 非所属者の結果にも typeCounts.FILE にも meta.total にも出ない（X の所属者には出る）")
        void AC_A1_FILE_非所属チームのファイルは出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long file = teamFile(teamX, insider, kw + " チームXの資料.pdf");

            assertNothingVisible(search(outsider, kw), FILE);
            JsonNode outsiderDetail = search(outsider, kw, FILE);
            assertNothingVisible(outsiderDetail, FILE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), FILE, file, 1);
            JsonNode insiderDetail = search(insider, kw, FILE);
            assertVisible(insiderDetail, FILE, file, 1);
            assertThat(insiderDetail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A1(FILE): 公開チーム・公開組織のファイルでも非所属者には出ない（FILE に公開経路は無い）。説明文だけの一致も同じ境界")
        void AC_A1_FILE_公開スコープでも非所属者に出ない() throws Exception {
            Long team = publicTeam("XP-" + kw);
            Long org = publicOrg("OP-" + kw);
            Long insider = viewer("a1p-insider");
            teamMember(insider, team);
            orgMember(insider, org);
            Long outsider = viewer("a1p-outsider");
            Long byName = teamFile(team, insider, kw + " 公開チームの資料.pdf");
            Long byDescription = fileIn(teamFolder(team, null), insider, "無関係な名前.pdf", "説明に " + kw, null);
            Long orgFile = orgFile(org, insider, kw + " 公開組織の資料.pdf");

            assertNothingVisible(search(outsider, kw), FILE);
            JsonNode outsiderDetail = search(outsider, kw, FILE);
            assertNothingVisible(outsiderDetail, FILE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            JsonNode own = search(insider, kw, FILE);
            assertThat(resultIds(own, FILE)).containsExactlyInAnyOrder(byName, byDescription, orgFile);
            assertThat(typeCount(own, FILE)).isEqualTo(3);
            assertThat(own.path("meta").path("total").asLong()).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-A2(FILE): 組織 O のファイルは別組織ユーザーにも、O 配下チームだけの所属者にも出ない（O の所属者には出る）")
        void AC_A2_FILE_別組織のファイルは出ない() throws Exception {
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
            Long f1 = orgFile(orgO, insider, kw + " 組織の資料1.pdf");
            Long f2 = fileIn(orgFolder(orgO, FileVisibilityRole.SUPPORTERS_AND_ABOVE), insider,
                    kw + " 組織の資料2.pdf", null, null);

            for (Long viewer : List.of(outsider, childOnly)) {
                assertNothingVisible(search(viewer, kw), FILE);
                JsonNode detail = search(viewer, kw, FILE);
                assertNothingVisible(detail, FILE);
                assertThat(detail.path("meta").path("total").asLong()).isZero();
            }

            JsonNode insiderBody = search(insider, kw, FILE);
            assertThat(resultIds(insiderBody, FILE)).containsExactlyInAnyOrder(f1, f2);
            assertThat(typeCount(insiderBody, FILE)).isEqualTo(2);
            assertThat(insiderBody.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームのファイルも、所属チームの管理者限定ファイルも出ない（既存 applyMinVisibleRole の SYSTEM_ADMIN 貫通を検索では使わない）")
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
            invisible.add(teamFile(foreign, author, kw + " 他チーム.pdf"));
            invisible.add(fileIn(teamFolder(own, null), author, kw + " 管理者限定.pdf", null,
                    FileVisibilityRole.ADMINS_AND_ABOVE));
            invisible.add(fileIn(teamFolder(own, FileVisibilityRole.ADMINS_AND_ABOVE), author,
                    kw + " 管理者限定フォルダ.pdf", null, null));

            JsonNode detail = search(admin, Map.of("q", kw, "type", FILE, "size", "50"));
            assertThat(resultIds(detail, FILE)).doesNotContainAnyElementsOf(invisible);
            assertThat(typeCount(detail, FILE)).as("件数にも数えない").isEqualTo(resultIds(detail, FILE).size());
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(resultIds(detail, FILE).size());
            assertThat(typeCount(search(admin, kw), FILE)).isEqualTo(resultIds(detail, FILE).size());

            // 対照: 同じファイル群で、通常の所属者には管理者限定以外の自チームのファイルだけが出る
            Long member = viewer("sa-member");
            teamMember(member, own);
            Long visible = teamFile(own, author, kw + " 自チーム.pdf");
            JsonNode memberBody = search(member, kw, FILE);
            assertThat(resultIds(memberBody, FILE)).containsExactly(visible);
            assertThat(typeCount(memberBody, FILE)).isEqualTo(1);
        }

        @Test
        @DisplayName("authorId（アップロード者）指定でも不可視のファイルは出ない（可視な同一アップロード者のファイルだけが出る）")
        void authorId指定でも不可視は出ない() throws Exception {
            Long foreign = privateTeam("AF-" + kw);
            Long mine = privateTeam("AM-" + kw);
            Long author = viewer("au-author");
            teamMember(author, foreign);
            teamMember(author, mine);
            teamAdmin(author, mine);
            Long me = viewer("au-me");
            teamMember(me, mine);
            teamFile(foreign, author, kw + " 他チーム.pdf");
            fileIn(teamFolder(mine, null), author, kw + " 自チーム管理者限定.pdf", null,
                    FileVisibilityRole.ADMINS_AND_ABOVE);
            fileIn(teamFolder(mine, FileVisibilityRole.ADMINS_AND_ABOVE), author, kw + " 管理者フォルダ.pdf", null, null);
            Long visible = teamFile(mine, author, kw + " 自チーム.pdf");
            Long other = viewer("au-other");
            teamMember(other, mine);
            teamFile(mine, other, kw + " 別アップロード者.pdf");

            JsonNode body = search(me, Map.of("q", kw, "type", FILE, "authorId", author.toString()));
            assertThat(resultIds(body, FILE)).containsExactly(visible);
            assertThat(typeCount(body, FILE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("既存のファイル閲覧の基本認可（checkMembership＝memberships の在籍行）と揃える: user_roles の ADMIN だけで在籍行の無い利用者には、そのチームのファイルが出ない（在籍者には出る）")
        void 在籍行の無いuser_roles_ADMINには出ない() throws Exception {
            Long team = privateTeam("NM-" + kw);
            Long roleOnly = viewer("nm-role-only");
            teamAdmin(roleOnly, team);
            Long member = viewer("nm-member");
            teamMember(member, team);
            Long file = teamFile(team, member, kw + " 在籍者向け.pdf");

            assertNothingVisible(search(roleOnly, kw), FILE);
            JsonNode detail = search(roleOnly, kw, FILE);
            assertNothingVisible(detail, FILE);
            assertThat(detail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(member, kw, FILE), FILE, file, 1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-X1 / AC-F1: 最低可視ロール（スコープ単位・ファイル AND パス上の全フォルダ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("最低可視ロール（X1 / F1）")
    class MinVisibleRole {

        @Test
        @DisplayName("AC-X1(FILE): X で ADMIN・Y で SUPPORTER の利用者に、Y の管理者限定・メンバー以上のファイルは出ず、X の同等ファイルは出る（スコープごとのロールで判定し和集合で判定しない）")
        void AC_X1_FILE_スコープごとのロールで判定() throws Exception {
            Long teamX = privateTeam("X1X-" + kw);
            Long teamY = privateTeam("X1Y-" + kw);
            Long me = viewer("x1-me");
            teamMember(me, teamX);
            teamAdmin(me, teamX);
            teamSupporter(me, teamY);
            Long author = viewer("x1-author");
            teamMember(author, teamX);
            teamMember(author, teamY);

            Long xAdminsFile = fileIn(teamFolder(teamX, null), author, kw + " X管理者限定.pdf", null,
                    FileVisibilityRole.ADMINS_AND_ABOVE);
            Long xAdminsFolder = fileIn(teamFolder(teamX, FileVisibilityRole.ADMINS_AND_ABOVE), author,
                    kw + " X管理者フォルダ.pdf", null, null);
            Long xMembers = fileIn(teamFolder(teamX, null), author, kw + " Xメンバー以上.pdf", null,
                    FileVisibilityRole.MEMBERS_AND_ABOVE);
            Set<Long> yInvisible = new HashSet<>();
            yInvisible.add(fileIn(teamFolder(teamY, null), author, kw + " Y管理者限定.pdf", null,
                    FileVisibilityRole.ADMINS_AND_ABOVE));
            yInvisible.add(fileIn(teamFolder(teamY, FileVisibilityRole.ADMINS_AND_ABOVE), author,
                    kw + " Y管理者フォルダ.pdf", null, null));
            yInvisible.add(fileIn(teamFolder(teamY, null), author, kw + " Yメンバー以上.pdf", null,
                    FileVisibilityRole.MEMBERS_AND_ABOVE));
            Long ySupporters = fileIn(teamFolder(teamY, null), author, kw + " Y応援者以上.pdf", null,
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE);
            Long yPlain = teamFile(teamY, author, kw + " Y所属者全員.pdf");

            assertExactlyVisible(me, yInvisible, xAdminsFile, xAdminsFolder, xMembers, ySupporters, yPlain);
        }

        @Test
        @DisplayName("AC-F1: ファイル自身の min_visible_role。ADMINS_AND_ABOVE は ADMIN のみ（DEPUTY_ADMIN にも出ない）、MEMBERS_AND_ABOVE は SUPPORTER に出ない、NULL / SUPPORTERS_AND_ABOVE は全所属者に出る")
        void AC_F1_ファイル自身の最低可視ロール() throws Exception {
            Long team = privateTeam("F1-" + kw);
            Roles r = rolesIn(team);
            Long folder = teamFolder(team, null);
            Long plain = fileIn(folder, r.admin(), kw + " 全員.pdf", null, null);
            Long supporters = fileIn(folder, r.admin(), kw + " 応援者以上.pdf", null,
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE);
            Long members = fileIn(folder, r.admin(), kw + " メンバー以上.pdf", null,
                    FileVisibilityRole.MEMBERS_AND_ABOVE);
            Long admins = fileIn(folder, r.admin(), kw + " 管理者限定.pdf", null, FileVisibilityRole.ADMINS_AND_ABOVE);

            assertExactlyVisible(r.admin(), Set.of(), plain, supporters, members, admins);
            assertExactlyVisible(r.deputy(), Set.of(admins), plain, supporters, members);
            assertExactlyVisible(r.member(), Set.of(admins), plain, supporters, members);
            assertExactlyVisible(r.supporter(), Set.of(admins, members), plain, supporters);
        }

        @Test
        @DisplayName("AC-F1: フォルダからの継承（ファイル値 NULL）。管理者限定フォルダのファイルは ADMIN のみ、メンバー以上フォルダのファイルは SUPPORTER に出ない")
        void AC_F1_フォルダからの継承() throws Exception {
            Long team = privateTeam("F1I-" + kw);
            Roles r = rolesIn(team);
            Long inAdmins = fileIn(teamFolder(team, FileVisibilityRole.ADMINS_AND_ABOVE), r.admin(),
                    kw + " 管理者フォルダ.pdf", null, null);
            Long inMembers = fileIn(teamFolder(team, FileVisibilityRole.MEMBERS_AND_ABOVE), r.admin(),
                    kw + " メンバーフォルダ.pdf", null, null);
            Long inSupporters = fileIn(teamFolder(team, FileVisibilityRole.SUPPORTERS_AND_ABOVE), r.admin(),
                    kw + " 応援者フォルダ.pdf", null, null);

            assertExactlyVisible(r.admin(), Set.of(), inAdmins, inMembers, inSupporters);
            assertExactlyVisible(r.deputy(), Set.of(inAdmins), inMembers, inSupporters);
            assertExactlyVisible(r.member(), Set.of(inAdmins), inMembers, inSupporters);
            assertExactlyVisible(r.supporter(), Set.of(inAdmins, inMembers), inSupporters);
        }

        @Test
        @DisplayName("AC-F1: ファイル値がフォルダ値より緩くても、フォルダ値を満たさなければ出ない（一覧経路はフォルダ認可の後にファイル値で絞る）。厳しい方が効く")
        void AC_F1_ファイルとフォルダの両方を満たす() throws Exception {
            Long team = privateTeam("F1B-" + kw);
            Roles r = rolesIn(team);
            Long looserFile = fileIn(teamFolder(team, FileVisibilityRole.ADMINS_AND_ABOVE), r.admin(),
                    kw + " 管理者フォルダの応援者以上.pdf", null, FileVisibilityRole.SUPPORTERS_AND_ABOVE);
            Long stricterFile = fileIn(teamFolder(team, FileVisibilityRole.SUPPORTERS_AND_ABOVE), r.admin(),
                    kw + " 応援者フォルダの管理者限定.pdf", null, FileVisibilityRole.ADMINS_AND_ABOVE);
            Long membersInSupporters = fileIn(teamFolder(team, FileVisibilityRole.MEMBERS_AND_ABOVE), r.admin(),
                    kw + " メンバーフォルダの応援者以上.pdf", null, FileVisibilityRole.SUPPORTERS_AND_ABOVE);

            assertExactlyVisible(r.admin(), Set.of(), looserFile, stricterFile, membersInSupporters);
            assertExactlyVisible(r.deputy(), Set.of(looserFile, stricterFile), membersInSupporters);
            assertExactlyVisible(r.member(), Set.of(looserFile, stricterFile), membersInSupporters);
            assertExactlyVisible(r.supporter(), Set.of(looserFile, stricterFile, membersInSupporters));
        }

        @Test
        @DisplayName("AC-F1（多段）: 祖先フォルダの min_visible_role も満たす必要がある。管理者限定フォルダ配下の NULL サブフォルダのファイルは ADMIN のみ、メンバー以上の根の孫フォルダのファイルは SUPPORTER に出ない")
        void AC_F1_祖先フォルダの最低可視ロール() throws Exception {
            Long team = privateTeam("F1M-" + kw);
            Roles r = rolesIn(team);
            Long adminsRoot = teamFolder(team, FileVisibilityRole.ADMINS_AND_ABOVE);
            Long underAdmins = fileIn(teamSubFolder(team, adminsRoot, null), r.admin(),
                    kw + " 管理者フォルダの子.pdf", null, null);
            Long membersRoot = teamFolder(team, FileVisibilityRole.MEMBERS_AND_ABOVE);
            Long child = teamSubFolder(team, membersRoot, null);
            Long grandChild = teamSubFolder(team, child, FileVisibilityRole.SUPPORTERS_AND_ABOVE);
            Long underMembers = fileIn(grandChild, r.admin(), kw + " メンバー根の孫.pdf", null, null);
            Long plainRoot = teamFolder(team, null);
            Long plainDeep = fileIn(teamSubFolder(team, teamSubFolder(team, plainRoot, null), null), r.admin(),
                    kw + " 制限なしの深い階層.pdf", null, null);

            assertExactlyVisible(r.admin(), Set.of(), underAdmins, underMembers, plainDeep);
            assertExactlyVisible(r.deputy(), Set.of(underAdmins), underMembers, plainDeep);
            assertExactlyVisible(r.member(), Set.of(underAdmins), underMembers, plainDeep);
            assertExactlyVisible(r.supporter(), Set.of(underAdmins, underMembers), plainDeep);
        }

        @Test
        @DisplayName("AC-F1: 組織ファイルでも同じ。管理者限定は組織 MEMBER に出ず組織 ADMIN に出る。メンバー以上フォルダは組織 SUPPORTER に出ない")
        void AC_F1_組織ファイルの最低可視ロール() throws Exception {
            Long org = privateOrg("F1O-" + kw);
            Long admin = viewer("f1o-admin");
            orgMember(admin, org);
            orgAdmin(admin, org);
            Long member = viewer("f1o-member");
            orgMember(member, org);
            Long supporter = viewer("f1o-supporter");
            inTx(() -> {
                MembershipTestHelper.insertMembership(em, supporter, ScopeType.ORGANIZATION, org, RoleKind.SUPPORTER);
                return null;
            });
            Long admins = fileIn(orgFolder(org, null), member, kw + " 組織管理者限定.pdf", null,
                    FileVisibilityRole.ADMINS_AND_ABOVE);
            Long members = fileIn(orgFolder(org, FileVisibilityRole.MEMBERS_AND_ABOVE), member,
                    kw + " 組織メンバーフォルダ.pdf", null, null);
            Long plain = orgFile(org, member, kw + " 組織全員.pdf");

            assertExactlyVisible(admin, Set.of(), admins, members, plain);
            assertExactlyVisible(member, Set.of(admins), members, plain);
            assertExactlyVisible(supporter, Set.of(admins, members), plain);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-F1: 個別権限（file_permissions）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個別権限 file_permissions（F1）")
    class IndividualPermission {

        @Test
        @DisplayName("AC-F1（個別権限は広げない）: 非所属者本人（USER）・非所属者のチーム（TEAM）への READ 付与があっても、チーム X のファイルは非所属者に出ない（ファイル付与・フォルダ付与とも）")
        void 個別権限で可視範囲は広がらない() throws Exception {
            Long teamX = privateTeam("PX-" + kw);
            Long teamY = privateTeam("PY-" + kw);
            Long owner = viewer("px-owner");
            teamMember(owner, teamX);
            Long outsider = viewer("px-outsider");
            teamMember(outsider, teamY);
            Long fileGranted = teamFile(teamX, owner, kw + " 個人付与.pdf");
            permission("FILE", fileGranted, PermissionTargetType.USER, outsider);
            Long teamGranted = teamFile(teamX, owner, kw + " チーム付与.pdf");
            permission("FILE", teamGranted, PermissionTargetType.TEAM, teamY);
            Long grantedFolder = teamFolder(teamX, null);
            permission("FOLDER", grantedFolder, PermissionTargetType.USER, outsider);
            fileIn(grantedFolder, owner, kw + " フォルダ付与.pdf", null, null);

            assertNothingVisible(search(outsider, kw), FILE);
            JsonNode detail = search(outsider, kw, FILE);
            assertNothingVisible(detail, FILE);
            assertThat(detail.path("meta").path("total").asLong()).isZero();
        }

        @Test
        @DisplayName("AC-F1（個別権限は fail-closed）: ファイル自身・直属フォルダ・祖先フォルダのいずれかに個別権限行があるファイルは、所属者・ADMIN・被付与者本人にも結果・件数ともに出ない（権限行の無いファイルは出る）")
        void 個別権限の設定されたファイルは出ない() throws Exception {
            Long team = privateTeam("PF-" + kw);
            Long admin = viewer("pf-admin");
            teamMember(admin, team);
            teamAdmin(admin, team);
            Long grantee = viewer("pf-grantee");
            teamMember(grantee, team);
            Long other = viewer("pf-other");
            teamMember(other, team);

            Set<Long> restricted = new HashSet<>();
            Long onFile = teamFile(team, admin, kw + " ファイルに付与.pdf");
            permission("FILE", onFile, PermissionTargetType.USER, grantee);
            restricted.add(onFile);
            Long directFolder = teamFolder(team, null);
            permission("FOLDER", directFolder, PermissionTargetType.USER, grantee);
            restricted.add(fileIn(directFolder, admin, kw + " 直属フォルダに付与.pdf", null, null));
            Long ancestor = teamFolder(team, null);
            permission("FOLDER", ancestor, PermissionTargetType.TEAM, team);
            restricted.add(fileIn(teamSubFolder(team, teamSubFolder(team, ancestor, null), null), admin,
                    kw + " 祖先フォルダに付与.pdf", null, null));
            Long free = teamFile(team, admin, kw + " 付与なし.pdf");

            for (Long viewer : List.of(admin, grantee, other)) {
                assertExactlyVisible(viewer, restricted, free);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 対象外スコープ（PERSONAL / TOURNAMENT / TOURNAMENT_DIVISION）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PERSONAL フォルダのファイルは所有者本人にも出ない。TOURNAMENT / TOURNAMENT_DIVISION フォルダのファイルは organization_id が自組織（主催組織）でも組織の ADMIN・MEMBER に出ない")
    void 対象外スコープのフォルダは出ない() throws Exception {
        Long org = privateOrg("TO-" + kw);
        Long admin = viewer("to-admin");
        orgMember(admin, org);
        orgAdmin(admin, org);
        Long member = viewer("to-member");
        orgMember(member, org);
        Set<Long> outOfScope = new HashSet<>();
        for (Long owner : List.of(admin, member)) {
            Long personal = folder(FileScopeType.PERSONAL, null, null, owner, null, null, null);
            outOfScope.add(fileIn(personal, owner, kw + " 個人フォルダ.pdf", null, null));
        }
        Long tournament = folder(FileScopeType.TOURNAMENT, null, org, null, 987654321L, null, null);
        outOfScope.add(fileIn(tournament, admin, kw + " 大会フォルダ.pdf", null, null));
        Long division = folder(FileScopeType.TOURNAMENT_DIVISION, null, org, null, 987654322L, null, null);
        outOfScope.add(fileIn(division, admin, kw + " ディビジョンフォルダ.pdf", null, null));
        Long orgVisible = orgFile(org, admin, kw + " 組織フォルダ.pdf");

        for (Long viewer : List.of(admin, member)) {
            assertExactlyVisible(viewer, outOfScope, orgVisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(FILE): 非所属スコープ（ファイルあり）指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0、data/meta/error）。自スコープ指定ではそのスコープのファイルだけが出る")
    void AC_A3_FILE_存在オラクルなし() throws Exception {
        Long foreign = publicTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        teamFile(foreign, foreignAuthor, kw + " 他所の資料.pdf");
        Long mine = privateTeam("M-" + kw);
        Long myOrg = privateOrg("MO-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        orgMember(me, myOrg);
        Long mineFile = teamFile(mine, me, kw + " 自チームの資料.pdf");
        Long orgFile = orgFile(myOrg, me, kw + " 自組織の資料.pdf");

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", FILE, "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", FILE, "scopeType", "TEAM",
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
        assertThat(typeCount(foreignDetail, FILE)).isZero();
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", FILE, "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, FILE)).as("スコープ指定時はそのスコープのファイルだけ").containsExactly(mineFile);
        assertThat(resultIds(ownScope, FILE)).doesNotContain(orgFile);
        assertThat(typeCount(ownScope, FILE)).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);

        JsonNode orgScope = search(me, Map.of("q", kw, "type", FILE, "scopeType", "ORGANIZATION",
                "scopeId", myOrg.toString()));
        assertThat(resultIds(orgScope, FILE)).containsExactly(orgFile);
        assertThat(typeCount(orgScope, FILE)).isEqualTo(1);

        JsonNode unscoped = search(me, kw, FILE);
        assertThat(resultIds(unscoped, FILE)).as("スコープ指定なしでも他所のファイルは出ない（公開経路なし）")
                .containsExactlyInAnyOrder(mineFile, orgFile);
        assertThat(typeCount(unscoped, FILE)).isEqualTo(2);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(FILE): GUEST ロールしか持たないスコープ（公開チーム・非公開組織）のファイルは出ない（応援者以上フォルダ・制限なしとも）。同じチームの MEMBER には出る")
    void AC_A4_FILE_GUESTのみのスコープは母集団外() throws Exception {
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
        Long plain = teamFile(team, author, kw + " 所属者向け.pdf");
        Long supporters = fileIn(teamFolder(team, FileVisibilityRole.SUPPORTERS_AND_ABOVE), author,
                kw + " 応援者以上.pdf", null, null);
        orgFile(org, author, kw + " 組織の資料.pdf");

        assertNothingVisible(search(guest, kw), FILE);
        JsonNode guestDetail = search(guest, kw, FILE);
        assertNothingVisible(guestDetail, FILE);
        assertThat(guestDetail.path("meta").path("total").asLong()).isZero();

        JsonNode memberDetail = search(member, kw, FILE);
        assertThat(resultIds(memberDetail, FILE)).containsExactlyInAnyOrder(plain, supporters);
        assertThat(typeCount(memberDetail, FILE)).isEqualTo(2);
    }

    @Nested
    @DisplayName("論理削除・アーカイブ（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(FILE): 論理削除済みファイル、削除済みフォルダ直下の（未削除の）ファイル、削除済み祖先フォルダ配下の（未削除の）ファイルは、所属者・ADMIN・アップロード者本人にも出ない")
        void AC_A5_FILE_削除済みファイルとフォルダ配下は出ない() throws Exception {
            Long team = privateTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            teamAdmin(me, team);
            Long member = viewer("a5-member");
            teamMember(member, team);
            Long alive = teamFile(team, me, kw + " 生存.pdf");
            Set<Long> gone = new HashSet<>();
            Long deletedFile = teamFile(team, me, kw + " 削除済み.pdf");
            nativeUpdate("UPDATE shared_files SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deletedFile);
            gone.add(deletedFile);
            Long deletedFolder = teamFolder(team, null);
            gone.add(fileIn(deletedFolder, me, kw + " 削除済みフォルダ直下.pdf", null, null));
            nativeUpdate("UPDATE shared_folders SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deletedFolder);
            Long deletedAncestor = teamFolder(team, null);
            gone.add(fileIn(teamSubFolder(team, deletedAncestor, null), me, kw + " 削除済み祖先の孫.pdf", null, null));
            nativeUpdate("UPDATE shared_folders SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deletedAncestor);

            for (Long viewer : List.of(me, member)) {
                assertExactlyVisible(viewer, gone, alive);
            }
        }

        @Test
        @DisplayName("AC-A5(FILE): 論理削除・アーカイブ済み・未稼働（PROVISIONED）のチーム、論理削除・アーカイブ済みの組織のファイルは所属者に出ない")
        void AC_A5_FILE_削除済みスコープは出ない() throws Exception {
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
                gone.add(teamFile(t, me, kw + " チーム " + t + ".pdf"));
            }
            for (Long o : List.of(deletedOrg, archivedOrg)) {
                gone.add(orgFile(o, me, kw + " 組織 " + o + ".pdf"));
            }
            Long live = teamFile(liveTeam, me, kw + " 生存チーム.pdf");
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
        @DisplayName("AC-A6(FILE): 不可視のファイル（他チーム・管理者限定・管理者フォルダ・削除・削除フォルダ配下・個別権限・個人・大会）を足しても件数は変わらず、meta.total と typeCounts.FILE が一致する")
        void AC_A6_FILE_不可視を足しても件数不変() throws Exception {
            Long foreign = privateTeam("A6F-" + kw);
            Long mine = privateTeam("A6M-" + kw);
            Long myOrg = privateOrg("A6O-" + kw);
            Long me = viewer("a6-me");
            teamMember(me, mine);
            orgMember(me, myOrg);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            teamFile(mine, me, kw + " 1.pdf");
            fileIn(teamFolder(mine, FileVisibilityRole.MEMBERS_AND_ABOVE), stranger, kw + " 2.pdf", null, null);

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, FILE)).isEqualTo(2);

            teamFile(foreign, stranger, kw + " 他チーム.pdf");
            fileIn(teamFolder(mine, null), stranger, kw + " 管理者限定.pdf", null, FileVisibilityRole.ADMINS_AND_ABOVE);
            fileIn(teamSubFolder(mine, teamFolder(mine, FileVisibilityRole.ADMINS_AND_ABOVE), null), stranger,
                    kw + " 管理者フォルダの子.pdf", null, null);
            nativeUpdate("UPDATE shared_files SET deleted_at = UTC_TIMESTAMP() WHERE id = :id",
                    teamFile(mine, stranger, kw + " 削除.pdf"));
            Long deletedFolder = teamFolder(mine, null);
            fileIn(deletedFolder, stranger, kw + " 削除フォルダ配下.pdf", null, null);
            nativeUpdate("UPDATE shared_folders SET deleted_at = UTC_TIMESTAMP() WHERE id = :id", deletedFolder);
            permission("FILE", teamFile(mine, stranger, kw + " 個別権限.pdf"), PermissionTargetType.USER, me);
            fileIn(folder(FileScopeType.PERSONAL, null, null, me, null, null, null), me, kw + " 個人.pdf", null, null);
            fileIn(folder(FileScopeType.TOURNAMENT, null, myOrg, null, 987654323L, null, null), me,
                    kw + " 大会.pdf", null, null);

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, FILE);
            assertThat(typeCount(after, FILE)).isEqualTo(typeCount(before, FILE));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, FILE));
            assertThat(typeCount(detail, FILE)).isEqualTo(typeCount(after, FILE));
            assertThat(resultIds(detail, FILE)).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(FILE): 可視11件・不可視3件 → typeCounts.FILE=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を重複・欠落なく返す")
        void AC_C8_FILE_真の件数とページ送り() throws Exception {
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
            Long root = teamFolder(mine, null);
            for (int i = 0; i < 7; i++) {
                visible.add(fileIn(root, i % 2 == 0 ? me : stranger, kw + " チーム" + i + ".pdf", null, null));
            }
            Long sub = teamSubFolder(mine, root, FileVisibilityRole.MEMBERS_AND_ABOVE);
            for (int i = 0; i < 2; i++) {
                visible.add(fileIn(sub, stranger, kw + " サブ" + i + ".pdf", null, null));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(orgFile(myOrg, me, kw + " 組織" + i + ".pdf"));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(teamFile(foreign, stranger, kw + " 他チーム.pdf"));
            invisible.add(fileIn(root, stranger, kw + " 管理者限定.pdf", null, FileVisibilityRole.ADMINS_AND_ABOVE));
            invisible.add(fileIn(teamFolder(mine, FileVisibilityRole.ADMINS_AND_ABOVE), stranger,
                    kw + " 管理者フォルダ.pdf", null, null));

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, FILE)).isEqualTo(11);
            assertThat(resultIds(overview, FILE)).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", FILE, "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, FILE)).isEqualTo(11);
                all.addAll(resultIds(detail, FILE));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", FILE, "size", "50"));
            assertThat(resultIds(big, FILE)).hasSize(11).doesNotContainAnyElementsOf(invisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / 一致列 / URL / createdAt
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープ・一致列・遷移先・作成日時")
    class EscapingUrlAndTime {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視のファイルは混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(FILE): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならず、不可視は混ざらない")
        void AC_C11_FILE_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long mine = privateTeam("C11M-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            Long me = viewer("c11-me");
            teamMember(me, mine);
            Long invisible = fileIn(teamFolder(foreign, null), stranger, op + kw + op + " 他チーム.pdf", op + kw + op,
                    null);
            Long adminsOnly = fileIn(teamFolder(mine, null), stranger, op + kw + op + " 管理者限定.pdf", op + kw + op,
                    FileVisibilityRole.ADMINS_AND_ABOVE);
            Long visible = fileIn(teamFolder(mine, null), me, op + kw + op + " 自チーム.pdf", op + kw + op, null);

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", FILE));
                assertThat(resultIds(body, FILE)).doesNotContain(invisible, adminsOnly);
                assertThat(typeCount(body, FILE)).isLessThanOrEqualTo(1);
                assertThat(body.path("data").path("timedOutTypes").toString()).doesNotContain(FILE);
            }
            assertVisible(search(me, kw), FILE, visible, 1);
        }

        @Test
        @DisplayName("一致列: name だけの一致（説明なし）でも description だけの一致でもヒットし、両方一致でも 1 件として数える")
        void nameとdescriptionのどちらでもヒット() throws Exception {
            Long team = privateTeam("NM-" + kw);
            Long me = viewer("nd-me");
            teamMember(me, team);
            Long folder = teamFolder(team, null);
            Long byName = fileIn(folder, me, kw + " 名前で一致.pdf", null, null);
            Long byDescription = fileIn(folder, me, "名前は無関係.pdf", "説明文に " + kw + " を含む", null);
            Long both = fileIn(folder, me, kw + " 両方.pdf", "説明にも " + kw, null);
            fileIn(folder, me, "どちらにも無い.pdf", "無関係な説明", null);

            JsonNode detail = search(me, kw, FILE);
            assertThat(resultIds(detail, FILE)).containsExactlyInAnyOrder(byName, byDescription, both);
            assertThat(typeCount(detail, FILE)).isEqualTo(3);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(3);
            assertThat(typeCount(search(me, kw), FILE)).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-C12(FILE): title はファイル名のプレーン（<mark> なし・生のまま）で highlights.title が一致位置を指す。snippet は description 由来で HTML エスケープされ一致箇所だけが <mark>、200文字以下")
        void AC_C12_FILE_titleとsnippet() throws Exception {
            Long team = privateTeam("C12-" + kw);
            Long me = viewer("c12-me");
            teamMember(me, team);
            String name = "<i>" + kw + "</i> 資料.pdf";
            String description = "<script>alert(1)</script> 前文 " + kw + " 説明<b>太字</b>" + "あ".repeat(300);
            Long id = fileIn(teamFolder(team, null), me, name, description, null);

            JsonNode hit = hitOf(search(me, kw, FILE), id);
            assertThat(hit.path("title").asText()).isEqualTo(name).doesNotContain("<mark>");
            JsonNode range = hit.path("highlights").path("title");
            assertThat(range.isArray()).as("highlights.title に [start, end]").isTrue();
            assertThat(name.substring(range.get(0).asInt(), range.get(1).asInt())).isEqualToIgnoringCase(kw);

            String snippet = hit.path("snippet").asText();
            assertThat(snippet).contains("<mark>" + kw + "</mark>");
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<")
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(snippet.split("<mark>", -1)).as("<mark> は一致箇所の1か所だけ").hasSize(2);
        }

        @Test
        @DisplayName("FILE の結果（チーム）は type=FILE・url=/teams/{slug}/files（slug は teams.slug。数値 ID ではない）、scope はチーム、author はアップロード者")
        void URLはチームのファイル一覧() throws Exception {
            Long team = privateTeam("URL-" + kw);
            Long me = viewer("url-me");
            teamMember(me, team);
            String slug = jdbc.queryForObject("SELECT slug FROM teams WHERE id = ?", String.class, team);
            Long id = fileIn(teamSubFolder(team, teamFolder(team, null), null), me, kw + " 遷移先.pdf", null, null);

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo(FILE);
            assertThat(hit.path("url").asText()).isEqualTo("/teams/" + slug + "/files");
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("TEAM");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(team);
            assertThat(hit.path("author").path("id").asLong()).isEqualTo(me);
            assertThat(typeCount(overview, FILE)).as("FILE の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }

        @Test
        @DisplayName("FILE の結果（組織）は url=/organizations/{slug}/files、scope は組織")
        void URLは組織のファイル一覧() throws Exception {
            Long org = privateOrg("URLO-" + kw);
            Long me = viewer("urlo-me");
            orgMember(me, org);
            String slug = jdbc.queryForObject("SELECT slug FROM organizations WHERE id = ?", String.class, org);
            Long id = orgFile(org, me, kw + " 組織遷移先.pdf");

            JsonNode hit = hitOf(search(me, kw, FILE), id);
            assertThat(hit.path("url").asText()).isEqualTo("/organizations/" + slug + "/files");
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("ORGANIZATION");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(org);
        }

        @Test
        @DisplayName("createdAt: DB の UTC 格納値 00:00 のファイルの createdAt は同一瞬間（JST 09:00+09:00）")
        void createdAtは同一瞬間() throws Exception {
            Long team = privateTeam("CA-" + kw);
            Long me = viewer("ca-me");
            teamMember(me, team);
            Long id = teamFile(team, me, kw + " 作成日時.pdf");
            nativeUpdate("UPDATE shared_files SET created_at = '2026-03-10 00:00:00' WHERE id = :id", id);

            JsonNode hit = hitOf(search(me, kw, FILE), id);
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
        JsonNode detail = search(viewer, Map.of("q", kw, "type", FILE, "size", "50"));
        assertThat(resultIds(detail, FILE)).as("viewer=%s の結果", viewer).containsExactlyInAnyOrder(expected);
        // AssertJ の doesNotContainAnyElementsOf は空集合を渡すと IllegalArgumentException になるため、
        // 不可視の指定があるときだけ確かめる（containsExactlyInAnyOrder が既に余分な ID を許さない）
        if (!neverVisible.isEmpty()) {
            assertThat(resultIds(detail, FILE)).as("viewer=%s の結果（不可視）", viewer)
                    .doesNotContainAnyElementsOf(neverVisible);
        }
        assertThat(typeCount(detail, FILE)).as("viewer=%s の typeCounts", viewer).isEqualTo(expected.length);
        assertThat(detail.path("meta").path("total").asLong()).as("viewer=%s の meta.total", viewer)
                .isEqualTo(expected.length);
        assertThat(typeCount(search(viewer, kw), FILE)).as("viewer=%s の概要 typeCounts", viewer)
                .isEqualTo(expected.length);
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (FILE.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("FILE id=" + id + " が結果に無い（応答: " + body + "）");
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

    /** 任意のスコープのフォルダを commit する。 */
    private Long folder(FileScopeType scopeType, Long teamId, Long orgId, Long userId, Long scopeRefId, Long parentId,
                        FileVisibilityRole minVisibleRole) {
        Long id = inTx(() -> {
            SharedFolderEntity folder = SharedFolderEntity.builder().scopeType(scopeType).teamId(teamId)
                    .organizationId(orgId).userId(userId).scopeRefId(scopeRefId).parentId(parentId)
                    .name("フォルダ-" + UUID.randomUUID().toString().substring(0, 8))
                    .minVisibleRole(minVisibleRole).build();
            em.persist(folder);
            em.flush();
            return folder.getId();
        });
        createdFolderIds.add(id);
        return id;
    }

    /** チームのルートフォルダ。 */
    private Long teamFolder(Long teamId, FileVisibilityRole minVisibleRole) {
        return folder(FileScopeType.TEAM, teamId, null, null, null, null, minVisibleRole);
    }

    /** チームのサブフォルダ。 */
    private Long teamSubFolder(Long teamId, Long parentId, FileVisibilityRole minVisibleRole) {
        return folder(FileScopeType.TEAM, teamId, null, null, null, parentId, minVisibleRole);
    }

    /** 組織のルートフォルダ。 */
    private Long orgFolder(Long orgId, FileVisibilityRole minVisibleRole) {
        return folder(FileScopeType.ORGANIZATION, null, orgId, null, null, null, minVisibleRole);
    }

    /** フォルダにファイルを commit する。 */
    private Long fileIn(Long folderId, Long uploaderId, String name, String description,
                        FileVisibilityRole minVisibleRole) {
        Long id = inTx(() -> {
            SharedFileEntity file = SharedFileEntity.builder().folderId(folderId).name(name)
                    .fileKey("shared/test/" + UUID.randomUUID()).fileSize(1024L).contentType("application/pdf")
                    .description(description).createdBy(uploaderId).minVisibleRole(minVisibleRole).build();
            em.persist(file);
            em.flush();
            return file.getId();
        });
        createdFileIds.add(id);
        return id;
    }

    /** 制限なしのチームルートフォルダ（新規）に置いた制限なしのファイル。 */
    private Long teamFile(Long teamId, Long uploaderId, String name) {
        return fileIn(teamFolder(teamId, null), uploaderId, name, null, null);
    }

    /** 制限なしの組織ルートフォルダ（新規）に置いた制限なしのファイル。 */
    private Long orgFile(Long orgId, Long uploaderId, String name) {
        return fileIn(orgFolder(orgId, null), uploaderId, name, null, null);
    }

    /** 個別権限（READ）を付ける。targetType は FILE / FOLDER。 */
    private void permission(String targetType, Long targetId, PermissionTargetType granteeType, Long granteeId) {
        inTx(() -> {
            em.persist(FilePermissionEntity.builder().targetType(targetType).targetId(targetId)
                    .permissionType(PermissionType.READ).permissionTargetType(granteeType)
                    .permissionTargetId(granteeId).build());
            em.flush();
            return null;
        });
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'shared_files' "
                        + "AND index_name = '" + FULLTEXT_INDEX + "'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE shared_files ADD FULLTEXT INDEX " + FULLTEXT_INDEX
                    + " (name, description) WITH PARSER ngram");
        }
    }
}
