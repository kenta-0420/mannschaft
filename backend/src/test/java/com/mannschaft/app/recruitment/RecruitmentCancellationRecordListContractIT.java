package com.mannschaft.app.recruitment;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.11.1 キャンセル料記録の一覧 EP の認可契約テスト（設計書 §12.2）。
 *
 * <p><b>この EP が守るべきこと</b>: 一覧に出す記録の受取先は、<b>escrow（payment ドメイン）が持つ
 * 受取先が権威</b>である。{@code recruitment_listings} の {@code payeeKind}/{@code payeeUserId}/
 * {@code scopeId} は募集の作成後に変更できる可変の値であり、権威にはできない。
 * TEAM/ORG/個人（USER）の 3 通りすべてで「受取先側には見える」「無関係な他者には見えない」を
 * 対で検証する——肯定側だけでは判定が常に true でも緑になる。{@code SYSTEM_ADMIN} は全件見える。</p>
 *
 * <p><b>受取先を変更した後の不一致</b>（{@link PayeeChangedAfterwards}）は、この EP の要である。
 * listing の受取先だけで絞ると、受取先を差し替えた瞬間に新しい受取先へ従前の記録が見えてしまう。
 * escrow を権威にしている限りそうはならない、ということをここで固定する。</p>
 *
 * <p>金型: {@link RecruitmentCancellationFeeWaiveContractIT}（免除 EP の認可契約テストと対）。
 * 一覧の閲覧と免除の実行は同一の判定（payment 側で共通化）を通る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.11.1 キャンセル料記録一覧 認可契約テスト（受取先絞り込み）")
class RecruitmentCancellationRecordListContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    @Autowired
    private EscrowTransactionRepository escrowTransactionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgId;

    /** teamA（受取先）の ADMIN。teamA 受取の記録が見える。 */
    private Long teamAAdminId;
    /** teamB の ADMIN。teamA/org/個人受取の記録には無関係＝見えない。 */
    private Long teamBAdminId;
    /** org（受取先）の ADMIN。org 受取の記録が見える。 */
    private Long orgAdminId;
    /** 受取先が個人（payeeKind=USER）の記録における受取本人。 */
    private Long individualPayeeId;
    /** キャンセル料を負っている本人（債務者）。どの記録の受取先でもない。 */
    private Long debtorId;
    /** どこにも所属しない部外者。 */
    private Long outsiderId;
    /** SYSTEM_ADMIN。全件見える。 */
    private Long systemAdminId;

    private Long teamRecordId;
    private Long orgRecordId;
    private Long userRecordId;
    private Long teamBRecordId;

    private Long teamListingId;
    private Long orgListingId;
    private Long userListingId;
    private Long teamBListingId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CANFEELIST チームA");
        teamBId = insertTeam("CANFEELIST チームB");
        orgId = insertOrganization("CANFEELIST 組織");

        teamAAdminId = insertUser("canfeelist-team-a-admin@example.com");
        teamBAdminId = insertUser("canfeelist-team-b-admin@example.com");
        orgAdminId = insertUser("canfeelist-org-admin@example.com");
        individualPayeeId = insertUser("canfeelist-individual-payee@example.com");
        debtorId = insertUser("canfeelist-debtor@example.com");
        outsiderId = insertUser("canfeelist-outsider@example.com");
        systemAdminId = insertUser("canfeelist-system-admin@example.com");

        MembershipTestHelper.insertMembership(em, teamAAdminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAAdminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamBAdminId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamBAdminId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        grantManageRecruitmentsToAdmin();

        teamListingId = insertListing("TEAM", teamAId, "TEAM", null);
        orgListingId = insertListing("ORGANIZATION", orgId, "ORG", null);
        userListingId = insertListing("TEAM", teamAId, "USER", individualPayeeId);
        teamBListingId = insertListing("TEAM", teamBId, "TEAM", null);

        teamRecordId = insertRecord(teamListingId, 3001L, debtorId, CancellationPaymentStatus.PENDING);
        orgRecordId = insertRecord(orgListingId, 3002L, debtorId, CancellationPaymentStatus.PENDING);
        userRecordId = insertRecord(userListingId, 3003L, debtorId, CancellationPaymentStatus.PENDING);
        teamBRecordId = insertRecord(teamBListingId, 3004L, debtorId, CancellationPaymentStatus.PENDING);

        // 受取先の権威は escrow にある。記録 1 件につき escrow を 1 件立てる
        // （実運用でもキャンセル料の記録は参加費の与信＝escrow を伴って生まれる）。
        insertEscrow(teamListingId, 3001L, insertConnectAccount(ScopeKind.TEAM, teamAId));
        insertEscrow(orgListingId, 3002L, insertConnectAccount(ScopeKind.ORG, orgId));
        insertEscrow(userListingId, 3003L, insertConnectAccount(ScopeKind.USER, individualPayeeId));
        insertEscrow(teamBListingId, 3004L, insertConnectAccount(ScopeKind.TEAM, teamBId));

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 受取先が TEAM
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 受取先が TEAM のとき")
    class TeamPayee {

        @Test
        @DisplayName("肯定: 受取先 TEAM の ADMIN には TEAM 受取の記録が見える")
        void 受取先TEAMのADMINには見える() throws Exception {
            setAuth(teamAAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(teamRecordId);
        }

        @Test
        @DisplayName("否定: 無関係な TEAM の ADMIN には見えない（テナント越境の遮断）")
        void 無関係TEAMのADMINには見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId);
            // 自分（teamB）受取の記録だけは見える
            assertThat(ids).contains(teamBRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 受取先が ORG
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 受取先が ORG のとき")
    class OrgPayee {

        @Test
        @DisplayName("肯定: 受取先 ORG の ADMIN には ORG 受取の記録が見える")
        void 受取先ORGのADMINには見える() throws Exception {
            setAuth(orgAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(orgRecordId);
        }

        @Test
        @DisplayName("否定: ORG に無関係な TEAM の ADMIN には見えない")
        void 無関係な者には見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(orgRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 受取先が個人（payeeKind=USER）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 受取先が個人のとき")
    class UserPayee {

        @Test
        @DisplayName("肯定: 受取本人には見える")
        void 受取本人には見える() throws Exception {
            setAuth(individualPayeeId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(userRecordId);
        }

        @Test
        @DisplayName("否定: 他人には見えない")
        void 他人には見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(userRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 債務者・部外者・SYSTEM_ADMIN
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. その他の主体")
    class Others {

        /** 債務者本人は受取先でも運営でもないため、一覧には何も見えない（本波では債務者向け一覧を作らない）。 */
        @Test
        @DisplayName("債務者本人には何も見えない（本波のスコープ外）")
        void 債務者には見えない() throws Exception {
            setAuth(debtorId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }

        @Test
        @DisplayName("何の権限も持たない部外者には何も見えない")
        void 部外者には見えない() throws Exception {
            setAuth(outsiderId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN には全件見える")
        void SYSTEM_ADMINには全件見える() throws Exception {
            setAuth(systemAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 受取先を変更した後（権威は escrow であって listing ではない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 受取先を変更した後")
    class PayeeChangedAfterwards {

        /**
         * 募集の受取先（listing 側の可変な値）を teamB へ差し替えても、escrow 上の債権者は
         * teamA のままである。listing を権威にすると、この瞬間に teamB へ teamA の債権記録
         * （債務者 ID・金額・状態）が見えてしまう。
         */
        @Test
        @DisplayName("listing の受取先を差し替えても、変更後の受取先には従前の記録が見えない")
        void listingの受取先を変えても新受取先には見えない() throws Exception {
            changeListingPayee(teamListingId, "TEAM", teamBId, null);

            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId);
        }

        /** 上の裏返し。listing を差し替えても、escrow 上の受取先である teamA には見え続ける。 */
        @Test
        @DisplayName("listing の受取先を差し替えても、escrow 上の受取先には引き続き見える")
        void listingの受取先を変えても旧受取先には見え続ける() throws Exception {
            changeListingPayee(teamListingId, "TEAM", teamBId, null);

            setAuth(teamAAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(teamRecordId);
        }

        /** escrow（権威）の受取先を動かしたときは、見え方も正しく追随する。 */
        @Test
        @DisplayName("escrow の受取先を差し替えると、新しい受取先に見え、旧受取先には見えなくなる")
        void escrowの受取先を変えると見え方が追随する() throws Exception {
            UUID teamBAccount = insertConnectAccount(ScopeKind.TEAM, teamBId);
            em.flush();
            changeEscrowPayee(teamListingId, 3001L, teamBAccount);

            setAuth(teamBAdminId);
            assertThat(listRecordIds()).contains(teamRecordId);

            setAuth(teamAAdminId);
            assertThat(listRecordIds()).doesNotContain(teamRecordId);
        }

        /**
         * escrow を引けない記録は誰の受取先でもない（§10.2 の最終行）。
         * listing だけを見ていると受取先側に見えてしまうため、対で固定する。
         */
        @Test
        @DisplayName("escrow が存在しない記録は受取先側にも見えない（SYSTEM_ADMIN のみ）")
        void escrowが無い記録は受取先側に見えない() throws Exception {
            Long orphanListingId = insertListing("TEAM", teamAId, "TEAM", null);
            Long orphanRecordId = insertRecord(orphanListingId, 3999L, debtorId, CancellationPaymentStatus.PENDING);
            em.flush();
            em.clear();

            setAuth(teamAAdminId);
            assertThat(listRecordIds()).doesNotContain(orphanRecordId);

            setAuth(systemAdminId);
            assertThat(listRecordIds()).contains(orphanRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 絞り込み・ページング・入力検証
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 絞り込み・ページング・入力検証")
    class FilteringAndPaging {

        @Test
        @DisplayName("status を指定するとその状態の記録だけが返る")
        void 状態別の絞り込みが効く() throws Exception {
            Long waivedRecordId = insertRecord(teamListingId, 3101L, debtorId, CancellationPaymentStatus.WAIVED);
            insertEscrow(teamListingId, 3101L, insertConnectAccount(ScopeKind.TEAM, teamAId));
            em.flush();
            em.clear();

            setAuth(teamAAdminId);

            // 既定（PENDING/FAILED/UNCOLLECTIBLE）には WAIVED は含まれない。
            assertThat(listRecordIds()).contains(teamRecordId).doesNotContain(waivedRecordId);

            // WAIVED を明示するとその記録だけが返る。
            List<Long> waivedOnly = listRecordIds(query -> query.param("status", "WAIVED"));
            assertThat(waivedOnly).contains(waivedRecordId).doesNotContain(teamRecordId);
        }

        /**
         * 1 ページ 20 件のちょうど境界。20 件なら 1 ページで収まり続きは無い、
         * 21 件なら続きが出て 2 ページ目に残り 1 件が現れる——ページ送りが実際に必要になる件数で見る。
         */
        @Test
        @DisplayName("20件ちょうどなら1ページで収まり、21件目はページ送りで到達できる")
        void ページ送りの境界20件と21件() throws Exception {
            // 既存の teamA 受取の記録（teamRecordId・userRecordId の 2 件）と合わせて 21 件になるよう積む。
            // teamA の TEAM 受取だけを数えるため、専用の listing を使う。
            Long pagingListingId = insertListing("TEAM", teamAId, "TEAM", null);
            UUID teamAAccount = insertConnectAccount(ScopeKind.TEAM, teamAId);
            List<Long> pagedIds = new java.util.ArrayList<>();
            for (int i = 0; i < 21; i++) {
                long participantId = 4000L + i;
                // cancelledAt をずらして順序を決定的にする（同時刻の検証は別テスト）。
                pagedIds.add(insertRecordAt(pagingListingId, participantId, debtorId,
                        CancellationPaymentStatus.PENDING, LocalDateTime.now().minusMinutes(i)));
                insertEscrow(pagingListingId, participantId, teamAAccount);
            }
            em.flush();
            em.clear();

            setAuth(teamAAdminId);

            // size=20 の 1 ページ目には 20 件。続きがあると申告される。
            String firstPage = rawList(query -> query.param("size", "20"));
            assertThat(extractIds(firstPage)).hasSize(20);
            assertThat(extractHasNext(firstPage)).isTrue();

            // カーソルを辿ると 21 件目以降に到達でき、1 ページ目と重複しない。
            String cursor = extractNextCursor(firstPage);
            assertThat(cursor).isNotNull();
            String secondPage = rawList(query -> query.param("size", "20").param("cursor", cursor));
            List<Long> secondIds = extractIds(secondPage);
            assertThat(secondIds).isNotEmpty();
            assertThat(secondIds).doesNotContainAnyElementsOf(extractIds(firstPage));

            // 2 ページを通じて 21 件すべてに到達できる（1 件も飛ばさない）。
            List<Long> reachable = new java.util.ArrayList<>(extractIds(firstPage));
            reachable.addAll(secondIds);
            assertThat(reachable).containsAll(pagedIds);
        }

        /**
         * ソートキーが {@code cancelledAt} 単独だと、同一時刻の行がページ境界で重複・欠落しうる。
         * 一意な複合キー {@code (cancelledAt, id)} でページングしていることを、全行同時刻で確かめる。
         */
        @Test
        @DisplayName("cancelledAt が同一の行がページ境界で重複・欠落しない")
        void 同一時刻の行が重複も欠落もしない() throws Exception {
            Long tiedListingId = insertListing("TEAM", teamAId, "TEAM", null);
            UUID teamAAccount = insertConnectAccount(ScopeKind.TEAM, teamAId);
            LocalDateTime sameInstant = LocalDateTime.now().minusDays(1).withNano(0);
            List<Long> tiedIds = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                long participantId = 5000L + i;
                tiedIds.add(insertRecordAt(tiedListingId, participantId, debtorId,
                        CancellationPaymentStatus.PENDING, sameInstant));
                insertEscrow(tiedListingId, participantId, teamAAccount);
            }
            em.flush();
            em.clear();

            setAuth(teamAAdminId);

            // size=3 で最後まで辿り、同時刻 10 件がちょうど 1 回ずつ現れることを確かめる。
            List<Long> seen = new java.util.ArrayList<>();
            String cursor = null;
            for (int page = 0; page < 20; page++) {
                final String currentCursor = cursor;
                String body = rawList(query -> {
                    query.param("size", "3");
                    if (currentCursor != null) {
                        query.param("cursor", currentCursor);
                    }
                });
                seen.addAll(extractIds(body));
                if (!extractHasNext(body)) {
                    break;
                }
                cursor = extractNextCursor(body);
            }

            List<Long> seenTied = seen.stream().filter(tiedIds::contains).toList();
            assertThat(seenTied).containsExactlyInAnyOrderElementsOf(tiedIds);
            assertThat(seenTied).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("size が 0 以下なら 400（500 にしない）")
        void sizeが0以下なら400() throws Exception {
            setAuth(teamAAdminId);
            mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("size", "0"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("size", "-1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("size が上限を超えるなら 400（過大取得を許さない）")
        void sizeが上限超なら400() throws Exception {
            setAuth(teamAAdminId);
            mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("size", "101"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("cursor が壊れていれば 400（500 にしない）")
        void 壊れたcursorなら400() throws Exception {
            setAuth(teamAAdminId);
            mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("cursor", "not-a-cursor"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private List<Long> listRecordIds() throws Exception {
        return listRecordIds(query -> query.param("size", "50"));
    }

    private List<Long> listRecordIds(java.util.function.Consumer<QueryParams> customizer) throws Exception {
        return extractIds(rawList(customizer));
    }

    /** クエリパラメータを組み立てるための薄いラッパ（ヘルパー間で組み方を揃える）。 */
    private static final class QueryParams {
        private final org.springframework.util.MultiValueMap<String, String> values =
                new org.springframework.util.LinkedMultiValueMap<>();

        QueryParams param(String name, String value) {
            values.add(name, value);
            return this;
        }
    }

    private String rawList(java.util.function.Consumer<QueryParams> customizer) throws Exception {
        QueryParams query = new QueryParams();
        customizer.accept(query);
        if (!query.values.containsKey("size")) {
            query.param("size", "50");
        }
        return mockMvc.perform(get("/api/v1/recruitment-cancellation-records").params(query.values))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<Long> extractIds(String body) {
        // data 配列の各要素の先頭が "id" であることを利用する（listingId 等を拾わないよう境界を付ける）。
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[{,]\"id\":(\\d+)").matcher(body);
        List<Long> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.valueOf(matcher.group(1)));
        }
        return ids;
    }

    private boolean extractHasNext(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"hasNext\":(true|false)").matcher(body);
        assertThat(matcher.find()).as("meta.hasNext が応答に含まれる").isTrue();
        return Boolean.parseBoolean(matcher.group(1));
    }

    private String extractNextCursor(String body) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertRecord(Long listingId, Long participantId, Long userId, CancellationPaymentStatus status) {
        return insertRecordAt(listingId, participantId, userId, status, LocalDateTime.now());
    }

    private Long insertRecordAt(Long listingId, Long participantId, Long userId,
            CancellationPaymentStatus status, LocalDateTime cancelledAt) {
        return cancellationRecordRepository.save(RecruitmentCancellationRecordEntity.builder()
                .participantId(participantId)
                .listingId(listingId)
                .userId(userId)
                .cancelledAt(cancelledAt)
                .cancelledBy(userId)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build()).getId();
    }

    /**
     * 受取先の Connect アカウントを 1 件作る（{@code scope_kind} × {@code scope_id} が受取先の実体）。
     *
     * @return 作成した口座の ID（escrow の {@code payee_connect_account_id} に入れる）
     */
    private UUID insertConnectAccount(ScopeKind scopeKind, Long scopeId) {
        return connectAccountRepository.save(ConnectAccountEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .stripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(true)
                .payoutsEnabled(true)
                .country("JP")
                .defaultCurrency("JPY")
                .build()).getId();
    }

    /** 引き当ての三つ組（RECRUITMENT × listingId × participantId）で escrow を 1 件作る。 */
    private void insertEscrow(Long listingId, Long participantId, UUID payeeConnectAccountId) {
        escrowTransactionRepository.save(EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(listingId)
                .sourceParticipantId(participantId)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(debtorId)
                .payeeKind(ScopeKind.TEAM)
                .payeeConnectAccountId(payeeConnectAccountId)
                .faceAmount(5_000L)
                .amount(5_125L)
                .currency("JPY")
                .applicationFeeAmount(250L)
                .feePolicyKey("DEFAULT")
                .status(EscrowStatus.AUTHORIZED)
                .build());
    }

    /**
     * escrow の受取先口座を差し替える（受取先の変更を模す）。
     *
     * <p>listing の {@code payeeKind}/{@code scopeId} を変えるだけでは escrow は追随しない。
     * 権威が escrow にある以上、見え方を変えるにはこちらを動かす必要がある——という
     * 非対称そのものが検証対象である。</p>
     */
    private void changeEscrowPayee(Long listingId, Long participantId, UUID newPayeeConnectAccountId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository
                .findBySourceKindAndSourceIdAndSourceParticipantId(
                        EscrowSourceKind.RECRUITMENT, listingId, participantId)
                .orElseThrow();
        escrow.setPayeeConnectAccountId(newPayeeConnectAccountId);
        escrowTransactionRepository.save(escrow);
        em.flush();
        em.clear();
    }

    /**
     * listing の受取先（可変・権威ではない）を差し替える。
     *
     * <p>Entity には setter が無く更新は {@code updateForEdit} 経由だが、ここで確かめたいのは
     * 「listing 上の受取先がどう変わろうと一覧の見え方は escrow に従う」という一点であり、
     * 変更の経路は問わない。列を直接動かすことで、どんな更新経路であっても成り立つことを示す。</p>
     */
    private void changeListingPayee(Long listingId, String payeeKind, Long scopeId, Long payeeUserId) {
        em.createNativeQuery(
                        "UPDATE recruitment_listings SET payee_kind = :payeeKind, scope_id = :scopeId, "
                                + "payee_user_id = :payeeUserId WHERE id = :id")
                .setParameter("payeeKind", payeeKind)
                .setParameter("scopeId", scopeId)
                .setParameter("payeeUserId", payeeUserId)
                .setParameter("id", listingId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private Long insertListing(String scopeTypeName, Long scopeId, String payeeKind, Long payeeUserId) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.valueOf(scopeTypeName))
                .scopeId(scopeId)
                .categoryId(1L)
                .title("CANFEELIST 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .paymentEnabled(true)
                .price(5_000)
                .payeeKind(payeeKind)
                .payeeUserId(payeeUserId)
                .createdBy(scopeId)
                .build()).getId();
    }

    /** {@code MANAGE_RECRUITMENTS} を権限カタログへ登録し ADMIN へ自動付与する（本番マイグレーションの写し）。 */
    private void grantManageRecruitmentsToAdmin() {
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT 'MANAGE_RECRUITMENTS', '募集（札）管理', 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_RECRUITMENTS')")
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_RECRUITMENTS' "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "  WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .executeUpdate();
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
                                + "VALUES (:email, 'CANFEELIST', 'テスト', 'CANFEELIST テスト', 'ACTIVE', "
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
                                + "CONCAT('canfeelist-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, visibility, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 0, "
                                + "CONCAT('canfeelist-org-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
