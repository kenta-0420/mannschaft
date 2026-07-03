package com.mannschaft.app.common;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GlobalExceptionHandler} の単体テスト。
 * 各例外ハンドラーが正しいHTTPステータスとエラーレスポンスを返すことを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler 単体テスト")
class GlobalExceptionHandlerTest {

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private GlobalExceptionHandler globalExceptionHandler;

    // ========================================
    // handleBusinessException
    // ========================================

    @Nested
    @DisplayName("handleBusinessException")
    class HandleBusinessException {

        @Test
        @DisplayName("正常系: COMMON_002エラーコードで403 Forbiddenが返る")
        void handleBusinessException_COMMON002_403Forbidden() {
            // Given
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_002);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_002");
        }

        @Test
        @DisplayName("正常系: COMMON_003エラーコードで409 Conflictが返る")
        void handleBusinessException_COMMON003_409Conflict() {
            // Given
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_003);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_003");
        }

        @Test
        @DisplayName("正常系: COMMON_000エラーコード（未認証）で401 Unauthorizedが返る")
        void handleBusinessException_COMMON000_401Unauthorized() {
            // Given
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_000);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            // COMMON_000 は JWT 認証失敗エラーのため、個別マッピングで 401 UNAUTHORIZED が返る
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_000");
        }

        @Test
        @DisplayName("正常系: COMMON_999エラーコード（ERROR severity）で500が返る")
        void handleBusinessException_COMMON999_500InternalError() {
            // Given
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_999);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_999");
        }

        @Test
        @DisplayName("正常系: フィールドエラーありの場合にフィールドエラーが含まれる")
        void handleBusinessException_フィールドエラーあり_エラー詳細含む() {
            // Given
            List<ErrorResponse.FieldError> fieldErrors = List.of(
                    new ErrorResponse.FieldError("email", "メールアドレスは必須です")
            );
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_001, fieldErrors);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getError().getFieldErrors()).hasSize(1);
            assertThat(response.getBody().getError().getFieldErrors().get(0).getField()).isEqualTo("email");
        }
    }

    // ========================================
    // handleValidationException
    // ========================================

    @Nested
    @DisplayName("handleValidationException")
    class HandleValidationException {

        @Test
        @DisplayName("正常系: バリデーションエラーで400 BadRequestとフィールドエラーが返る")
        void handleValidationException_バリデーションエラー_400BadRequest() {
            // Given
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("user", "email", "メールアドレスは必須です");
            given(bindingResult.getFieldErrors()).willReturn(List.of(fieldError));

            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleValidationException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_001");
            assertThat(response.getBody().getError().getFieldErrors()).hasSize(1);
            assertThat(response.getBody().getError().getFieldErrors().get(0).getField()).isEqualTo("email");
        }

        @Test
        @DisplayName("正常系: フィールドエラーなしの場合は空リスト")
        void handleValidationException_フィールドエラーなし_空リスト() {
            // Given
            BindingResult bindingResult = mock(BindingResult.class);
            given(bindingResult.getFieldErrors()).willReturn(List.of());

            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleValidationException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getError().getFieldErrors()).isEmpty();
        }
    }

    // ========================================
    // handleHttpMessageNotReadable
    // ========================================

    @Nested
    @DisplayName("handleHttpMessageNotReadable")
    class HandleHttpMessageNotReadable {

        @Test
        @DisplayName("正常系: JSONパースエラーで400 BadRequestが返る")
        void handleHttpMessageNotReadable_JSONパースエラー_400() {
            // Given
            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            given(ex.getMessage()).willReturn("JSON parse error");

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleHttpMessageNotReadable(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_001");
        }
    }

    // ========================================
    // handleTypeMismatch
    // ========================================

    @Nested
    @DisplayName("handleTypeMismatch")
    class HandleTypeMismatch {

        @Test
        @DisplayName("正常系: 型変換エラーで400 BadRequestが返る")
        void handleTypeMismatch_型変換エラー_400() {
            // Given
            MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
            given(ex.getName()).willReturn("id");
            given(ex.getValue()).willReturn("invalid");

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleTypeMismatch(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_001");
        }
    }

    // ========================================
    // handleMissingParam
    // ========================================

    @Nested
    @DisplayName("handleMissingParam")
    class HandleMissingParam {

        @Test
        @DisplayName("正常系: 必須パラメータ欠落で400 BadRequestが返る")
        void handleMissingParam_必須パラメータ欠落_400() {
            // Given
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("page", "Integer");

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleMissingParam(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_001");
        }
    }

    // ========================================
    // handleOptimisticLock
    // ========================================

    @Nested
    @DisplayName("handleOptimisticLock")
    class HandleOptimisticLock {

        @Test
        @DisplayName("正常系: 楽観ロック競合で409 Conflictが返る")
        void handleOptimisticLock_楽観ロック競合_409() {
            // Given
            ObjectOptimisticLockingFailureException ex =
                    new ObjectOptimisticLockingFailureException("EntityClass", new RuntimeException("conflict"));

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleOptimisticLock(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_003");
        }
    }

    // ========================================
    // handleUnexpectedException
    // ========================================

    @Nested
    @DisplayName("handleUnexpectedException")
    class HandleUnexpectedException {

        @Test
        @DisplayName("正常系: 予期しない例外で500 InternalServerErrorが返る")
        void handleUnexpectedException_予期しない例外_500() {
            // Given
            Exception ex = new RuntimeException("予期しないエラー");

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleUnexpectedException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_999");
        }
    }

    // ========================================
    // resolveHttpStatus
    // ========================================

    @Nested
    @DisplayName("resolveHttpStatus")
    class ResolveHttpStatus {

        @Test
        @DisplayName("正常系: WARN severityのエラーコードは400 BadRequestになる")
        void resolveHttpStatus_WARNseverity_400() {
            // When
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(CommonErrorCode.COMMON_001);

            // Then
            assertThat(result).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("正常系: ERROR severityのエラーコードは500になる")
        void resolveHttpStatus_ERRORseverity_500() {
            // When
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(CommonErrorCode.COMMON_999);

            // Then
            assertThat(result).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("正常系: 個別マッピングがERROR severityより優先される")
        void resolveHttpStatus_個別マッピング優先() {
            // When: COMMON_002はWARNだが個別マッピングで403になる
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(CommonErrorCode.COMMON_002);

            // Then
            assertThat(result).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("F22.1 市: RECRUITMENT_204（配信対象0件）は ERROR severity だが個別マッピングで400になる")
        void resolveHttpStatus_RECRUITMENT_204_400() {
            // RECRUITMENT_204 (EMPTY_DISTRIBUTION_TARGETS) は Severity.ERROR 既定では 500 だが、
            // 入力不備（PUBLIC 札の配信対象未設定）であり 400 を返すべき。
            // 未登録だと publish 失敗が 500 として漏れ、PUBLIC 札が市に出ない（実機 CRUD E2E で発覚）。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.recruitment.RecruitmentErrorCode.EMPTY_DISTRIBUTION_TARGETS);

            assertThat(result).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("F22.1 市: RECRUITMENT_207（visibility×配信対象不整合）は ERROR severity だが個別マッピングで400になる")
        void resolveHttpStatus_RECRUITMENT_207_400() {
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.recruitment.RecruitmentErrorCode.VISIBILITY_TARGETS_INCONSISTENT);

            assertThat(result).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("F22.1 市: RECRUITMENT_204 の BusinessException は 400 BadRequest で返る（500 漏れ防止の回帰固定）")
        void handleBusinessException_RECRUITMENT_204_400() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.recruitment.RecruitmentErrorCode.EMPTY_DISTRIBUTION_TARGETS);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RECRUITMENT_204");
        }

        @Test
        @DisplayName("F05.5: FOLDER_NOT_FOUND（FILE_SHARING_001）は個別マッピングで 404 NotFound になる（存在隠蔽・WARN 既定 400 の上書き回帰固定）")
        void resolveHttpStatus_FILE_SHARING_001_404() {
            // フォルダ詳細 API は他人/他チームのフォルダ ID を渡されても存在を漏らさず 404 を返す。
            // FOLDER_NOT_FOUND は Severity.WARN 既定（400）のため、個別マッピングで 404 に上書きする。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.filesharing.FileSharingErrorCode.FOLDER_NOT_FOUND);

            assertThat(result).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("F05.5: FOLDER_NOT_FOUND の BusinessException は 404 NotFound で返る")
        void handleBusinessException_FILE_SHARING_001_404() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.filesharing.FileSharingErrorCode.FOLDER_NOT_FOUND);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("FILE_SHARING_001");
        }

        @Test
        @DisplayName("F03.4 バグ#5: INVALID_TIME_RANGE（start>=end）は WARN severity で 400 BadRequest になる（500 漏れ防止の回帰固定）")
        void resolveHttpStatus_INVALID_TIME_RANGE_400() {
            // 実機 E2E で start>=end が 500 を返していた（旧 Severity.ERROR）。
            // 入力不正なので 400 が正。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.reservation.ReservationErrorCode.INVALID_TIME_RANGE);

            assertThat(result).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("F03.4 バグ#5: INVALID_TIME_RANGE の BusinessException は 400 BadRequest で返る")
        void handleBusinessException_INVALID_TIME_RANGE_400() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.INVALID_TIME_RANGE);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_007");
        }

        @Test
        @DisplayName("F03.4 バグ#5: 臨時休業の日付・時刻範囲不正も入力不正なので 400 BadRequest になる")
        void resolveHttpStatus_INVALID_CLOSURE_RANGES_400() {
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.reservation.ReservationErrorCode.INVALID_CLOSURE_DATE_RANGE))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.reservation.ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("F03.4 バグ#6: SLOT_HAS_ACTIVE_RESERVATIONS は個別マッピングで 409 Conflict になる（オーファン化防止）")
        void resolveHttpStatus_SLOT_HAS_ACTIVE_RESERVATIONS_409() {
            // 予約入り枠の削除を拒否する 409。Severity.WARN 既定（400）を個別マッピングで 409 に上書き。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.reservation.ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);

            assertThat(result).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("F03.4 バグ#6: SLOT_HAS_ACTIVE_RESERVATIONS の BusinessException は 409 Conflict で返る")
        void handleBusinessException_SLOT_HAS_ACTIVE_RESERVATIONS_409() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_020");
        }

        @Test
        @DisplayName("F03.4 予約認可ゲート: RESERVATION_PERMISSION_DENIED は個別マッピングで 403 Forbidden になる")
        void resolveHttpStatus_RESERVATION_PERMISSION_DENIED_403() {
            // 非所属者が一般公開OFFのチームに予約 → 403。Severity.WARN 既定（400）を個別マッピングで 403 に上書き。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.reservation.ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

            assertThat(result).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("F03.4 予約認可ゲート: RESERVATION_PERMISSION_DENIED の BusinessException は 403 Forbidden（code=RESERVATION_021）で返る")
        void handleBusinessException_RESERVATION_PERMISSION_DENIED_403() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            // #1601 の RESERVATION_020(409) と別物であることを保証
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_021");
        }

        @Test
        @DisplayName("F03.4 段階拡張⑧ 予約重複: DUPLICATE_RESERVATION の BusinessException は 409 Conflict（code=RESERVATION_013）で返る")
        void handleBusinessException_DUPLICATE_RESERVATION_409() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.DUPLICATE_RESERVATION);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_013");
        }

        @Test
        @DisplayName("F03.4 機能D 上限超過: NOTIFY_RECIPIENT_LIMIT_EXCEEDED は 400 Bad Request（code=RESERVATION_028・個別 map なし WARN 既定）")
        void handleBusinessException_NOTIFY_RECIPIENT_LIMIT_EXCEEDED_400() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.NOTIFY_RECIPIENT_LIMIT_EXCEEDED);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_028");
        }

        @Test
        @DisplayName("F03.4 機能D 有料必須: NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED は 402 Payment Required（code=RESERVATION_029）")
        void handleBusinessException_NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED_402() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_029");
        }

        @Test
        @DisplayName("F03.4 機能D 重複: NOTIFY_RECIPIENT_DUPLICATE は 409 Conflict（code=RESERVATION_030）")
        void handleBusinessException_NOTIFY_RECIPIENT_DUPLICATE_409() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.NOTIFY_RECIPIENT_DUPLICATE);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_030");
        }

        @Test
        @DisplayName("F03.4 機能D 不在: NOTIFY_RECIPIENT_NOT_FOUND は 404 Not Found（code=RESERVATION_031）")
        void handleBusinessException_NOTIFY_RECIPIENT_NOT_FOUND_404() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.NOTIFY_RECIPIENT_NOT_FOUND);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_031");
        }

        // ========================================
        // AC-7: セッション失効系（AUTH_039 等）は 401 Unauthorized
        // リフレッシュトークン並行更新の自爆バグ根治で、全セッション無効化後のアクセスは
        // 401 で返さないとクライアントが再ログインに遷移できない。
        // AUTH_039 / AUTH_026 は Severity.WARN 既定（400）のため個別マッピングで 401 に上書きする。
        // ========================================

        @Test
        @DisplayName("AC-7: AUTH_039（全デバイス無効化後アクセス）は resolveHttpStatus で 401 Unauthorized になる")
        void ac7_resolveHttpStatus_AUTH039_401() {
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.auth.AuthErrorCode.AUTH_039);

            assertThat(result).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("AC-7: AUTH_039 の BusinessException は 401 Unauthorized で返る")
        void ac7_handleBusinessException_AUTH039_401() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.auth.AuthErrorCode.AUTH_039);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("AUTH_039");
        }

        @Test
        @DisplayName("AC-7: AUTH_026（リプレイ検出・全セッション無効化）も 401 Unauthorized で返る")
        void ac7_handleBusinessException_AUTH026_401() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.auth.AuthErrorCode.AUTH_026);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("AUTH_026");
        }
    }

    // ========================================
    // F10.6 Phase 10-β-1 — recordBackendException 連携
    // ========================================

    @Nested
    @DisplayName("recordBackendException 連携 (F10.6 Phase 10-β-1)")
    class RecordBackendExceptionWiring {

        /**
         * F10.6 連携用に明示的に DI したハンドラ。
         * @InjectMocks 配下の globalExceptionHandler は引数1個のコンストラクタで生成されるため、
         * 連携テストはここで自前生成する。
         */
        @SuppressWarnings("unchecked")
        private GlobalExceptionHandler newHandlerWith(ErrorReportService service, ErrorReportNotifier notifier) {
            ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
            ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
            // lenient: テストにより呼ばれない経路もあるため Strictness を緩める
            org.mockito.Mockito.lenient().when(serviceProvider.getIfAvailable()).thenReturn(service);
            org.mockito.Mockito.lenient().when(notifierProvider.getIfAvailable()).thenReturn(notifier);
            return new GlobalExceptionHandler(messageSource, serviceProvider, notifierProvider);
        }

        @Test
        @DisplayName("handleUnexpectedException: severity=HIGH で recordBackendException が呼ばれる")
        void unexpected_recordsAsHigh() {
            ErrorReportService service = mock(ErrorReportService.class);
            ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
            GlobalExceptionHandler handler = newHandlerWith(service, notifier);

            HttpServletRequest req = mock(HttpServletRequest.class);
            RuntimeException ex = new RuntimeException("boom");

            ResponseEntity<ErrorResponse> resp = handler.handleUnexpectedException(ex, req);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(service).recordBackendException(eq(ex), eq(req), eq(ErrorReportSeverity.HIGH));
        }

        @Test
        @DisplayName("handleBusinessException: 5xx を返すコードでは MEDIUM で記録される")
        void businessException_5xx_recordsAsMedium() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            HttpServletRequest req = mock(HttpServletRequest.class);
            // COMMON_999 は ERROR severity → 500
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_999);

            ResponseEntity<ErrorResponse> resp = handler.handleBusinessException(ex, req);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(service).recordBackendException(eq(ex), eq(req), eq(ErrorReportSeverity.MEDIUM));
        }

        @Test
        @DisplayName("handleBusinessException: 4xx を返すコードでは記録されない（バリデーション含む通常エラー）")
        void businessException_4xx_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            // COMMON_001 は WARN → 400
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_001);

            handler.handleBusinessException(ex, mock(HttpServletRequest.class));

            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
        }

        @Test
        @DisplayName("handleValidationException: バリデーションエラーは recordBackendException が呼ばれない")
        void validation_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            // バリデーション例外を組み立てる
            BindingResult bindingResult = mock(BindingResult.class);
            given(bindingResult.getFieldErrors()).willReturn(List.of(
                    new FieldError("obj", "field", "must not be null")));
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            given(ex.getBindingResult()).willReturn(bindingResult);

            ResponseEntity<ErrorResponse> resp = handler.handleValidationException(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
        }

        @Test
        @DisplayName("handleHttpMessageNotReadable: バリデーション系として記録されない")
        void httpMessageNotReadable_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
            given(ex.getMessage()).willReturn("invalid json");

            handler.handleHttpMessageNotReadable(ex);

            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
        }

        @Test
        @DisplayName("handleIllegalState: 競合（409）は記録されない")
        void illegalState_conflict_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            IllegalStateException ex = new IllegalStateException("リトライ後も競合が解消しない");
            ResponseEntity<ErrorResponse> resp = handler.handleIllegalState(ex, mock(HttpServletRequest.class));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
        }

        @Test
        @DisplayName("handleIllegalState: 競合以外（500）は HIGH で記録される")
        void illegalState_500_isRecordedAsHigh() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            IllegalStateException ex = new IllegalStateException("想定外の状態");
            HttpServletRequest req = mock(HttpServletRequest.class);
            ResponseEntity<ErrorResponse> resp = handler.handleIllegalState(ex, req);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            verify(service).recordBackendException(eq(ex), eq(req), eq(ErrorReportSeverity.HIGH));
        }

        @Test
        @DisplayName("Bean 未配線（既存コンストラクタ）でも例外なく完走する")
        void unwired_handler_works() {
            // 既存ユニットテスト互換コンストラクタ（messageSource のみ）
            GlobalExceptionHandler unwired = new GlobalExceptionHandler(messageSource);
            ResponseEntity<ErrorResponse> resp = unwired.handleUnexpectedException(new RuntimeException("x"));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("F10.6 後続-①: ConstraintViolationException は recordBackendException が呼ばれない")
        void constraintViolation_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            // jakarta.validation.ConstraintViolation は final な実装なので mock する
            ConstraintViolation<?> v = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("listJobs.page");
            when(v.getPropertyPath()).thenReturn(path);
            when(v.getMessage()).thenReturn("must be greater than or equal to 1");

            ConstraintViolationException ex =
                    new ConstraintViolationException("validation failed", Set.of(v));

            ResponseEntity<ErrorResponse> resp = handler.handleConstraintViolation(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getError().getCode()).isEqualTo("COMMON_001");
            assertThat(resp.getBody().getError().getFieldErrors()).hasSize(1);
            assertThat(resp.getBody().getError().getFieldErrors().get(0).getField()).isEqualTo("page");
            // 設計書 F10.6 §5.2: バリデーションエラーは error_reports に記録しない
            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
            verify(service, never()).recordBackendException(any(), anyString(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("F10.6 後続-①: HandlerMethodValidationException も recordBackendException が呼ばれない")
        void handlerMethodValidation_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
            when(ex.getMessage()).thenReturn("validation failed");

            ResponseEntity<ErrorResponse> resp = handler.handleHandlerMethodValidation(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().getError().getCode()).isEqualTo("COMMON_001");
            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
            verify(service, never()).recordBackendException(any(), anyString(), anyString(), anyString(), anyString(), any());
        }
    }
}
