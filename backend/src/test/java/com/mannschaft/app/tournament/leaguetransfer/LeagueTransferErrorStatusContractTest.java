package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.tournament.TournamentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.7.1/03 リーグ移籍エラーコード（TOUR_038〜045）の HTTP ステータス契約テスト。
 *
 * <p>検分指摘🔴-1 の根治確認。これらのコードが {@code ERROR_CODE_STATUS_MAP} 未登録のままだと、
 * 設計書§7 が定める IDOR 404 / 認可 403 / 状態機械 409 が**実際は全部 400** に化ける。
 * errorCode 文字列しか見ない既存 UT はこのバグを素通りさせたため、ここでは
 * {@link GlobalExceptionHandler#handleBusinessException(BusinessException)} を実際に通し、
 * 返却される {@link HttpStatus} を直接検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("リーグ移籍エラーコード HTTP ステータス契約 (TOUR_038〜045)")
class LeagueTransferErrorStatusContractTest {

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private GlobalExceptionHandler globalExceptionHandler;

    private HttpStatus statusOf(TournamentErrorCode code) {
        ResponseEntity<ErrorResponse> response =
                globalExceptionHandler.handleBusinessException(new BusinessException(code));
        // エラーコード文字列も合わせて検証（取り違え防止）
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo(code.getCode());
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    @Test
    @DisplayName("TOUR_038 LEAGUE_TRANSFER_NOT_FOUND は 404（IDOR 隠蔽）")
    void notFound_404() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("TOUR_039 DISPATCH_FORBIDDEN は 403")
    void dispatchForbidden_403() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("TOUR_040 RESPOND_FORBIDDEN は 403")
    void respondForbidden_403() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_RESPOND_FORBIDDEN))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("TOUR_041 VIEW_FORBIDDEN は 403")
    void viewForbidden_403() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_VIEW_FORBIDDEN))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("TOUR_042 TARGET_NOT_RESOLVABLE は 422")
    void targetNotResolvable_422() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("TOUR_043 TEAM_NOT_IN_SLOT は 422")
    void teamNotInSlot_422() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("TOUR_044 ALREADY_DISPATCHED は 409（二重起票）")
    void alreadyDispatched_409() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_ALREADY_DISPATCHED))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("TOUR_045 NOT_DISPATCHED は 409（状態機械違反）")
    void notDispatched_409() {
        assertThat(statusOf(TournamentErrorCode.LEAGUE_TRANSFER_NOT_DISPATCHED))
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("回帰防止: いずれのコードも既定 400 に化けていない")
    void noneFallThroughTo400() {
        for (TournamentErrorCode code : new TournamentErrorCode[]{
                TournamentErrorCode.LEAGUE_TRANSFER_NOT_FOUND,
                TournamentErrorCode.LEAGUE_TRANSFER_DISPATCH_FORBIDDEN,
                TournamentErrorCode.LEAGUE_TRANSFER_RESPOND_FORBIDDEN,
                TournamentErrorCode.LEAGUE_TRANSFER_VIEW_FORBIDDEN,
                TournamentErrorCode.LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE,
                TournamentErrorCode.LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT,
                TournamentErrorCode.LEAGUE_TRANSFER_ALREADY_DISPATCHED,
                TournamentErrorCode.LEAGUE_TRANSFER_NOT_DISPATCHED
        }) {
            assertThat(statusOf(code))
                    .as("%s は ERROR_CODE_STATUS_MAP に登録され、既定の 400 に化けてはならない", code.name())
                    .isNotEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
