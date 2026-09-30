package com.mannschaft.app.member;

import com.mannschaft.app.dashboard.MinRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-9（軍議書 gungi-3387-d3t.md 第3版 §4.4・§7）。
 *
 * <p>判定に使う状態を<b>読む直前</b>で latch により止め、その間に別スレッド（本テストのスレッド。
 * JdbcTemplate の自動コミット）で権限の縮小を確定させてから再開すると、必ず拒否されることを固定する。
 * 「判定はその場で最新の状態を読む（member の読み取り TX のスナップショットや先取りに頼らない）」ことの
 * 固定であり、案A（裁可済み）で許容する範囲の外側の境界を守る。</p>
 *
 * <p>latch の位置（第3版で修正）:</p>
 * <ul>
 *   <li>①所属の離脱・②降格: {@code AccessControlService} の呼び出しの直前</li>
 *   <li>④min_role の引き上げ: {@code MemberSubtabVisibilityService#resolveMinRole} の直前</li>
 *   <li>⑤PUBLIC→MEMBERS_ONLY・⑥下書き化: {@code TeamPageRepository#findById} の実行前</li>
 * </ul>
 *
 * <p>ページ取得<b>後</b>の⑤⑥は案A の許容レース（§4.4・F06.6）であり、ここでは期待しない。
 * 案B 差分（AC-D3T-10）は裁可により対象外。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR #3387 D-3T 試練 AC-9: 閲覧の判定は最新の状態を読む（latch 並行・実 DB）")
class MemberReadAuthzFreshnessIT extends MemberTxBoundaryITSupport {

    /** 期待する拒否の形。 */
    enum Denial { NOT_FOUND, FORBIDDEN, DEFAULT_SETTINGS }

    /** 閲覧経路。 */
    enum ReadPath {
        GET_PAGE(Denial.NOT_FOUND),
        LIST_SECTIONS(Denial.NOT_FOUND),
        LIST_PROFILES(Denial.NOT_FOUND),
        GET_PROFILE(Denial.NOT_FOUND),
        LOOKUP(Denial.NOT_FOUND),
        LIST_ORG_PAGES(Denial.FORBIDDEN),
        GET_SETTINGS(Denial.DEFAULT_SETTINGS);

        final Denial denial;

        ReadPath(Denial denial) {
            this.denial = denial;
        }
    }

    private RequestBuilder request(ReadPath path) {
        return switch (path) {
            case GET_PAGE -> getPageRequest(pubPageId);
            case LIST_SECTIONS -> listSectionsRequest(pubPageId);
            case LIST_PROFILES -> listProfilesRequest(pubPageId);
            case GET_PROFILE -> getProfileRequest(visibleProfileId);
            case LOOKUP -> lookupRequest(pubPageId);
            case LIST_ORG_PAGES -> listOrgPagesRequest();
            case GET_SETTINGS -> getSettingsRequest();
        };
    }

    /**
     * 1回だけ止まる Answer。止まった時点で {@code reached} を倒し、{@code resume} を待ってから実体を呼ぶ。
     */
    private static Answer<Object> gateOnce(AtomicBoolean armed, CountDownLatch reached, CountDownLatch resume) {
        return inv -> {
            if (armed.compareAndSet(true, false)) {
                reached.countDown();
                if (!resume.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("AC-9 latch: 再開の合図が来ない");
                }
            }
            return inv.callRealMethod();
        };
    }

    /**
     * 要求を別スレッドで走らせ、latch で止まった所で {@code reduction} をコミットしてから再開する。
     */
    private MvcResult runWithReductionAtGate(Long viewerId, RequestBuilder request,
                                             AtomicBoolean armed, CountDownLatch reached, CountDownLatch resume,
                                             Runnable reduction) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            armed.set(true);
            Future<MvcResult> future = worker.submit(() -> {
                setAuth(viewerId);
                try {
                    return mockMvc.perform(request).andReturn();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            assertThat(reached.await(60, TimeUnit.SECONDS))
                    .as("latch の位置（判定に使う状態を読む直前）に到達すること").isTrue();
            reduction.run();
            resume.countDown();
            return future.get(120, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            worker.shutdownNow();
        }
    }

    private void assertDenied(ReadPath path, MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        switch (path.denial) {
            case NOT_FOUND -> assertThat(status).as("%s は縮小後に 404", path).isEqualTo(404);
            case FORBIDDEN -> assertThat(status).as("%s は縮小後に 403", path).isEqualTo(403);
            case DEFAULT_SETTINGS -> {
                // getSettings は拒否の代わりに既定値を返す（MEMBER 未満には実設定を見せない）
                assertThat(status).isEqualTo(200);
                String body = result.getResponse().getContentAsString();
                assertThat(objectMapper.readTree(body).at("/data/subtabs"))
                        .as("%s は縮小後に既定値のみ", path)
                        .isNotEmpty()
                        .allSatisfy(item -> {
                            // Lombok の boolean isDefault は Jackson 上 "default" になりうるため両方を見る
                            var flag = item.has("isDefault") ? item.get("isDefault") : item.get("default");
                            assertThat(flag).as("isDefault 相当の項目がある").isNotNull();
                            assertThat(flag.asBoolean()).isTrue();
                        });
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ①② 所属・ロール: AccessControlService の呼び出しの直前
    // ═════════════════════════════════════════════════════════════════════

    private void runMembershipReduction(ReadPath path, Runnable reduction) throws Exception {
        if (path == ReadPath.GET_SETTINGS) {
            // 実設定と既定値を見分けられるよう、既定（MEMBER）と異なる設定行を置いておく
            setProfilesSubtab(MinRole.SUPPORTER);
        }
        AtomicBoolean armed = new AtomicBoolean(false);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        stubAccessControl(gateOnce(armed, reached, resume));

        MvcResult result = runWithReductionAtGate(memId, request(path), armed, reached, resume, reduction);
        assertDenied(path, result);
    }

    @ParameterizedTest(name = "① 所属の離脱 × {0}")
    @EnumSource(ReadPath.class)
    @DisplayName("AC-9 ①: 判定の直前に MEMBER が離脱を確定させたら拒否される")
    void 所属の離脱は判定に効く(ReadPath path) throws Exception {
        runMembershipReduction(path, () -> jdbc.update(
                "UPDATE memberships SET left_at = NOW() "
                        + "WHERE user_id = ? AND scope_type = 'ORGANIZATION' AND scope_id = ?", memId, orgId));
    }

    @ParameterizedTest(name = "② 降格(MEMBER→SUPPORTER) × {0}")
    @EnumSource(ReadPath.class)
    @DisplayName("AC-9 ②: 判定の直前に MEMBER→SUPPORTER の降格を確定させたら拒否される")
    void 降格は判定に効く(ReadPath path) throws Exception {
        runMembershipReduction(path, () -> jdbc.update(
                "UPDATE memberships SET role_kind = 'SUPPORTER' "
                        + "WHERE user_id = ? AND scope_type = 'ORGANIZATION' AND scope_id = ?", memId, orgId));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ④ min_role の引き上げ: resolveMinRole の直前
    // ═════════════════════════════════════════════════════════════════════

    /** ④ が効く経路（紹介サブタブの min_role で通していた非会員の閲覧）。 */
    enum MinRolePath {
        GET_PAGE, LIST_SECTIONS, LIST_PROFILES, GET_PROFILE, LIST_ORG_PAGES
    }

    @ParameterizedTest(name = "④ min_role PUBLIC→MEMBER × {0}")
    @EnumSource(MinRolePath.class)
    @DisplayName("AC-9 ④: resolveMinRole の直前に紹介サブタブを PUBLIC→MEMBER に引き上げたら非会員は拒否される")
    void min_roleの引き上げは判定に効く(MinRolePath minRolePath) throws Exception {
        setProfilesSubtab(MinRole.PUBLIC);
        ReadPath path = ReadPath.valueOf(minRolePath.name());
        AtomicBoolean armed = new AtomicBoolean(false);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(gateOnce(armed, reached, resume))
                .when(memberSubtabVisibilityService).resolveMinRole(any(), any(), any());

        MvcResult result = runWithReductionAtGate(outId, request(path), armed, reached, resume, () -> jdbc.update(
                "UPDATE member_subtab_role_visibility SET min_role = 'MEMBER' "
                        + "WHERE scope_type = 'ORGANIZATION' AND scope_id = ? AND subtab_key = 'member_profiles'",
                orgId));
        assertDenied(path, result);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ⑤⑥ ページの可視性・状態: TeamPageRepository#findById の実行前
    // ═════════════════════════════════════════════════════════════════════

    /** ⑤⑥ が効く経路。 */
    enum PagePath {
        GET_PAGE, LIST_SECTIONS, LIST_PROFILES
    }

    private void runPageReduction(PagePath pagePath, Long viewerId, String updateSql) throws Exception {
        ReadPath path = ReadPath.valueOf(pagePath.name());
        AtomicBoolean armed = new AtomicBoolean(false);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(gateOnce(armed, reached, resume)).when(teamPageRepository).findById(any());

        MvcResult result = runWithReductionAtGate(viewerId, request(path), armed, reached, resume,
                () -> jdbc.update(updateSql, pubPageId));
        assertDenied(path, result);
    }

    @ParameterizedTest(name = "⑤ PUBLIC→MEMBERS_ONLY × {0}")
    @EnumSource(PagePath.class)
    @DisplayName("AC-9 ⑤: ページ取得の前に PUBLIC→MEMBERS_ONLY を確定させたら非会員は拒否される")
    void 非公開化は判定に効く(PagePath pagePath) throws Exception {
        setProfilesSubtab(MinRole.PUBLIC);
        runPageReduction(pagePath, outId, "UPDATE team_pages SET visibility = 'MEMBERS_ONLY' WHERE id = ?");
    }

    @ParameterizedTest(name = "⑥ 下書き化 × {0}")
    @EnumSource(PagePath.class)
    @DisplayName("AC-9 ⑥: ページ取得の前に公開→下書きを確定させたら組織の非管理者は拒否される")
    void 下書き化は判定に効く(PagePath pagePath) throws Exception {
        runPageReduction(pagePath, memId, "UPDATE team_pages SET status = 'DRAFT' WHERE id = ?");
    }
}
