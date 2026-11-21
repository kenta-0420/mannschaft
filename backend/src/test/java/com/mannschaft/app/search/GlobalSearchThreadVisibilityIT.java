package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索 THREAD（掲示板スレッド）種別の受け入れテスト（CMP-261116-0722 PR-4 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性（台帳 D4 / 設計書 §7）</b>: 次の<b>すべて</b>を満たすスレッドだけが出る。</p>
 * <ol>
 *   <li>{@code scope_type} が TEAM または ORGANIZATION で、その {@code scope_id} が集約側 {@link SearchViewer} の
 *       母集団（本人が MEMBER 以上で所属する、削除・アーカイブされていないスコープ）に含まれる。
 *       {@code BulletinThreadVisibilityResolver} は {@code SCOPE_AFFILIATED} 固定（visibility 列なし・
 *       ロール別の閲覧制限なし）なので、所属していればスレッドの閲覧に追加のロール条件は無い
 *       （カテゴリの {@code post_min_role} は<b>投稿</b>権限であり閲覧権限ではない）。</li>
 *   <li>PERSONAL / VILLAGE / TOURNAMENT / TOURNAMENT_DIVISION は対象外。{@code scope_id} は別種別の ID と
 *       数値が衝突し得る（TOURNAMENT の {@code scope_id} が閲覧者所属チームの ID と同値、等）ので、
 *       本試練は<b>衝突させた fixture</b>で「種別を見ずに ID だけで IN していない」ことを固定する。</li>
 *   <li>{@code deleted_at IS NULL} かつ {@code is_archived = FALSE}。</li>
 * </ol>
 *
 * <p><b>返信は対象外（AC-T2）</b>: 設計書 §7 の THREAD は「{@code bulletin_threads} の title / body」のみで、
 * {@code bulletin_replies} は検索対象に挙げていない（MESSAGE の「スレッド返信は親の可視性に従う」のような
 * 明記もない）。返信テーブルは scope 列を持たず、親スレッド経由の可視性を別述語で持つ必要があるため、
 * 本戦役では返信本文を THREAD として出さない。</p>
 *
 * <p><b>FULLTEXT</b>: 本番 V5.002 の {@code ft_bulletin_threads_title_body}（ngram）をテストスキーマにも補う
 * （{@code test-schema-fulltext-indexes.sql} と本クラスの {@link #ensureFulltextIndex()}）。InnoDB の FULLTEXT は
 * コミット済みの行だけを拾うため、fixture は基底の {@link #inTx} で commit する。キーワードは ngram の
 * ストップワードを含まない子音だけで作る。</p>
 *
 * <p><b>title の形</b>: 設計書 §3 / 既存契約 IT（AC-C12）に合わせ、title はプレーン（{@code <mark>} なし）で
 * {@code highlights.title} に {@code [start, end]} を返す。{@code <mark>} 付きエスケープになるのは snippet（本文由来）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 THREAD 種別の認可境界（CMP-261116-0722 PR-4 試練）")
class GlobalSearchThreadVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String THREAD = "THREAD";

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdThreadIds = new ArrayList<>();

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
    void cleanUpThreads() {
        inTx(() -> {
            for (Long threadId : createdThreadIds) {
                for (String sql : List.of(
                        "DELETE FROM bulletin_replies WHERE thread_id = :id",
                        "DELETE FROM bulletin_threads WHERE id = :id")) {
                    em.createNativeQuery(sql).setParameter("id", threadId).executeUpdate();
                }
            }
            return null;
        });
        createdThreadIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2: スコープの境界
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界（A1 / A2）")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(THREAD): チーム X のスレッドは X 非所属者の結果にも typeCounts.THREAD にも出ない（X の所属者には出る）")
        void AC_A1_THREAD_非所属チームのスレッドは出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long thread = teamThread(teamX, insider, kw + " チームXの連絡", "本文");

            assertNothingVisible(search(outsider, kw), THREAD);
            JsonNode outsiderDetail = search(outsider, kw, THREAD);
            assertNothingVisible(outsiderDetail, THREAD);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), THREAD, thread, 1);
            JsonNode insiderDetail = search(insider, kw, THREAD);
            assertVisible(insiderDetail, THREAD, thread, 1);
            assertThat(insiderDetail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A1(THREAD): 本文だけがキーワードに一致するスレッドも同じ境界で守られる（title/body 両方が検索対象）")
        void AC_A1_THREAD_本文一致も境界で守られる() throws Exception {
            Long teamX = privateTeam("XB-" + kw);
            Long insider = viewer("a1b-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1b-outsider");
            Long bodyOnly = teamThread(teamX, insider, "無関係な題", "本文に " + kw + " がある");
            Long titleOnly = teamThread(teamX, insider, kw + " 題だけ一致", "無関係な本文");

            JsonNode own = search(insider, kw, THREAD);
            assertThat(resultIds(own, THREAD)).containsExactlyInAnyOrder(bodyOnly, titleOnly);
            assertThat(typeCount(own, THREAD)).isEqualTo(2);
            assertNothingVisible(search(outsider, kw), THREAD);
            assertNothingVisible(search(outsider, kw, THREAD), THREAD);
        }

        @Test
        @DisplayName("AC-A2(THREAD): 組織 O のスレッドは別組織ユーザーに出ない（O の所属者には出る）")
        void AC_A2_THREAD_別組織のスレッドは出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long orgP = privateOrg("P-" + kw);
            Long insider = viewer("a2-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("a2-outsider");
            orgMember(outsider, orgP);
            Long t1 = orgThread(orgO, insider, kw + " 組織の連絡1", "本文");
            Long t2 = orgThread(orgO, insider, kw + " 組織の連絡2", "本文");

            assertNothingVisible(search(outsider, kw), THREAD);
            JsonNode outsiderDetail = search(outsider, kw, THREAD);
            assertNothingVisible(outsiderDetail, THREAD);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            JsonNode insiderBody = search(insider, kw, THREAD);
            assertThat(resultIds(insiderBody, THREAD)).containsExactlyInAnyOrder(t1, t2);
            assertThat(typeCount(insiderBody, THREAD)).isEqualTo(2);
            assertThat(insiderBody.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームのスレッドは出ない")
        void SYSTEM_ADMINはバイパスしない() throws Exception {
            Long team = privateTeam("SA-" + kw);
            Long author = viewer("sa-author");
            teamMember(author, team);
            Long admin = viewer("sysadmin");
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            teamThread(team, author, kw + " チーム内", "本文");

            assertNothingVisible(search(admin, kw), THREAD);
            assertNothingVisible(search(admin, kw, THREAD), THREAD);
        }

        @Test
        @DisplayName("authorId 指定でも不可視のスレッドは出ない（可視な同一著者のスレッドだけが出る）")
        void authorId指定でも不可視は出ない() throws Exception {
            Long foreign = privateTeam("AF-" + kw);
            Long mine = privateTeam("AM-" + kw);
            Long author = viewer("au-author");
            teamMember(author, foreign);
            teamMember(author, mine);
            Long me = viewer("au-me");
            teamMember(me, mine);
            teamThread(foreign, author, kw + " 他チーム", "本文");
            Long visible = teamThread(mine, author, kw + " 自チーム", "本文");

            JsonNode body = search(me, Map.of("q", kw, "type", THREAD, "authorId", author.toString()));
            assertThat(resultIds(body, THREAD)).containsExactly(visible);
            assertThat(typeCount(body, THREAD)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(THREAD): 非所属スコープ指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）。自スコープ指定ではそのスコープのスレッドだけが出る")
    void AC_A3_THREAD_存在オラクルなし() throws Exception {
        Long foreign = privateTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        teamThread(foreign, foreignAuthor, kw + " 他所のスレッド", "本文");
        Long mine = privateTeam("M-" + kw);
        Long myOrg = privateOrg("MO-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        orgMember(me, myOrg);
        Long mineThread = teamThread(mine, me, kw + " 自チームのスレッド", "本文");
        Long orgThread = orgThread(myOrg, me, kw + " 自組織のスレッド", "本文");

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", THREAD, "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", THREAD, "scopeType", "TEAM",
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
        assertThat(typeCount(foreignDetail, THREAD)).isZero();
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", THREAD, "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, THREAD)).as("スコープ指定時はそのスコープのスレッドだけ")
                .containsExactly(mineThread);
        assertThat(resultIds(ownScope, THREAD)).doesNotContain(orgThread);
        assertThat(typeCount(ownScope, THREAD)).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(THREAD): GUEST ロールしか持たないスコープのスレッドは出ない（MEMBER なら出る）")
    void AC_A4_THREAD_GUESTのみのスコープは母集団外() throws Exception {
        Long team = privateTeam("G-" + kw);
        Long org = privateOrg("GO-" + kw);
        Long author = viewer("a4-author");
        teamMember(author, team);
        orgMember(author, org);
        Long guest = viewer("a4-guest");
        teamGuest(guest, team);
        orgGuest(guest, org);
        Long member = viewer("a4-member");
        teamMember(member, team);
        Long teamThread = teamThread(team, author, kw + " チームのお知らせ", "本文");
        orgThread(org, author, kw + " 組織のお知らせ", "本文");

        assertNothingVisible(search(guest, kw), THREAD);
        JsonNode guestDetail = search(guest, kw, THREAD);
        assertNothingVisible(guestDetail, THREAD);
        assertThat(guestDetail.path("meta").path("total").asLong()).isZero();
        assertVisible(search(member, kw), THREAD, teamThread, 1);
    }

    @Nested
    @DisplayName("論理削除・アーカイブ（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(THREAD): 論理削除済み・アーカイブ済みスレッドは所属者にも出ない（作成者本人にも出ない）")
        void AC_A5_THREAD_削除済みアーカイブ済みスレッドは出ない() throws Exception {
            Long team = privateTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            Long alive = teamThread(team, me, kw + " 生存", "本文");
            Long deleted = teamThread(team, me, kw + " 削除済み", "本文");
            Long archived = teamThread(team, me, kw + " アーカイブ済み", "本文");
            nativeUpdate("UPDATE bulletin_threads SET deleted_at = NOW() WHERE id = :id", deleted);
            nativeUpdate("UPDATE bulletin_threads SET is_archived = TRUE WHERE id = :id", archived);

            JsonNode body = search(me, kw, THREAD);
            assertThat(resultIds(body, THREAD)).containsExactly(alive);
            assertThat(typeCount(body, THREAD)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
            assertThat(typeCount(search(me, kw), THREAD)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A5(THREAD): 論理削除済み・アーカイブ済みのチーム／組織のスレッドは所属者にも出ない")
        void AC_A5_THREAD_削除済みスコープは出ない() throws Exception {
            Long deletedTeam = privateTeam("DT-" + kw);
            Long archivedTeam = privateTeam("AT-" + kw);
            Long deletedOrg = privateOrg("DO-" + kw);
            Long archivedOrg = privateOrg("AO-" + kw);
            Long liveTeam = privateTeam("LT-" + kw);
            Long me = viewer("a5s-me");
            teamMember(me, deletedTeam);
            teamMember(me, archivedTeam);
            orgMember(me, deletedOrg);
            orgMember(me, archivedOrg);
            teamMember(me, liveTeam);
            teamThread(deletedTeam, me, kw + " 削除チーム", "本文");
            teamThread(archivedTeam, me, kw + " アーカイブチーム", "本文");
            orgThread(deletedOrg, me, kw + " 削除組織", "本文");
            orgThread(archivedOrg, me, kw + " アーカイブ組織", "本文");
            Long live = teamThread(liveTeam, me, kw + " 生存チーム", "本文");
            softDeleteTeam(deletedTeam);
            archiveTeam(archivedTeam);
            softDeleteOrg(deletedOrg);
            archiveOrg(archivedOrg);

            JsonNode body = search(me, kw, THREAD);
            assertThat(resultIds(body, THREAD)).containsExactly(live);
            assertThat(typeCount(body, THREAD)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-T1 / T2: THREAD 固有
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("THREAD 固有（T1 / T2）")
    class ThreadSpecific {

        @Test
        @DisplayName("AC-T1: PERSONAL / VILLAGE / TOURNAMENT / TOURNAMENT_DIVISION のスレッドは、scope_id が閲覧者所属チームの ID と衝突していても所属者・作成者に出ない（TEAM のものは出る）")
        void AC_T1_TEAMとORG以外のスコープは出ない() throws Exception {
            Long team = privateTeam("T1-" + kw);
            Long me = viewer("t1-me");
            teamMember(me, team);
            Long visible = teamThread(team, me, kw + " チーム", "本文");
            // scope_id を閲覧者所属チーム ID と同値にして、種別を見ずに ID だけで IN する実装を検出する。
            threadOf(ScopeType.PERSONAL, me, null, me, kw + " 個人(本人ID)", "本文");
            threadOf(ScopeType.PERSONAL, team, null, me, kw + " 個人(チームID衝突)", "本文");
            threadOf(ScopeType.TOURNAMENT, team, null, me, kw + " 大会(チームID衝突)", "本文");
            threadOf(ScopeType.TOURNAMENT_DIVISION, team, null, me, kw + " 部門(チームID衝突)", "本文");
            threadOf(ScopeType.VILLAGE, 0L, UUID.randomUUID(), me, kw + " 村", "本文");
            threadOf(ScopeType.VILLAGE, team, UUID.randomUUID(), me, kw + " 村(チームID衝突)", "本文");

            JsonNode overview = search(me, kw);
            JsonNode detail = search(me, kw, THREAD);
            assertThat(resultIds(overview, THREAD)).containsExactly(visible);
            assertThat(typeCount(overview, THREAD)).isEqualTo(1);
            assertThat(resultIds(detail, THREAD)).containsExactly(visible);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-T1: scope_type=ORGANIZATION で scope_id が閲覧者所属チームの ID と同値のスレッドは出ない（チーム ID と組織 ID を取り違えない）")
        void AC_T1_ORG種別でチームIDと衝突しても出ない() throws Exception {
            Long team = privateTeam("T1X-" + kw);
            Long otherOrg = privateOrg("T1XO-" + kw);
            Long author = viewer("t1x-author");
            orgMember(author, otherOrg);
            Long me = viewer("t1x-me");
            teamMember(me, team);
            // 閲覧者は組織に一切所属しない。組織スコープのスレッドの scope_id を所属チーム ID と同値にする。
            threadOf(ScopeType.ORGANIZATION, team, null, author, kw + " 組織(チームID衝突)", "本文");
            // 逆: scope_type=TEAM で scope_id が閲覧者所属組織の ID と同値。
            Long orgMe = viewer("t1x-org-me");
            orgMember(orgMe, otherOrg);
            threadOf(ScopeType.TEAM, otherOrg, null, author, kw + " チーム(組織ID衝突)", "本文");

            assertNothingVisible(search(me, kw), THREAD);
            assertNothingVisible(search(me, kw, THREAD), THREAD);
            assertNothingVisible(search(orgMe, kw), THREAD);
            assertNothingVisible(search(orgMe, kw, THREAD), THREAD);
        }

        @Test
        @DisplayName("AC-T1: 別スコープ（自分は所属しない別チーム）のスレッドは出ない。同じチームのスレッドとは混ざらない")
        void AC_T1_別スコープのスレッドは出ない() throws Exception {
            Long mine = privateTeam("T1M-" + kw);
            Long other = privateTeam("T1O-" + kw);
            Long author = viewer("t1m-author");
            teamMember(author, other);
            Long me = viewer("t1m-me");
            teamMember(me, mine);
            Long visible = teamThread(mine, me, kw + " 自チーム", "本文");
            Long invisible = teamThread(other, author, kw + " 別チーム", "本文");

            JsonNode body = search(me, kw, THREAD);
            assertThat(resultIds(body, THREAD)).containsExactly(visible).doesNotContain(invisible);
            assertThat(typeCount(body, THREAD)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-T2: 返信（bulletin_replies）の本文は THREAD として出さない。スレッドの title / body だけが対象で、返信にだけ一致するスレッドは結果にも件数にも出ない")
        void AC_T2_返信本文は対象外() throws Exception {
            Long team = privateTeam("T2-" + kw);
            Long me = viewer("t2-me");
            teamMember(me, team);
            Long thread = teamThread(team, me, "無関係な題", "無関係な本文");
            reply(thread, me, kw + " 返信にだけ書いたキーワード");

            JsonNode overview = search(me, kw);
            JsonNode detail = search(me, kw, THREAD);
            assertNothingVisible(overview, THREAD);
            assertNothingVisible(detail, THREAD);
            assertThat(detail.path("meta").path("total").asLong()).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A6 / AC-C8
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数の一貫性（A6 / C8）")
    class Counts {

        @Test
        @DisplayName("AC-A6(THREAD): 不可視（他チーム・削除済み・アーカイブ済み・PERSONAL・VILLAGE・TOURNAMENT）を足しても件数は変わらず、meta.total と typeCounts.THREAD が一致する")
        void AC_A6_THREAD_不可視を足しても件数不変() throws Exception {
            Long mine = privateTeam("A6M-" + kw);
            Long foreign = privateTeam("A6F-" + kw);
            Long me = viewer("a6-me");
            teamMember(me, mine);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            teamThread(mine, me, kw + " 1", "本文");
            teamThread(mine, stranger, kw + " 2", "本文");

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, THREAD)).isEqualTo(2);

            teamThread(foreign, stranger, kw + " 他チーム", "本文");
            nativeUpdate("UPDATE bulletin_threads SET deleted_at = NOW() WHERE id = :id",
                    teamThread(mine, stranger, kw + " 削除", "本文"));
            nativeUpdate("UPDATE bulletin_threads SET is_archived = TRUE WHERE id = :id",
                    teamThread(mine, stranger, kw + " アーカイブ", "本文"));
            threadOf(ScopeType.PERSONAL, stranger, null, stranger, kw + " 個人", "本文");
            threadOf(ScopeType.VILLAGE, 0L, UUID.randomUUID(), stranger, kw + " 村", "本文");
            threadOf(ScopeType.TOURNAMENT, mine, null, stranger, kw + " 大会", "本文");

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, THREAD);
            assertThat(typeCount(after, THREAD)).isEqualTo(typeCount(before, THREAD));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, THREAD));
            assertThat(typeCount(detail, THREAD)).isEqualTo(typeCount(after, THREAD));
            assertThat(resultIds(detail, THREAD)).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(THREAD): 可視11件・不可視3件 → typeCounts.THREAD=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を過不足なく返す")
        void AC_C8_THREAD_真の件数とページ送り() throws Exception {
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
            for (int i = 0; i < 9; i++) {
                visible.add(teamThread(mine, i % 2 == 0 ? me : stranger, kw + " チーム" + i, "本文"));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(orgThread(myOrg, me, kw + " 組織" + i, "本文"));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(teamThread(foreign, stranger, kw + " 他チーム", "本文"));
            invisible.add(threadOf(ScopeType.PERSONAL, me, null, me, kw + " 個人", "本文"));
            Long archived = teamThread(mine, me, kw + " アーカイブ", "本文");
            nativeUpdate("UPDATE bulletin_threads SET is_archived = TRUE WHERE id = :id", archived);
            invisible.add(archived);

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, THREAD)).isEqualTo(11);
            assertThat(resultIds(overview, THREAD)).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", THREAD, "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, THREAD)).isEqualTo(11);
                all.addAll(resultIds(detail, THREAD));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", THREAD, "size", "50"));
            assertThat(resultIds(big, THREAD)).hasSize(11).doesNotContainAnyElementsOf(invisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / URL / createdAt
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープ・遷移先・作成日時")
    class EscapingUrlAndTime {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視のスレッドは混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(THREAD): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならない")
        void AC_C11_THREAD_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long mine = privateTeam("C11M-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            Long me = viewer("c11-me");
            teamMember(me, mine);
            Long invisible = teamThread(foreign, stranger, op + kw + op + " 他チーム", op + kw + op);
            Long visible = teamThread(mine, me, op + kw + op + " 自チーム", op + kw + op);

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", THREAD));
                assertThat(resultIds(body, THREAD)).doesNotContain(invisible);
                assertThat(typeCount(body, THREAD)).isLessThanOrEqualTo(1);
                assertThat(body.path("data").path("timedOutTypes").toString()).doesNotContain(THREAD);
            }
            assertVisible(search(me, kw), THREAD, visible, 1);
        }

        @Test
        @DisplayName("AC-C12(THREAD): title はプレーン（<mark> なし・生のまま）で highlights.title が一致位置を指す。snippet は本文由来で HTML エスケープされ一致箇所だけが <mark>、200文字以下")
        void AC_C12_THREAD_titleとsnippet() throws Exception {
            Long team = privateTeam("C12-" + kw);
            Long me = viewer("c12-me");
            teamMember(me, team);
            String title = "<i>" + kw + "</i> 件名";
            String body = "<script>alert(1)</script> 前文 " + kw + " 本文<b>太字</b>" + "あ".repeat(300);
            Long id = teamThread(team, me, title, body);

            JsonNode hit = hitOf(search(me, kw, THREAD), id);
            assertThat(hit.path("title").asText()).isEqualTo(title).doesNotContain("<mark>");
            JsonNode range = hit.path("highlights").path("title");
            assertThat(range.isArray()).as("highlights.title に [start, end]").isTrue();
            assertThat(title.substring(range.get(0).asInt(), range.get(1).asInt())).isEqualToIgnoringCase(kw);

            String snippet = hit.path("snippet").asText();
            assertThat(snippet).contains("<mark>" + kw + "</mark>");
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>");
            assertThat(snippet).contains("&lt;script&gt;");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<")
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(snippet.split("<mark>", -1)).as("<mark> は一致箇所の1か所だけ").hasSize(2);
        }

        @Test
        @DisplayName("THREAD の結果（チーム）は type=THREAD・url=/teams/{slug}/bulletin?threadId={id}（slug は teams.slug。数値 ID ではない）")
        void URLはチームの掲示板() throws Exception {
            Long team = privateTeam("URL-" + kw);
            Long me = viewer("url-me");
            teamMember(me, team);
            Long id = teamThread(team, me, kw + " 遷移先", "本文");
            String slug = jdbc.queryForObject("SELECT slug FROM teams WHERE id = ?", String.class, team);

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo(THREAD);
            assertThat(hit.path("url").asText()).isEqualTo("/teams/" + slug + "/bulletin?threadId=" + id);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("TEAM");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(team);
            assertThat(typeCount(overview, THREAD)).as("THREAD の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }

        @Test
        @DisplayName("THREAD の結果（組織）は url=/organizations/{slug}/bulletin?threadId={id}")
        void URLは組織の掲示板() throws Exception {
            Long org = privateOrg("URLO-" + kw);
            Long me = viewer("urlo-me");
            orgMember(me, org);
            Long id = orgThread(org, me, kw + " 組織遷移先", "本文");
            String slug = jdbc.queryForObject("SELECT slug FROM organizations WHERE id = ?", String.class, org);

            JsonNode hit = hitOf(search(me, kw, THREAD), id);
            assertThat(hit.path("url").asText()).isEqualTo("/organizations/" + slug + "/bulletin?threadId=" + id);
            assertThat(hit.path("scope").path("type").asText()).isEqualTo("ORGANIZATION");
            assertThat(hit.path("scope").path("id").asLong()).isEqualTo(org);
        }

        @Test
        @DisplayName("createdAt: DB の UTC 格納値 00:00 のスレッドの createdAt は同一瞬間（JST 09:00+09:00）")
        void createdAtは同一瞬間() throws Exception {
            Long team = privateTeam("CA-" + kw);
            Long me = viewer("ca-me");
            teamMember(me, team);
            Long id = teamThread(team, me, kw + " 作成日時", "本文");
            jdbc.update("UPDATE bulletin_threads SET created_at = ? WHERE id = ?", "2026-03-10 00:00:00", id);

            JsonNode hit = hitOf(search(me, kw, THREAD), id);
            String text = hit.path("createdAt").asText();
            assertThat(text).as("createdAt が出ていること（%s）", hit).isNotBlank();
            assertThat(OffsetDateTime.parse(text).toInstant()).isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private Long teamThread(Long teamId, Long authorId, String title, String body) {
        return threadOf(ScopeType.TEAM, teamId, null, authorId, title, body);
    }

    private Long orgThread(Long orgId, Long authorId, String title, String body) {
        return threadOf(ScopeType.ORGANIZATION, orgId, null, authorId, title, body);
    }

    /** 任意の scope_type / scope_id のスレッドを commit する（scope_id を他種別の ID と衝突させる試験用）。 */
    private Long threadOf(ScopeType scopeType, Long scopeId, UUID villageId, Long authorId, String title,
                          String body) {
        Long id = inTx(() -> {
            BulletinThreadEntity thread = BulletinThreadEntity.builder().scopeType(scopeType).scopeId(scopeId)
                    .scopeVillageId(villageId).authorId(authorId).title(title).body(body).build();
            em.persist(thread);
            em.flush();
            return thread.getId();
        });
        createdThreadIds.add(id);
        return id;
    }

    private Long reply(Long threadId, Long authorId, String body) {
        return inTx(() -> {
            BulletinReplyEntity reply = BulletinReplyEntity.builder().threadId(threadId).authorId(authorId)
                    .body(body).build();
            em.persist(reply);
            em.flush();
            return reply.getId();
        });
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (THREAD.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("THREAD id=" + id + " が結果に無い（応答: " + body + "）");
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'bulletin_threads' "
                        + "AND index_name = 'ft_bulletin_threads_title_body'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE bulletin_threads ADD FULLTEXT INDEX ft_bulletin_threads_title_body "
                    + "(title, body) WITH PARSER ngram");
        }
    }
}
