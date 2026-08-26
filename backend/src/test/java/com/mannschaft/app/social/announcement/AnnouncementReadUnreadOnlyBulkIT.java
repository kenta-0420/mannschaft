package com.mannschaft.app.social.announcement;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 一括既読の件数上限・未読限定クエリの統合テスト（#2494 / #2530）。
 *
 * <p><b>目的</b>: 一括既読を「未読分だけを DB 側で絞るクエリのチャンク処理」に寄せた改修について、
 * <b>実 MySQL 経路で</b>次の 2 点を固定する。</p>
 * <ol>
 *   <li><b>可視性の 1 対 1</b>（最重要）— 未読抽出クエリ
 *       {@link AnnouncementFeedQueryRepository#findUnreadIdsByScope} が返す集合が、
 *       一覧クエリ {@link AnnouncementFeedQueryRepository#findByScope} の集合と
 *       <b>閲覧者ロールを変えても完全一致</b>すること。#2478 が確立した
 *       「一覧に出る集合＝既読にできる集合」を壊していないことの機械的な裏取りである。
 *       境界（期限切れの厳密な {@code >}・元コンテンツ削除済み・応援者と内輪限定）を
 *       フィクスチャに含めて突き合わせる。</li>
 *   <li><b>チャンクをまたぐ一括既読が 1 リクエストで完結</b>し、しかも
 *       不可視のお知らせには 1 行も作られないこと。2 回目の呼び出しで 1 行も増えない
 *       （＝未読だけを対象にしている）ことも併せて固定する。</li>
 * </ol>
 *
 * <p>認可そのものの契約は {@link SocialAnnouncementScopeContractIT} が担う。
 * 本 IT はその不変条件を「件数上限の改修が壊していないか」という角度から補強する。</p>
 *
 * <p>構成（{@code @AutoConfigureMockMvc(addFilters=false)} + {@code @Transactional}）は
 * {@link SocialAnnouncementScopeContractIT} と完全に一致させている
 * （TestContext Cache を分裂させないため。{@link AbstractMySqlIntegrationTest} の注意書き参照）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("お知らせ一括既読 件数上限・未読限定クエリ 統合テスト（#2494）")
class AnnouncementReadUnreadOnlyBulkIT extends AbstractMySqlIntegrationTest {

    /** 一覧クエリ側に渡す「事実上の無制限」件数（1 対 1 突き合わせ用）。 */
    private static final int UNLIMITED = 10_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    @Autowired
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Autowired
    private AnnouncementReadStatusRepository readStatusRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long memberId;
    private Long supporterId;
    private Long outsiderId;

    private long sourceIdSeq = 700_000L;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("BULK チーム");
        memberId = insertUser("bulk-member@example.com");
        supporterId = insertUser("bulk-supporter@example.com");
        outsiderId = insertUser("bulk-outsider@example.com");

        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        // outsiderId はどこにも所属させない。
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 可視性の 1 対 1（一覧クエリ ↔ 未読抽出クエリ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 未読抽出クエリの可視集合が一覧クエリと完全一致する")
    class VisibilityParity {

        /**
         * 境界を全部載せたフィクスチャを作る。
         *
         * <p>可視性 3 種 × （期限なし / 未来期限 / 期限切れ / 元コンテンツ削除済み）。
         * 期限切れは {@code expiresAt > CURRENT_TIMESTAMP} の<b>厳密な {@code >}</b> の側に落ちる
         * 過去日時を入れる。オフセットは {@link SocialAnnouncementScopeContractIT} の期限切れ
         * フィクスチャ（{@code minusHours(1)}）に揃える（JVM/DB のタイムゾーン整合の前提を共有するため）。</p>
         */
        private void seedBoundaryFixture() {
            for (String visibility : List.of(
                    AnnouncementVisibility.PUBLIC,
                    AnnouncementVisibility.SUPPORTERS_AND_ABOVE,
                    AnnouncementVisibility.MEMBERS_AND_ABOVE)) {
                saveFeed(visibility, null, null);                                     // 期限なし・生存
                saveFeed(visibility, null, LocalDateTime.now().plusDays(1));          // 未来期限・生存
                saveFeed(visibility, null, LocalDateTime.now().minusHours(1));      // 期限切れ
                saveFeed(visibility, LocalDateTime.now().minusDays(1), null);         // 元コンテンツ削除済み
                saveFeed(visibility, LocalDateTime.now().minusDays(1),
                        LocalDateTime.now().plusDays(1));                             // 削除済み かつ 未来期限
            }
            em.flush();
        }

        private void assertParityFor(Long userId, String viewerRoleName) {
            Set<String> allowed = AnnouncementVisibility.allowedFor(viewerRoleName);

            Set<Long> listedIds = feedQueryRepository
                    .findByScope(AnnouncementScopeType.TEAM, teamId, allowed, null, UNLIMITED)
                    .stream()
                    .map(AnnouncementFeedEntity::getId)
                    .collect(Collectors.toSet());

            Set<Long> unreadIds = Set.copyOf(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, userId, null, UNLIMITED));

            assertThat(unreadIds)
                    .as("既読ゼロの閲覧者(%s)では『未読かつ可視』＝『一覧に出る集合』でなければならない",
                            viewerRoleName)
                    .isEqualTo(listedIds);
            // 空集合同士の一致で空虚に緑にならないよう、非空であることも固定する
            assertThat(listedIds).as("フィクスチャが可視な行を含んでいること").isNotEmpty();
        }

        @Test
        @DisplayName("MEMBER: 未読抽出集合＝一覧集合（内輪も含む・期限切れ／削除済みは除く）")
        void member_1対1() {
            seedBoundaryFixture();
            assertParityFor(memberId, "MEMBER");
        }

        @Test
        @DisplayName("SUPPORTER: 未読抽出集合＝一覧集合（内輪限定は両方に出ない）")
        void supporter_1対1() {
            seedBoundaryFixture();
            assertParityFor(supporterId, "SUPPORTER");

            // 応援者の集合に MEMBERS_AND_ABOVE が 1 件も混ざっていないことを直接固定する
            Set<Long> unreadIds = Set.copyOf(feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId,
                    AnnouncementVisibility.allowedFor("SUPPORTER"), supporterId, null, UNLIMITED));
            List<AnnouncementFeedEntity> picked = feedRepository.findByIdIn(unreadIds);
            assertThat(picked).isNotEmpty();
            assertThat(picked).allSatisfy(feed ->
                    assertThat(feed.getVisibility()).isNotEqualTo(AnnouncementVisibility.MEMBERS_AND_ABOVE));
        }

        @Test
        @DisplayName("部外者(PUBLICロール): 未読抽出集合＝一覧集合（PUBLIC のみ）")
        void outsider_1対1() {
            seedBoundaryFixture();
            assertParityFor(outsiderId, "PUBLIC");
        }

        @Test
        @DisplayName("既読を1件付けると未読抽出集合からその1件だけが消える（一覧集合との差分が既読分と一致）")
        void 既読分だけが差分になる() {
            seedBoundaryFixture();
            Set<String> allowed = AnnouncementVisibility.allowedFor("MEMBER");

            List<Long> before = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, null, UNLIMITED);
            assertThat(before).isNotEmpty();

            Long readTarget = before.get(0);
            insertReadStatus(readTarget, memberId);
            em.flush();

            List<Long> after = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, null, UNLIMITED);

            assertThat(after).hasSize(before.size() - 1);
            assertThat(after).doesNotContain(readTarget);
            assertThat(after).containsExactlyElementsOf(before.subList(1, before.size()));
        }

        /**
         * {@code NOT EXISTS} の相関条件が {@code r.userId = :userId} に束縛されていることを固定する。
         *
         * <p>束縛が落ちる（あるいは誤ったユーザーに束縛される）と、<b>他人が既読にしたお知らせが
         * 自分の未読集合から消える</b> — 一括既読で既読化されず、未読バッジにも出ないまま
         * 見落とされる。コード読解では正しいが、実 DB で他ユーザーの既読行を実際に置いて
         * 縮まないことを確認しておく。</p>
         */
        @Test
        @DisplayName("他ユーザーの既読行は自分の未読集合を1件も縮めない（NOT EXISTS の userId 束縛）")
        void 他ユーザーの既読は自分の未読集合に影響しない() {
            seedBoundaryFixture();
            Set<String> allowed = AnnouncementVisibility.allowedFor("MEMBER");

            List<Long> before = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, null, UNLIMITED);
            assertThat(before).isNotEmpty();

            // 別ユーザーが同じお知らせを全件既読にする
            for (Long feedId : before) {
                insertReadStatus(feedId, supporterId);
            }
            em.flush();

            List<Long> after = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, null, UNLIMITED);

            assertThat(after)
                    .as("他ユーザー(id=%d)の既読行が自分(id=%d)の未読集合を縮めてはならない",
                            supporterId, memberId)
                    .containsExactlyElementsOf(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. チャンクをまたぐ一括既読
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. チャンク上限を超える一括既読が1リクエストで完結する")
    class ChunkedMarkAll {

        /** チャンク境界（500）を確実にまたぐ可視件数。 */
        private static final int VISIBLE_COUNT = AnnouncementReadService.MARK_ALL_BATCH_SIZE + 60;

        /** 一覧に出ない（＝既読行を作ってはいけない）件数。 */
        private static final int INVISIBLE_COUNT = 30;

        @Test
        @DisplayName("チャンク上限超の未読でも1リクエストで全件既読になり、不可視には1行も作られない")
        void チャンクをまたいでも1リクエストで完結する() throws Exception {
            List<Long> visibleIds = new ArrayList<>();
            for (int i = 0; i < VISIBLE_COUNT; i++) {
                visibleIds.add(saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null));
            }
            List<Long> invisibleIds = new ArrayList<>();
            for (int i = 0; i < INVISIBLE_COUNT; i++) {
                // 期限切れ（一覧に出ない）
                invisibleIds.add(saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null,
                        LocalDateTime.now().minusHours(1)));
            }
            em.flush();

            setAuth(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk())
                    // #2530 ①: 応答が実件数を伝える（ハードコードの 0 ではない）
                    .andExpect(jsonPath("$.data.markedCount").value(VISIBLE_COUNT))
                    .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
            em.flush();

            assertThat(countReadStatusByUser(memberId))
                    .as("チャンク（%d 件）を超える %d 件が 1 リクエストで全件既読になること",
                            AnnouncementReadService.MARK_ALL_BATCH_SIZE, VISIBLE_COUNT)
                    .isEqualTo(VISIBLE_COUNT);
            for (Long invisibleId : invisibleIds) {
                assertThat(countReadStatus(invisibleId, memberId))
                        .as("一覧に出ないお知らせ(%d)には既読行を作らない", invisibleId)
                        .isZero();
            }
            // チャンクの先頭・境界前後・末尾に実際に行が作られていること（件数だけの空虚な緑を防ぐ）
            for (int idx : new int[]{0,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE - 1,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE,
                    VISIBLE_COUNT - 1}) {
                assertThat(countReadStatus(visibleIds.get(idx), memberId))
                        .as("可視なお知らせ(index=%d)が既読になっていること", idx)
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("2回目の一括既読は1行も増やさない（未読だけを対象にしている）")
        void 二回目は行が増えない() throws Exception {
            for (int i = 0; i < VISIBLE_COUNT; i++) {
                saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            }
            em.flush();

            setAuth(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk());
            em.flush();
            long afterFirst = countReadStatusByUser(memberId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk());
            em.flush();

            assertThat(afterFirst).isEqualTo(VISIBLE_COUNT);
            assertThat(countReadStatusByUser(memberId)).isEqualTo(afterFirst);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. カーソル（lastSeenId）で再スキャンを線形化する（#2530 ②）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. lastSeenId カーソルが『二度読まない』かつ『取りこぼさない』")
    class CursorPaging {

        /** 可視なお知らせを {@code count} 件作り、ID 昇順のリストを返す。 */
        private List<Long> seedVisible(int count) {
            List<Long> created = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                created.add(saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null));
            }
            em.flush();
            return created.stream().sorted().toList();
        }

        @Test
        @DisplayName("カーソルを渡すとその ID 以下は 1 件も返らない（同じ行を二度読まない）")
        void カーソル以下は返らない() {
            List<Long> all = seedVisible(12);
            Set<String> allowed = AnnouncementVisibility.allowedFor("MEMBER");

            List<Long> firstChunk = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, null, 5);
            assertThat(firstChunk).containsExactlyElementsOf(all.subList(0, 5));

            Long cursor = firstChunk.get(firstChunk.size() - 1);
            List<Long> secondChunk = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, cursor, 5);

            assertThat(secondChunk)
                    .as("カーソル(%d)以下の ID を再スキャンしてはならない", cursor)
                    .allSatisfy(id -> assertThat(id).isGreaterThan(cursor));
            assertThat(secondChunk).containsExactlyElementsOf(all.subList(5, 10));
        }

        @Test
        @DisplayName("カーソルで全周回すると全件を 1 度ずつ、重複も取りこぼしもなく列挙できる")
        void 重複も取りこぼしもない() {
            List<Long> all = seedVisible(11);
            Set<String> allowed = AnnouncementVisibility.allowedFor("MEMBER");

            List<Long> collected = new ArrayList<>();
            Long cursor = null;
            for (int i = 0; i < 10; i++) {
                List<Long> chunk = feedQueryRepository.findUnreadIdsByScope(
                        AnnouncementScopeType.TEAM, teamId, allowed, memberId, cursor, 4);
                if (chunk.isEmpty()) {
                    break;
                }
                collected.addAll(chunk);
                cursor = chunk.get(chunk.size() - 1);
            }

            assertThat(collected).containsExactlyElementsOf(all);
            assertThat(collected).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("カーソルと NOT EXISTS は併用される（既読済みはカーソル以降でも除外される）")
        void カーソルと未読条件が併用される() {
            List<Long> all = seedVisible(8);
            Set<String> allowed = AnnouncementVisibility.allowedFor("MEMBER");

            // 6 番目（index 5）だけ先に既読にしておく
            Long alreadyRead = all.get(5);
            insertReadStatus(alreadyRead, memberId);
            em.flush();

            Long cursor = all.get(3);
            List<Long> chunk = feedQueryRepository.findUnreadIdsByScope(
                    AnnouncementScopeType.TEAM, teamId, allowed, memberId, cursor, 10);

            assertThat(chunk)
                    .as("カーソル超過かつ未読のものだけが返る")
                    .containsExactly(all.get(4), all.get(6), all.get(7));
        }

        @Test
        @DisplayName("チャンク境界ちょうど・+1 件でも read-all が全件既読にする（実 HTTP 経路）")
        void 境界ちょうどと1件超えで取りこぼさない() throws Exception {
            int count = AnnouncementReadService.MARK_ALL_BATCH_SIZE + 1;
            List<Long> all = seedVisible(count);

            setAuth(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.markedCount").value(count))
                    .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
            em.flush();

            assertThat(countReadStatusByUser(memberId)).isEqualTo(count);
            // 境界の直前・直後・末尾が確実に既読になっている（件数だけの空虚な緑を防ぐ）
            for (int idx : new int[]{AnnouncementReadService.MARK_ALL_BATCH_SIZE - 1,
                    AnnouncementReadService.MARK_ALL_BATCH_SIZE,
                    count - 1}) {
                assertThat(countReadStatus(all.get(idx), memberId))
                        .as("index=%d が既読になっていること", idx)
                        .isEqualTo(1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 既読 INSERT の冪等性（#2530 ⑤ uq_ars_feed_user 違反 → 500 の根治）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 既読 INSERT が DB 側で冪等（同時実行で UNIQUE 違反にならない）")
    class Idempotency {

        @Test
        @DisplayName("同じ (feedId, userId) を二度 UPSERT しても例外にならず 1 行のまま")
        void 二度のUPSERTでも1行() {
            Long feedId = saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            em.flush();

            readStatusRepository.insertReadStatusesIgnoringExisting(memberId, List.of(feedId));
            // 同時実行の 2 本目が同じ未読 ID を拾った状況そのもの。
            // 素の INSERT なら uq_ars_feed_user 違反で 500 になる経路。
            readStatusRepository.insertReadStatusesIgnoringExisting(memberId, List.of(feedId));

            assertThat(countReadStatus(feedId, memberId)).isEqualTo(1);
        }

        @Test
        @DisplayName("既読済みと未読が混ざったチャンクでも、未読分だけが増える")
        void 既読混在チャンクでも未読分だけ増える() {
            Long alreadyRead = saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            Long fresh = saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            em.flush();
            insertReadStatus(alreadyRead, memberId);
            em.flush();

            readStatusRepository.insertReadStatusesIgnoringExisting(memberId, List.of(alreadyRead, fresh));

            assertThat(countReadStatus(alreadyRead, memberId)).isEqualTo(1);
            assertThat(countReadStatus(fresh, memberId)).isEqualTo(1);
            assertThat(countReadStatusByUser(memberId)).isEqualTo(2);
        }

        @Test
        @DisplayName("read-all を 2 連続で叩いても 200 のまま（500 に落ちない）")
        void readAll二連打でも200() throws Exception {
            for (int i = 0; i < 5; i++) {
                saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            }
            em.flush();

            setAuth(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.markedCount").value(5));
            em.flush();
            mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.markedCount").value(0))
                    .andExpect(jsonPath("$.data.hasMoreUnread").value(false));
            em.flush();

            assertThat(countReadStatusByUser(memberId)).isEqualTo(5);
        }

        @Test
        @DisplayName("単件既読の直後にもう一度同じお知らせを既読にしても 200（冪等・500 に落ちない）")
        void 単件既読二連打でも200() throws Exception {
            Long feedId = saveFeed(AnnouncementVisibility.MEMBERS_AND_ABOVE, null, null);
            em.flush();

            setAuth(memberId);
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/{id}/read", teamId, feedId))
                        .andExpect(status().isOk());
                em.flush();
            }

            assertThat(countReadStatus(feedId, memberId)).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long saveFeed(String visibility, LocalDateTime sourceDeletedAt, LocalDateTime expiresAt) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM)
                .scopeId(teamId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(++sourceIdSeq)
                .titleCache("BULK お知らせ " + sourceIdSeq)
                .visibility(visibility)
                .sourceDeletedAt(sourceDeletedAt)
                .expiresAt(expiresAt)
                .build();
        return feedRepository.save(feed).getId();
    }

    private void insertReadStatus(Long announcementFeedId, Long userId) {
        em.createNativeQuery(
                        "INSERT INTO announcement_read_status "
                                + "(announcement_feed_id, user_id, read_at, is_proxy_confirmed) "
                                + "VALUES (:aid, :uid, NOW(), 0)")
                .setParameter("aid", announcementFeedId)
                .setParameter("uid", userId)
                .executeUpdate();
    }

    private long countReadStatus(Long announcementId, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM announcement_read_status "
                                + "WHERE announcement_feed_id = :aid AND user_id = :uid")
                .setParameter("aid", announcementId)
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    private long countReadStatusByUser(Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM announcement_read_status WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'BULK', 'テスト', 'BULK テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('bulk-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
