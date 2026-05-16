package com.mannschaft.app.common;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.todo.exception.MilestoneLockedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * グローバル例外ハンドラー。
 * 全ての例外を統一された ErrorResponse 形式に変換する。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    /**
     * F10.6 Phase 10-β-1 — error_reports への記録経路。
     * ObjectProvider 経由で遅延解決し、未配線時（既存ユニットテストなど）はスキップする。
     */
    private final ObjectProvider<ErrorReportService> errorReportServiceProvider;

    /**
     * F10.6 Phase 10-β-1 — Slack エスカレーション通知。
     */
    private final ObjectProvider<ErrorReportNotifier> errorReportNotifierProvider;

    /**
     * 既存ユニットテスト互換コンストラクタ（@RequiredArgsConstructor 同等）。
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        this(messageSource, null, null);
    }

    /**
     * Spring が自動配線するコンストラクタ。
     * 複数コンストラクタがある場合、@Autowired を明示しないと Spring が default constructor を探してしまうため必須。
     */
    @Autowired
    public GlobalExceptionHandler(MessageSource messageSource,
                                  ObjectProvider<ErrorReportService> errorReportServiceProvider,
                                  ObjectProvider<ErrorReportNotifier> errorReportNotifierProvider) {
        this.messageSource = messageSource;
        this.errorReportServiceProvider = errorReportServiceProvider;
        this.errorReportNotifierProvider = errorReportNotifierProvider;
    }

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
            Map.entry("ACTION_MEMO_021", HttpStatus.NOT_FOUND),      // org_not_found (Phase 4-α, IDOR対策)
            Map.entry("ACTION_MEMO_022", HttpStatus.FORBIDDEN),      // todo_revert_not_allowed (Phase 4-β)
            Map.entry("ACTION_MEMO_023", HttpStatus.BAD_REQUEST),    // todo_not_completed_by_memo (Phase 4-β)
            Map.entry("ACTION_MEMO_024", HttpStatus.FORBIDDEN),      // dashboard_forbidden (Phase 4-β)
            Map.entry("ACTION_MEMO_025", HttpStatus.BAD_REQUEST),    // reminder_time_required (Phase 4-β)
            // F05.4 アンケート 督促 API（権限なしのみ 403、その他は Severity.WARN 既定の 400）
            Map.entry("SURVEY_014", HttpStatus.FORBIDDEN),           // REMIND_PERMISSION_DENIED
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
            Map.entry("JOB_QR_TOKEN_EXPIRED", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_QR_TOKEN_REUSED", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_QR_SHORT_CODE_NOT_FOUND", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_CHECK_IN_ALREADY_EXISTS", HttpStatus.BAD_REQUEST),
            Map.entry("JOB_CHECK_OUT_BEFORE_CHECK_IN", HttpStatus.CONFLICT),
            Map.entry("JOB_CHECK_IN_CONCURRENT_CONFLICT", HttpStatus.FORBIDDEN),
            Map.entry("JOB_INVALID_STATE_TRANSITION", HttpStatus.CONFLICT),
            // F03.13 学校出欠管理
            Map.entry("SCHOOL_HOMEROOM_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_HOMEROOM_ALREADY_EXISTS", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_DAILY_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_DAILY_RECORD_DUPLICATE", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_PERIOD_RECORD_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_FAMILY_NOTICE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_FAMILY_NOTICE_ALREADY_APPLIED", HttpStatus.CONFLICT),
            Map.entry("SCHOOL_TRANSITION_ALERT_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("SCHOOL_TRANSITION_ALERT_ALREADY_RESOLVED", HttpStatus.CONFLICT),
            // F08.7 シフト予算 (Phase 9-α: 逆算 API)
            Map.entry("SHIFT_BUDGET_001", HttpStatus.SERVICE_UNAVAILABLE),  // FEATURE_DISABLED
            Map.entry("SHIFT_BUDGET_002", HttpStatus.BAD_REQUEST),          // EMPTY_POSITION_LIST
            Map.entry("SHIFT_BUDGET_003", HttpStatus.BAD_REQUEST),          // DUPLICATE_POSITION_ID
            Map.entry("SHIFT_BUDGET_004", HttpStatus.BAD_REQUEST),          // INVALID_REQUIRED_COUNT
            Map.entry("SHIFT_BUDGET_005", HttpStatus.BAD_REQUEST),          // INVALID_SLOT_HOURS
            Map.entry("SHIFT_BUDGET_006", HttpStatus.BAD_REQUEST),          // MISSING_EXPLICIT_RATE
            Map.entry("SHIFT_BUDGET_007", HttpStatus.BAD_REQUEST),          // MISSING_POSITION_COUNTS
            Map.entry("SHIFT_BUDGET_008", HttpStatus.NOT_FOUND),            // TEAM_NOT_FOUND (IDOR対策で404)
            Map.entry("SHIFT_BUDGET_009", HttpStatus.BAD_REQUEST),          // INVALID_BUDGET_AMOUNT
            // F08.7 シフト予算 (Phase 9-β: 割当 / 消化記録 CRUD)
            Map.entry("SHIFT_BUDGET_010", HttpStatus.NOT_FOUND),            // ALLOCATION_NOT_FOUND (IDOR対策で404)
            Map.entry("SHIFT_BUDGET_011", HttpStatus.CONFLICT),             // ALLOCATION_ALREADY_EXISTS
            Map.entry("SHIFT_BUDGET_012", HttpStatus.CONFLICT),             // HAS_CONSUMPTIONS_PLANNED
            Map.entry("SHIFT_BUDGET_013", HttpStatus.CONFLICT),             // HAS_CONSUMPTIONS_CONFIRMED
            Map.entry("SHIFT_BUDGET_014", HttpStatus.CONFLICT),             // OPTIMISTIC_LOCK_CONFLICT
            Map.entry("SHIFT_BUDGET_015", HttpStatus.BAD_REQUEST),          // INVALID_PERIOD
            Map.entry("SHIFT_BUDGET_016", HttpStatus.BAD_REQUEST),          // INVALID_ALLOCATED_AMOUNT
            Map.entry("SHIFT_BUDGET_017", HttpStatus.CONFLICT),             // CONFIRMED_RECORD_IMMUTABLE
            Map.entry("SHIFT_BUDGET_018", HttpStatus.FORBIDDEN),            // BUDGET_VIEW_REQUIRED
            Map.entry("SHIFT_BUDGET_019", HttpStatus.FORBIDDEN),            // BUDGET_MANAGE_REQUIRED
            // F08.7 シフト予算 (Phase 9-γ: TODO/プロジェクト 予算紐付)
            Map.entry("SHIFT_BUDGET_020", HttpStatus.BAD_REQUEST),          // INVALID_LINK_TARGET
            Map.entry("SHIFT_BUDGET_021", HttpStatus.BAD_REQUEST),          // INVALID_LINK_PARAMETER
            Map.entry("SHIFT_BUDGET_022", HttpStatus.CONFLICT),             // LINK_ALREADY_EXISTS
            Map.entry("SHIFT_BUDGET_023", HttpStatus.NOT_FOUND),            // LINK_NOT_FOUND (IDOR 対策で 404)
            Map.entry("SHIFT_BUDGET_024", HttpStatus.NOT_FOUND),            // PROJECT_NOT_FOUND (IDOR 対策で 404)
            Map.entry("SHIFT_BUDGET_025", HttpStatus.NOT_FOUND),            // TODO_NOT_FOUND (IDOR 対策で 404)
            Map.entry("SHIFT_BUDGET_026", HttpStatus.FORBIDDEN),            // LINK_PERMISSION_REQUIRED
            // F08.7 シフト予算 (Phase 9-δ: 警告 / 月次締め / BUDGET_ADMIN クリーンカット)
            Map.entry("SHIFT_BUDGET_027", HttpStatus.FORBIDDEN),            // BUDGET_ADMIN_REQUIRED
            Map.entry("SHIFT_BUDGET_028", HttpStatus.CONFLICT),             // MONTHLY_ALREADY_CLOSED
            Map.entry("SHIFT_BUDGET_029", HttpStatus.NOT_FOUND),            // ALERT_NOT_FOUND (IDOR 対策で 404)
            // F08.7 シフト予算 (Phase 10-β: 通知失敗リトライ + 失敗イベント管理)
            Map.entry("SHIFT_BUDGET_030", HttpStatus.NOT_FOUND),            // FAILED_EVENT_NOT_FOUND (IDOR 対策で 404)
            Map.entry("SHIFT_BUDGET_031", HttpStatus.CONFLICT),             // FAILED_EVENT_NOT_RETRIABLE
            // F03.15 個人時間割（IDOR 対策で 404 統一、上限・遷移エラーは 409）
            Map.entry("PERSONAL_TIMETABLE_001", HttpStatus.NOT_FOUND),       // PERSONAL_TIMETABLE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_010", HttpStatus.CONFLICT),        // LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_020", HttpStatus.CONFLICT),        // NOT_DRAFT
            Map.entry("PERSONAL_TIMETABLE_021", HttpStatus.CONFLICT),        // NOT_ACTIVE
            Map.entry("PERSONAL_TIMETABLE_022", HttpStatus.CONFLICT),        // NOT_ARCHIVED
            Map.entry("PERSONAL_TIMETABLE_023", HttpStatus.CONFLICT),        // INVALID_STATUS_TRANSITION
            // F03.15 Phase 2 時限定義
            Map.entry("PERSONAL_TIMETABLE_040", HttpStatus.CONFLICT),        // PERIOD_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_041", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_INVALID_TIME_RANGE
            Map.entry("PERSONAL_TIMETABLE_042", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_NUMBER_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_043", HttpStatus.UNPROCESSABLE_ENTITY), // PERIOD_NUMBER_OUT_OF_RANGE
            Map.entry("PERSONAL_TIMETABLE_044", HttpStatus.CONFLICT),        // NOT_EDITABLE (DRAFT のみ)
            // F03.15 Phase 2 コマ
            Map.entry("PERSONAL_TIMETABLE_050", HttpStatus.CONFLICT),        // SLOT_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_051", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_BREAK_PERIOD_ASSIGNED
            Map.entry("PERSONAL_TIMETABLE_052", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_PERIOD_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_053", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_WEEK_PATTERN_CONFLICT
            Map.entry("PERSONAL_TIMETABLE_054", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_WEEK_PATTERN_NOT_ENABLED
            Map.entry("PERSONAL_TIMETABLE_055", HttpStatus.UNPROCESSABLE_ENTITY), // SLOT_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_056", HttpStatus.BAD_REQUEST),     // LINK_NOT_SUPPORTED_YET (Phase 4 で対応)
            // F03.15 Phase 3 個人メモ
            Map.entry("PERSONAL_TIMETABLE_060", HttpStatus.NOT_FOUND),       // NOTE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_061", HttpStatus.PRECONDITION_FAILED), // NOTE_PRECONDITION_FAILED
            Map.entry("PERSONAL_TIMETABLE_062", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_UNSAFE_MARKDOWN
            Map.entry("PERSONAL_TIMETABLE_063", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_FIELD_TOO_LONG
            Map.entry("PERSONAL_TIMETABLE_064", HttpStatus.BAD_REQUEST),     // NOTE_INVALID_SLOT_KIND
            Map.entry("PERSONAL_TIMETABLE_065", HttpStatus.NOT_FOUND),       // NOTE_SLOT_NOT_OWNED (IDOR対策で404)
            Map.entry("PERSONAL_TIMETABLE_066", HttpStatus.NOT_FOUND),       // NOTE_TEAM_NOT_MEMBER (IDOR対策で404)
            // F03.15 Phase 3 カスタムフィールド
            Map.entry("PERSONAL_TIMETABLE_070", HttpStatus.NOT_FOUND),       // NOTE_FIELD_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_071", HttpStatus.CONFLICT),        // NOTE_FIELD_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_072", HttpStatus.CONFLICT),        // NOTE_FIELD_LABEL_DUPLICATED
            Map.entry("PERSONAL_TIMETABLE_073", HttpStatus.UNPROCESSABLE_ENTITY), // NOTE_FIELD_INVALID_MAX_LENGTH
            // F03.15 Phase 3 添付ファイル
            Map.entry("PERSONAL_TIMETABLE_080", HttpStatus.NOT_FOUND),       // ATTACHMENT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_081", HttpStatus.CONFLICT),        // ATTACHMENT_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_082", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_SIZE_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_083", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_UNSUPPORTED_TYPE
            Map.entry("PERSONAL_TIMETABLE_084", HttpStatus.TOO_MANY_REQUESTS), // ATTACHMENT_QUOTA_EXCEEDED (429)
            Map.entry("PERSONAL_TIMETABLE_085", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_MAGIC_BYTE_MISMATCH
            Map.entry("PERSONAL_TIMETABLE_086", HttpStatus.UNPROCESSABLE_ENTITY), // ATTACHMENT_OBJECT_NOT_FOUND
            // F03.15 Phase 4 チームリンク
            Map.entry("PERSONAL_TIMETABLE_090", HttpStatus.NOT_FOUND),       // SLOT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_091", HttpStatus.NOT_FOUND),       // LINK_TARGET_TEAM_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_092", HttpStatus.NOT_FOUND),       // LINK_TARGET_TIMETABLE_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_093", HttpStatus.NOT_FOUND),       // LINK_TARGET_SLOT_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_094", HttpStatus.FORBIDDEN),       // LINK_NOT_TEAM_MEMBER
            Map.entry("PERSONAL_TIMETABLE_095", HttpStatus.CONFLICT),        // LINK_STATUS_INVALID
            Map.entry("PERSONAL_TIMETABLE_096", HttpStatus.CONFLICT),        // LINK_POSITION_MISMATCH
            Map.entry("PERSONAL_TIMETABLE_097", HttpStatus.BAD_REQUEST),     // LINK_TIMETABLE_REQUIRED
            // F03.15 Phase 5 家族共有
            Map.entry("PERSONAL_TIMETABLE_100", HttpStatus.CONFLICT),        // SHARE_TARGET_LIMIT_EXCEEDED
            Map.entry("PERSONAL_TIMETABLE_101", HttpStatus.UNPROCESSABLE_ENTITY), // SHARE_TARGET_NOT_FAMILY_TEAM
            Map.entry("PERSONAL_TIMETABLE_102", HttpStatus.FORBIDDEN),       // SHARE_TARGET_NOT_TEAM_MEMBER
            Map.entry("PERSONAL_TIMETABLE_103", HttpStatus.NOT_FOUND),       // SHARE_TARGET_TEAM_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_104", HttpStatus.NOT_FOUND),       // SHARE_TARGET_NOT_FOUND
            Map.entry("PERSONAL_TIMETABLE_105", HttpStatus.CONFLICT),        // SHARE_TARGET_DUPLICATED
            // F09.8.1 コルクボード ピン止め
            Map.entry("CORKBOARD_011", HttpStatus.FORBIDDEN),                // PIN_PERSONAL_ONLY
            Map.entry("CORKBOARD_012", HttpStatus.BAD_REQUEST),              // PIN_ARCHIVED_NOT_ALLOWED
            Map.entry("CORKBOARD_013", HttpStatus.CONFLICT),                 // PIN_LIMIT_EXCEEDED
            // F09.8 Phase A2 コルクボード詳細取得 — 設計書通り 403/404 を返す
            Map.entry("CORKBOARD_001", HttpStatus.NOT_FOUND),                // BOARD_NOT_FOUND
            Map.entry("CORKBOARD_009", HttpStatus.FORBIDDEN),                // INSUFFICIENT_PERMISSION
            // F13 ストレージクォータ統合機構（Phase 4-α）
            Map.entry("STORAGE_QUOTA_001", HttpStatus.CONFLICT),             // QUOTA_EXCEEDED (容量超過)
            Map.entry("STORAGE_QUOTA_002", HttpStatus.INTERNAL_SERVER_ERROR), // SUBSCRIPTION_NOT_FOUND
            // F04.2 チャット 添付ファイル（F13 Phase 4-β）
            Map.entry("CHAT_015", HttpStatus.PAYLOAD_TOO_LARGE),             // ATTACHMENT_SIZE_EXCEEDED (UX ガード 500MB 超過)
            Map.entry("CHAT_019", HttpStatus.CONFLICT),                      // ATTACHMENT_QUOTA_EXCEEDED (F13 統合クォータ超過)
            // F05.5 ファイル共有（F13 Phase 4-epsilon）
            Map.entry("FILE_SHARING_016", HttpStatus.CONFLICT),              // STORAGE_QUOTA_EXCEEDED (F13 統合クォータ超過)
            // F02.3.1 TODO カスタムステータスラベル
            Map.entry("TODO_070", HttpStatus.CONFLICT),                      // LABEL_NAME_DUPLICATED
            Map.entry("TODO_071", HttpStatus.CONFLICT),                      // LABEL_LIMIT_EXCEEDED
            Map.entry("TODO_072", HttpStatus.CONFLICT),                      // LABEL_IN_USE
            Map.entry("TODO_073", HttpStatus.FORBIDDEN),                     // SYSTEM_LABEL_IMMUTABLE（書き込み禁止リソース → 403）
            Map.entry("TODO_076", HttpStatus.NOT_FOUND),                     // STATUS_LABEL_NOT_FOUND (IDOR 対策)
            // F14.2 メンバー情報収集
            Map.entry("MEMBER_INFO_002", HttpStatus.FORBIDDEN),              // FIELD_BELONGS_TO_OTHER_TEAM
            Map.entry("MEMBER_INFO_008", HttpStatus.TOO_MANY_REQUESTS),      // REMIND_TOO_SOON
            // F15.2 マイスコープフォルダ（IDOR対策で404統一、所属なしは403）
            Map.entry("SCOPE_FOLDER_NOT_FOUND", HttpStatus.NOT_FOUND),       // フォルダ不存在 / 他ユーザーフォルダ (IDOR対策)
            Map.entry("SCOPE_FOLDER_ACCESS_DENIED", HttpStatus.FORBIDDEN),   // アクセス権限なし
            Map.entry("SCOPE_FOLDER_LIMIT_EXCEEDED", HttpStatus.CONFLICT),   // フォルダ上限超過
            Map.entry("SCOPE_FOLDER_NAME_DUPLICATE", HttpStatus.CONFLICT),   // 同名フォルダ重複
            Map.entry("SCOPE_FOLDER_NOT_MEMBER", HttpStatus.FORBIDDEN),      // スコープ非所属
            // F08.8 Phase 1 案5 修繕計画項目（IDOR 対策で 404、楽観ロック競合は 409）
            Map.entry("REPAIR_PLAN_001", HttpStatus.NOT_FOUND),              // ITEM_NOT_FOUND
            Map.entry("REPAIR_PLAN_002", HttpStatus.CONFLICT),               // ITEM_VERSION_CONFLICT
            Map.entry("REPAIR_PLAN_003", HttpStatus.BAD_REQUEST),            // INVALID_SCOPE
            // F08.8 Phase 2 シミュレーションシナリオ（保存上限・ロック・エンジンバージョン・レートリミット）
            Map.entry("REPAIR_PLAN_005", HttpStatus.CONFLICT),               // SCENARIO_LIMIT_EXCEEDED
            Map.entry("REPAIR_PLAN_006", HttpStatus.CONFLICT),               // SCENARIO_ALREADY_LOCKED
            Map.entry("REPAIR_PLAN_007", HttpStatus.CONFLICT),               // ENGINE_VERSION_MISMATCH
            Map.entry("REPAIR_PLAN_009", HttpStatus.TOO_MANY_REQUESTS),      // RATE_LIMIT_EXCEEDED
            Map.entry("REPAIR_PLAN_012", HttpStatus.UNPROCESSABLE_ENTITY),   // SIMULATION_BASELINE_STALE
            // F08.8 修繕長期計画ダッシュボード — テンプレ/モジュール判定（足軽5）
            Map.entry("REPAIR_PLAN_013", HttpStatus.UNPROCESSABLE_ENTITY),   // TEMPLATE_NOT_APARTMENT
            Map.entry("REPAIR_PLAN_014", HttpStatus.UNPROCESSABLE_ENTITY),   // MODULE_NOT_ENABLED
            // F08.7 Phase 9/9-B エントリー表・テンプレート
            Map.entry("TOUR_019", HttpStatus.NOT_FOUND),              // ENTRY_MEMBER_NOT_FOUND (IDOR対策で404)
            Map.entry("TOUR_020", HttpStatus.CONFLICT),               // ENTRY_LOCKED
            Map.entry("TOUR_021", HttpStatus.UNPROCESSABLE_ENTITY),   // USER_NOT_TEAM_MEMBER
            Map.entry("TOUR_022", HttpStatus.UNPROCESSABLE_ENTITY),   // MIN_ENTRY_COUNT_VIOLATION
            Map.entry("TOUR_023", HttpStatus.UNPROCESSABLE_ENTITY),   // MAX_ENTRY_COUNT_EXCEEDED
            Map.entry("TOUR_024", HttpStatus.NOT_FOUND),              // ENTRY_TEMPLATE_NOT_FOUND (IDOR対策で404)
            Map.entry("TOUR_025", HttpStatus.UNPROCESSABLE_ENTITY),   // MAX_TEMPLATE_COUNT_EXCEEDED
            Map.entry("TOUR_026", HttpStatus.NOT_FOUND),              // TEAM_NOT_IN_ORGANIZATION (IDOR対策で404)
            Map.entry("TOUR_027", HttpStatus.CONFLICT),               // DUPLICATE_ENTRY_MEMBER
            Map.entry("TOUR_028", HttpStatus.FORBIDDEN),              // TEMPLATE_TEAM_MISMATCH
            // F17.1 村機能 Phase 1（B2 村CRUD / B3 メンバーシップ / B4 ニックネーム / B5 村作成申請）統合
            Map.entry("VILLAGE_001", HttpStatus.NOT_FOUND),            // VILLAGE_NOT_FOUND（IDOR 対策で 404）
            Map.entry("VILLAGE_002", HttpStatus.FORBIDDEN),            // VILLAGE_UNLISTED
            Map.entry("VILLAGE_006", HttpStatus.CONFLICT),             // ALREADY_MEMBER
            Map.entry("VILLAGE_007", HttpStatus.NOT_FOUND),            // NOT_MEMBER（IDOR 対策で 404）
            Map.entry("VILLAGE_008", HttpStatus.CONFLICT),             // NICKNAME_TAKEN
            Map.entry("VILLAGE_010", HttpStatus.TOO_MANY_REQUESTS),    // CREATION_REQUEST_THROTTLED
            Map.entry("VILLAGE_011", HttpStatus.TOO_MANY_REQUESTS),    // NICKNAME_CHANGE_THROTTLED
            Map.entry("VILLAGE_012", HttpStatus.TOO_MANY_REQUESTS),    // PARTICIPATION_LIMIT_EXCEEDED
            Map.entry("VILLAGE_014", HttpStatus.UNPROCESSABLE_ENTITY), // GUIDELINE_NOT_AGREED
            Map.entry("VILLAGE_015", HttpStatus.FORBIDDEN),            // REPRESENT_FORBIDDEN
            Map.entry("VILLAGE_016", HttpStatus.FORBIDDEN),            // SUBJECT_NOT_MEMBER
            Map.entry("VILLAGE_017", HttpStatus.CONFLICT),             // HEADMAN_CANNOT_LEAVE
            Map.entry("VILLAGE_018", HttpStatus.CONFLICT),             // VERSION_CONFLICT
            Map.entry("VILLAGE_019", HttpStatus.CONFLICT),             // VILLAGE_JOIN_REQUIRES_APPROVAL
            Map.entry("VILLAGE_022", HttpStatus.FORBIDDEN),            // NEW_ACCOUNT_RESTRICTED
            Map.entry("VILLAGE_024", HttpStatus.FORBIDDEN),            // MODERATION_FORBIDDEN
            Map.entry("VILLAGE_025", HttpStatus.CONFLICT),             // JOIN_RATE_EXCEEDED
            Map.entry("VILLAGE_027", HttpStatus.CONFLICT),             // VILLAGE_ALREADY_ARCHIVED
            Map.entry("VILLAGE_028", HttpStatus.UNPROCESSABLE_ENTITY), // NICKNAME_INVALID
            Map.entry("VILLAGE_031", HttpStatus.FORBIDDEN),            // MEMBER_BANNED
            Map.entry("VILLAGE_032", HttpStatus.NOT_FOUND),            // CREATION_REQUEST_NOT_FOUND
            Map.entry("VILLAGE_033", HttpStatus.CONFLICT),             // CREATION_REQUEST_ALREADY_REVIEWED
            Map.entry("VILLAGE_034", HttpStatus.FORBIDDEN),            // CREATION_REQUEST_REJECTED
            Map.entry("VILLAGE_035", HttpStatus.CONFLICT),             // CREATION_REQUEST_SLUG_TAKEN
            Map.entry("VILLAGE_036", HttpStatus.FORBIDDEN),            // OFFICIAL_VILLAGE_FORBIDDEN
            Map.entry("VILLAGE_037", HttpStatus.FORBIDDEN),            // VILLAGE_CREATE_FORBIDDEN
            // F17.1 Phase 1 B6 — 村参加申請（VILLAGE_038〜041）
            Map.entry("VILLAGE_038", HttpStatus.NOT_FOUND),            // VILLAGE_JOIN_REQUEST_NOT_FOUND
            Map.entry("VILLAGE_039", HttpStatus.CONFLICT),             // VILLAGE_JOIN_REQUEST_PENDING_DUPLICATE
            Map.entry("VILLAGE_040", HttpStatus.CONFLICT),             // VILLAGE_JOIN_REQUEST_ALREADY_REVIEWED
            Map.entry("VILLAGE_041", HttpStatus.UNPROCESSABLE_ENTITY), // VILLAGE_FREE_VILLAGE_DIRECT_JOIN
            // F17.1 Phase 1 B7 — 通報 + モデレーション（設計書 §10 予約 VILLAGE_009/026 + 追加 VILLAGE_042/043）
            Map.entry("VILLAGE_009", HttpStatus.TOO_MANY_REQUESTS),    // VILLAGE_REPORT_RATE_LIMITED
            Map.entry("VILLAGE_026", HttpStatus.UNPROCESSABLE_ENTITY), // VILLAGE_REPORT_INVALID_TARGET
            Map.entry("VILLAGE_042", HttpStatus.NOT_FOUND),            // VILLAGE_REPORT_NOT_FOUND（IDOR 対策で 404）
            Map.entry("VILLAGE_043", HttpStatus.CONFLICT),             // VILLAGE_REPORT_ALREADY_RESOLVED
            // F17.1 Phase 1 B8 — お気に入り村ピン留め（VILLAGE_013 + VILLAGE_044/045/047）
            Map.entry("VILLAGE_013", HttpStatus.UNPROCESSABLE_ENTITY), // VILLAGE_PIN_LIMIT_EXCEEDED
            Map.entry("VILLAGE_044", HttpStatus.NOT_FOUND),            // VILLAGE_PIN_NOT_FOUND
            Map.entry("VILLAGE_045", HttpStatus.CONFLICT),             // VILLAGE_PIN_ALREADY_EXISTS
            Map.entry("VILLAGE_047", HttpStatus.UNPROCESSABLE_ENTITY), // VILLAGE_PIN_ORDER_MISMATCH
            // F17.1 Phase 1 B9 — 井戸端会議 + 投稿主体一覧（VILLAGE_048〜050）
            Map.entry("VILLAGE_048", HttpStatus.FORBIDDEN),            // VILLAGE_POSTING_IDENTITY_FORBIDDEN
            Map.entry("VILLAGE_049", HttpStatus.NOT_FOUND),            // VILLAGE_LOBBY_NOT_FOUND
            Map.entry("VILLAGE_050", HttpStatus.INTERNAL_SERVER_ERROR),// VILLAGE_LOBBY_CHANNEL_INIT_FAILED
            // F17.1 Phase 1 B10 — 村内検索 + ダッシュボード集約（VILLAGE_051）
            Map.entry("VILLAGE_051", HttpStatus.UNPROCESSABLE_ENTITY), // VILLAGE_SEARCH_INVALID_QUERY
            // F17 Phase 2 U3 — 村代表委任（VILLAGE_052〜055）
            Map.entry("VILLAGE_052", HttpStatus.NOT_FOUND),            // REPRESENTATIVE_NOT_FOUND
            Map.entry("VILLAGE_053", HttpStatus.CONFLICT),             // REPRESENTATIVE_ALREADY_GRANTED
            Map.entry("VILLAGE_054", HttpStatus.UNPROCESSABLE_ENTITY), // REPRESENTATIVE_NOT_TEAM_OR_ORG_MEMBERSHIP
            Map.entry("VILLAGE_055", HttpStatus.UNPROCESSABLE_ENTITY), // REPRESENTATIVE_USER_NOT_IN_SUBJECT
            // F17 Phase 2 U4 — 歳時記カレンダー（VILLAGE_056〜058）
            Map.entry("VILLAGE_056", HttpStatus.NOT_FOUND),            // CALENDAR_EVENT_NOT_FOUND
            Map.entry("VILLAGE_057", HttpStatus.UNPROCESSABLE_ENTITY), // CALENDAR_EVENT_INVALID_DATE_RANGE
            Map.entry("VILLAGE_058", HttpStatus.UNPROCESSABLE_ENTITY), // CALENDAR_EVENT_INVALID_COLOR
            // F17 Phase 2 U5 — お祭り（VILLAGE_059〜062）
            Map.entry("VILLAGE_059", HttpStatus.NOT_FOUND),            // FESTIVAL_NOT_FOUND
            Map.entry("VILLAGE_060", HttpStatus.UNPROCESSABLE_ENTITY), // FESTIVAL_INVALID_PERIOD
            Map.entry("VILLAGE_061", HttpStatus.UNPROCESSABLE_ENTITY), // FESTIVAL_INVALID_COLOR
            Map.entry("VILLAGE_062", HttpStatus.CONFLICT),             // FESTIVAL_ALREADY_ENDED
            // F17 Phase 2 U6 — 練習試合・審判募集（VILLAGE_063〜068）
            Map.entry("VILLAGE_063", HttpStatus.NOT_FOUND),            // MATCH_RECRUIT_NOT_FOUND
            Map.entry("VILLAGE_064", HttpStatus.CONFLICT),             // MATCH_RECRUIT_NOT_OPEN
            Map.entry("VILLAGE_065", HttpStatus.UNPROCESSABLE_ENTITY), // MATCH_RECRUIT_TIME_INVALID
            Map.entry("VILLAGE_066", HttpStatus.NOT_FOUND),            // MATCH_APPLICATION_NOT_FOUND
            Map.entry("VILLAGE_067", HttpStatus.CONFLICT),             // MATCH_APPLICATION_DUPLICATE
            Map.entry("VILLAGE_068", HttpStatus.UNPROCESSABLE_ENTITY), // MATCH_APPLICATION_INVALID_STATUS

            // F17 Phase 3-β — 寄合（VILLAGE_069〜074）
            Map.entry("VILLAGE_069", HttpStatus.NOT_FOUND),            // MEETUP_NOT_FOUND
            Map.entry("VILLAGE_070", HttpStatus.CONFLICT),             // MEETUP_ALREADY_CONFIRMED
            Map.entry("VILLAGE_071", HttpStatus.CONFLICT),             // MEETUP_INVALID_STATUS
            Map.entry("VILLAGE_072", HttpStatus.NOT_FOUND),            // CANDIDATE_DATE_NOT_FOUND
            Map.entry("VILLAGE_073", HttpStatus.CONFLICT),             // VOTE_DUPLICATE
            Map.entry("VILLAGE_074", HttpStatus.FORBIDDEN),            // MEETUP_NOT_MEMBER

            // F17 Phase 3-β — 村史（VILLAGE_075）
            Map.entry("VILLAGE_075", HttpStatus.NOT_FOUND),            // CHRONICLE_NOT_FOUND

            // F18 個人ポイントカードウォレット（設計書 §6.3 整合）
            Map.entry("POINT_CARD_001", HttpStatus.FORBIDDEN),         // WALLET_NOT_ENABLED
            Map.entry("POINT_CARD_002", HttpStatus.BAD_REQUEST),       // INVALID_BARCODE_VALUE（Severity.WARN 既定と同じだが明示）
            Map.entry("POINT_CARD_003", HttpStatus.CONFLICT),          // CARD_LIMIT_EXCEEDED
            Map.entry("POINT_CARD_004", HttpStatus.CONFLICT),          // GROUP_LIMIT_EXCEEDED
            Map.entry("POINT_CARD_005", HttpStatus.CONFLICT),          // GROUP_ITEM_LIMIT_EXCEEDED
            Map.entry("POINT_CARD_006", HttpStatus.NOT_FOUND),         // CARD_NOT_FOUND（IDOR 対策で 403→404）
            Map.entry("POINT_CARD_007", HttpStatus.NOT_FOUND),         // PROVIDER_NOT_FOUND
            Map.entry("POINT_CARD_008", HttpStatus.TOO_MANY_REQUESTS), // RATE_LIMIT_EXCEEDED
            Map.entry("POINT_CARD_009", HttpStatus.UNAUTHORIZED),      // BIOMETRIC_REQUIRED
            // F18 Phase 2 第二陣 2B — 自店プロバイダー CRUD（POINT_CARD_010〜011）
            Map.entry("POINT_CARD_010", HttpStatus.CONFLICT),          // PROVIDER_LIMIT_EXCEEDED (20 個超過)
            Map.entry("POINT_CARD_011", HttpStatus.NOT_FOUND),         // PROVIDER_NOT_OWNED (IDOR 対策で 404)
            // F18 Phase 2 第二陣 2C — スタンプ押印（POINT_CARD_012〜014）
            Map.entry("POINT_CARD_012", HttpStatus.BAD_REQUEST),       // STAMP_INVALID_PROVIDER
            Map.entry("POINT_CARD_013", HttpStatus.BAD_REQUEST),       // STAMP_INVALID_PROVIDER_TYPE
            Map.entry("POINT_CARD_014", HttpStatus.BAD_REQUEST),       // STAMP_DELTA_ZERO
            // F18 Phase 3 — 残高型（POINT_CARD_015〜018）
            Map.entry("POINT_CARD_015", HttpStatus.BAD_REQUEST),       // BALANCE_INVALID_PROVIDER_TYPE
            Map.entry("POINT_CARD_016", HttpStatus.BAD_REQUEST),       // BALANCE_DELTA_ZERO
            Map.entry("POINT_CARD_017", HttpStatus.BAD_REQUEST),       // INSUFFICIENT_BALANCE
            Map.entry("POINT_CARD_018", HttpStatus.CONFLICT),          // BALANCE_LIMIT_EXCEEDED
            // F18 Phase 3 第二陣 2A — QR 自動特定（一時トークン）
            Map.entry("POINT_CARD_019", HttpStatus.NOT_FOUND),         // TOKEN_NOT_FOUND
            // F18 Phase 3 第二陣 2B — 残高型 REFUND 超過
            Map.entry("POINT_CARD_020", HttpStatus.CONFLICT),          // REFUND_EXCEEDS_ORIGINAL
            // F02.9 お気に入りウィジェット
            Map.entry("FAV_001", HttpStatus.CONFLICT),                  // ALREADY_REGISTERED（重複登録）
            Map.entry("FAV_002", HttpStatus.UNPROCESSABLE_ENTITY),      // LIMIT_EXCEEDED（上限20件超過）
            Map.entry("FAV_003", HttpStatus.NOT_FOUND),                 // ENTITY_NOT_FOUND（IDOR対策で404）
            Map.entry("FAV_004", HttpStatus.FORBIDDEN),                 // ACCESS_DENIED（他ユーザーお気に入り）
            Map.entry("FAV_005", HttpStatus.BAD_REQUEST),               // INVALID_ENTITY_TYPE
            Map.entry("FAV_006", HttpStatus.BAD_REQUEST)                // INVALID_ENTITY_ID
    );

    /**
     * 業務例外ハンドラー。
     * F11.3: resolveMessage() でロケールに応じた多言語メッセージに解決する。
     *
     * <p>F10.6 Phase 10-β-1: HTTP 5xx を返すケース（ErrorCode.severity=ERROR で
     * 個別マッピングが存在しないか 500 を返す場合）のみ error_reports に severity=MEDIUM で
     * 記録する。4xx を返す通常の業務エラーは記録しない（設計書 §5.2）。</p>
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex,
                                                                  HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        String message = resolveMessage(errorCode);
        log.warn("BusinessException: code={}, message={}", errorCode.getCode(), message);

        HttpStatus status = resolveHttpStatus(errorCode);

        // F10.6: 5xx を返す BusinessException のみ記録対象（severity=MEDIUM）
        if (status.is5xxServerError()) {
            recordBackendException(ex, request, ErrorReportSeverity.MEDIUM);
        }

        ErrorResponse body;
        if (ex.getFieldErrors().isEmpty()) {
            body = new ErrorResponse(
                    new ErrorResponse.ErrorDetail(errorCode.getCode(), message, List.of()));
        } else {
            body = new ErrorResponse(
                    new ErrorResponse.ErrorDetail(errorCode.getCode(), message, ex.getFieldErrors()));
        }
        return ResponseEntity
                .status(status)
                .body(body);
    }

    /**
     * 既存ユニットテスト互換用 overload。HttpServletRequest を渡せない既存呼び出し向け。
     * 内部的に request=null で本体ハンドラに委譲する。
     */
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        return handleBusinessException(ex, null);
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
     * F10.6 Phase 10-β 後続-① — {@code @Validated} 付き controller の引数（path / query / body）の
     * バリデーション失敗で投げられる {@link ConstraintViolationException} のハンドラ。
     *
     * <p>設計書 F10.6 §5.2 表で「バリデーションエラーは error_reports に記録しない」と明示されているため、
     * 既存の {@link MethodArgumentNotValidException} と同じく {@link #recordBackendException} は呼ばない。
     * デフォルトの {@link #handleUnexpectedException} に流して severity=HIGH で記録される事故を防ぐ。</p>
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations()
                .stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        log.warn("ConstraintViolationException: {} field error(s)", fieldErrors.size());
        ErrorResponse body = ErrorResponse.of(CommonErrorCode.COMMON_001, fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Spring 6.1+ の宣言的メソッドパラメータ検証で投げられる
     * {@link HandlerMethodValidationException} のハンドラ。
     * controller メソッド引数の {@code @Min} / {@code @NotBlank} 等で発火する。
     *
     * <p>{@link ConstraintViolationException} と同じく error_reports には記録しない。</p>
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        log.warn("HandlerMethodValidationException: {}", ex.getMessage());
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
     *
     * <p>F10.6 Phase 10-β-1: 競合以外（500 を返すケース）は error_reports に記録する（HIGH）。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex,
                                                             HttpServletRequest request) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        if (msg.contains("競合") || msg.contains("conflict")) {
            log.warn("ゲート更新競合: {}", msg);
            return ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(CommonErrorCode.COMMON_003));
        }
        log.error("IllegalStateException", ex);
        recordBackendException(ex, request, ErrorReportSeverity.HIGH);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * 既存ユニットテスト互換用 overload。
     */
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return handleIllegalState(ex, null);
    }

    /**
     * その他の予期しない例外。
     *
     * <p>F10.6 Phase 10-β-1: error_reports に severity=HIGH で記録し、Slack エスカレーション通知を送る。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex,
                                                                    HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        recordBackendException(ex, request, ErrorReportSeverity.HIGH);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_999));
    }

    /**
     * 既存ユニットテスト互換用 overload。
     */
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        return handleUnexpectedException(ex, null);
    }

    /**
     * F10.6 Phase 10-β-1 — error_reports への記録ヘルパー。
     * Bean 未配線時は静かにスキップする。記録時の例外は元のレスポンスに影響させない。
     *
     * <p>バリデーション系（{@link MethodArgumentNotValidException} /
     * {@link HttpMessageNotReadableException} / {@link MethodArgumentTypeMismatchException} /
     * {@link MissingServletRequestParameterException}）は呼び出し側でこのメソッドを呼ばないことで
     * 設計書 §5.2 「バリデーションエラーは記録しない」方針を実現する。</p>
     */
    private void recordBackendException(Throwable ex, HttpServletRequest request, ErrorReportSeverity severity) {
        if (errorReportServiceProvider == null) return;
        try {
            ErrorReportService service = errorReportServiceProvider.getIfAvailable();
            if (service != null) {
                service.recordBackendException(ex, request, severity);
            }
        } catch (Exception inner) {
            log.warn("recordBackendException failed: severity={}, ex={}", severity, ex.getClass().getName(), inner);
        }
    }

    /**
     * {@link ConstraintViolation} を {@link ErrorResponse.FieldError} に変換する。
     * {@code property path} の最後のノード名をフィールド名として採用する
     * （例: {@code listJobs.page} → {@code page}）。
     */
    private static ErrorResponse.FieldError toFieldError(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
        int lastDot = path.lastIndexOf('.');
        String field = (lastDot >= 0 && lastDot < path.length() - 1)
                ? path.substring(lastDot + 1)
                : path;
        return new ErrorResponse.FieldError(field, v.getMessage());
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
