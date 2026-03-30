package com.mannschaft.app.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
        @DisplayName("正常系: COMMON_000エラーコード（WARN severity）で400 BadRequestが返る")
        void handleBusinessException_COMMON000_400BadRequest() {
            // Given
            BusinessException ex = new BusinessException(CommonErrorCode.COMMON_000);

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
    }
}
