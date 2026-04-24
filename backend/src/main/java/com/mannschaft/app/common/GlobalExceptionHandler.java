package com.mannschaft.app.common;

import com.mannschaft.app.todo.exception.MilestoneLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * グローバル例外ハンドラー。
 * 全ての例外を統一された ErrorResponse 形式に変換する。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * ErrorCode ごとの HttpStatus 個別マッピング。
     * Severity ベースのデフォルトマッピングを上書きしたい場合にここへ追加する。
     */
    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.ofEntries(
            // 未認証は 401 を返す（Severity.WARN のデフォルト 400 を上書き）
            Map.entry(CommonErrorCode.COMMON_000.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(CommonErrorCode.COMMON_002.getCode(), HttpStatus.FORBIDDEN),
            Map.entry(CommonErrorCode.COMMON_003.getCode(), HttpStatus.CONFLICT),
            Map.entry("AD_006", HttpStatus.CONFLICT),
            Map.entry("AD_007", HttpStatus.CONFLICT),
            Map.entry("AD_010", HttpStatus.FORBIDDEN),
            Map.entry("AUTH_033", HttpStatus.NOT_FOUND),
            Map.entry("AUTH_034", HttpStatus.CONFLICT),
            // F02.5 行動メモ: IDOR 対策で 403 ではなく 404 を返す
            Map.entry("ACTION_MEMO_001", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_006", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_008", HttpStatus.NOT_FOUND),
            // F02.5 publish-daily: 対象日0件は 400 を明示（Severity.WARN 既定と同じだが宣言的に）
            Map.entry("ACTION_MEMO_007", HttpStatus.BAD_REQUEST),
            // F02.5 Phase 3: チーム投稿系エラー
            Map.entry("ACTION_MEMO_015", HttpStatus.BAD_REQUEST),    // only_work_can_be_posted
            Map.entry("ACTION_MEMO_016", HttpStatus.CONFLICT),        // already_posted
            Map.entry("ACTION_MEMO_017", HttpStatus.BAD_REQUEST),    // team_id_required
            Map.entry("ACTION_MEMO_018", HttpStatus.BAD_REQUEST),    // no_work_memo_today
            Map.entry("ACTION_MEMO_019", HttpStatus.NOT_FOUND),      // team_not_found (IDOR対策)
            Map.entry("ACTION_MEMO_020", HttpStatus.BAD_REQUEST),    // invalid_default_team
            // F11.1 オフライン同期
            Map.entry("SYNC_002", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("SYNC_003", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("SYNC_004", HttpStatus.NOT_FOUND),
            Map.entry("SYNC_005", HttpStatus.FORBIDDEN),
            Map.entry("SYNC_006", HttpStatus.CONFLICT),
            // F01.5 フレンドチーム
            Map.entry("SOCIAL_102", HttpStatus.CONFLICT),          // FRIEND_ALREADY_FOLLOWING
            Map.entry("SOCIAL_103", HttpStatus.NOT_FOUND),         // FRIEND_FOLLOW_NOT_FOUND
            Map.entry("SOCIAL_104", HttpStatus.NOT_FOUND),         // FRIEND_TARGET_TEAM_NOT_FOUND
            Map.entry("SOCIAL_105", HttpStatus.FORBIDDEN),         // FRIEND_INSUFFICIENT_PERMISSION
            Map.entry("SOCIAL_106", HttpStatus.NOT_FOUND),         // FRIEND_RELATION_NOT_FOUND
            Map.entry("SOCIAL_107", HttpStatus.FORBIDDEN),         // FRIEND_VISIBILITY_ADMIN_ONLY
            Map.entry("SOCIAL_109", HttpStatus.FORBIDDEN),         // FRIEND_FEATURE_DISABLED
            // F01.5 フレンドフォルダ
            Map.entry("SOCIAL_110", HttpStatus.NOT_FOUND),         // FRIEND_FOLDER_NOT_FOUND
            Map.entry("SOCIAL_111", HttpStatus.CONFLICT),          // FRIEND_FOLDER_LIMIT_EXCEEDED
            Map.entry("SOCIAL_112", HttpStatus.CONFLICT),          // FRIEND_FOLDER_MEMBER_ALREADY_EXISTS
            Map.entry("SOCIAL_113", HttpStatus.NOT_FOUND),         // FRIEND_FOLDER_MEMBER_NOT_FOUND
            // F01.5 フレンドコンテンツ転送
            Map.entry("SOCIAL_120", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_NOT_FOUND
            Map.entry("SOCIAL_121", HttpStatus.CONFLICT),          // FRIEND_FORWARD_ALREADY_EXISTS
            Map.entry("SOCIAL_122", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_SOURCE_POST_NOT_FOUND
            Map.entry("SOCIAL_123", HttpStatus.BAD_REQUEST),       // FRIEND_FORWARD_NOT_SHARABLE
            Map.entry("SOCIAL_124", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_RELATION_NOT_FOUND
            Map.entry("SOCIAL_125", HttpStatus.BAD_REQUEST),       // FRIEND_FORWARD_SUPPORTER_NOT_ALLOWED
            // F04.10 組織委員会
            Map.entry("COMMITTEE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("COMMITTEE_MEMBER_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("COMMITTEE_NAME_DUPLICATE", HttpStatus.CONFLICT),
            Map.entry("COMMITTEE_INVALID_STATUS_TRANSITION", HttpStatus.BAD_REQUEST),
            Map.entry("COMMITTEE_CHAIR_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("COMMITTEE_LAST_CHAIR_CANNOT_LEAVE", HttpStatus.BAD_REQUEST),
            Map.entry("COMMITTEE_NOT_MEMBER", HttpStatus.FORBIDDEN),
            Map.entry("COMMITTEE_DRAFT_CANNOT_DISTRIBUTE", HttpStatus.BAD_REQUEST),
            Map.entry("COMMITTEE_ALREADY_MEMBER", HttpStatus.CONFLICT),
            Map.entry("COMMITTEE_INVITATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("COMMITTEE_INVITATION_ALREADY_RESOLVED", HttpStatus.CONFLICT),
            Map.entry("COMMITTEE_INVITATION_EXPIRED", HttpStatus.GONE),
            Map.entry("COMMITTEE_INVITATION_TOKEN_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("COMMITTEE_MINUTES_ALREADY_CONFIRMED", HttpStatus.CONFLICT),
            Map.entry("COMMITTEE_MINUTES_NOT_COMMITTEE_SCOPE", HttpStatus.BAD_REQUEST),
            // F01.7 カスタム公開範囲テンプレート
            Map.entry("VT_001", HttpStatus.NOT_FOUND),        // TEMPLATE_NOT_FOUND（IDOR対策で404）
            Map.entry("VT_002", HttpStatus.FORBIDDEN),        // TEMPLATE_LIMIT_EXCEEDED
            Map.entry("VT_003", HttpStatus.CONFLICT),         // TEMPLATE_NAME_CONFLICT
            Map.entry("VT_004", HttpStatus.FORBIDDEN),        // FORBIDDEN_PRESET_MODIFY
            // F13.1 求人マッチング（Phase 13.1.1 MVP）
            Map.entry("JOB_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_CAPACITY_FULL", HttpStatus.CONFLICT),
            Map.entry("JOB_ALREADY_APPLIED", HttpStatus.CONFLICT),
            Map.entry("JOB_APPLICATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_CONTRACT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_PERMISSION_DENIED", HttpStatus.FORBIDDEN),
            // F13.1 Phase 13.1.2: QR チェックイン／アウト
            Map.entry("JOB_QR_TOKEN_INVALID_SIGNATURE", HttpStatus.UNAUTHORIZED),
            Map.entry("JOB_QR_TOKEN_WRONG_WORKER", HttpStatus.FORBIDDEN),
            Map.entry("JOB_CHECK_OUT_BEFORE_CHECK_IN", HttpStatus.CONFLICT),
            Map.entry("JOB_CHECK_IN_CONCURRENT_CONFLICT", HttpStatus.FORBIDDEN)
    );

    /**
     * 業務例外ハンドラー。
     * F11.3: resolveMessage() でロケールに応じた多言語メッセージに解決する。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        String message = resolveMessage(errorCode);
        log.warn("BusinessException: code={}, message={}", errorCode.getCode(), message);

        ErrorResponse body;
        if (ex.getFieldErrors().isEmpty()) {
            body = new ErrorResponse(
                    new ErrorResponse.ErrorDetail(errorCode.getCode(), message, List.of()));
        } else {
            body = new ErrorResponse(
                    new ErrorResponse.ErrorDetail(errorCode.getCode(), message, ex.getFieldErrors()));
        }
        return ResponseEntity
                .status(resolveHttpStatus(errorCode))
                .body(body);
    }

    /**
     * Bean Validation エラー（@Valid 付きリクエストボディ）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        log.warn("Validation failed: {} field error(s)", fieldErrors.size());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001, fieldErrors));
    }

    /**
     * リクエストボディのパースエラー（JSON 形式不正など）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        log.warn("Message not readable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * パスパラメータ・リクエストパラメータの型変換エラー。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: parameter={}, value={}", ex.getName(), ex.getValue());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * 必須リクエストパラメータの欠落。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex) {
        log.warn("Missing parameter: {}", ex.getParameterName());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /**
     * 楽観ロック競合。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_003));
    }

    /**
     * F02.7 マイルストーンロック例外 → 423 Locked にマッピング。
     *
     * <p>ロック中マイルストーン配下の TODO に対するステータス変更・編集等が試みられた際に
     * {@link MilestoneLockedException} が送出される。レスポンスにはエラーコード
     * {@code MILESTONE_LOCKED} とロック解除条件（前マイルストーンタイトル）を含める。</p>
     */
    @ExceptionHandler(MilestoneLockedException.class)
    public ResponseEntity<ErrorResponse> handleMilestoneLocked(MilestoneLockedException ex) {
        log.warn("MilestoneLockedException: milestoneId={}, lockedBy={}",
                ex.getMilestoneId(), ex.getLockedByMilestoneTitle());
        String unlockCondition = "前マイルストーン『" + ex.getLockedByMilestoneTitle() + "』を完了";
        List<ErrorResponse.FieldError> details = List.of(
                new ErrorResponse.FieldError("milestone_id", String.valueOf(ex.getMilestoneId())),
                new ErrorResponse.FieldError("unlock_condition", unlockCondition)
        );
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorDetail("MILESTONE_LOCKED", ex.getMessage(), details));
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    /**
     * F02.7 ゲート更新時の楽観的ロックリトライ失敗 → 409 Conflict。
     *
     * <p>{@link com.mannschaft.app.todo.service.MilestoneGateService} が
     * リトライ 1 回でも競合を解消できなかった場合 {@link IllegalStateException} を送出する。
     * メッセージに "競合" を含む場合のみ 409 として扱い、それ以外は上位の予期せぬ例外に委ねる。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("競合") || msg.contains("conflict")) {
            log.warn("ゲート更新競合: {}", msg);
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(CommonErrorCode.COMMON_003));
        }
        log.error("IllegalStateException", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * その他の予期しない例外。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * ErrorCode から多言語メッセージを解決する。
     * messages_{locale}.properties のキー形式: error.{ドメイン小文字}.{番号}
     * 例: TEAM_001 → error.team.001, COMMON_001 → error.common.001
     * キーが存在しない場合は ErrorCode.getMessage()（日本語）にフォールバック。
     */
    private String resolveMessage(ErrorCode errorCode) {
        String code = errorCode.getCode();
        // TEAM_001 → "error.team.001" に変換
        int lastUnderscore = code.lastIndexOf('_');
        String messageKey;
        if (lastUnderscore > 0) {
            String domain = code.substring(0, lastUnderscore).toLowerCase().replace('_', '.');
            String number = code.substring(lastUnderscore + 1);
            messageKey = "error." + domain + "." + number;
        } else {
            messageKey = "error." + code.toLowerCase();
        }

        try {
            return messageSource.getMessage(
                    messageKey,
                    null,
                    LocaleContextHolder.getLocale()
            );
        } catch (NoSuchMessageException e) {
            // properties にキーがない場合は日本語の getMessage() にフォールバック
            return errorCode.getMessage();
        }
    }

    /**
     * ErrorCode から HttpStatus を解決する。
     * 個別マッピング（ERROR_CODE_STATUS_MAP）が存在すればそちらを優先し、
     * なければ Severity に基づくデフォルトマッピングを返す。
     *
     * @param errorCode エラーコード
     * @return 対応する HttpStatus
     */
    protected HttpStatus resolveHttpStatus(ErrorCode errorCode) {
        // 個別マッピングを優先
        HttpStatus mapped = ERROR_CODE_STATUS_MAP.get(errorCode.getCode());
        if (mapped != null) {
            return mapped;
        }

        // Severity ベースのデフォルトマッピング
        return switch (errorCode.getSeverity()) {
            case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case WARN -> HttpStatus.BAD_REQUEST;
            case INFO -> HttpStatus.OK;
        };
    }
}
