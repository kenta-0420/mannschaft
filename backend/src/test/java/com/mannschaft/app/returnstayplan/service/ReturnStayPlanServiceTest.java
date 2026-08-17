package com.mannschaft.app.returnstayplan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F02.11 の日付・場所・公開範囲・表示状態を固定する red 試練。 */
class ReturnStayPlanServiceTest {

    private static final long OWNER_ID = 10L;
    private static final LocalDate OWNER_TODAY = LocalDate.of(2026, 8, 17);
    private static final Clock JST_CLOCK = Clock.fixed(
            Instant.parse("2026-08-17T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private final ReturnStayPlanService service = new ReturnStayPlanService(JST_CLOCK);

    @Test
    @DisplayName("AC-03 国内帰省予定: 作成結果は全入力とAsia/Tokyoを保持する")
    void ac03_国内帰省予定を作成すると契約値を保持する() {
        var request = request("HOMECOMING", true, "JP", "13", null,
                OWNER_TODAY, OWNER_TODAY.plusDays(3), List.of(30L, 40L));

        var created = service.create(OWNER_ID, request);

        assertThat(created.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(created.getPlanType().name()).isEqualTo("HOMECOMING");
        assertThat(created.getPublished()).isTrue();
        assertThat(created.getCountryCode()).isEqualTo("JP");
        assertThat(created.getPrefectureCode()).isEqualTo("13");
        assertThat(created.getRegionName()).isNull();
        assertThat(created.getTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(created.getStartDate()).isEqualTo(OWNER_TODAY);
        assertThat(created.getEndDate()).isEqualTo(OWNER_TODAY.plusDays(3));
    }

    @Test
    @DisplayName("AC-06 開始日下限: ownerTodayの前日はINVALID_REQUEST")
    void ac06_ownerToday前日の開始日は拒否する() {
        var input = request("HOMECOMING", false, "JP", "01", null,
                OWNER_TODAY.minusDays(1), OWNER_TODAY.plusDays(1), List.of());

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-07 終了日上限: ownerToday+365日は許可する")
    void ac07_ownerTodayから365日後の終了日は許可する() {
        var input = request("STAYING", false, "JP", "47", null,
                OWNER_TODAY.plusDays(1), OWNER_TODAY.plusDays(365), List.of(81L));

        var created = service.create(OWNER_ID, input);

        assertThat(created.getEndDate()).isEqualTo(OWNER_TODAY.plusDays(365));
    }

    @Test
    @DisplayName("AC-07 終了日上限: ownerToday+366日はINVALID_REQUEST")
    void ac07_ownerTodayから366日後の終了日は拒否する() {
        var input = request("STAYING", false, "JP", "47", null,
                OWNER_TODAY.plusDays(1), OWNER_TODAY.plusDays(366), List.of(81L));

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-08 国内場所: 都道府県コード00はINVALID_REQUEST")
    void ac08_都道府県コード00は拒否する() {
        var input = request("HOMECOMING", false, "JP", "00", null,
                OWNER_TODAY.plusDays(2), OWNER_TODAY.plusDays(4), List.of());

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-09 国内場所: regionName併用はINVALID_REQUEST")
    void ac09_JPでregionNameを併用すると拒否する() {
        var input = request("STAYING", false, "JP", "27", "Osaka",
                OWNER_TODAY.plusDays(5), OWNER_TODAY.plusDays(8), List.of());

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-10 海外段階導入: USの地域指定はINVALID_REQUEST")
    void ac10_featureFlag前の海外予定は拒否する() {
        var input = request("STAYING", false, "US", null, "California",
                OWNER_TODAY.plusDays(10), OWNER_TODAY.plusDays(14), List.of());

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-11 公開下限: 公開ONでteamIds空はINVALID_REQUEST")
    void ac11_公開ONで公開先が空なら拒否する() {
        var input = request("HOMECOMING", true, "JP", "26", null,
                OWNER_TODAY.plusDays(20), OWNER_TODAY.plusDays(21), List.of());

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-12 公開上限: teamIdsが21件ならLIMIT_EXCEEDED")
    void ac12_公開先が21件なら上限エラー() {
        var teamIds = java.util.stream.LongStream.rangeClosed(100, 120).boxed().toList();
        var input = request("HOMECOMING", true, "JP", "40", null,
                OWNER_TODAY.plusDays(30), OWNER_TODAY.plusDays(32), teamIds);

        assertBusinessError(ReturnStayPlanErrorCode.LIMIT_EXCEEDED,
                () -> service.create(OWNER_ID, input));
    }

    @Test
    @DisplayName("AC-15 状態: 開始日前はUPCOMING")
    void ac15_開始日前はupcoming() {
        var status = service.resolveStatus(
                OWNER_TODAY.plusDays(1), OWNER_TODAY.plusDays(4), "Asia/Tokyo");

        assertThat(status).isEqualTo(ReturnStayPlanService.DisplayStatus.UPCOMING);
    }

    @Test
    @DisplayName("AC-15 状態: 開始日と終了日の当日はACTIVE")
    void ac15_期間の両端を含めてactive() {
        var status = service.resolveStatus(OWNER_TODAY, OWNER_TODAY, "Asia/Tokyo");

        assertThat(status).isEqualTo(ReturnStayPlanService.DisplayStatus.ACTIVE);
    }

    @Test
    @DisplayName("AC-15 状態: 終了日の翌日はENDED")
    void ac15_終了日の翌日はended() {
        var status = service.resolveStatus(
                OWNER_TODAY.minusDays(4), OWNER_TODAY.minusDays(1), "Asia/Tokyo");

        assertThat(status).isEqualTo(ReturnStayPlanService.DisplayStatus.ENDED);
    }

    private void assertBusinessError(
            ReturnStayPlanErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(expected));
    }

    private ReturnStayPlanCreateRequest request(
            String planType,
            boolean published,
            String countryCode,
            String prefectureCode,
            String regionName,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> teamIds) {
        return new ReturnStayPlanCreateRequest(
                planType,
                published,
                new ReturnStayPlanCreateRequest.Location(countryCode, prefectureCode, regionName),
                startDate,
                endDate,
                teamIds);
    }
}
