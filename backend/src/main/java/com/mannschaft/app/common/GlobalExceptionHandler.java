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
 * 繧ｰ繝ｭ繝ｼ繝舌Ν萓句､悶ワ繝ｳ繝峨Λ繝ｼ縲・
 * 蜈ｨ縺ｦ縺ｮ萓句､悶ｒ邨ｱ荳縺輔ｌ縺・ErrorResponse 蠖｢蠑上↓螟画鋤縺吶ｋ縲・
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * ErrorCode 縺斐→縺ｮ HttpStatus 蛟句挨繝槭ャ繝斐Φ繧ｰ縲・
     * Severity 繝吶・繧ｹ縺ｮ繝・ヵ繧ｩ繝ｫ繝医・繝・ヴ繝ｳ繧ｰ繧剃ｸ頑嶌縺阪＠縺溘＞蝣ｴ蜷医↓縺薙％縺ｸ霑ｽ蜉縺吶ｋ縲・
     */
    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.ofEntries(
            // 譛ｪ隱崎ｨｼ縺ｯ 401 繧定ｿ斐☆・・everity.WARN 縺ｮ繝・ヵ繧ｩ繝ｫ繝・400 繧剃ｸ頑嶌縺搾ｼ・
            Map.entry(CommonErrorCode.COMMON_000.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(CommonErrorCode.COMMON_002.getCode(), HttpStatus.FORBIDDEN),
            Map.entry(CommonErrorCode.COMMON_003.getCode(), HttpStatus.CONFLICT),
            Map.entry("AD_006", HttpStatus.CONFLICT),
            Map.entry("AD_007", HttpStatus.CONFLICT),
            Map.entry("AD_010", HttpStatus.FORBIDDEN),
            Map.entry("AUTH_033", HttpStatus.NOT_FOUND),
            Map.entry("AUTH_034", HttpStatus.CONFLICT),
            // F02.5 陦悟虚繝｡繝｢: IDOR 蟇ｾ遲悶〒 403 縺ｧ縺ｯ縺ｪ縺・404 繧定ｿ斐☆
            Map.entry("ACTION_MEMO_001", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_006", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_008", HttpStatus.NOT_FOUND),
            // F02.5 publish-daily: 蟇ｾ雎｡譌･0莉ｶ縺ｯ 400 繧呈・遉ｺ・・everity.WARN 譌｢螳壹→蜷後§縺縺悟ｮ｣險逧・↓・・
            Map.entry("ACTION_MEMO_007", HttpStatus.BAD_REQUEST),
            // F02.5 Phase 3: 繝√・繝謚慕ｨｿ邉ｻ繧ｨ繝ｩ繝ｼ
            Map.entry("ACTION_MEMO_015", HttpStatus.BAD_REQUEST),    // only_work_can_be_posted
            Map.entry("ACTION_MEMO_016", HttpStatus.CONFLICT),        // already_posted
            Map.entry("ACTION_MEMO_017", HttpStatus.BAD_REQUEST),    // team_id_required
            Map.entry("ACTION_MEMO_018", HttpStatus.BAD_REQUEST),    // no_work_memo_today
            Map.entry("ACTION_MEMO_019", HttpStatus.NOT_FOUND),      // team_not_found (IDOR蟇ｾ遲・
            Map.entry("ACTION_MEMO_020", HttpStatus.BAD_REQUEST),    // invalid_default_team
            Map.entry("ACTION_MEMO_021", HttpStatus.NOT_FOUND),      // org_not_found (Phase 4-ﾎｱ, IDOR蟇ｾ遲・
            Map.entry("ACTION_MEMO_022", HttpStatus.FORBIDDEN),      // todo_revert_not_allowed (Phase 4-ﾎｲ)
            Map.entry("ACTION_MEMO_023", HttpStatus.BAD_REQUEST),    // todo_not_completed_by_memo (Phase 4-ﾎｲ)
            Map.entry("ACTION_MEMO_024", HttpStatus.FORBIDDEN),      // dashboard_forbidden (Phase 4-ﾎｲ)
            Map.entry("ACTION_MEMO_025", HttpStatus.BAD_REQUEST),    // reminder_time_required (Phase 4-ﾎｲ)
            // F05.4 繧｢繝ｳ繧ｱ繝ｼ繝・逹｣菫・API・域ｨｩ髯舌↑縺励・縺ｿ 403縲√◎縺ｮ莉悶・ Severity.WARN 譌｢螳壹・ 400・・
            Map.entry("SURVEY_014", HttpStatus.FORBIDDEN),           // REMIND_PERMISSION_DENIED
            // F11.1 繧ｪ繝輔Λ繧､繝ｳ蜷梧悄
            Map.entry("SYNC_002", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("SYNC_003", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("SYNC_004", HttpStatus.NOT_FOUND),
            Map.entry("SYNC_005", HttpStatus.FORBIDDEN),
            Map.entry("SYNC_006", HttpStatus.CONFLICT),
            // F01.5 繝輔Ξ繝ｳ繝峨メ繝ｼ繝
            Map.entry("SOCIAL_102", HttpStatus.CONFLICT),          // FRIEND_ALREADY_FOLLOWING
            Map.entry("SOCIAL_103", HttpStatus.NOT_FOUND),         // FRIEND_FOLLOW_NOT_FOUND
            Map.entry("SOCIAL_104", HttpStatus.NOT_FOUND),         // FRIEND_TARGET_TEAM_NOT_FOUND
            Map.entry("SOCIAL_105", HttpStatus.FORBIDDEN),         // FRIEND_INSUFFICIENT_PERMISSION
            Map.entry("SOCIAL_106", HttpStatus.NOT_FOUND),         // FRIEND_RELATION_NOT_FOUND
            Map.entry("SOCIAL_107", HttpStatus.FORBIDDEN),         // FRIEND_VISIBILITY_ADMIN_ONLY
            Map.entry("SOCIAL_109", HttpStatus.FORBIDDEN),         // FRIEND_FEATURE_DISABLED
            // F01.5 繝輔Ξ繝ｳ繝峨ヵ繧ｩ繝ｫ繝
            Map.entry("SOCIAL_110", HttpStatus.NOT_FOUND),         // FRIEND_FOLDER_NOT_FOUND
            Map.entry("SOCIAL_111", HttpStatus.CONFLICT),          // FRIEND_FOLDER_LIMIT_EXCEEDED
            Map.entry("SOCIAL_112", HttpStatus.CONFLICT),          // FRIEND_FOLDER_MEMBER_ALREADY_EXISTS
            Map.entry("SOCIAL_113", HttpStatus.NOT_FOUND),         // FRIEND_FOLDER_MEMBER_NOT_FOUND
            // F01.5 繝輔Ξ繝ｳ繝峨さ繝ｳ繝・Φ繝・ｻ｢騾・
            Map.entry("SOCIAL_120", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_NOT_FOUND
            Map.entry("SOCIAL_121", HttpStatus.CONFLICT),          // FRIEND_FORWARD_ALREADY_EXISTS
            Map.entry("SOCIAL_122", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_SOURCE_POST_NOT_FOUND
            Map.entry("SOCIAL_123", HttpStatus.BAD_REQUEST),       // FRIEND_FORWARD_NOT_SHARABLE
            Map.entry("SOCIAL_124", HttpStatus.NOT_FOUND),         // FRIEND_FORWARD_RELATION_NOT_FOUND
            Map.entry("SOCIAL_125", HttpStatus.BAD_REQUEST),       // FRIEND_FORWARD_SUPPORTER_NOT_ALLOWED
            // F04.10 邨・ｹ泌ｧ泌藤莨・
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
            // F01.7 繧ｫ繧ｹ繧ｿ繝蜈ｬ髢狗ｯ・峇繝・Φ繝励Ξ繝ｼ繝・
            Map.entry("VT_001", HttpStatus.NOT_FOUND),        // TEMPLATE_NOT_FOUND・・DOR蟇ｾ遲悶〒404・・
            Map.entry("VT_002", HttpStatus.FORBIDDEN),        // TEMPLATE_LIMIT_EXCEEDED
            Map.entry("VT_003", HttpStatus.CONFLICT),         // TEMPLATE_NAME_CONFLICT
            Map.entry("VT_004", HttpStatus.FORBIDDEN),        // FORBIDDEN_PRESET_MODIFY
            // F13.1 豎ゆｺｺ繝槭ャ繝√Φ繧ｰ・・hase 13.1.1 MVP・・
            Map.entry("JOB_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_CAPACITY_FULL", HttpStatus.CONFLICT),
            Map.entry("JOB_ALREADY_APPLIED", HttpStatus.CONFLICT),
            Map.entry("JOB_APPLICATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_CONTRACT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("JOB_PERMISSION_DENIED", HttpStatus.FORBIDDEN),
            // F13.1 Phase 13.1.2: QR 繝√ぉ繝・け繧､繝ｳ・上い繧ｦ繝・
            Map.entry("JOB_QR_TOKEN_INVALID_SIGNATURE", HttpStatus.UNAUTHORIZED),
            Map.entry("JOB_QR_TOKEN_WRONG_WORKER", HttpStatus.FORBIDDEN),
            Map.entry("JOB_QR_TOKEN_EXPIRED", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_QR_TOKEN_REUSED", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_QR_SHORT_CODE_NOT_FOUND", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_CHECK_IN_ALREADY_EXISTS", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_CHECK_OUT_BEFORE_CHECK_IN", HttpStatus.CONFLICT),
            Map.entry("JOB_CHECK_IN_CONCURRENT_CONFLICT", HttpStatus.FORBIDDEN),
            Map.entry("JOB_INVALID_STATE_TRANSITION", HttpStatus.CONFLICT),
            // F03.13 蟄ｦ譬｡蜃ｺ谺邂｡逅・
            Map.entry("SCHOOL_HOMEROOM_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_HOMEROOM_ALREADY_EXISTS", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_DAILY_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_DAILY_RECORD_DUPLICATE", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_PERIOD_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_FAMILY_NOTICE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_FAMILY_NOTICE_ALREADY_APPLIED", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_TRANSITION_ALERT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_TRANSITION_ALERT_ALREADY_RESOLVED", HttpStatus.CONFLICT),
            // F08.7 繧ｷ繝輔ヨ莠育ｮ・(Phase 9-ﾎｱ: 騾・ｮ・API)
            Map.entry("SHIFT_BUDGET_001", HttpStatus.SERVICE_UNAVAILABLE),  // FEATURE_DISABLED
            Map.entry("SHIFT_BUDGET_002", HttpStatus.BAD_REQUEST),          // EMPTY_POSITION_LIST
            Map.entry("SHIFT_BUDGET_003", HttpStatus.BAD_REQUEST),          // DUPLICATE_POSITION_ID
            Map.entry("SHIFT_BUDGET_004", HttpStatus.BAD_REQUEST),          // INVALID_REQUIRED_COUNT
            Map.entry("SHIFT_BUDGET_005", HttpStatus.BAD_REQUEST),          // INVALID_SLOT_HOURS
            Map.entry("SHIFT_BUDGET_006", HttpStatus.BAD_REQUEST),          // MISSING_EXPLICIT_RATE
            Map.entry("SHIFT_BUDGET_007", HttpStatus.BAD_REQUEST),          // MISSING_POSITION_COUNTS
            Map.entry("SHIFT_BUDGET_008", HttpStatus.NOT_FOUND),            // TEAM_NOT_FOUND (IDOR蟇ｾ遲悶〒404)
            Map.entry("SHIFT_BUDGET_009", HttpStatus.BAD_REQUEST),          // INVALID_BUDGET_AMOUNT
            // F08.7 繧ｷ繝輔ヨ莠育ｮ・(Phase 9-ﾎｲ: 蜑ｲ蠖・/ 豸亥喧險倬鹸 CRUD)
            Map.entry("SHIFT_BUDGET_010", HttpStatus.NOT_FOUND),            // ALLOCATION_NOT_FOUND (IDOR蟇ｾ遲悶〒404)
            Map.entry("SHIFT_BUDGET_011", HttpStatus.CONFLICT),             // ALLOCATION_ALREADY_EXISTS
            Map.entry("SHIFT_BUDGET_012", HttpStatus.CONFLICT),             // HAS_CONSUMPTIONS_PLANNED
            Map.entry("SHIFT_BUDGET_013", HttpStatus.CONFLICT),             // HAS_CONSUMPTIONS_CONFIRMED
            Map.entry("SHIFT_BUDGET_014", HttpStatus.CONFLICT),             // OPTIMISTIC_LOCK_CONFLICT
            Map.entry("SHIFT_BUDGET_015", HttpStatus.BAD_REQUEST),          // INVALID_PERIOD
            Map.entry("SHIFT_BUDGET_016", HttpStatus.BAD_REQUEST),          // INVALID_ALLOCATED_AMOUNT
            Map.entry("SHIFT_BUDGET_017", HttpStatus.CONFLICT),             // CONFIRMED_RECORD_IMMUTABLE
            Map.entry("SHIFT_BUDGET_018", HttpStatus.FORBIDDEN),            // BUDGET_VIEW_REQUIRED
            Map.entry("SHIFT_BUDGET_019", HttpStatus.FORBIDDEN),            // BUDGET_MANAGE_REQUIRED
            // F08.7 繧ｷ繝輔ヨ莠育ｮ・(Phase 9-ﾎｳ: TODO/繝励Ο繧ｸ繧ｧ繧ｯ繝・莠育ｮ礼ｴ蝉ｻ・
            Map.entry("SHIFT_BUDGET_020", HttpStatus.BAD_REQUEST),          // INVALID_LINK_TARGET
            Map.entry("SHIFT_BUDGET_021", HttpStatus.BAD_REQUEST),          // INVALID_LINK_PARAMETER
            Map.entry("SHIFT_BUDGET_022", HttpStatus.CONFLICT),             // LINK_ALREADY_EXISTS
            Map.entry("SHIFT_BUDGET_023", HttpStatus.NOT_FOUND),            // LINK_NOT_FOUND (IDOR 蟇ｾ遲悶〒 404)
            Map.entry("SHIFT_BUDGET_024", HttpStatus.NOT_FOUND),            // PROJECT_NOT_FOUND (IDOR 蟇ｾ遲悶〒 404)
            Map.entry("SHIFT_BUDGET_025", HttpStatus.NOT_FOUND),            // TODO_NOT_FOUND (IDOR 蟇ｾ遲悶〒 404)
            Map.entry("SHIFT_BUDGET_026", HttpStatus.FORBIDDEN),            // LINK_PERMISSION_REQUIRED
            // F08.7 繧ｷ繝輔ヨ莠育ｮ・(Phase 9-ﾎｴ: 隴ｦ蜻・/ 譛域ｬ｡邱繧・/ BUDGET_ADMIN 繧ｯ繝ｪ繝ｼ繝ｳ繧ｫ繝・ヨ)
            Map.entry("SHIFT_BUDGET_027", HttpStatus.FORBIDDEN),            // BUDGET_ADMIN_REQUIRED
            // F03.15 蛟倶ｺｺ譎る俣蜑ｲ・・DOR 蟇ｾ遲悶〒 404 邨ｱ荳縲∽ｸ企剞繝ｻ驕ｷ遘ｻ繧ｨ繝ｩ繝ｼ縺ｯ 409・・
            Map.entry("PERSONAL_TIMETABLE_001", HttpStatus.NOT_FOUND),       // PERSONAL_TIMETABLE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_010", HttpStatus.CONFLICT),        // LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_020", HttpStatus.CONFLICT),        // NOT_DRAFT
            Map.entry("PERSONAL_TIMETABLE_021", HttpStatus.CONFLICT),        // NOT_ACTIVE
            Map.entry("PERSONAL_TIMETABLE_022", HttpStatus.CONFLICT),        // NOT_ARCHIVED
            Map.entry("PERSONAL_TIMETABLE_023", HttpStatus.CONFLICT),        // INVALID_STATUS_TRANSITION
            // F03.15 Phase 2 譎る剞螳夂ｾｩ
            Map.entry("PERSONAL_TIMETABLE_040", HttpStatus.CONFLICT),        // PERIOD_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_041", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_INVALID_TIME_RANGE
            Map.entry("PERSONAL_TIMETABLE_042", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_NUMBER_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_043", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_NUMBER_OUT_OF_RANGE
            Map.entry("PERSONAL_TIMETABLE_044", HttpStatus.CONFLICT),        // NOT_EDITABLE (DRAFT 縺ｮ縺ｿ)
            // F03.15 Phase 2 繧ｳ繝・
            Map.entry("PERSONAL_TIMETABLE_050", HttpStatus.CONFLICT),        // SLOT_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_051", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_BREAK_PERIOD_ASSIGNED
            Map.entry("PERSONAL_TIMETABLE_052", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_PERIOD_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_053", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_WEEK_PATTERN_CONFLICT
            Map.entry("PERSONAL_TIMETABLE_054", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_WEEK_PATTERN_NOT_ENABLED
            Map.entry("PERSONAL_TIMETABLE_055", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_056", HttpStatus.BAD_REQUEST),     // LINK_NOT_SUPPORTED_YET (Phase 4 縺ｧ蟇ｾ蠢・
            // F03.15 Phase 3 蛟倶ｺｺ繝｡繝｢
            Map.entry("PERSONAL_TIMETABLE_060", HttpStatus.NOT_FOUND),       // NOTE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_061", HttpStatus.PRECONDITION_FAILED), // NOTE_PRECONDITION_FAILED
            Map.entry("PERSONAL_TIMETABLE_062", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_UNSAFE_MARKDOWN
            Map.entry("PERSONAL_TIMETABLE_063", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_FIELD_TOO_LONG
            Map.entry("PERSONAL_TIMETABLE_064", HttpStatus.BAD_REQUEST),     // NOTE_INVALID_SLOT_KIND
            Map.entry("PERSONAL_TIMETABLE_065", HttpStatus.NOT_FOUND),       // NOTE_SLOT_NOT_OWNED (IDOR蟇ｾ遲悶〒404)
            Map.entry("PERSONAL_TIMETABLE_066", HttpStatus.NOT_FOUND),       // NOTE_TEAM_NOT_MEMBER (IDOR蟇ｾ遲悶〒404)
            // F03.15 Phase 3 繧ｫ繧ｹ繧ｿ繝繝輔ぅ繝ｼ繝ｫ繝・
            Map.entry("PERSONAL_TIMETABLE_070", HttpStatus.NOT_FOUND),       // NOTE_FIELD_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_071", HttpStatus.CONFLICT),        // NOTE_FIELD_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_072", HttpStatus.CONFLICT),        // NOTE_FIELD_LABEL_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_073", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_FIELD_INVALID_MAX_LENGTH
            // F03.15 Phase 3 豺ｻ莉倥ヵ繧｡繧､繝ｫ
            Map.entry("PERSONAL_TIMETABLE_080", HttpStatus.NOT_FOUND),       // ATTACHMENT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_081", HttpStatus.CONFLICT),        // ATTACHMENT_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_082", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_SIZE_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_083", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_UNSUPPORTED_TYPE
            Map.entry("PERSONAL_TIMETABLE_084", HttpStatus.TOO_MANY_REQUESTS), // ATTACHMENT_QUOTA_EXCEEDED (429)
            Map.entry("PERSONAL_TIMETABLE_085", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_MAGIC_BYTE_MISMATCH
            Map.entry("PERSONAL_TIMETABLE_086", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_OBJECT_NOT_FOUND
            // F03.15 Phase 4 繝√・繝繝ｪ繝ｳ繧ｯ
            Map.entry("PERSONAL_TIMETABLE_090", HttpStatus.NOT_FOUND),       // SLOT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_091", HttpStatus.NOT_FOUND),       // LINK_TARGET_TEAM_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_092", HttpStatus.NOT_FOUND),       // LINK_TARGET_TIMETABLE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_093", HttpStatus.NOT_FOUND),       // LINK_TARGET_SLOT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_094", HttpStatus.FORBIDDEN),       // LINK_NOT_TEAM_MEMBER
            Map.entry("PERSONAL_TIMETABLE_095", HttpStatus.CONFLICT),        // LINK_STATUS_INVALID
            Map.entry("PERSONAL_TIMETABLE_096", HttpStatus.CONFLICT),        // LINK_POSITION_MISMATCH
            Map.entry("PERSONAL_TIMETABLE_097", HttpStatus.BAD_REQUEST),     // LINK_TIMETABLE_REQUIRED
            // F03.15 Phase 5 螳ｶ譌丞・譛・
            Map.entry("PERSONAL_TIMETABLE_100", HttpStatus.CONFLICT),        // SHARE_TARGET_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_101", HttpStatus.UNPROCESSABLE_ENTITY), // SHARE_TARGET_NOT_FAMILY_TEAM
            Map.entry("PERSONAL_TIMETABLE_102", HttpStatus.FORBIDDEN),       // SHARE_TARGET_NOT_TEAM_MEMBER
            Map.entry("PERSONAL_TIMETABLE_103", HttpStatus.NOT_FOUND),       // SHARE_TARGET_TEAM_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_104", HttpStatus.NOT_FOUND),       // SHARE_TARGET_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_105", HttpStatus.CONFLICT),        // SHARE_TARGET_DUPLICATED
            // F09.8.1 繧ｳ繝ｫ繧ｯ繝懊・繝・繝斐Φ豁｢繧・
            Map.entry("CORKBOARD_011", HttpStatus.FORBIDDEN),                // PIN_PERSONAL_ONLY
            Map.entry("CORKBOARD_012", HttpStatus.BAD_REQUEST),              // PIN_ARCHIVED_NOT_ALLOWED
            Map.entry("CORKBOARD_013", HttpStatus.CONFLICT),                 // PIN_LIMIT_EXCEEDED
            // F09.8 Phase A2 コルクボード詳細取得 — 設計書通り 403/404 を返す
            Map.entry("CORKBOARD_001", HttpStatus.NOT_FOUND),                // BOARD_NOT_FOUND
            Map.entry("CORKBOARD_009", HttpStatus.FORBIDDEN),                // INSUFFICIENT_PERMISSION
            // F13 ストレージクォータ統合機構（Phase 4-α）
            Map.entry("STORAGE_QUOTA_001", HttpStatus.CONFLICT),             // QUOTA_EXCEEDED (容量超過)
            Map.entry("STORAGE_QUOTA_002", HttpStatus.INTERNAL_SERVER_ERROR) // SUBSCRIPTION_NOT_FOUND
    );

    /**
     * 讌ｭ蜍吩ｾ句､悶ワ繝ｳ繝峨Λ繝ｼ縲・
     * F11.3: resolveMessage() 縺ｧ繝ｭ繧ｱ繝ｼ繝ｫ縺ｫ蠢懊§縺溷､夊ｨ隱槭Γ繝・そ繝ｼ繧ｸ縺ｫ隗｣豎ｺ縺吶ｋ縲・
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
     * Bean Validation 繧ｨ繝ｩ繝ｼ・・Valid 莉倥″繝ｪ繧ｯ繧ｨ繧ｹ繝医・繝・ぅ・峨・
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
     * 繝ｪ繧ｯ繧ｨ繧ｹ繝医・繝・ぅ縺ｮ繝代・繧ｹ繧ｨ繝ｩ繝ｼ・・SON 蠖｢蠑丈ｸ肴ｭ｣縺ｪ縺ｩ・峨・
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
     * 繝代せ繝代Λ繝｡繝ｼ繧ｿ繝ｻ繝ｪ繧ｯ繧ｨ繧ｹ繝医ヱ繝ｩ繝｡繝ｼ繧ｿ縺ｮ蝙句､画鋤繧ｨ繝ｩ繝ｼ縲・
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
     * 蠢・医Μ繧ｯ繧ｨ繧ｹ繝医ヱ繝ｩ繝｡繝ｼ繧ｿ縺ｮ谺關ｽ縲・
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
     * 讌ｽ隕ｳ繝ｭ繝・け遶ｶ蜷医・
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
     * F02.7 繝槭う繝ｫ繧ｹ繝医・繝ｳ繝ｭ繝・け萓句､・竊・423 Locked 縺ｫ繝槭ャ繝斐Φ繧ｰ縲・
     *
     * <p>繝ｭ繝・け荳ｭ繝槭う繝ｫ繧ｹ繝医・繝ｳ驟堺ｸ九・ TODO 縺ｫ蟇ｾ縺吶ｋ繧ｹ繝・・繧ｿ繧ｹ螟画峩繝ｻ邱ｨ髮・ｭ峨′隧ｦ縺ｿ繧峨ｌ縺滄圀縺ｫ
     * {@link MilestoneLockedException} 縺碁∝・縺輔ｌ繧九ゅΞ繧ｹ繝昴Φ繧ｹ縺ｫ縺ｯ繧ｨ繝ｩ繝ｼ繧ｳ繝ｼ繝・
     * {@code MILESTONE_LOCKED} 縺ｨ繝ｭ繝・け隗｣髯､譚｡莉ｶ・亥燕繝槭う繝ｫ繧ｹ繝医・繝ｳ繧ｿ繧､繝医Ν・峨ｒ蜷ｫ繧√ｋ縲・/p>
     */
    @ExceptionHandler(MilestoneLockedException.class)
    public ResponseEntity<ErrorResponse> handleMilestoneLocked(MilestoneLockedException ex) {
        log.warn("MilestoneLockedException: milestoneId={}, lockedBy={}",
                ex.getMilestoneId(), ex.getLockedByMilestoneTitle());
        String unlockCondition = "蜑阪・繧､繝ｫ繧ｹ繝医・繝ｳ縲・ + ex.getLockedByMilestoneTitle() + "縲上ｒ螳御ｺ・;
        List<ErrorResponse.FieldError> details = List.of(
                new ErrorResponse.FieldError("milestone_id", String.valueOf(ex.getMilestoneId())),
                new ErrorResponse.FieldError("unlock_condition", unlockCondition)
        );
        ErrorResponse body = new ErrorResponse(
                new ErrorResponse.ErrorDetail("MILESTONE_LOCKED", ex.getMessage(), details));
        return ResponseEntity.status(HttpStatus.LOCKED).body(body);
    }

    /**
     * F02.7 繧ｲ繝ｼ繝域峩譁ｰ譎ゅ・讌ｽ隕ｳ逧・Ο繝・け繝ｪ繝医Λ繧､螟ｱ謨・竊・409 Conflict縲・
     *
     * <p>{@link com.mannschaft.app.todo.service.MilestoneGateService} 縺・
     * 繝ｪ繝医Λ繧､ 1 蝗槭〒繧らｫｶ蜷医ｒ隗｣豸医〒縺阪↑縺九▲縺溷ｴ蜷・{@link IllegalStateException} 繧帝∝・縺吶ｋ縲・
     * 繝｡繝・そ繝ｼ繧ｸ縺ｫ "遶ｶ蜷・ 繧貞性繧蝣ｴ蜷医・縺ｿ 409 縺ｨ縺励※謇ｱ縺・√◎繧御ｻ･螟悶・荳贋ｽ阪・莠域悄縺帙〓萓句､悶↓蟋斐・繧九・/p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("遶ｶ蜷・) || msg.contains("conflict")) {
            log.warn("繧ｲ繝ｼ繝域峩譁ｰ遶ｶ蜷・ {}", msg);
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
     * 縺昴・莉悶・莠域悄縺励↑縺・ｾ句､悶・
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * ErrorCode 縺九ｉ螟夊ｨ隱槭Γ繝・そ繝ｼ繧ｸ繧定ｧ｣豎ｺ縺吶ｋ縲・
     * messages_{locale}.properties 縺ｮ繧ｭ繝ｼ蠖｢蠑・ error.{繝峨Γ繧､繝ｳ蟆乗枚蟄抑.{逡ｪ蜿ｷ}
     * 萓・ TEAM_001 竊・error.team.001, COMMON_001 竊・error.common.001
     * 繧ｭ繝ｼ縺悟ｭ伜惠縺励↑縺・ｴ蜷医・ ErrorCode.getMessage()・域律譛ｬ隱橸ｼ峨↓繝輔か繝ｼ繝ｫ繝舌ャ繧ｯ縲・
     */
    private String resolveMessage(ErrorCode errorCode) {
        String code = errorCode.getCode();
        // TEAM_001 竊・"error.team.001" 縺ｫ螟画鋤
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
            // properties 縺ｫ繧ｭ繝ｼ縺後↑縺・ｴ蜷医・譌･譛ｬ隱槭・ getMessage() 縺ｫ繝輔か繝ｼ繝ｫ繝舌ャ繧ｯ
            return errorCode.getMessage();
        }
    }

    /**
     * ErrorCode 縺九ｉ HttpStatus 繧定ｧ｣豎ｺ縺吶ｋ縲・
     * 蛟句挨繝槭ャ繝斐Φ繧ｰ・・RROR_CODE_STATUS_MAP・峨′蟄伜惠縺吶ｌ縺ｰ縺昴■繧峨ｒ蜆ｪ蜈医＠縲・
     * 縺ｪ縺代ｌ縺ｰ Severity 縺ｫ蝓ｺ縺･縺上ョ繝輔か繝ｫ繝医・繝・ヴ繝ｳ繧ｰ繧定ｿ斐☆縲・
     *
     * @param errorCode 繧ｨ繝ｩ繝ｼ繧ｳ繝ｼ繝・
     * @return 蟇ｾ蠢懊☆繧・HttpStatus
     */
    protected HttpStatus resolveHttpStatus(ErrorCode errorCode) {
        // 蛟句挨繝槭ャ繝斐Φ繧ｰ繧貞━蜈・
        HttpStatus mapped = ERROR_CODE_STATUS_MAP.get(errorCode.getCode());
        if (mapped != null) {
            return mapped;
        }

        // Severity 繝吶・繧ｹ縺ｮ繝・ヵ繧ｩ繝ｫ繝医・繝・ヴ繝ｳ繧ｰ
        return switch (errorCode.getSeverity()) {
            case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case WARN -> HttpStatus.BAD_REQUEST;
            case INFO -> HttpStatus.OK;
        };
    }
}
