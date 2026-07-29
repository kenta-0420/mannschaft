package com.mannschaft.app.recruitment;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #2497: 募集枠を論理削除したとき、配下に残る<b>未解決の無断欠席異議</b>が自動で取り下げられることの契約テスト。
 *
 * <h2>何が壊れていたか</h2>
 * <p>異議解決 EP が使う {@code RecruitmentNoShowRecordRepository#findByIdAndScopeTypeAndScopeId}（#2477 で新設）は
 * スコープ境界を得るために {@code RecruitmentListingEntity} を JOIN しており、募集枠側の
 * {@code @SQLRestriction("deleted_at IS NULL")} が効く。よって団体が募集枠を論理削除すると
 * （{@code RecruitmentListingService#archive} → {@code entity.softDelete()}）、
 * 配下の NO_SHOW 記録は<b>二度と裁定できなくなる</b>。</p>
 *
 * <p>一方ペナルティ判定の {@code countConfirmedNoShows} は
 * 「{@code dispute_resolution <> 'REVOKED' OR dispute_resolution IS NULL}」＝
 * <b>{@code REVOKED} 以外はすべて算入</b>という条件のため、未解決（{@code IS NULL}）の記録は
 * 算入され続ける。結果として利用者は「異議を申し立てたのに永久に裁かれず、ペナルティだけ負う」状態に置かれていた。</p>
 *
 * <h2>なぜ REVOKED を当てるか</h2>
 * <p>団体が募集枠を消して裁定の根拠を失わせた以上、裁かれないまま利用者にペナルティを負わせるのは不当。
 * {@code disputeResolution} の消費者は ①{@code countConfirmedNoShows}（本テストの AC-4 が実証）と
 * ②応答 DTO（表示のみ）の 2 つだけで、{@code REVOKED} 件数を団体の不正指標として数える統計は存在しない。</p>
 *
 * <h2>本 IT が唯一の実証である事項</h2>
 * <p>派生クエリ {@code findByListingIdAndDisputedTrueAndDisputeResolutionIsNull} の絞り込みが
 * 実 DB で本当に効くこと（＝解決済み・非異議・他スコープの記録を巻き込まないこと）は
 * 実 MySQL でしか確かめられない。単体テスト
 * {@code RecruitmentNoShowServiceTest.AutoRevokeOpenDisputesOnListingArchived} は
 * Repository をモックしているため<b>フィルタ条件を検証していない</b>（監査ログの引数と当てる値のみ）。</p>
 *
 * <p>金型: {@link RecruitmentNoShowScopeContractIT}（同ドメイン・同構成の契約 IT）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("#2497 募集枠論理削除に伴う未解決異議の自動取下げ 契約テスト")
class RecruitmentListingArchiveDisputeAutoRevokeIT extends AbstractMySqlIntegrationTest {

    /** ペナルティ集計期間の起点（全フィクスチャが確実に含まれる十分過去）。 */
    private static final LocalDateTime PENALTY_SINCE = LocalDateTime.now().minusDays(365);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentNoShowRecordRepository noShowRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    /** teamA の ADMIN（論理削除の実行者）。 */
    private Long adminAId;
    /** teamB の ADMIN。 */
    private Long adminBId;

    /** 未解決異議の当事者（AC-4 のペナルティ算入判定に使う専用ユーザー）。 */
    private Long openDisputeUserId;
    /** 既に UPHELD で裁定済みの当事者。 */
    private Long upheldUserId;
    /** 既に REVOKED で裁定済みの当事者。 */
    private Long alreadyRevokedUserId;
    /** 異議を申し立てていない当事者。 */
    private Long notDisputedUserId;
    /** teamB 側の未解決異議の当事者（巻き添え検知用）。 */
    private Long otherScopeUserId;
    /** 生存中の募集枠（teamB）に紐づく異議未申立の当事者（AC-10 非回帰用）。 */
    private Long liveNotDisputedUserId;

    /** 論理削除の対象となる teamA の募集枠。 */
    private Long listingAId;
    /** teamB の募集枠（触られてはならない）。 */
    private Long listingBId;

    /** teamA・未解決（disputed=true / resolution=null / confirmed=true）。 */
    private Long noShowOpenId;
    /** teamA・UPHELD で裁定済み。 */
    private Long noShowUpheldId;
    /** teamA・REVOKED で裁定済み。 */
    private Long noShowAlreadyRevokedId;
    /** teamA・異議なし（disputed=false）。 */
    private Long noShowNotDisputedId;
    /** teamB・未解決（他スコープ）。 */
    private Long noShowOtherScopeId;
    /** teamB（生存中）・異議未申立。AC-10 の非回帰で「生存中なら自動取下げしない」ことを固定する。 */
    private Long noShowLiveNotDisputedId;

    /**
     * NO_SHOW 記録ごとにユニークな participant_id を割り当てるためのカウンタ。
     *
     * <p>1 募集枠に複数の NO_SHOW 記録を作るため、participant_id を使い回すと
     * 「1 参加者 1 記録」を前提とする {@code findByParticipantId} の意味が壊れる。
     * 実在の参加者行は本テストの検証対象外なので、衝突しない値を採番するだけでよい。</p>
     */
    private long nextParticipantId;

    @BeforeEach
    void setUp() {
        nextParticipantId = 1L;
        teamAId = insertTeam("RCRT2497 チームA");
        teamBId = insertTeam("RCRT2497 チームB");

        adminAId = insertUser("rcrt2497-admin-a@example.com");
        adminBId = insertUser("rcrt2497-admin-b@example.com");
        openDisputeUserId = insertUser("rcrt2497-open@example.com");
        upheldUserId = insertUser("rcrt2497-upheld@example.com");
        alreadyRevokedUserId = insertUser("rcrt2497-revoked@example.com");
        notDisputedUserId = insertUser("rcrt2497-notdisputed@example.com");
        otherScopeUserId = insertUser("rcrt2497-otherscope@example.com");
        liveNotDisputedUserId = insertUser("rcrt2497-livenotdisputed@example.com");

        // isScopeAdmin（user_roles）と isMember（memberships）は別系統のため両方張る。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);

        listingAId = insertListing(RecruitmentScopeType.TEAM, teamAId, adminAId);
        listingBId = insertListing(RecruitmentScopeType.TEAM, teamBId, adminBId);

        noShowOpenId = insertNoShow(listingAId, openDisputeUserId, true, null);
        noShowUpheldId = insertNoShow(listingAId, upheldUserId, true, DisputeResolution.UPHELD);
        noShowAlreadyRevokedId = insertNoShow(listingAId, alreadyRevokedUserId, true, DisputeResolution.REVOKED);
        noShowNotDisputedId = insertNoShow(listingAId, notDisputedUserId, false, null);
        noShowOtherScopeId = insertNoShow(listingBId, otherScopeUserId, true, null);
        noShowLiveNotDisputedId = insertNoShow(listingBId, liveNotDisputedUserId, false, null);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 自動取下げの本体
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /api/v1/recruitment-listings/{id}/archive（募集枠の論理削除）")
    class ArchiveAutoRevoke {

        /**
         * AC-1: 未解決の異議（disputed=true かつ dispute_resolution IS NULL）が REVOKED になる。
         *
         * <p>修正前は archive が NO_SHOW 記録に一切触れないため resolution は NULL のままで red。</p>
         */
        @Test
        @DisplayName("AC-1: 論理削除で未解決の異議が REVOKED になる")
        void ac_1_未解決異議がREVOKEDになる() throws Exception {
            // 事前条件: 未解決であることを明示（テスト自体が空虚でないことの担保）
            assertThat(noShowRepository.findById(noShowOpenId).orElseThrow().getDisputeResolution())
                    .as("前提: 論理削除前は未解決（NULL）でなければならない")
                    .isNull();

            archiveListing(listingAId, adminAId);

            RecruitmentNoShowRecordEntity revoked = noShowRepository.findById(noShowOpenId).orElseThrow();
            assertThat(revoked.getDisputeResolution())
                    .as("裁定の根拠（募集枠）が消えた以上、未解決の異議は認容（REVOKED）として取り下げる")
                    .isEqualTo(DisputeResolution.REVOKED);
            assertThat(revoked.isDisputed())
                    .as("異議を申し立てた事実自体は履歴として残す")
                    .isTrue();
        }

        /**
         * AC-2（非回帰）: 既に管理者が裁定済みの異議は書き換えない。
         *
         * <p>特に {@code UPHELD}（却下）を {@code REVOKED} に反転させると、
         * 管理者の正当な裁定を募集枠削除が覆すことになり不当。</p>
         */
        @Test
        @DisplayName("AC-2: 既に解決済み（UPHELD / REVOKED）の異議は書き換えられない")
        void ac_2_解決済み異議は不変() throws Exception {
            archiveListing(listingAId, adminAId);

            assertThat(noShowRepository.findById(noShowUpheldId).orElseThrow().getDisputeResolution())
                    .as("管理者が却下（UPHELD）した裁定を募集枠削除が覆してはならない")
                    .isEqualTo(DisputeResolution.UPHELD);
            assertThat(noShowRepository.findById(noShowAlreadyRevokedId).orElseThrow().getDisputeResolution())
                    .as("既に認容済みの記録も再書き込み対象にしない")
                    .isEqualTo(DisputeResolution.REVOKED);
        }

        /** AC-3（非回帰）: 異議が申し立てられていない記録には触れない。 */
        @Test
        @DisplayName("AC-3: 異議未申立（disputed=false）の記録は触られない")
        void ac_3_異議未申立の記録は不変() throws Exception {
            archiveListing(listingAId, adminAId);

            RecruitmentNoShowRecordEntity untouched =
                    noShowRepository.findById(noShowNotDisputedId).orElseThrow();
            assertThat(untouched.getDisputeResolution())
                    .as("異議のない NO_SHOW を取り消す理由はない")
                    .isNull();
            assertThat(untouched.isDisputed())
                    .as("disputed フラグも立てられてはならない")
                    .isFalse();
        }

        /**
         * AC-4: 取り下げ後、当該レコードがペナルティ算入から外れる（本 issue の実害の解消）。
         *
         * <p>{@code countConfirmedNoShows} は「{@code REVOKED} 以外は算入」なので、
         * 未解決のままだと 1 件、REVOKED になると 0 件になる。
         * 比較のため、異議未申立ユーザーは論理削除後も 1 件のままであることを併せて固定する
         * （＝カウントが単に全滅しているのではないことの担保）。</p>
         */
        @Test
        @DisplayName("AC-4: 取り下げ後は countConfirmedNoShows に算入されなくなる")
        void ac_4_ペナルティ算入から外れる() throws Exception {
            assertThat(noShowRepository.countConfirmedNoShows(openDisputeUserId, PENALTY_SINCE))
                    .as("前提: 未解決の異議はペナルティに算入され続ける（これが #2497 の実害）")
                    .isEqualTo(1L);

            archiveListing(listingAId, adminAId);

            assertThat(noShowRepository.countConfirmedNoShows(openDisputeUserId, PENALTY_SINCE))
                    .as("REVOKED になった記録はペナルティ判定から除外される")
                    .isZero();
            assertThat(noShowRepository.countConfirmedNoShows(notDisputedUserId, PENALTY_SINCE))
                    .as("異議のない NO_SHOW は引き続き算入される（カウントの全滅ではない）")
                    .isEqualTo(1L);
            assertThat(noShowRepository.countConfirmedNoShows(upheldUserId, PENALTY_SINCE))
                    .as("却下（UPHELD）済みも引き続き算入される")
                    .isEqualTo(1L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 帰属（他スコープを巻き添えにしない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 帰属 — 他の募集枠・他スコープを巻き添えにしない")
    class Attribution {

        /**
         * AC-5: teamB の募集枠に紐づく未解決異議は、teamA の募集枠を論理削除しても不変。
         *
         * <p>絞り込みが {@code listing_id} で効いていない実装（例: 全件走査）なら red になる。</p>
         */
        @Test
        @DisplayName("AC-5: 他スコープの募集枠に紐づく未解決異議は巻き添えにならない")
        void ac_5_他スコープの記録は不変() throws Exception {
            archiveListing(listingAId, adminAId);

            RecruitmentNoShowRecordEntity otherScope =
                    noShowRepository.findById(noShowOtherScopeId).orElseThrow();
            assertThat(otherScope.getDisputeResolution())
                    .as("teamA の募集枠削除で teamB の未解決異議が取り下げられてはならない")
                    .isNull();
            assertThat(noShowRepository.countConfirmedNoShows(otherScopeUserId, PENALTY_SINCE))
                    .as("他スコープ利用者のペナルティ算入も変わらない")
                    .isEqualTo(1L);
        }

        /** AC-5（補強）: teamB の募集枠自体も論理削除されていないこと。 */
        @Test
        @DisplayName("AC-5: 他スコープの募集枠は論理削除されない")
        void ac_5_他スコープの募集枠は残る() throws Exception {
            archiveListing(listingAId, adminAId);

            assertThat(isSoftDeleted(listingAId))
                    .as("teamA の募集枠は論理削除されていること（archive 自体が効いている担保）")
                    .isTrue();
            assertThat(isSoftDeleted(listingBId))
                    .as("teamB の募集枠は無傷であること")
                    .isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. archive 済み募集枠へ「後から」申し立てられた異議（#2497 第二の窓）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * archive 時点で `disputed = FALSE` だった記録は一括取下げの対象外（意図した設計）。
     * だがその後に利用者が異議を申し立てると、申立自体は成功する一方
     * （{@code dispute} は募集枠を JOIN しない {@code findById} で引くため）、
     * 裁定側の {@code findByIdAndScopeTypeAndScopeId} は JOIN するため引けず
     * <b>永久に裁定不能</b>になり、ペナルティに算入され続ける。
     * 時間軸がずれただけで利用者から見た被害は #2497 と同一である。
     */
    @Nested
    @DisplayName("3. POST /api/v1/recruitment/no-shows/{id}/dispute（archive 後の新規申立）")
    class DisputeAfterArchive {

        /**
         * 本 issue の根本原因そのものを実 DB で固定する。
         *
         * <p>`@SQLRestriction("deleted_at IS NULL")` により、archive 済み募集枠は
         * JPA 経由（`findById` / `existsById` / JPQL / 派生クエリ）から到達不能になる。
         * これが「NO_SHOW 記録のスコープ帰属クエリが JOIN 越しに引けなくなる」＝
         * 裁定不能の機序である。実装がこの前提の上に立っているため、推定で済ませず実測で固定する。</p>
         */
        @Test
        @DisplayName("AC-6: archive 済み募集枠は existsById / findById から到達不能（@SQLRestriction の実測）")
        void ac_6_archive済みはJPAから到達不能() throws Exception {
            assertThat(listingRepository.existsById(listingAId))
                    .as("前提: archive 前は JPA から見えていること")
                    .isTrue();

            archiveListing(listingAId, adminAId);

            assertThat(listingRepository.existsById(listingAId))
                    .as("@SQLRestriction により archive 済み募集枠は existsById からも消える")
                    .isFalse();
            assertThat(listingRepository.findById(listingAId))
                    .as("findById も同様に空になる")
                    .isEmpty();
            assertThat(listingRepository.existsById(listingBId))
                    .as("生存中の募集枠は引き続き見えること（フィルタが全滅していない担保）")
                    .isTrue();
        }

        /**
         * AC-7: 実装が依存するネイティブクエリの挙動を実測で固定する。
         *
         * <p>{@code findArchivedScopeById} は {@code @SQLRestriction} を迂回する唯一の経路であり、
         * 「戻り値が存在すること自体が archive 済みの信号」かつ「監査ログに残すスコープ文脈の
         * 唯一の入手経路」という二役を担う。射影インタフェースの列マッピングが壊れていれば
         * ここで red になる。</p>
         */
        @Test
        @DisplayName("AC-7: findArchivedScopeById は archive 済みのみスコープを返す（射影マッピングの実測）")
        void ac_7_archive済みスコープ取得の実測() throws Exception {
            assertThat(listingRepository.findArchivedScopeById(listingAId))
                    .as("前提: archive 前は空でなければならない")
                    .isEmpty();

            archiveListing(listingAId, adminAId);

            assertThat(listingRepository.findArchivedScopeById(listingAId))
                    .as("archive 済みならネイティブクエリで引ける（@SQLRestriction を迂回）")
                    .hasValueSatisfying(scope -> {
                        assertThat(scope.getScopeType())
                                .as("監査ログの TEAM/ORGANIZATION 振り分けに使う")
                                .isEqualTo(RecruitmentScopeType.TEAM.name());
                        assertThat(scope.getScopeId()).isEqualTo(teamAId);
                    });
            assertThat(listingRepository.findArchivedScopeById(listingBId))
                    .as("生存中の募集枠は空（archive 済みの信号として使える担保）")
                    .isEmpty();
        }

        /**
         * AC-8: archive 済み募集枠の NO_SHOW に異議を申し立てると、その場で `REVOKED` になる。
         *
         * <p>本 PR の第一版（archive 時の一括取下げのみ）では
         * `disputed=true, resolution=null` で止まり永久に裁定不能になるため red。</p>
         */
        @Test
        @DisplayName("AC-8: archive 済み募集枠への異議申立はその場で REVOKED になる")
        void ac_8_archive後の申立は即時REVOKED() throws Exception {
            // noShowNotDisputed は archive 時点で disputed=false ＝ 一括取下げの対象外
            archiveListing(listingAId, adminAId);
            assertThat(noShowRepository.findById(noShowNotDisputedId).orElseThrow().isDisputed())
                    .as("前提: 一括取下げでは触られていないこと")
                    .isFalse();

            disputeNoShow(noShowNotDisputedId, notDisputedUserId);

            RecruitmentNoShowRecordEntity record =
                    noShowRepository.findById(noShowNotDisputedId).orElseThrow();
            assertThat(record.isDisputed())
                    .as("申立自体は受け付ける（拒否は利用者から救済手段を奪う）")
                    .isTrue();
            assertThat(record.getDisputeResolution())
                    .as("裁定経路が塞がっている以上、その場で認容（REVOKED）として取り下げる")
                    .isEqualTo(DisputeResolution.REVOKED);
        }

        /** AC-9: 取り下げられた結果、ペナルティ算入からも外れる。 */
        @Test
        @DisplayName("AC-9: archive 後の申立で取り下げられた記録はペナルティ算入から外れる")
        void ac_9_archive後の申立でペナルティ算入から外れる() throws Exception {
            archiveListing(listingAId, adminAId);
            assertThat(noShowRepository.countConfirmedNoShows(notDisputedUserId, PENALTY_SINCE))
                    .as("前提: archive 直後はまだ算入されている（未申立のため一括取下げの対象外）")
                    .isEqualTo(1L);

            disputeNoShow(noShowNotDisputedId, notDisputedUserId);

            assertThat(noShowRepository.countConfirmedNoShows(notDisputedUserId, PENALTY_SINCE))
                    .as("REVOKED になった記録はペナルティ判定から除外される")
                    .isZero();
        }

        /**
         * AC-10（非回帰）: 生存中の募集枠では従来どおり「申立中・未解決」で止まること。
         *
         * <p>archive 判定を誤って常時 true にすると、通常の異議申立が全部即時認容されて
         * 管理者の裁定機会が消える。ここが red になればその事故を検知できる。</p>
         */
        @Test
        @DisplayName("AC-10: 生存中の募集枠への異議申立は従来どおり disputed=true / resolution=null")
        void ac_10_生存中の募集枠では従来どおり() throws Exception {
            disputeNoShow(noShowLiveNotDisputedId, liveNotDisputedUserId);

            RecruitmentNoShowRecordEntity record =
                    noShowRepository.findById(noShowLiveNotDisputedId).orElseThrow();
            assertThat(record.isDisputed()).isTrue();
            assertThat(record.getDisputeResolution())
                    .as("生存中なら管理者が裁定できるので、自動取下げしてはならない")
                    .isNull();
            assertThat(noShowRepository.countConfirmedNoShows(liveNotDisputedUserId, PENALTY_SINCE))
                    .as("未解決のままなのでペナルティには算入され続ける（従来どおり）")
                    .isEqualTo(1L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 異議申立 EP を叩く（200 OK）。以降のアサートは DB を読み直して行う。 */
    private void disputeNoShow(Long noShowId, Long actorUserId) throws Exception {
        setAuth(actorUserId);
        mockMvc.perform(post("/api/v1/recruitment/no-shows/{noShowId}/dispute", noShowId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"RCRT2497 異議申立テスト\"}"))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
    }

    /** 論理削除 EP を叩く（204 No Content）。以降のアサートは DB を読み直して行う。 */
    private void archiveListing(Long listingId, Long actorUserId) throws Exception {
        setAuth(actorUserId);
        mockMvc.perform(post("/api/v1/recruitment-listings/{id}/archive", listingId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();
    }

    /**
     * 募集枠が論理削除済みかを DB 直読みで判定する。
     *
     * <p>{@code RecruitmentListingEntity} には {@code @SQLRestriction("deleted_at IS NULL")} が
     * 効いているため JPA 経由では「消えた」ことしか判らず、行の実体を確認できない。
     * 削除の有無を確実に見るためネイティブクエリで {@code deleted_at} を直接読む。</p>
     */
    private boolean isSoftDeleted(Long listingId) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_listings WHERE id = :id AND deleted_at IS NOT NULL")
                .setParameter("id", listingId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertListing(RecruitmentScopeType scopeType, Long scopeId, Long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(1L)
                .title("RCRT2497 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(createdBy)
                .build()).getId();
    }

    /**
     * NO_SHOW 記録を 1 件作る。
     *
     * <p>{@code confirmed = true}（確定済み）で作る点が要。{@code countConfirmedNoShows} は
     * 未確定（{@code confirmed = false}）を数えないため、確定させないと AC-4 が空虚になる。</p>
     */
    private Long insertNoShow(Long listingId, Long userId, boolean disputed, DisputeResolution resolution) {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(nextParticipantId++)
                .listingId(listingId)
                .userId(userId)
                .reason(NoShowReason.ADMIN_MARKED)
                .recordedBy(userId)
                .build();
        record.confirm();
        if (disputed) {
            record.dispute();
        }
        if (resolution != null) {
            record.resolveDispute(resolution);
        }
        return noShowRepository.save(record).getId();
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
                                + "VALUES (:email, 'RCRT2497', 'テスト', 'RCRT2497 テスト', 'ACTIVE', "
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
                                + "CONCAT('rcrt24-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
