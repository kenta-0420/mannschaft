package com.mannschaft.app.common;

import com.mannschaft.app.billing.EntitlementNotEntitledDetails;
import com.mannschaft.app.billing.FeatureNotEntitledException;
import com.mannschaft.app.billing.api.dto.FeatureNotEntitledErrorResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Set;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
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
    // handleFeatureNotEntitled（F20.1 402 details 追補）
    // ========================================

    @Nested
    @DisplayName("handleFeatureNotEntitled")
    class HandleFeatureNotEntitled {

        private EntitlementNotEntitledDetails details(Integer addonPriceJpy) {
            return EntitlementNotEntitledDetails.builder()
                    .featureKey("ads.hide")
                    .addonAvailable(true)
                    .addonPriceJpy(addonPriceJpy)
                    .plansContaining(List.of("FULL"))
                    .scopeKind("TEAM")
                    .scopeId(123L)
                    .build();
        }

        @Test
        @DisplayName("正常系: 402 Payment Required・envelope（error.code=ENTITLEMENT_003）＋details 直列化")
        void handleFeatureNotEntitled_402WithDetails() {
            // Given
            FeatureNotEntitledException ex = new FeatureNotEntitledException(details(500));

            // When
            ResponseEntity<FeatureNotEntitledErrorResponse> response =
                    globalExceptionHandler.handleFeatureNotEntitled(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("ENTITLEMENT_003");
            assertThat(response.getBody().getError().getFieldErrors()).isEmpty();
            assertThat(response.getBody().getError().getDetails()).isNotNull();
            assertThat(response.getBody().getError().getDetails().getFeatureKey()).isEqualTo("ads.hide");
            assertThat(response.getBody().getError().getDetails().isAddonAvailable()).isTrue();
            assertThat(response.getBody().getError().getDetails().getAddonPriceJpy()).isEqualTo(500);
            assertThat(response.getBody().getError().getDetails().getPlansContaining()).containsExactly("FULL");
            assertThat(response.getBody().getError().getDetails().getScopeKind()).isEqualTo("TEAM");
            assertThat(response.getBody().getError().getDetails().getScopeId()).isEqualTo(123L);
        }

        @Test
        @DisplayName("正常系: addonPriceJpy=null 時は details.addonPriceJpy が null のまま一貫して返る")
        void handleFeatureNotEntitled_addonPriceJpyNull() {
            // Given
            FeatureNotEntitledException ex = new FeatureNotEntitledException(details(null));

            // When
            ResponseEntity<FeatureNotEntitledErrorResponse> response =
                    globalExceptionHandler.handleFeatureNotEntitled(ex);

            // Then
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getDetails().getAddonPriceJpy()).isNull();
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
    // handleResponseStatusException
    //
    // 共通基盤バグの再発防止:
    // ResponseStatusException 用ハンドラが無いと catch-all の handleUnexpectedException に落ち、
    // 本来のステータスを失って 500 COMMON_999 に化ける（実測: 画像枚数上限 422 → 500）。
    // ここでステータスが保たれることを機械的に固定する。
    // ========================================

    @Nested
    @DisplayName("handleResponseStatusException")
    class HandleResponseStatusException {

        @ParameterizedTest(name = "{0} を投げたら {0} が返り、code={1}")
        @DisplayName("正常系: 送出したステータスがそのまま保たれる（500 に化けない）")
        @CsvSource({
                "BAD_REQUEST,            COMMON_001",
                "UNAUTHORIZED,           COMMON_000",
                "FORBIDDEN,              COMMON_002",
                "NOT_FOUND,              COMMON_005",
                "METHOD_NOT_ALLOWED,     COMMON_004",
                "CONFLICT,               COMMON_003",
                "UNPROCESSABLE_ENTITY,   COMMON_001",
                "PAYLOAD_TOO_LARGE,      COMMON_001",
                "TOO_MANY_REQUESTS,      COMMON_001",
                "GONE,                   COMMON_001"
        })
        void handleResponseStatusException_ステータスが保たれる(HttpStatus status, String expectedCode) {
            // Given
            ResponseStatusException ex = new ResponseStatusException(status, "理由メッセージ");

            // When
            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleResponseStatusException(ex);

            // Then: 500 COMMON_999 に化けていないこと
            assertThat(response.getStatusCode()).isEqualTo(status);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo(expectedCode);
            assertThat(response.getBody().getError().getMessage()).isEqualTo("理由メッセージ");
            assertThat(response.getBody().getError().getFieldErrors()).isEmpty();
        }

        @Test
        @DisplayName("正常系: 画像枚数上限の 422 が 500 に化けず 422 のまま返る（実測不具合の回帰テスト）")
        void handleResponseStatusException_画像枚数上限422() {
            // Given: BlogMediaService が投げるのと同型の例外
            ResponseStatusException ex = new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "画像の枚数が上限に達しています");

            // When
            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleResponseStatusException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isNotEqualTo("COMMON_999");
            assertThat(response.getBody().getError().getMessage())
                    .isEqualTo("画像の枚数が上限に達しています");
        }

        @Test
        @DisplayName("正常系: reason が無い場合は ErrorCode の多言語メッセージにフォールバックする")
        void handleResponseStatusException_reason無し_多言語メッセージ() {
            // Given
            when(messageSource.getMessage(eq("error.common.005"), any(), any()))
                    .thenReturn("リソースが見つかりません");
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND);

            // When
            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleResponseStatusException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_005");
            assertThat(response.getBody().getError().getMessage()).isEqualTo("リソースが見つかりません");
        }

        @Test
        @DisplayName("正常系: 5xx は reason を伏せて COMMON_999 の定型文を返す（内部情報の露出防止）")
        void handleResponseStatusException_5xx_reasonを伏せる() {
            // Given
            ResponseStatusException ex = new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "内部の詳細な失敗理由");

            // When
            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleResponseStatusException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_999");
            assertThat(response.getBody().getError().getMessage())
                    .isEqualTo(CommonErrorCode.COMMON_999.getMessage())
                    .doesNotContain("内部の詳細な失敗理由");
        }

        @Test
        @DisplayName("正常系: 例外が保持するレスポンスヘッダを引き継ぐ（Retry-After 等）")
        void handleResponseStatusException_ヘッダ引継ぎ() {
            // Given
            // ResponseStatusException のヘッダは getHeaders() のオーバーライドで表現される
            ResponseStatusException ex =
                    new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "レート制限") {
                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders headers = new HttpHeaders();
                            headers.add("Retry-After", "120");
                            return headers;
                        }
                    };

            // When
            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleResponseStatusException(ex);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("120");
        }

        /**
         * ハンドラの「選択」を検証する。ハンドラメソッドを直接呼ぶテストでは
         * 「{@code @ExceptionHandler(Exception.class)} の総受けに落ちていないこと」を証明できないため、
         * 実際の {@code ExceptionHandlerExceptionResolver} を通す MockMvc で確認する。
         * 本バグの本質は「解決順序で catch-all に吸われる」ことなので、この経路の固定が要となる。
         */
        @Test
        @DisplayName("正常系: MVC 経由でも catch-all(Exception) に吸われず 422 のまま返る")
        void handleResponseStatusException_MVC経由で総受けに吸われない() throws Exception {
            // Given: 422 を投げるだけの controller と、本物の GlobalExceptionHandler を載せた MockMvc
            @org.springframework.web.bind.annotation.RestController
            class ThrowingController {
                @org.springframework.web.bind.annotation.GetMapping("/test-rse")
                public String throwRse() {
                    throw new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "画像の枚数が上限に達しています");
                }
            }

            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(new ThrowingController())
                    .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                    .build();

            // When / Then
            mockMvc.perform(get("/test-rse"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                    .andExpect(jsonPath("$.error.message").value("画像の枚数が上限に達しています"));
        }

        /**
         * IDOR/BOLA 対策の「存在秘匿 404」が MVC 経路でも 404 のまま返ることを固定する。
         * ProfileMediaService 等は権限が無いリソースを 403 ではなく 404 で秘匿する設計だが、
         * catch-all に吸われて 500 になると設計が意図した秘匿の形にならない。
         * 本 PR が最重要と位置づける経路のため、MVC 経路で機械的に固定する。
         */
        @Test
        @DisplayName("正常系: MVC 経由で存在秘匿の 404 が 500 に化けず 404 のまま返る")
        void handleResponseStatusException_MVC経由で存在秘匿404が保たれる() throws Exception {
            // Given: 存在秘匿の 404 を投げる controller（reason 未設定＝情報を与えない形）
            @org.springframework.web.bind.annotation.RestController
            class ConcealingController {
                @org.springframework.web.bind.annotation.GetMapping("/test-conceal")
                public String conceal() {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }
            }

            when(messageSource.getMessage(eq("error.common.005"), any(), any()))
                    .thenReturn("リソースが見つかりません");

            MockMvc mockMvc = MockMvcBuilders
                    .standaloneSetup(new ConcealingController())
                    .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                    .build();

            // When / Then: 403 でも 500 でもなく 404 で秘匿されること
            mockMvc.perform(get("/test-conceal"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("COMMON_005"))
                    .andExpect(jsonPath("$.error.message").value("リソースが見つかりません"));
        }

        @Test
        @DisplayName("正常系: 4xx は error_reports に記録しない / 5xx は severity=MEDIUM で記録する")
        void handleResponseStatusException_記録方針() {
            ErrorReportService service = mock(ErrorReportService.class);
            ErrorReportNotifier notifier = mock(ErrorReportNotifier.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<ErrorReportService> serviceProvider = mock(ObjectProvider.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<ErrorReportNotifier> notifierProvider = mock(ObjectProvider.class);
            org.mockito.Mockito.lenient().when(serviceProvider.getIfAvailable()).thenReturn(service);
            org.mockito.Mockito.lenient().when(notifierProvider.getIfAvailable()).thenReturn(notifier);
            GlobalExceptionHandler handler =
                    new GlobalExceptionHandler(messageSource, serviceProvider, notifierProvider);
            HttpServletRequest req = mock(HttpServletRequest.class);

            // 4xx: 記録しない
            handler.handleResponseStatusException(
                    new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "上限超過"), req);
            verify(service, never()).recordBackendException(any(), any(), any());

            // 5xx: severity=MEDIUM で記録する
            ResponseStatusException serverError =
                    new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream 失敗");
            handler.handleResponseStatusException(serverError, req);
            verify(service).recordBackendException(
                    eq(serverError), eq(req), eq(ErrorReportSeverity.MEDIUM));
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
    // handleNoResourceFound
    // ========================================

    @Nested
    @DisplayName("HandleNoResourceFound")
    class HandleNoResourceFound {

        @Test
        @DisplayName("未マップパスへのリクエストが 404 NOT_FOUND + COMMON_005 で返る")
        void noResourceFound_returns404WithCommon005() {
            // Given
            NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/zzz-not-exists");

            // When
            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleNoResourceFound(ex, null);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("COMMON_005");
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
        @DisplayName("認可根治 Wave3-B12event: EVENT_NOT_FOUND（EVENT_001）は個別マッピングで 404 NotFound になる"
                + "（スコープ帰属不一致・IDOR 秘匿・WARN 既定 400 の上書き回帰固定）")
        void resolveHttpStatus_EVENT_001_404() {
            // EventScopeAccessGuard 等の Javadoc は「404 で秘匿する」と明記していたが、
            // 個別マッピング未登録のため Severity.WARN 既定の 400 のままだった実装漏れを根治した。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.EVENT_NOT_FOUND);

            assertThat(result).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("認可根治 Wave3-B12event: 参加登録/チケット/招待トークン/チケット種別/タイムテーブル項目の "
                + "NOT_FOUND 系（親子BOLA秘匿）は個別マッピングで 404 NotFound になる")
        void resolveHttpStatus_eventSubResourceNotFound_404() {
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.REGISTRATION_NOT_FOUND))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.TICKET_NOT_FOUND))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.INVITE_TOKEN_NOT_FOUND))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.TICKET_TYPE_NOT_FOUND))
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.event.EventErrorCode.TIMETABLE_ITEM_NOT_FOUND))
                    .isEqualTo(HttpStatus.NOT_FOUND);
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
        @DisplayName("F09.19.1 AC-1.5/1.4: AD_027（状態遷移違反・編集不可）は個別マッピングで 409 Conflict になる")
        void resolveHttpStatus_AD_027_409() {
            // 状態遷移違反・編集不可状態・編集不可フィールドの変更（F09.19 §15）。
            // Severity.WARN 既定（400）のため ERROR_CODE_STATUS_MAP で 409 に上書きが必要。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.advertising.AdvertisingErrorCode.AD_027);

            assertThat(result).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("F09.19: AD_029（visit/click IP レート制限）は個別マッピングで 429 TooManyRequests になる")
        void resolveHttpStatus_AD_029_429() {
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.advertising.AdvertisingErrorCode.AD_029);

            assertThat(result).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("F09.19: AD_033（通報自動停止中の resume 拒否）は個別マッピングで 403 Forbidden になる")
        void resolveHttpStatus_AD_033_403() {
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.advertising.AdvertisingErrorCode.AD_033);

            assertThat(result).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("F09.19.1 AC-1.12: AD_034（参照中の料金カード削除拒否）は個別マッピングで 409 Conflict になる")
        void resolveHttpStatus_AD_034_409() {
            // FK violation 500 回帰防御（F09.19 §5.2 V144.002）。
            HttpStatus result = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.advertising.AdvertisingErrorCode.AD_034);

            assertThat(result).isEqualTo(HttpStatus.CONFLICT);
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
        @DisplayName("F03.4.2 テンプレ不在: TEMPLATE_NOT_FOUND は 404 Not Found（code=RESERVATION_036・IDOR 秘匿）")
        void handleBusinessException_TEMPLATE_NOT_FOUND_404() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.TEMPLATE_NOT_FOUND);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_036");
        }

        @Test
        @DisplayName("F03.4.2 テンプレ上限: TEMPLATE_LIMIT_EXCEEDED は 400 Bad Request（code=RESERVATION_037・個別 map なし WARN 既定）")
        void handleBusinessException_TEMPLATE_LIMIT_EXCEEDED_400() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.TEMPLATE_LIMIT_EXCEEDED);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_037");
        }

        @Test
        @DisplayName("F03.4.2 §5.6 ライン不一致: SLOT_LINE_MISMATCH は 400 Bad Request（code=RESERVATION_038）")
        void handleBusinessException_SLOT_LINE_MISMATCH_400() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.SLOT_LINE_MISMATCH);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_038");
        }

        @Test
        @DisplayName("F03.4.2 §6 生成レート制限: TEMPLATE_GENERATE_RATE_LIMITED は 429 Too Many Requests（code=RESERVATION_044）")
        void handleBusinessException_TEMPLATE_GENERATE_RATE_LIMITED_429() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.TEMPLATE_GENERATE_RATE_LIMITED);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_044");
        }

        @Test
        @DisplayName("F03.4.2 §5.5 ライン削除ガード: LINE_HAS_ACTIVE_RESERVATIONS は 409 Conflict（code=RESERVATION_045）")
        void handleBusinessException_LINE_HAS_ACTIVE_RESERVATIONS_409() {
            BusinessException ex = new BusinessException(
                    com.mannschaft.app.reservation.ReservationErrorCode.LINE_HAS_ACTIVE_RESERVATIONS);

            ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleBusinessException(ex);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RESERVATION_045");
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

    // ════════════════════════════════════════════════════════════════════
    // エラーコード HTTP ステータス契約の全数分類 — 500 漏れの回帰固定（関連 #2468）
    //
    // ERROR_CODE_STATUS_MAP 未登録 かつ Severity.ERROR のコードは既定で 500 を返す。
    // 全 *ErrorCode.java を機械的に走査して Severity.ERROR を全数抽出し、
    // throw 箇所のコードを読んで「サーバ障害 / クライアント起因 / 判断保留」に分類した。
    // クライアント起因と分類したものは Severity を WARN に正し（既定 400）、
    // 400 では粗すぎるものだけ ERROR_CODE_STATUS_MAP へ 409 で登録した。
    //
    // 本 Nested はその是正結果を機械的に固定する。ここが赤くなったら
    // 「クライアントの入力誤りで 500 を返す」状態に退行したということ。
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("エラーコード HTTP ステータス契約: クライアント起因のコードが 500 を返さない")
    class ClientErrorMustNotBe500 {

        /** 400 Bad Request に是正したコード（Severity.ERROR → WARN・既定マッピング）。 */
        private final List<ErrorCode> badRequestCases = List.of(
                com.mannschaft.app.bulletin.BulletinErrorCode.PARENT_REPLY_MISMATCH,
                com.mannschaft.app.circulation.CirculationErrorCode.EMPTY_RECIPIENTS,
                com.mannschaft.app.payment.PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID,
                com.mannschaft.app.receipt.ReceiptErrorCode.LINE_ITEMS_AMOUNT_MISMATCH,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.INVALID_CAPACITY,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.PRICE_REQUIRED,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.IMAGE_URL_NOT_WHITELISTED,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.CAPACITY_BELOW_CONFIRMED,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.INVALID_CANCELLATION_POLICY,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.TIER_LIMIT_EXCEEDED,
                com.mannschaft.app.recruitment.RecruitmentErrorCode.TIER_RANGE_OVERLAP,
                com.mannschaft.app.safetycheck.SafetyCheckErrorCode.INVALID_RESPONSE_STATUS,
                com.mannschaft.app.safetycheck.SafetyCheckErrorCode.INVALID_SCOPE_TYPE,
                com.mannschaft.app.safetycheck.SafetyCheckErrorCode.BULK_RESPOND_LIMIT_EXCEEDED,
                com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH,
                com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode.ANNUAL_COPY_SAME_YEAR,
                com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode.ACADEMIC_YEAR_DATE_MISMATCH,
                com.mannschaft.app.shift.ShiftErrorCode.INVALID_DATE_RANGE,
                com.mannschaft.app.shift.ShiftErrorCode.SWAP_SELF_REQUEST,
                com.mannschaft.app.survey.SurveyErrorCode.INVALID_TIME_RANGE,
                com.mannschaft.app.timeline.TimelineErrorCode.MAX_ATTACHMENTS_EXCEEDED,
                com.mannschaft.app.timeline.TimelineErrorCode.EMPTY_POST_CONTENT,
                com.mannschaft.app.timeline.TimelineErrorCode.ATTACHMENT_NOT_FOUND_IN_STORAGE,
                com.mannschaft.app.workflow.WorkflowErrorCode.INVALID_FIELD_VALUE);

        /** 409 Conflict に是正したコード（Severity.ERROR → WARN ＋ ERROR_CODE_STATUS_MAP 登録）。 */
        private final List<ErrorCode> conflictCases = List.of(
                com.mannschaft.app.event.EventErrorCode.MAX_TICKET_TYPES,
                com.mannschaft.app.event.EventErrorCode.MAX_TIMETABLE_ITEMS,
                com.mannschaft.app.reservation.ReservationErrorCode.MAX_REMINDERS_EXCEEDED,
                com.mannschaft.app.search.SearchErrorCode.MAX_SAVED_QUERIES_EXCEEDED,
                com.mannschaft.app.shift.ShiftErrorCode.SLOT_ASSIGNMENT_EXCEEDED);

        @Test
        @DisplayName("入力不備・状態遷移違反として分類したコードは Severity.WARN かつ resolveHttpStatus で 400 になる")
        void 入力不備系は400() {
            for (ErrorCode errorCode : badRequestCases) {
                assertThat(errorCode.getSeverity())
                        .as("%s はクライアント起因のため Severity.WARN でなければならない", errorCode.getCode())
                        .isEqualTo(ErrorCode.Severity.WARN);
                assertThat(globalExceptionHandler.resolveHttpStatus(errorCode))
                        .as("%s が 500 に戻っている（クライアントの入力誤りをサーバ障害として返している）",
                                errorCode.getCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("入力不備系の BusinessException は 400 で返り、envelope の code も一致する")
        void 入力不備系のBusinessExceptionは400() {
            for (ErrorCode errorCode : badRequestCases) {
                ResponseEntity<ErrorResponse> response =
                        globalExceptionHandler.handleBusinessException(new BusinessException(errorCode));

                assertThat(response.getStatusCode())
                        .as("%s の BusinessException が 400 で返らない", errorCode.getCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getError().getCode()).isEqualTo(errorCode.getCode());
            }
        }

        @Test
        @DisplayName("保存済み資源の件数上限・状態競合として分類したコードは Severity.WARN かつ 409 になる")
        void 状態競合系は409() {
            for (ErrorCode errorCode : conflictCases) {
                assertThat(errorCode.getSeverity())
                        .as("%s はクライアント起因のため Severity.WARN でなければならない", errorCode.getCode())
                        .isEqualTo(ErrorCode.Severity.WARN);
                assertThat(globalExceptionHandler.resolveHttpStatus(errorCode))
                        .as("%s が 409 でなくなっている", errorCode.getCode())
                        .isEqualTo(HttpStatus.CONFLICT);
            }
        }

        @Test
        @DisplayName("状態競合系の BusinessException は 409 で返り、envelope の code も一致する")
        void 状態競合系のBusinessExceptionは409() {
            for (ErrorCode errorCode : conflictCases) {
                ResponseEntity<ErrorResponse> response =
                        globalExceptionHandler.handleBusinessException(new BusinessException(errorCode));

                assertThat(response.getStatusCode())
                        .as("%s の BusinessException が 409 で返らない", errorCode.getCode())
                        .isEqualTo(HttpStatus.CONFLICT);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getError().getCode()).isEqualTo(errorCode.getCode());
            }
        }

        @Test
        @DisplayName("F03.11 §5.2: RECRUITMENT_301（未払いキャンセル料による申込ブロック）は設計書どおり 402 Payment Required")
        void 未払いキャンセル料は402() {
            ErrorCode code = com.mannschaft.app.recruitment.RecruitmentErrorCode.CANCELLATION_PAYMENT_FAILED;

            assertThat(code.getSeverity())
                    .as("申込者の未払い残という利用者側の事情であり Severity.ERROR ではない")
                    .isEqualTo(ErrorCode.Severity.WARN);
            assertThat(globalExceptionHandler.resolveHttpStatus(code))
                    .as("設計書 F03.11 §5.2 は 402 + RECRUITMENT_301 を契約として明示している")
                    .isEqualTo(HttpStatus.PAYMENT_REQUIRED);

            ResponseEntity<ErrorResponse> response =
                    globalExceptionHandler.handleBusinessException(new BusinessException(code));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getError().getCode()).isEqualTo("RECRUITMENT_301");
        }

        @Test
        @DisplayName("非対称の解消: platform Webhook の PAYMENT_019 と Connect Webhook の PAYMENT_C040 が"
                + "同じ 400 になる（同一概念で 500 と 400 に割れていた退行の固定）")
        void webhook署名検証失敗は両系統とも400() {
            HttpStatus platform = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.payment.PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
            HttpStatus connect = globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.payment.connect.ConnectPaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);

            assertThat(platform).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(connect).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(platform)
                    .as("同一概念（Webhook 署名検証失敗）の HTTP 契約が系統で割れてはならない")
                    .isEqualTo(connect);
        }

        @Test
        @DisplayName("真のサーバ障害は 500 のまま（是正の巻き添えで 4xx 化していないこと）")
        void サーバ障害系は500のまま() {
            assertThat(globalExceptionHandler.resolveHttpStatus(CommonErrorCode.COMMON_999))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.common.storage.StorageErrorCode.UPLOAD_FAILED))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.payment.PaymentErrorCode.STRIPE_API_ERROR))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(globalExceptionHandler.resolveHttpStatus(
                    com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_003))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
        @DisplayName("handleNoResourceFound: 404 NOT_FOUND が返り recordBackendException は呼ばれない")
        void noResourceFound_isNotRecorded() {
            ErrorReportService service = mock(ErrorReportService.class);
            GlobalExceptionHandler handler = newHandlerWith(service, mock(ErrorReportNotifier.class));

            NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/zzz-not-exists");
            HttpServletRequest req = mock(HttpServletRequest.class);

            ResponseEntity<ErrorResponse> resp = handler.handleNoResourceFound(ex, req);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getError().getCode()).isEqualTo("COMMON_005");
            // エラー集約へ通報しない（ノイズ根絶）
            verify(service, never()).recordBackendException(any(), any(HttpServletRequest.class), any());
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
