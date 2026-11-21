package com.mannschaft.app.shiftbudget.closeperiod;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 PR-α 締め日設定 API（実DB・実HTTP）。
 *
 * <p>試練105（AC27）・111（AC34）・114b（AC37 締め日変更側）・116（AC39）・129a（AC52 の API 部分）、
 * および FE 試練108 と共有する契約 {@code min_effective_period_key}（AC27・37 の境界）。</p>
 *
 * <p>実装前の red 理由: {@code /api/v1/shift-budget/closing-settings} が無く 404（応答 data が空）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α 締め日設定 API（実DB・実HTTP）")
class ClosingSettingApiIT extends ClosePeriodTestBase {

    private long changeRows() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM shift_budget_closing_day_changes WHERE organization_id = ?",
                Long.class, orgId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("試練116 AC39 設定なしの GET は closing_day=0（末日）・revision=0・履歴空・min_effective_period_key＝今日を含む期間")
    void 試練116_設定なしのGET() throws Exception {
        JsonNode s = data(getSettings(adminId, orgId).andExpect(status().isOk()));
        assertThat(s.path("closing_day").isInt()).as("closing_day 欄: %s", s).isTrue();
        assertThat(s.path("closing_day").asInt()).isZero();
        assertThat(s.path("revision").asLong()).isZero();
        assertThat(s.path("changes").isArray()).isTrue();
        assertThat(s.path("changes")).isEmpty();
        assertThat(s.path("min_effective_period_key").asText()).isEqualTo("2026-10");
        assertThat(changeRows()).as("GET は設定行・履歴を作らない").isZero();
    }

    @Test
    @DisplayName("試練105 AC27 変更履歴は追記のみ（revision 昇順・前の締め日・理由・実施者・日時）、理由必須、更新・削除の経路なし")
    void 試練105_履歴は追記のみ() throws Exception {
        changeClosingDay(adminId, orgId, 0, 15, "2026-11", "15日締めへ").andExpect(status().is2xxSuccessful());
        changeClosingDay(adminId, orgId, 1, 10, "2026-12", "10日締めへ").andExpect(status().is2xxSuccessful());

        JsonNode s = data(getSettings(adminId, orgId).andExpect(status().isOk()));
        assertThat(s.path("revision").asLong()).isEqualTo(2);
        JsonNode changes = s.path("changes");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).path("revision").asLong()).isEqualTo(1);
        assertThat(changes.get(0).path("effective_period_key").asText()).isEqualTo("2026-11");
        assertThat(changes.get(0).path("closing_day").asInt()).isEqualTo(15);
        assertThat(changes.get(0).path("previous_closing_day").asInt()).isZero();
        assertThat(changes.get(0).path("reason").asText()).isEqualTo("15日締めへ");
        assertThat(changes.get(0).path("actor_user_id").asLong()).isEqualTo(adminId);
        assertThat(changes.get(0).path("created_at").asText()).isNotBlank();
        assertThat(changes.get(1).path("previous_closing_day").asInt()).isEqualTo(15);
        assertThat(changes.get(1).path("closing_day").asInt()).isEqualTo(10);
        assertThat(s.path("closing_day").asInt()).as("今日（10/01）時点の締め日は末日のまま").isZero();
        assertThat(changeRows()).isEqualTo(2);

        // 理由なし・空白・500字超は 400、行は増えない
        changeClosingDay(adminId, orgId, 2, 5, "2027-01", null).andExpect(status().isBadRequest());
        changeClosingDay(adminId, orgId, 2, 5, "2027-01", "  ").andExpect(status().isBadRequest());
        changeClosingDay(adminId, orgId, 2, 5, "2027-01", "x".repeat(501)).andExpect(status().isBadRequest());
        // 古い revision は 409
        changeClosingDay(adminId, orgId, 1, 5, "2027-01", "古い版").andExpect(status().isConflict());
        assertThat(changeRows()).isEqualTo(2);

        // 更新・削除の経路は無い
        int putStatus = mvc.perform(as(put("/api/v1/shift-budget/closing-settings/changes/1"), adminId, orgId)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
        int deleteStatus = mvc.perform(as(delete("/api/v1/shift-budget/closing-settings/changes/1"), adminId, orgId))
                .andReturn().getResponse().getStatus();
        assertThat(putStatus).isIn(404, 405);
        assertThat(deleteStatus).isIn(404, 405);
        assertThat(changeRows()).isEqualTo(2);
    }

    @ParameterizedTest(name = "closing_day={0}")
    @ValueSource(strings = {"29", "30", "31", "-1", "null", "\"abc\"", "1.5"})
    @DisplayName("試練111 AC34 締め日 29／30／31／-1／null／非数値は 400 SHIFT_BUDGET_037、変更0")
    void 試練111_不正な締め日は400(String raw) throws Exception {
        String body = "{\"expected_revision\":0,\"closing_day\":" + raw
                + ",\"effective_period_key\":\"2026-11\",\"reason\":\"不正値\"}";
        mvc.perform(as(post("/api/v1/shift-budget/closing-settings/changes"), adminId, orgId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_037"));
        assertThat(changeRows()).isZero();
    }

    @Test
    @DisplayName("試練111 AC34 境界: 0（末日）・1・28 は受理される")
    void 試練111_境界値は受理() throws Exception {
        changeClosingDay(adminId, orgId, 0, 1, "2026-11", "1日").andExpect(status().is2xxSuccessful());
        changeClosingDay(adminId, orgId, 1, 28, "2026-12", "28日").andExpect(status().is2xxSuccessful());
        changeClosingDay(adminId, orgId, 2, 0, "2027-01", "末日").andExpect(status().is2xxSuccessful());
        assertThat(changeRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("試練114b AC37 締め日変更: 今日を含む期間より前は 409 SHIFT_BUDGET_038、締め済み期間以前も 409、その翌期間は受理")
    void 試練114b_締め日変更の過去と締め済み() throws Exception {
        allocation(d("2026-09-01"), d("2026-09-30"));
        worked(d("2026-09-10"), workerId);
        today(d("2026-09-30"));
        close("2026-09").andExpect(status().isOk());

        changeClosingDay(adminId, orgId, 0, 15, "2026-08", "過去").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_038"));
        changeClosingDay(adminId, orgId, 0, 15, "2026-09", "今日を含むが締め済み").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_038"));
        assertThat(changeRows()).isZero();
        changeClosingDay(adminId, orgId, 0, 15, "2026-10", "締め済みの翌期間").andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("min_effective_period_key（AC27・37 境界）: 今日を含む期間と最後の締めの次の期間のうち遅い方。締め日設定後は期間で数える")
    void min_effective_period_keyの境界() throws Exception {
        // 締めなし・今日 10/01（末日締め）→ 2026-10
        assertThat(data(getSettings(adminId, orgId)).path("min_effective_period_key").asText()).isEqualTo("2026-10");

        // 9/30（終了日当日）に 2026-09 を締めた → 今日を含む 2026-09 より締めの次 2026-10 が遅い
        allocation(d("2026-09-01"), d("2026-09-30"));
        worked(d("2026-09-10"), workerId);
        today(d("2026-09-30"));
        close("2026-09").andExpect(status().isOk());
        assertThat(data(getSettings(adminId, orgId)).path("min_effective_period_key").asText()).isEqualTo("2026-10");

        // 15日締めを 2026-10 から適用し、今日 10/20 → 今日を含む期間は 10/16〜11/15 の 2026-11
        changeClosingDay(adminId, orgId, 0, 15, "2026-10", "15日締め").andExpect(status().is2xxSuccessful());
        today(d("2026-10-20"));
        assertThat(data(getSettings(adminId, orgId)).path("min_effective_period_key").asText()).isEqualTo("2026-11");
        // 今日 10/15（期間 2026-10 の終了日当日）→ 2026-10
        today(d("2026-10-15"));
        assertThat(data(getSettings(adminId, orgId)).path("min_effective_period_key").asText()).isEqualTo("2026-10");
        // 下限ちょうどは受理、1つ前は 409
        changeClosingDay(adminId, orgId, 1, 20, "2026-09", "下限の前").andExpect(status().isConflict());
        changeClosingDay(adminId, orgId, 1, 20, "2026-10", "下限ちょうど").andExpect(status().is2xxSuccessful());
    }

    @Test
    @DisplayName("試練129a AC52 2026-04 締め済みの組織で 15日締めを 2026-04 から適用 → 409、2026-05 から → 受理。2026-04 は 4/1〜4/30 のまま")
    void 試練129a_締め済み月の後からのみ締め日変更() throws Exception {
        allocation(d("2026-04-01"), d("2026-05-31"));
        worked(d("2026-04-10"), workerId);
        today(d("2026-04-30"));
        close("2026-04").andExpect(status().isOk());

        changeClosingDay(adminId, orgId, 0, 15, "2026-04", "締め済み月から").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_038"));
        changeClosingDay(adminId, orgId, 0, 15, "2026-05", "翌月から").andExpect(status().is2xxSuccessful());

        JsonNode items = data(closePeriods(adminId, orgId, "2026-04", "2026-06").andExpect(status().isOk()))
                .path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("period_key").asText()).isEqualTo("2026-04");
        assertThat(items.get(0).path("period_start").asText()).isEqualTo("2026-04-01");
        assertThat(items.get(0).path("period_end").asText()).isEqualTo("2026-04-30");
        assertThat(items.get(0).path("closing_day").asInt()).isZero();
        assertThat(items.get(1).path("period_start").asText()).as("移行期間（短い期間）").isEqualTo("2026-05-01");
        assertThat(items.get(1).path("period_end").asText()).isEqualTo("2026-05-15");
        assertThat(items.get(2).path("period_start").asText()).isEqualTo("2026-05-16");
        assertThat(items.get(2).path("period_end").asText()).isEqualTo("2026-06-15");
    }
}
