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
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
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
    // 型推論限界回避のため明示型指定（エントリ数増加に伴う javac 推論破綻を根治）
    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.<String, HttpStatus>ofEntries(
            // F00 共通可視性基盤（Severity.WARN デフォルト 400 を設計書 §7.4 の正しい status に上書き）
            Map.entry("VISIBILITY_001", HttpStatus.FORBIDDEN),   // 認可拒否（権限不足）→ 403
            Map.entry("VISIBILITY_004", HttpStatus.NOT_FOUND),  // コンテンツ不在 → 404
            // 未認証は 401 を返す（Severity.WARN のデフォルト 400 を上書き）
            Map.entry(CommonErrorCode.COMMON_000.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(CommonErrorCode.COMMON_002.getCode(), HttpStatus.FORBIDDEN),
            Map.entry(CommonErrorCode.COMMON_003.getCode(), HttpStatus.CONFLICT),
            // F15.4 Phase 5-α: 店舗詳細 Public API（IDOR対策で 404）
            Map.entry("TEAM_001", HttpStatus.NOT_FOUND),
            // F19.1 公開ページ Public API（IDOR / レート制限）
            Map.entry("PUBLIC_001", HttpStatus.NOT_FOUND),         // PUBLIC でないチーム / 組織は 404 で隠蔽
            Map.entry("PUBLIC_002", HttpStatus.TOO_MANY_REQUESTS), // レート制限超過
            Map.entry("PUBLIC_003", HttpStatus.NOT_FOUND),         // 公開投稿不在も 404 で隠蔽
            // F19.1 Phase 2: Admin 切替 API エラーコード
            Map.entry("PUBLIC_004", HttpStatus.NOT_FOUND),         // NAME_DISCLOSURE_NOT_FOUND
            Map.entry("PUBLIC_005", HttpStatus.BAD_REQUEST),       // NAME_DISCLOSURE_CONFIRM_REQUIRED
            Map.entry("PUBLIC_006", HttpStatus.FORBIDDEN),         // NAME_DISCLOSURE_FORBIDDEN
            // F19.1 Phase 6: 公開ユーザープロフィール（IDOR 対策で 404）
            Map.entry("PUBLIC_007", HttpStatus.NOT_FOUND),         // 非公開 / 不在 / 削除済みを 404 で隠蔽
            // F19.1 Phase 6-B: 公開投稿コメント
            Map.entry("PUBLIC_008", HttpStatus.NOT_FOUND),         // 対象投稿が存在しないか非公開
            Map.entry("PUBLIC_009", HttpStatus.NOT_FOUND),         // コメントが見つからない
            Map.entry("PUBLIC_010", HttpStatus.FORBIDDEN),         // コメント削除権限なし
            // F19.1 Phase 7: ブログ投稿 public_visible / 公開設定変更
            Map.entry("PUBLIC_011", HttpStatus.FORBIDDEN),         // 投稿 public_visible 変更権限なし（投稿者本人以外）
            Map.entry("PUBLIC_012", HttpStatus.FORBIDDEN),         // チーム/組織 公開設定変更権限なし
            Map.entry("AD_006", HttpStatus.CONFLICT),
            Map.entry("AD_007", HttpStatus.CONFLICT),
            Map.entry("AD_010", HttpStatus.FORBIDDEN),
            // F09.17 メッセージ型キャンペーン (DRAFT CRUD)
            Map.entry("AD_CAMPAIGN_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("AD_CAMPAIGN_INVALID_STATE", HttpStatus.CONFLICT),
            Map.entry("AD_CAMPAIGN_NOT_EDITABLE", HttpStatus.CONFLICT),
            Map.entry("AD_CAMPAIGN_FORBIDDEN_TENANT", HttpStatus.NOT_FOUND), // IDOR 対策で 404
            Map.entry("AD_CAMPAIGN_CREDIT_EXCEEDED", HttpStatus.PAYMENT_REQUIRED),
            Map.entry("AD_CAMPAIGN_MODERATION_BLOCKED", HttpStatus.FORBIDDEN),
            Map.entry("AD_AUDIENCE_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("AD_CHANNEL_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("AD_CHANNEL_DUPLICATE", HttpStatus.CONFLICT),
            // F09.17 Phase 11-b: unsubscribe / 開封ピクセル JWT
            Map.entry("AD_UNSUBSCRIBE_TOKEN_EXPIRED", HttpStatus.GONE),               // 410: 期限切れ・version 不一致
            Map.entry("AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH", HttpStatus.GONE),      // 410: ローテート済
            Map.entry("AD_UNSUBSCRIBE_TOKEN_INVALID", HttpStatus.BAD_REQUEST),        // 400: 改竄・形式不正
            // 開封ピクセルは常に 200 で返すため Controller 内で握り潰す。ここで 200 を明示しても到達しない設計。
            Map.entry("AD_OPEN_PIXEL_TOKEN_INVALID", HttpStatus.OK),
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
            // F03.10 代理出席（スケジュール側 §4.1 / §5.6）
            Map.entry("SCHEDULE_070", HttpStatus.NOT_FOUND),                // SCHEDULE_DELEGATION_NOT_FOUND
            Map.entry("SCHEDULE_071", HttpStatus.FORBIDDEN),                // 委任者がスコープ外
            Map.entry("SCHEDULE_072", HttpStatus.UNPROCESSABLE_ENTITY),     // 代理人がスコープ外
            Map.entry("SCHEDULE_073", HttpStatus.UNPROCESSABLE_ENTITY),     // 自己代理
            Map.entry("SCHEDULE_074", HttpStatus.CONFLICT),                 // アクティブ代理重複
            Map.entry("SCHEDULE_075", HttpStatus.UNPROCESSABLE_ENTITY),     // 連鎖代理禁止
            Map.entry("SCHEDULE_076", HttpStatus.UNPROCESSABLE_ENTITY),     // allow_proxy_attendance=FALSE
            Map.entry("SCHEDULE_077", HttpStatus.UNPROCESSABLE_ENTITY),     // CANCELLED/COMPLETED
            Map.entry("SCHEDULE_078", HttpStatus.UNPROCESSABLE_ENTITY),     // 親スケジュール
            Map.entry("SCHEDULE_079", HttpStatus.FORBIDDEN),                // 代理人本人でない
            Map.entry("SCHEDULE_080", HttpStatus.UNPROCESSABLE_ENTITY),     // PENDING でない
            // 機能55 予約作成（第三陣）予約タスク取消
            Map.entry("SCHEDULE_091", HttpStatus.NOT_FOUND),                // SCHEDULED_TASK_NOT_FOUND（IDOR対策で 404）
            Map.entry("SCHEDULE_092", HttpStatus.CONFLICT),                 // SCHEDULED_TASK_NOT_CANCELLABLE（PENDING 以外）
            // F03.10 代理出席（イベント側 §4.2 / §5.6 / §5.7）
            Map.entry("EVENT_030", HttpStatus.NOT_FOUND),                   // DELEGATION_NOT_FOUND
            Map.entry("EVENT_031", HttpStatus.FORBIDDEN),                   // 委任者がスコープ外
            Map.entry("EVENT_032", HttpStatus.UNPROCESSABLE_ENTITY),        // 代理人がスコープ外
            Map.entry("EVENT_033", HttpStatus.UNPROCESSABLE_ENTITY),        // 自己代理
            Map.entry("EVENT_034", HttpStatus.CONFLICT),                    // アクティブ代理重複
            Map.entry("EVENT_035", HttpStatus.UNPROCESSABLE_ENTITY),        // 連鎖代理禁止
            Map.entry("EVENT_036", HttpStatus.UNPROCESSABLE_ENTITY),        // allow_proxy_attendance=FALSE
            Map.entry("EVENT_037", HttpStatus.UNPROCESSABLE_ENTITY),        // CANCELLED/COMPLETED
            Map.entry("EVENT_038", HttpStatus.FORBIDDEN),                   // 代理人本人でない
            Map.entry("EVENT_039", HttpStatus.UNPROCESSABLE_ENTITY),        // PENDING でない
            Map.entry("EVENT_040", HttpStatus.UNPROCESSABLE_ENTITY),        // 投票セッション事前条件違反
            Map.entry("EVENT_041", HttpStatus.UNPROCESSABLE_ENTITY),        // 代理チェックイン: ACCEPTED でない
            Map.entry("EVENT_042", HttpStatus.CONFLICT),                    // 代理チェックイン: 既にチェックイン済み
            Map.entry("EVENT_043", HttpStatus.FORBIDDEN),                   // 代理チェックイン: 権限なし
            // F03.5 シフト管理（Phase 11 第二陣で summary / remind 追加）
            Map.entry("SHIFT_001", HttpStatus.NOT_FOUND),                   // SHIFT_SCHEDULE_NOT_FOUND
            Map.entry("SHIFT_012", HttpStatus.CONFLICT),                    // INVALID_SCHEDULE_STATUS
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
            // F04.2 Phase 11 第二陣 2-β: チャンネルアイコン Pre-signed URL
            Map.entry("CHAT_022", HttpStatus.PAYLOAD_TOO_LARGE),             // ICON_SIZE_EXCEEDED (2MB 超過)
            Map.entry("CHAT_023", HttpStatus.FORBIDDEN),                     // CHANNEL_ICON_PERMISSION_DENIED (OWNER/ADMIN ではない)
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
            // F08.7 大会基本（IDOR 対策で 404 に統一）
            Map.entry("TOUR_001", HttpStatus.NOT_FOUND),              // TOURNAMENT_NOT_FOUND (IDOR対策で404)
            // F08.7 順位UI 項目③ スコアキーパー指名（TOUR_059/060）
            Map.entry("TOUR_059", HttpStatus.FORBIDDEN),              // SCOREKEEPER_MANAGE_FORBIDDEN (管理権限不足→403)
            Map.entry("TOUR_060", HttpStatus.NOT_FOUND),              // SCOREKEEPER_NOT_FOUND (IDOR対策で404)
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
            // F08.7.1/07 大会費用支払い（F08.2 決済基盤 再利用・薄い連結 tournament_fee）
            Map.entry("TOUR_033", HttpStatus.NOT_FOUND),              // FEE_NOT_FOUND（IDOR 対策で 404）
            Map.entry("TOUR_034", HttpStatus.FORBIDDEN),              // FEE_MANAGE_FORBIDDEN
            Map.entry("TOUR_035", HttpStatus.FORBIDDEN),              // FEE_PAY_FORBIDDEN
            Map.entry("TOUR_036", HttpStatus.UNPROCESSABLE_ENTITY),  // FEE_PAYMENT_ITEM_SCOPE_MISMATCH
            Map.entry("TOUR_037", HttpStatus.FORBIDDEN),              // FEE_TEAM_NOT_TARGET
            // F08.7.1/03 リーグ・ピラミッド＋昇降格移籍（league_transfer §7）
            Map.entry("TOUR_038", HttpStatus.NOT_FOUND),              // LEAGUE_TRANSFER_NOT_FOUND（IDOR 対策で 404）
            Map.entry("TOUR_039", HttpStatus.FORBIDDEN),              // LEAGUE_TRANSFER_DISPATCH_FORBIDDEN
            Map.entry("TOUR_040", HttpStatus.FORBIDDEN),              // LEAGUE_TRANSFER_RESPOND_FORBIDDEN
            Map.entry("TOUR_041", HttpStatus.FORBIDDEN),              // LEAGUE_TRANSFER_VIEW_FORBIDDEN
            Map.entry("TOUR_042", HttpStatus.UNPROCESSABLE_ENTITY),   // LEAGUE_TRANSFER_TARGET_NOT_RESOLVABLE
            Map.entry("TOUR_043", HttpStatus.UNPROCESSABLE_ENTITY),   // LEAGUE_TRANSFER_TEAM_NOT_IN_SLOT
            Map.entry("TOUR_044", HttpStatus.CONFLICT),               // LEAGUE_TRANSFER_ALREADY_DISPATCHED
            Map.entry("TOUR_045", HttpStatus.CONFLICT),               // LEAGUE_TRANSFER_NOT_DISPATCHED
            // F08.7.1/05 試合メンバー表（roster）— 採番衝突回避で 046-050 に再配置（旧 038-042）
            Map.entry("TOUR_046", HttpStatus.FORBIDDEN),              // ROSTER_TEAM_NOT_IN_MATCH（対戦当事者でない）
            Map.entry("TOUR_047", HttpStatus.FORBIDDEN),              // ROSTER_EDIT_FORBIDDEN（自チーム ADMIN/DEPUTY 限定）
            Map.entry("TOUR_048", HttpStatus.CONFLICT),               // ROSTER_DEADLINE_PASSED（締切後ロック・409）
            Map.entry("TOUR_049", HttpStatus.FORBIDDEN),              // ROSTER_MANAGE_FORBIDDEN（主催組織 ADMIN 限定）
            Map.entry("TOUR_050", HttpStatus.NOT_FOUND),              // UNIFORM_SET_NOT_FOUND（IDOR 対策で 404）
            Map.entry("TOUR_051", HttpStatus.NOT_FOUND),              // SUBMISSION_REQ_NOT_FOUND（IDOR 対策で 404）
            Map.entry("TOUR_052", HttpStatus.FORBIDDEN),              // SUBMISSION_REQ_MANAGE_FORBIDDEN
            Map.entry("TOUR_053", HttpStatus.FORBIDDEN),              // SUBMISSION_REQ_VIEW_FORBIDDEN
            Map.entry("TOUR_054", HttpStatus.FORBIDDEN),              // SUBMISSION_SUBMIT_FORBIDDEN
            Map.entry("TOUR_055", HttpStatus.UNPROCESSABLE_ENTITY),   // SUBMISSION_TEAM_NOT_TARGET
            Map.entry("TOUR_056", HttpStatus.UNPROCESSABLE_ENTITY),   // SUBMISSION_DEADLINE_PASSED
            Map.entry("TOUR_057", HttpStatus.UNPROCESSABLE_ENTITY),   // SUBMISSION_PAYMENT_REQUIRED
            Map.entry("TOUR_058", HttpStatus.UNPROCESSABLE_ENTITY),   // SUBMISSION_TEMPLATE_SCOPE_MISMATCH
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
            // F17 Phase 3-β-E — 村ニュースレター（VILLAGE_078〜080）
            Map.entry("VILLAGE_078", HttpStatus.NOT_FOUND),            // NEWSLETTER_NOT_FOUND
            Map.entry("VILLAGE_079", HttpStatus.CONFLICT),             // NEWSLETTER_ALREADY_OPTED_OUT
            Map.entry("VILLAGE_080", HttpStatus.CONFLICT),             // NEWSLETTER_NOT_OPTED_OUT
            // F17.1 村掲示板グローバル方式 — 掲示板閲覧認可（VILLAGE_081）
            Map.entry("VILLAGE_081", HttpStatus.FORBIDDEN),            // VILLAGE_BULLETIN_VIEW_FORBIDDEN
            // F17.1 村掲示板グローバル方式 — モデレーション認可（VILLAGE_082）
            Map.entry("VILLAGE_082", HttpStatus.FORBIDDEN),            // VILLAGE_BULLETIN_MODERATE_FORBIDDEN

            // F17 Phase 3-β — 村史（VILLAGE_075）
            Map.entry("VILLAGE_075", HttpStatus.NOT_FOUND),            // CHRONICLE_NOT_FOUND
            // F17 Phase 3-β — ご縁スコア（VILLAGE_076）
            Map.entry("VILLAGE_076", HttpStatus.NOT_FOUND),            // SERENDIPITY_NOT_FOUND
            // F17 Phase 3-β — 巡礼（VILLAGE_077）
            Map.entry("VILLAGE_077", HttpStatus.NOT_FOUND),            // PILGRIMAGE_NOT_FOUND

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
            // F18 Phase 4 第三陣 S3 — 同義語管理 UI
            Map.entry("POINT_CARD_021", HttpStatus.CONFLICT),          // SYNONYM_DUPLICATE
            // F18 Phase 5 — 残高 Permission 駆動化（POINT_CARD_022〜023）
            Map.entry("POINT_CARD_022", HttpStatus.FORBIDDEN),         // BALANCE_OPERATE_PERMISSION_REQUIRED
            Map.entry("POINT_CARD_023", HttpStatus.FORBIDDEN),         // BALANCE_REFUND_PERMISSION_REQUIRED
            // F18 SELF_ISSUED_BALANCE 凍結（資金決済法対応・2026-05-17 マスター御裁可）
            Map.entry("POINT_CARD_024", HttpStatus.SERVICE_UNAVAILABLE), // BALANCE_SERVICE_DISABLED
            // F02.9 お気に入りウィジェット
            Map.entry("FAV_001", HttpStatus.CONFLICT),                  // ALREADY_REGISTERED（重複登録）
            Map.entry("FAV_002", HttpStatus.UNPROCESSABLE_ENTITY),      // LIMIT_EXCEEDED（上限20件超過）
            Map.entry("FAV_003", HttpStatus.NOT_FOUND),                 // ENTITY_NOT_FOUND（IDOR対策で404）
            Map.entry("FAV_004", HttpStatus.FORBIDDEN),                 // ACCESS_DENIED（他ユーザーお気に入り）
            Map.entry("FAV_005", HttpStatus.BAD_REQUEST),               // INVALID_ENTITY_TYPE
            Map.entry("FAV_006", HttpStatus.BAD_REQUEST),               // INVALID_ENTITY_ID
            // F09.17 Phase 11-a モデレーション (AD_CAMPAIGN_NOT_FOUND は §1 Campaign 域で定義済)
            Map.entry("AD_CAMPAIGN_NOT_REVIEWABLE", HttpStatus.BAD_REQUEST),  // 審査対象外状態
            Map.entry("AD_CAMPAIGN_ALREADY_BLOCKED", HttpStatus.CONFLICT),    // 既に BLOCKED
            // F09.17 残課題 3 UNBLOCK
            Map.entry("AD_CAMPAIGN_NOT_UNBLOCKABLE", HttpStatus.BAD_REQUEST), // status != BLOCKED で UNBLOCK 試行
            // F01.10 履歴書・職務経歴書
            Map.entry("RESUME_001", HttpStatus.NOT_FOUND),            // RESUME_NOT_FOUND（IDOR 対策で 404）
            Map.entry("RESUME_006", HttpStatus.PAYLOAD_TOO_LARGE),    // PHOTO_SIZE_EXCEEDED (413)
            Map.entry("RESUME_007", HttpStatus.UNSUPPORTED_MEDIA_TYPE), // PHOTO_UNSUPPORTED_FORMAT (415)
            Map.entry("RESUME_008", HttpStatus.TOO_MANY_REQUESTS),    // EXPORT_RATE_LIMITED (429)
            Map.entry("RESUME_009", HttpStatus.INTERNAL_SERVER_ERROR), // REPORT_GENERATION_FAILED (500)
            Map.entry("RESUME_010", HttpStatus.CONFLICT),             // OPTIMISTIC_LOCK_CONFLICT (409)
            // F05.1 掲示板
            Map.entry("BULLETIN_001", HttpStatus.NOT_FOUND),          // CATEGORY_NOT_FOUND
            Map.entry("BULLETIN_002", HttpStatus.NOT_FOUND),          // THREAD_NOT_FOUND
            Map.entry("BULLETIN_003", HttpStatus.NOT_FOUND),          // REPLY_NOT_FOUND
            Map.entry("BULLETIN_004", HttpStatus.LOCKED),             // THREAD_LOCKED (423)
            Map.entry("BULLETIN_005", HttpStatus.CONFLICT),           // THREAD_ARCHIVED (409)
            Map.entry("BULLETIN_006", HttpStatus.FORBIDDEN),          // INSUFFICIENT_POST_ROLE
            Map.entry("BULLETIN_007", HttpStatus.NOT_FOUND),          // ATTACHMENT_NOT_FOUND
            Map.entry("BULLETIN_008", HttpStatus.NOT_FOUND),          // REACTION_NOT_FOUND
            Map.entry("BULLETIN_009", HttpStatus.CONFLICT),           // DUPLICATE_REACTION
            Map.entry("BULLETIN_010", HttpStatus.CONFLICT),           // DUPLICATE_CATEGORY_NAME
            Map.entry("BULLETIN_011", HttpStatus.FORBIDDEN),          // NOT_AUTHOR
            Map.entry("BULLETIN_013", HttpStatus.BAD_REQUEST),        // INVALID_EMOJI
            Map.entry("BULLETIN_014", HttpStatus.FORBIDDEN),          // SAFETY_THREAD_DELETE_FORBIDDEN
            // F05.1 保管庫フォルダ
            Map.entry("BULLETIN_016", HttpStatus.NOT_FOUND),          // ARCHIVE_FOLDER_NOT_FOUND
            Map.entry("BULLETIN_017", HttpStatus.BAD_REQUEST),        // ARCHIVE_FOLDER_DEPTH_EXCEEDED
            Map.entry("BULLETIN_018", HttpStatus.BAD_REQUEST),        // ARCHIVE_FOLDER_CYCLE
            Map.entry("BULLETIN_019", HttpStatus.CONFLICT),           // ARCHIVE_FOLDER_LIMIT_EXCEEDED
            Map.entry("BULLETIN_020", HttpStatus.CONFLICT),           // ARCHIVE_FOLDER_SCOPE_MISMATCH
            Map.entry("BULLETIN_021", HttpStatus.CONFLICT),           // THREAD_NOT_ARCHIVED
            // F21.1 §5.5 FAQ駆動GEO（FAQ_001〜005 はバリデーション = Severity.WARN 既定 400 / FAQ_010 は IDOR 対策で 404）
            Map.entry("FAQ_010", HttpStatus.NOT_FOUND),               // 対象チーム / 組織が存在しない（IDOR 対策で 404）
            // F22.1 横スワイプ・ダッシュボード scope-tabs（02_api_design.md §4）
            Map.entry("SCOPE_TAB_001", HttpStatus.FORBIDDEN),         // 非所属スコープ混入 → 全体拒否
            Map.entry("SCOPE_TAB_002", HttpStatus.BAD_REQUEST),      // sortOrder 重複 / 範囲外
            Map.entry("SCOPE_TAB_003", HttpStatus.BAD_REQUEST),      // scopeType 不正
            Map.entry("SCOPE_TAB_004", HttpStatus.NOT_FOUND),         // フォルダ不在 / 他人所有（存在隠蔽）
            // F22.1 市（Market）（02_api_design.md §8）
            Map.entry("MARKET_001", HttpStatus.BAD_REQUEST),         // 地域コード不正 / 不整合
            Map.entry("MARKET_002", HttpStatus.BAD_REQUEST),         // フレンド宛先 0 件
            Map.entry("MARKET_003", HttpStatus.FORBIDDEN),           // フレンド未成立チームを宛先指定
            Map.entry("MARKET_004", HttpStatus.FORBIDDEN),           // 他チーム所有フォルダを宛先指定
            Map.entry("MARKET_005", HttpStatus.BAD_REQUEST),         // FRIEND_TEAMS_ONLY × distribution_targets 併用
            Map.entry("MARKET_404", HttpStatus.NOT_FOUND),           // 非公開 / 不在の札（存在秘匿）
            // F03.11 / F22.1 募集枠 公開（publish）時の配信対象検証
            //   いずれも「入力不備」であり 400（MARKET_002 と対称）。未登録だと Severity.ERROR 既定の 500 になり、
            //   PUBLIC 札の publish 失敗がフロントへ 500 として漏れる（実機 CRUD E2E で発覚）ため明示登録する。
            Map.entry("RECRUITMENT_204", HttpStatus.BAD_REQUEST),    // 配信対象 0 件（EMPTY_DISTRIBUTION_TARGETS）
            Map.entry("RECRUITMENT_207", HttpStatus.BAD_REQUEST),    // visibility と配信対象の不整合（VISIBILITY_TARGETS_INCONSISTENT）
            // F04.11 統合通知インボックス（02_api_design.md §3.6）
            Map.entry("INBOX_LABEL_NOT_FOUND", HttpStatus.NOT_FOUND),           // ラベル不在 / 他人ラベル（IDOR 秘匿）
            Map.entry("INBOX_SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND),          // triage 対象通知が不在 / 本人宛てでない
            Map.entry("INBOX_LABEL_NAME_DUPLICATE", HttpStatus.CONFLICT),       // 現役同名ラベル
            Map.entry("INBOX_LABEL_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY),    // ラベル 20 件上限超過
            Map.entry("INBOX_LABEL_PER_ITEM_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY),  // 1 通知 10 ラベル上限超過
            // F22.1 謝礼決済 Connect / エスクロー（02_api_design.md §7）
            //   Severity 既定（WARN=400 / ERROR=500）では設計の 403/404/409/422 を満たせないため明示登録する（#1279 前科）。
            Map.entry("PAYMENT_C001", HttpStatus.FORBIDDEN),                  // 認可エラー / IDOR
            Map.entry("PAYMENT_C002", HttpStatus.NOT_FOUND),                 // escrow / connect 不在・scope 不一致で秘匿
            Map.entry("PAYMENT_C010", HttpStatus.UNPROCESSABLE_ENTITY),      // PRICE_REQUIRED
            Map.entry("PAYMENT_C011", HttpStatus.UNPROCESSABLE_ENTITY),      // PAYEE_REQUIRED
            Map.entry("PAYMENT_C012", HttpStatus.UNPROCESSABLE_ENTITY),      // PAYEE_USER_REQUIRED
            Map.entry("PAYMENT_C013", HttpStatus.UNPROCESSABLE_ENTITY),      // PAYEE_NOT_IN_SCOPE
            Map.entry("PAYMENT_C020", HttpStatus.CONFLICT),                  // ALREADY_REFUNDED
            Map.entry("PAYMENT_C021", HttpStatus.UNPROCESSABLE_ENTITY),      // REFUND_AMOUNT_EXCEEDS
            Map.entry("PAYMENT_C030", HttpStatus.CONFLICT),                  // ONBOARDING_NOT_READY
            Map.entry("PAYMENT_C040", HttpStatus.BAD_REQUEST),               // WEBHOOK_SIGNATURE_INVALID
            Map.entry("PAYMENT_C041", HttpStatus.CONFLICT),                  // AUTHORIZATION_FAILED
            Map.entry("PAYMENT_C042", HttpStatus.CONFLICT),                  // INVALID_ESCROW_STATE（払出不能状態）
            Map.entry("PAYMENT_C043", HttpStatus.CONFLICT),                  // CAPTURE_FAILED（払出失敗）
            Map.entry("PAYMENT_C044", HttpStatus.CONFLICT),                  // AUTHORIZATION_NOT_CONFIRMED（札主 confirm 前の capture 拒否・第一陣）
            Map.entry("PAYMENT_C060", HttpStatus.UNPROCESSABLE_ENTITY),      // FEE_EXCEEDS_FACE_AMOUNT（安全ガード・R1・C050/C051-3 と衝突回避）
            Map.entry("PAYMENT_C051", HttpStatus.NOT_FOUND),                 // FEE_POLICY_NOT_FOUND（シスアド CRUD・R2・§11）
            Map.entry("PAYMENT_C052", HttpStatus.CONFLICT),                  // FEE_POLICY_DEFAULT_IMMUTABLE（DEFAULT 削除/無効化禁止・R2）
            Map.entry("PAYMENT_C053", HttpStatus.UNPROCESSABLE_ENTITY),      // FEE_POLICY_INVALID_RATE（率/固定額/キー形式不正・R2）
            Map.entry("PAYMENT_C054", HttpStatus.CONFLICT),                  // FEE_POLICY_ALREADY_EXISTS（POST 重複・PUT へ誘導・R2）
            Map.entry("PAYMENT_C055", HttpStatus.CONFLICT),                  // FEE_POLICY_ASSIGNMENT_DUPLICATE（割当 UNIQUE 違反・R2）
            Map.entry("PAYMENT_C056", HttpStatus.UNPROCESSABLE_ENTITY),      // FEE_POLICY_ASSIGNMENT_POLICY_DISABLED（割当先 policy 無効・R2）
            // F08.9 会費課金・代理払い認可（03_security.md §2）
            //   PAYMENT_Cxxx（F22.1）と衝突しない独立プレフィックス MEMBERSHIP_BILLING_xxx を採用。
            //   Severity.WARN 既定（400）では設計の 403/409 を満たせないため明示登録する。
            Map.entry("MEMBERSHIP_BILLING_001", HttpStatus.FORBIDDEN),       // 代理払い権原なし / IDOR
            Map.entry("MEMBERSHIP_BILLING_002", HttpStatus.CONFLICT),        // 既に有効な支払いが存在（二重課金防止）
            Map.entry("MEMBERSHIP_BILLING_003", HttpStatus.FORBIDDEN),       // 後見切替中の認証クリティカル操作（03_security §3.2 なりすまし防止: パスワード/メール/2FA/退会の代理禁止）
            Map.entry("MEMBERSHIP_BILLING_004", HttpStatus.FORBIDDEN),       // 後見切替の年齢ゲート封印（02_api §2.2 GUARDIANSHIP_SWITCH_AGE_LOCKED）
            Map.entry("MEMBERSHIP_BILLING_005", HttpStatus.FORBIDDEN),       // 後見切替の保護者リンクなし（02_api §2.2 GUARDIANSHIP_LINK_NOT_FOUND / IDOR）
            Map.entry("MEMBERSHIP_BILLING_006", HttpStatus.BAD_REQUEST),     // 自立移行の引き継ぎに子メールが必要 / 既存メール上書き拒否（02_api §2.3 handover/initiate）
            Map.entry("MEMBERSHIP_BILLING_007", HttpStatus.NOT_FOUND),       // 協会請求が見つからない / IDOR 秘匿（02_api §7 PAYMENT_REQUEST_NOT_FOUND）
            Map.entry("MEMBERSHIP_BILLING_008", HttpStatus.CONFLICT),        // 協会請求が現在の状態では操作不可（取消/支払いの状態制約）
            Map.entry("MEMBERSHIP_BILLING_009", HttpStatus.CONFLICT),        // 協会請求が支払い済み（二重支払い防止・02_api §7 PAYMENT_REQUEST_ALREADY_PAID）
            Map.entry("MEMBERSHIP_BILLING_010", HttpStatus.CONFLICT),        // 協会請求の着金口座が未 READY（支払い時に検証・02_api §11）
            Map.entry("MEMBERSHIP_BILLING_011", HttpStatus.FORBIDDEN),       // 協会請求の支払い権限なし / 請求先チーム不一致（IDOR・02_api §7 PAYMENT_REQUEST_NOT_FOR_THIS_TEAM）
            Map.entry("MEMBERSHIP_BILLING_012", HttpStatus.NOT_FOUND),       // 立替記録が見つからない / IDOR 秘匿
            Map.entry("MEMBERSHIP_BILLING_013", HttpStatus.CONFLICT),        // 立替が既に精算済み（重複確認防止・02_api §10 ADVANCE_ALREADY_SETTLED）
            Map.entry("MEMBERSHIP_BILLING_014", HttpStatus.CONFLICT),        // 協会請求の配信で受信者ゼロ（チーム ADMIN 不在・02_api §7 PAYMENT_REQUEST_NO_RECIPIENTS / INVALID_STATUS と分離）
            // F08.9 P5 継続課金（02_api §4）
            Map.entry("MEMBERSHIP_BILLING_015", HttpStatus.NOT_FOUND),       // 継続課金が見つからない / IDOR 秘匿（02_api §4 SUBSCRIPTION_NOT_FOUND）
            Map.entry("MEMBERSHIP_BILLING_016", HttpStatus.CONFLICT),        // 継続課金が ACTIVE でない（スキップ/再開不可・02_api §4.3 SUBSCRIPTION_NOT_ACTIVE）
            Map.entry("MEMBERSHIP_BILLING_017", HttpStatus.CONFLICT),        // 継続課金が既に今月スキップ済み（二重スキップ防止・02_api §4.3 SUBSCRIPTION_ALREADY_SKIPPED）
            Map.entry("MEMBERSHIP_BILLING_018", HttpStatus.FORBIDDEN),       // 継続課金の操作者がサブスク所有者でない（IDOR・03_security §1 SUBSCRIPTION_NOT_AUTHORIZED）
            Map.entry("MEMBERSHIP_BILLING_019", HttpStatus.CONFLICT),        // 加入対象が継続課金項目でない（02_api §4.1 SUBSCRIPTION_ITEM_NOT_RECURRING）
            Map.entry("MEMBERSHIP_BILLING_020", HttpStatus.CONFLICT),        // 継続課金加入に支払い方法が未保存（SetupIntent 導線へ・02_api §4.1 SUBSCRIPTION_PAYMENT_METHOD_NOT_SAVED）
            Map.entry("MEMBERSHIP_BILLING_021", HttpStatus.CONFLICT),        // 同一受益者・項目に有効な継続課金が既存（二重加入防止・02_api §4.1 SUBSCRIPTION_ALREADY_EXISTS）
            Map.entry("MEMBERSHIP_BILLING_022", HttpStatus.CONFLICT),        // 継続課金がスキップ中でないため再開できない（02_api §4.3 SUBSCRIPTION_NOT_SKIPPED）
            Map.entry("MEMBERSHIP_BILLING_023", HttpStatus.PAYMENT_REQUIRED), // 保存済みカードが off-session 初回課金に使えない（R2-1・02_api §4.1 SUBSCRIPTION_OFF_SESSION_AUTHENTICATION_REQUIRED）
            // セキュリティインシデント（GDPR Article 33）
            Map.entry("SEC_INCIDENT_001", HttpStatus.NOT_FOUND),             // SECURITY_INCIDENT_NOT_FOUND（IDOR 対策で 404）
            // F08.10 試合記録・分析（03 §C.4/C.6: 不在/越境/親子不一致は 404、権限不足は 403、検証系は 400）
            Map.entry("MATCH_001", HttpStatus.NOT_FOUND),                    // 試合不在 / テナント越境 / 削除済み（IDOR 秘匿）
            Map.entry("MATCH_002", HttpStatus.NOT_FOUND),                    // イベント不在 / 親子 match_id 不一致
            Map.entry("MATCH_003", HttpStatus.NOT_FOUND),                    // 出場記録不在 / 親子 match_id 不一致
            Map.entry("MATCH_010", HttpStatus.FORBIDDEN),                    // 操作権限なし
            Map.entry("MATCH_020", HttpStatus.BAD_REQUEST),                  // event_type がカタログ外
            Map.entry("MATCH_021", HttpStatus.BAD_REQUEST),                  // card_reason_code 不正
            Map.entry("MATCH_022", HttpStatus.NOT_FOUND),                    // linked_event_id 越境（親子不一致 → 404 統一）
            Map.entry("MATCH_023", HttpStatus.BAD_REQUEST),                  // COMPLETED に duration_minutes 必須
            Map.entry("MATCH_024", HttpStatus.BAD_REQUEST),                  // 入力値が業務範囲外
            Map.entry("MATCH_025", HttpStatus.FORBIDDEN),                    // team_side↔recorded_by_team_id 不整合（自名義捏造防止・03 §C.4a）
            // F08.10 6-④a ターン制（将棋/囲碁）＋団体戦（01 §B.6 / §D.7・検証系 400 / 不在・親子不一致 404）
            // MATCH_026/MATCH_027（COMPLETED 不可）・MATCH_028（win_method 不正）・MATCH_029（競技不一致）・
            // MATCH_032/033/034（局面写真の MIME/サイズ/件数）は Severity.WARN デフォルト 400 に従う（明示不要）。
            Map.entry("MATCH_030", HttpStatus.NOT_FOUND),                    // 団体戦ボードの帰属不一致 / 親非団体戦 / 親子テナント不整合（IDOR 秘匿）
            Map.entry("MATCH_031", HttpStatus.NOT_FOUND),                    // 局面写真不在 / 親子 match_id 不一致（IDOR 秘匿）
            // チーム/組織 ユーザー任意 slug（村方式統一）。形式不正・予約語は 422、重複は 409
            Map.entry("TEAM_060", HttpStatus.UNPROCESSABLE_ENTITY),          // SLUG_INVALID_FORMAT
            Map.entry("TEAM_061", HttpStatus.UNPROCESSABLE_ENTITY),          // SLUG_RESERVED
            Map.entry("TEAM_062", HttpStatus.CONFLICT),                      // SLUG_ALREADY_TAKEN
            Map.entry("ORG_060", HttpStatus.UNPROCESSABLE_ENTITY),           // SLUG_INVALID_FORMAT
            Map.entry("ORG_061", HttpStatus.UNPROCESSABLE_ENTITY),           // SLUG_RESERVED
            Map.entry("ORG_062", HttpStatus.CONFLICT)                        // SLUG_ALREADY_TAKEN
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
     * 不正な HTTP メソッド（PUT に対して GET でアクセスするなど）。
     * デフォルトでは Spring が {@link Exception} に流して 500 を返してしまうため、
     * 明示的に 405 METHOD_NOT_ALLOWED へマッピングする。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("メソッド不一致: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_004));
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
     * Spring Security の @PreAuthorize / @PostAuthorize が返す認可拒否例外。
     *
     * <p>Spring Security 6.x では {@code @PreAuthorize} が失敗すると
     * {@code AuthorizationDeniedException}（{@code AccessDeniedException} のサブクラス）が投げられる。
     * {@code ExceptionTranslationFilter} より前に Spring MVC の {@code @RestControllerAdvice} が
     * 捕捉すると 500 になる既知問題のため、ここで明示的に 403 に変換する。</p>
     *
     * <p>SecurityConfig の {@code accessDeniedHandler} はフィルターチェーン外の例外には到達しないため
     * 二重防御として本ハンドラーが必要。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex, HttpServletRequest request) {
        log.debug("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_002));
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
