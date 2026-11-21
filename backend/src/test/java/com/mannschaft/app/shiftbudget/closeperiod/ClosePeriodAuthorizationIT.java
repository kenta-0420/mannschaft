package com.mannschaft.app.shiftbudget.closeperiod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 認可（実DB・実HTTP）。試練123（AC46）・124（AC47）・125（AC48）。
 *
 * <p>本件で作り替える入口（手動締め・締め期間一覧・締め日設定）は {@code ShiftBudgetScopeAccessPolicy}（実所属＋権限）で判定し、
 * SYSTEM_ADMIN 単独・BUDGET_VIEW のみ（変更系）・他組織は 403／404、変更0（御裁定 追加4(1)）。
 * 既存の割当一覧・詳細は別CMP（SYSTEM_ADMIN 単独通過の是正）まで現状を固定する。</p>
 *
 * <p>実装前の red 理由: 締め日設定・期間一覧 API が無く 404（期待 200／403）。手動締めは現行 SYSTEM_ADMIN を通す（期待 403）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 締め日設定・期間一覧・手動締めの認可（実DB・実HTTP）")
class ClosePeriodAuthorizationIT extends ClosePeriodTestBase {

    private Long systemAdminOnly() {
        Long id = newUser();
        grantSystemAdmin(id);
        return id;
    }

    private long changeRows(Long org) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_closing_day_changes WHERE organization_id = ?",
                Long.class, org);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("試練123 AC46 締め日変更は組織 BUDGET_ADMIN のみ。BUDGET_VIEW のみ・SYSTEM_ADMIN 単独・他組織 BUDGET_ADMIN は 403 で変更0")
    void 試練123_締め日変更の認可() throws Exception {
        long[] other = otherOrganization();
        Long sys = systemAdminOnly();

        changeClosingDay(viewerId, orgId, 0, 15, "2026-11", "閲覧者").andExpect(status().isForbidden());
        changeClosingDay(sys, orgId, 0, 15, "2026-11", "シスアド単独").andExpect(status().isForbidden());
        changeClosingDay(other[2], orgId, 0, 15, "2026-11", "他組織").andExpect(status().isForbidden());
        assertThat(changeRows(orgId)).isZero();

        // 参照は BUDGET_VIEW で可、SYSTEM_ADMIN 単独・他組織は 403
        getSettings(viewerId, orgId).andExpect(status().isOk());
        getSettings(sys, orgId).andExpect(status().isForbidden());
        getSettings(other[2], orgId).andExpect(status().isForbidden());

        changeClosingDay(adminId, orgId, 0, 15, "2026-11", "担当者").andExpect(status().is2xxSuccessful());
        assertThat(changeRows(orgId)).isEqualTo(1);
    }

    @Test
    @DisplayName("試練124 AC47 割当の締め期間一覧で他組織の割当は 404（自組織ヘッダでも他組織ヘッダでも実在を漏らさない）")
    void 試練124_他組織の割当の期間一覧は404() throws Exception {
        long[] other = otherOrganization();
        Long otherAllocation = allocation(other[0], other[1], other[3], other[4], d("2026-04-01"), d("2026-06-30"));
        Long own = allocation(d("2026-04-01"), d("2026-06-30"));

        allocationClosePeriods(adminId, orgId, otherAllocation).andExpect(status().isNotFound());
        allocationClosePeriods(adminId, orgId, own).andExpect(status().isOk());
        allocationClosePeriods(other[2], other[0], own).andExpect(status().isNotFound());
        // 組織の期間一覧: 非所属の組織ヘッダは 403、所属の閲覧者は 200
        closePeriods(other[2], orgId, "2026-04", "2026-06").andExpect(status().isForbidden());
        closePeriods(viewerId, orgId, "2026-04", "2026-06").andExpect(status().isOk());
    }

    @Test
    @DisplayName("試練125 AC48 手動締めは SYSTEM_ADMIN 単独・BUDGET_VIEW のみ・他組織 BUDGET_ADMIN が 403 で変更0")
    void 試練125_手動締めの認可() throws Exception {
        long[] other = otherOrganization();
        Long sys = systemAdminOnly();
        Long allocationId = allocation(d("2026-04-01"), d("2026-04-30"));
        long[] s = worked(d("2026-04-10"), workerId);

        close(sys, orgId, "2026-04").andExpect(status().isForbidden());
        close(viewerId, orgId, "2026-04").andExpect(status().isForbidden());
        close(other[2], orgId, "2026-04").andExpect(status().isForbidden());

        assertThat(liveStatus(s[1], workerId)).isEqualTo("PLANNED");
        assertThat(journals(allocationId)).isEmpty();
        assertThat(fixedPeriods(orgId)).as("期間行も作らない").isEmpty();
    }

    @Test
    @DisplayName("試練125 AC48 期間一覧は SYSTEM_ADMIN 単独 403、割当の期間一覧も SYSTEM_ADMIN 単独 403")
    void 試練125_期間一覧の認可() throws Exception {
        Long sys = systemAdminOnly();
        Long own = allocation(d("2026-04-01"), d("2026-06-30"));
        closePeriods(sys, orgId, "2026-04", "2026-06").andExpect(status().isForbidden());
        allocationClosePeriods(sys, orgId, own).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("試練125 AC48 現状固定（別CMP: 既存 reader の SYSTEM_ADMIN 単独通過の是正・起票待ち）: 割当一覧・詳細は SYSTEM_ADMIN 単独で現状 200")
    void 試練125_既存readerの現状固定() throws Exception {
        Long sys = systemAdminOnly();
        Long own = allocation(d("2026-04-01"), d("2026-06-30"));
        mvc.perform(as(get("/api/v1/shift-budget/allocations").param("page", "0").param("size", "10"), sys, orgId))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/shift-budget/allocations/" + own), sys, orgId)).andExpect(status().isOk());
    }
}
