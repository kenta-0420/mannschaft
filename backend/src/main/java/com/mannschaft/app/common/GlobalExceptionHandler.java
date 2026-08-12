package com.mannschaft.app.common;

import com.mannschaft.app.billing.FeatureNotEntitledException;
import com.mannschaft.app.billing.api.dto.FeatureNotEntitledErrorResponse;
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
import org.springframework.http.HttpStatusCode;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
            // F03.17 キープ（日付未定の予定）: 認可の 2 コードを宣言どおりの status に上書きする。
            //  - SCHEDULE_KEEP_001 不在・スコープ不一致（IDOR）・非メンバー・応援者を畳んで 404
            //    （403 だと「そのチームにそのキープがある」ことが漏れる。設計書 §4.6.3）
            //  - SCHEDULE_KEEP_005 閲覧はできるが編集権限が無い（作成者でも ADMIN でもない）→ 403
            //  - SCHEDULE_KEEP_006/007/009 状態遷移違反 → 409（設計書 §5.3・§7）
            //  - SCHEDULE_KEEP_008 revert が出欠回答に阻まれる → 409
            //  - SCHEDULE_KEEP_010 スコープあたりの件数上限超過 → 409
            Map.entry("SCHEDULE_KEEP_001", HttpStatus.NOT_FOUND),
            Map.entry("SCHEDULE_KEEP_005", HttpStatus.FORBIDDEN),
            Map.entry("SCHEDULE_KEEP_006", HttpStatus.CONFLICT),
            Map.entry("SCHEDULE_KEEP_007", HttpStatus.CONFLICT),
            Map.entry("SCHEDULE_KEEP_008", HttpStatus.CONFLICT),
            Map.entry("SCHEDULE_KEEP_009", HttpStatus.CONFLICT),
            Map.entry("SCHEDULE_KEEP_010", HttpStatus.CONFLICT),
            // F08.1 マッチング: 認可拒否は 403（Severity.WARN 既定の 400 を上書き）。
            //  - MATCHING_010 権限不足（募集の編集/取り下げ・サービス内認可）
            //  - MATCHING_014 レビュー権限なし（対戦非参加/参加チームの管理者でない）
            Map.entry("MATCHING_010", HttpStatus.FORBIDDEN),
            Map.entry("MATCHING_014", HttpStatus.FORBIDDEN),
            // 認可監査 Wave6 ロットC: F08.1 マッチングの残り未登録分。
            //  - MATCHING_001/002 は不在・NGチームブロック・非OPEN非所属を同一コードで返す存在秘匿 → 404
            //    （MatchRequestService.getRequest 114-121行 実測。IDOR/存在秘匿）
            //  - MATCHING_015/018 は自チーム内リソースの単純な not-found → 404
            //  - MATCHING_007 NGチームブロックによる応募拒否 → 403
            //  - MATCHING_003/006/008/009/012/017/021/024 は状態競合 → 409
            //  - MATCHING_013 レビュー投稿期限切れ → 410（COMMITTEE_INVITATION_EXPIRED 等と同流儀）
            Map.entry("MATCHING_001", HttpStatus.NOT_FOUND),
            Map.entry("MATCHING_002", HttpStatus.NOT_FOUND),
            Map.entry("MATCHING_003", HttpStatus.CONFLICT),
            Map.entry("MATCHING_005", HttpStatus.CONFLICT),
            Map.entry("MATCHING_006", HttpStatus.CONFLICT),
            Map.entry("MATCHING_007", HttpStatus.FORBIDDEN),
            Map.entry("MATCHING_008", HttpStatus.CONFLICT),
            Map.entry("MATCHING_009", HttpStatus.CONFLICT),
            Map.entry("MATCHING_012", HttpStatus.CONFLICT),
            Map.entry("MATCHING_013", HttpStatus.GONE),
            Map.entry("MATCHING_015", HttpStatus.NOT_FOUND),
            Map.entry("MATCHING_017", HttpStatus.CONFLICT),
            Map.entry("MATCHING_018", HttpStatus.NOT_FOUND),
            Map.entry("MATCHING_021", HttpStatus.CONFLICT),
            Map.entry("MATCHING_024", HttpStatus.CONFLICT),
            // F00.5 メンバーシップ基盤: サポーター受け入れ無効スコープへの自己登録拒否は 403
            //（Severity.WARN 既定の 400 を上書き。認可根治 Wave6 でサポーター登録ゲートに使用）
            Map.entry("MEMBERSHIP_SUPPORTER_DISABLED", HttpStatus.FORBIDDEN),
            // 未認証は 401 を返す（Severity.WARN のデフォルト 400 を上書き）
            Map.entry(CommonErrorCode.COMMON_000.getCode(), HttpStatus.UNAUTHORIZED),
            Map.entry(CommonErrorCode.COMMON_002.getCode(), HttpStatus.FORBIDDEN),
            Map.entry(CommonErrorCode.COMMON_003.getCode(), HttpStatus.CONFLICT),
            // 未マップAPIパス・staticリソース不在は 404（Severity.WARN デフォルト 400 を上書き）
            Map.entry(CommonErrorCode.COMMON_005.getCode(), HttpStatus.NOT_FOUND),
            // F15.4 Phase 5-α: 店舗詳細 Public API（IDOR対策で 404）
            Map.entry("TEAM_001", HttpStatus.NOT_FOUND),
            // 組織不在は 404（Severity.WARN 既定の 400 を上書き）。兄弟の TEAM_001 と流儀を揃える。
            // 認可根治 Wave6: 組織 ID をリクエストボディで受ける経路で「不在は 404 秘匿」を成立させるため必須。
            Map.entry("ORG_001", HttpStatus.NOT_FOUND),
            // F10.1 目安箱: フィードバック不在は 404（Severity.WARN 既定の 400 を上書き）。
            // 認可根治 Wave5 で AdminFeedbackController が「別スコープのフィードバック」を
            // 存在秘匿する際にも本コードを使うため、404 への正規化が必須。
            Map.entry("ADMIN_FB_003", HttpStatus.NOT_FOUND),
            // F01.3 テンプレート/モジュール: モジュール不在は「見つからない」ため 404
            //（Severity.WARN 既定の 400 を上書き。SYSTEM_ADMIN トグル API 等で正しい status を返す）
            Map.entry("TMPL_002", HttpStatus.NOT_FOUND),
            // F04.1 タイムライン: 投稿不在は 404（Severity.WARN 既定の 400 を上書き）。
            // 認可根治 Wave3-B7 以降、越境アクセスは「存在しない」と同じ TIMELINE_001 に倒して
            // 対象 ID の実在を秘匿する設計になっているが、status 未登録のため実際には 400 が
            // 返っており、コード内 Javadoc の「404 相当」という記述と乖離していた（認可根治 Wave6 で是正）。
            Map.entry("TIMELINE_001", HttpStatus.NOT_FOUND),
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
            // F06.4 公開活動記録: 不在 / 非公開 / DRAFT / 削除済み / 親スコープ非公開 / スコープ詐称を
            // すべて 404 に倒して存在秘匿する（403 を返すと存在オラクルになる）
            Map.entry("PUBLIC_013", HttpStatus.NOT_FOUND),
            Map.entry("AD_006", HttpStatus.CONFLICT),
            Map.entry("AD_007", HttpStatus.CONFLICT),
            Map.entry("AD_010", HttpStatus.FORBIDDEN),
            // F09.19.1b: キャンペーン不在は「見つからない」ため 404（審査詳細等。Severity.WARN 既定 400 を上書き）
            Map.entry("AD_021", HttpStatus.NOT_FOUND),
            // F09.19.1 運用型キャンペーン CRUD（正本 §15）: Severity.WARN 既定の 400 を上書き
            Map.entry("AD_027", HttpStatus.CONFLICT),          // 状態遷移違反・編集不可状態/フィールド → 409
            Map.entry("AD_029", HttpStatus.TOO_MANY_REQUESTS), // visit/click の IP レート制限 → 429
            Map.entry("AD_033", HttpStatus.FORBIDDEN),         // 通報自動停止中の resume 拒否 → 403
            Map.entry("AD_034", HttpStatus.CONFLICT),          // 参照中料金カードの削除拒否 → 409
            Map.entry("AD_035", HttpStatus.NOT_FOUND),         // serve 証跡なし / deliveryId 帰属不一致 → 404
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
            Map.entry("AUTH_044", HttpStatus.TOO_MANY_REQUESTS), // レート制限超過 → 429
            // F01.1 リフレッシュトークン並行更新 競合制御（docs/security/06 §7）:
            //   セッション失効系は 401 で返さないとクライアントが再ログインへ遷移できない
            //   （Severity.WARN 既定の 400 を上書き）。
            Map.entry("AUTH_026", HttpStatus.UNAUTHORIZED), // リプレイ検出・全セッション無効化 → 401
            Map.entry("AUTH_039", HttpStatus.UNAUTHORIZED), // 全デバイスセッション無効化後のアクセス → 401
            // AUTH_007（リフレッシュトークンが無効／リボーク済み）も「認証情報が無効」の意味論であり、
            // 兄弟の AUTH_026 / AUTH_039 と同じく 401 が正しい（Severity.WARN 既定の 400 を上書き）。
            // 400 のままだと監視・アラートで認証失敗として集計できず、クライアント側も
            // 「400 も認証失敗とみなす」特例分岐で補償せざるを得なかった。
            // 使用箇所は AuthTokenRotationService（refresh フロー）のみで他ドメインへの巻き添えは無い。
            Map.entry("AUTH_007", HttpStatus.UNAUTHORIZED), // refresh_token 無効/失効/不在 → 401
            // ErrorCode ステータス写像是正ロットA: AuthTokenService のアクセストークン検証は
            // AUTH_007/026/039 と同じ「認証情報が無効」の意味論であり、兄弟同様 401 が正しい。
            Map.entry("AUTH_036", HttpStatus.UNAUTHORIZED), // アクセストークン期限切れ → 401
            Map.entry("AUTH_037", HttpStatus.UNAUTHORIZED), // アクセストークン不正（署名不一致・フォーマット異常） → 401
            Map.entry("AUTH_038", HttpStatus.UNAUTHORIZED), // アクセストークンがブラックリスト登録済み → 401
            // 自己スコープの「不在」は 404（IDOR ではなく単純な not-found だが、既存 EP 契約と揃える）
            Map.entry("AUTH_029", HttpStatus.NOT_FOUND),    // OAuthプロバイダー未連携（解除対象が存在しない）
            // 状態競合 → 409（AUTH_032 は上記の別ブロックで既に登録済み）
            // AUTH_018 は Auth2faService 内で「TOTPコード不正」（verifyTotpCode 失敗）と
            // 「TOTPコード使用済み」（リプレイ検出）の意味の異なる 2 箇所から throw されており、
            // 定数の意味が割れているため変更を見送る（Severity.WARN 既定の 400 のまま）。
            // AUTH_021（バックアップコード全使用済み）は throw 元が存在しない未使用定数のため対象外。
            Map.entry("AUTH_025", HttpStatus.CONFLICT),     // WebAuthnデバイス重複登録
            Map.entry("AUTH_030", HttpStatus.CONFLICT),     // OAuth連携解除時ログイン手段喪失
            // AUTH_001〜044/050〜072 の残り（AUTH_007/026/033/034/039/044 と上記以外）はログイン試行・
            // トークン検証・入力バリデーションの失敗であり、Severity.WARN 既定の 400 が妥当と判定し変更なし
            // （ErrorCode ステータス写像是正ロットA 調査で確認済み。AUTH_003 は要件検討により 423/429 が
            // 候補に挙がるが、既存の返却契約を壊すリスクがあり本ロットでは見送り）。
            // F01.1 退会取消: UserService.cancelWithdrawal() は deleted_at が NULL（＝取消対象の退会申請が
            // 無い）場合に throw する。自分自身の状態に対する操作であり IDOR ではないが、
            // 「取消可能な退会申請という状態が無い」ことを理由に拒否する状態遷移違反のため、
            // 兄弟の SCHEDULE_KEEP_006 等と同じく 409 Conflict が正準（入力不備の 400 ではない）。
            Map.entry("AUTH_032", HttpStatus.CONFLICT), // 退会申請が存在しない状態での取消要求 → 409
            // F03.3 カレンダー同期: 非メンバーの同期トグルは IDOR 対策で 403 ではなく 404（存在秘匿）
            Map.entry("GCAL_010", HttpStatus.NOT_FOUND),
            // F02.5 行動メモ: IDOR 対策で 403 ではなく 404 を返す
            Map.entry("ACTION_MEMO_001", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_006", HttpStatus.NOT_FOUND),
            Map.entry("ACTION_MEMO_008", HttpStatus.NOT_FOUND),
            // F02.5 汎用タグ: スコープ不一致・不在は存在秘匿で 404。TagController の Javadoc は
            // 「他スコープの tagId を指した越境は 404」と宣言しているが未登録のため
            // Severity.WARN 既定の 400 が返っていた（宣言と実挙動の乖離）。宣言どおりに揃える。
            Map.entry("QM_010", HttpStatus.NOT_FOUND),             // TAG_NOT_FOUND（BOLA 秘匿）
            // F16 school 出席要件規程: bare id EP（update/delete）の権限不足は存在秘匿で 404（Severity.WARN 既定 400 を上書き）
            Map.entry("S030", HttpStatus.NOT_FOUND),
            // F16 school 出席要件評価: bare id EP（resolve）の権限不足・不在は存在秘匿で 404
            Map.entry("S034", HttpStatus.NOT_FOUND),
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
            // F02.3 TODO: 不在 / スコープ不一致は IDOR 対策で 403 ではなく 404 で存在秘匿する
            //（TodoService.assertTodoScope / TodoCommentService.verifyScopeAndMembership 等。
            //  Severity.WARN 既定の 400 のまま未マップだった欠陥を根治。javadoc の「404 で返す」記述と実挙動を一致させる）
            Map.entry("TODO_010", HttpStatus.NOT_FOUND),             // TODO_NOT_FOUND (IDOR/BOLA 秘匿)
            // F05.4 アンケート 督促 API（権限なしのみ 403、その他は Severity.WARN 既定の 400）
            Map.entry("SURVEY_014", HttpStatus.FORBIDDEN),           // REMIND_PERMISSION_DENIED
            // F05.4 アンケート: 不在・スコープ不一致は 404（存在秘匿）、操作権限なしは 403。
            // 設計書 F05.4 のエラー表は全 EP で「404 アンケート不存在」「403 作成者・ADMIN 以外」と
            // 宣言しているが、ERROR_CODE_STATUS_MAP に未登録のため Severity.WARN 既定の 400 が
            // 返っていた（宣言と実挙動の乖離）。認可根治 Wave7 で宣言どおりに揃える。
            Map.entry("SURVEY_001", HttpStatus.NOT_FOUND),           // SURVEY_NOT_FOUND（BOLA 秘匿を含む）
            Map.entry("SURVEY_002", HttpStatus.NOT_FOUND),           // QUESTION_NOT_FOUND
            Map.entry("SURVEY_022", HttpStatus.FORBIDDEN),           // OPERATION_PERMISSION_DENIED
            // ロットD追補: survey の残り未登録分。権限不足は 403、状態競合（期限切れ・重複回答・
            // 設問なしでの公開・督促クールダウン未経過）は 409、指定回答者が未回答なのは 404 に上書きする。
            Map.entry("SURVEY_004", HttpStatus.CONFLICT),            // INVALID_SURVEY_STATUS（現在のステータスでは実行不可 → 409）
            Map.entry("SURVEY_005", HttpStatus.CONFLICT),            // SURVEY_EXPIRED（回答期限切れ → 409）
            Map.entry("SURVEY_006", HttpStatus.CONFLICT),            // DUPLICATE_RESPONSE（既に回答済み → 409）
            Map.entry("SURVEY_007", HttpStatus.FORBIDDEN),           // NOT_TARGET_USER（配信対象外 → 403）
            Map.entry("SURVEY_010", HttpStatus.FORBIDDEN),           // RESULT_ACCESS_DENIED
            Map.entry("SURVEY_012", HttpStatus.CONFLICT),            // NO_QUESTIONS（設問なしで公開不可 → 409）
            Map.entry("SURVEY_013", HttpStatus.FORBIDDEN),           // RESPONDENTS_ACCESS_DENIED
            Map.entry("SURVEY_015", HttpStatus.CONFLICT),            // REMIND_COOLDOWN_NOT_ELAPSED（24時間未経過 → 409）
            Map.entry("SURVEY_018", HttpStatus.FORBIDDEN),           // ANONYMOUS_RESPONSE_FORBIDDEN
            Map.entry("SURVEY_019", HttpStatus.FORBIDDEN),           // RESPONSE_ACCESS_DENIED
            Map.entry("SURVEY_020", HttpStatus.NOT_FOUND),           // USER_RESPONSE_NOT_FOUND（指定ユーザーが未回答 → 404）
            Map.entry("SURVEY_021", HttpStatus.NOT_FOUND),           // SERIES_NOT_FOUND
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
            // ロットD追補: social（F04.4 ソーシャルプロフィール・フォロー）の残り未登録分。
            // ハンドル/プロフィール重複・既フォローは 409、無効化済みプロフィールは所有者以外に
            // 非公開（PROFILE_NOT_FOUND と同一の存在秘匿）のため 404、フォロー一覧非公開は 403。
            Map.entry("SOCIAL_001", HttpStatus.NOT_FOUND),         // PROFILE_NOT_FOUND
            Map.entry("SOCIAL_002", HttpStatus.CONFLICT),          // HANDLE_ALREADY_TAKEN
            Map.entry("SOCIAL_003", HttpStatus.CONFLICT),          // PROFILE_ALREADY_EXISTS
            Map.entry("SOCIAL_004", HttpStatus.NOT_FOUND),         // PROFILE_INACTIVE（所有者以外は存在秘匿 → 404）
            Map.entry("SOCIAL_006", HttpStatus.CONFLICT),          // FOLLOW_ALREADY_EXISTS
            Map.entry("SOCIAL_007", HttpStatus.NOT_FOUND),         // FOLLOW_NOT_FOUND
            Map.entry("SOCIAL_009", HttpStatus.FORBIDDEN),         // FOLLOW_LIST_NOT_PUBLIC（対象ユーザーの存在は既知・一覧のみ非公開）
            Map.entry("SOCIAL_010", HttpStatus.NOT_FOUND),         // FOLLOW_USER_NOT_FOUND
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
            // F03.6 緊急安否確認 / 認可根治 Wave7: bare id EP（詳細・テンプレート・フォローアップ）は
            // 権限が無い場合も不在と同じ 404 に収束させて存在秘匿する。Severity.WARN 既定の 400 のままだと
            // 「404 で秘匿したつもり」が看板倒れになるため、ここで明示的に上書きする。
            Map.entry("SAFETY_001", HttpStatus.NOT_FOUND),           // SAFETY_CHECK_NOT_FOUND（IDOR 秘匿）
            Map.entry("SAFETY_006", HttpStatus.NOT_FOUND),           // TEMPLATE_NOT_FOUND（IDOR 秘匿）
            Map.entry("SAFETY_008", HttpStatus.NOT_FOUND),           // FOLLOWUP_NOT_FOUND（IDOR 秘匿）
            Map.entry("SAFETY_010", HttpStatus.FORBIDDEN),           // ACCESS_DENIED（スコープ宣言型 EP の権限不足）
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
            // F03.4 スケジュール本体・クロスチーム招待・アンケート設問の不在 → 404。
            // ErrorCodeHttpStatusDeclarationGuardTest 是正（ロットA）: throw 元
            // （ScheduleService/ScheduleRecurrenceService/ScheduleAttendanceService/
            //   PersonalScheduleService/ScheduleCrossRefService/EventSurveyService）を確認し、
            // いずれも findById 系の解決失敗のみで throw される（SCHEDULE_NOT_FOUND は teamId 非スコープの
            // 単純な findById であり、越境アクセスは別途 checkScopeAdminAccess 等が 403 で弾く）。
            Map.entry("SCHEDULE_001", HttpStatus.NOT_FOUND),                // SCHEDULE_NOT_FOUND
            Map.entry("SCHEDULE_010", HttpStatus.NOT_FOUND),                // CROSS_INVITE_NOT_FOUND
            Map.entry("SCHEDULE_013", HttpStatus.NOT_FOUND),                // SURVEY_NOT_FOUND
            // スケジュール状態競合（キャンセル済みへの操作・クロスチーム招待重複）→ 409。
            // throw 元（ScheduleService.cancel/PersonalScheduleService.cancel/ScheduleCrossRefService）は
            // いずれも既存状態を理由に拒否するガードであり、入力値自体の不備ではない。
            Map.entry("SCHEDULE_005", HttpStatus.CONFLICT),                 // SCHEDULE_ALREADY_CANCELLED
            Map.entry("SCHEDULE_009", HttpStatus.CONFLICT),                 // CROSS_INVITE_ALREADY_EXISTS
            // TodoScheduleLinkService: TODO-スケジュール連携の重複 → 409（TODO_032/033 と同型）。
            Map.entry("SCHEDULE_051", HttpStatus.CONFLICT),                 // TODO_ALREADY_LINKED
            // SCHEDULE_006（ALREADY_COMPLETED）/031（GOOGLE_CALENDAR_ALREADY_CONNECTED）/
            // 040（ICAL_TOKEN_NOT_FOUND）/060（MEDIA_NOT_FOUND）/061・062（MEDIA_FORBIDDEN）は
            // 定義のみで throw 元が存在しない未使用定数のため対象外（既定 400 のまま）。
            // ErrorCode ステータス写像是正ロットA: Schedule 残り。所有権チェック（403）と、
            // 件数上限・期限超過・状態遷移系（既存状態に依存し入力値自体の不備ではない → 409）。
            Map.entry("SCHEDULE_022", HttpStatus.FORBIDDEN),                // NOT_SCHEDULE_OWNER
            Map.entry("SCHEDULE_004", HttpStatus.CONFLICT),                 // ATTENDANCE_DEADLINE_PASSED
            Map.entry("SCHEDULE_007", HttpStatus.CONFLICT),                 // MAX_SURVEYS_EXCEEDED
            // SCHEDULE_008（MAX_REMINDERS_EXCEEDED）は兄弟の RESERVATION_015（enum 定数名まで
            // 同一概念）が既定 400 のままであるため、系統を割らないよう既定 400 のまま据え置く
            // （GlobalExceptionHandlerTest の系統の割れ防止番人が固定）。
            Map.entry("SCHEDULE_011", HttpStatus.CONFLICT),                 // CROSS_INVITE_INVALID_STATUS
            Map.entry("SCHEDULE_019", HttpStatus.CONFLICT),                 // PERSONAL_REMINDER_LIMIT_EXCEEDED
            Map.entry("SCHEDULE_020", HttpStatus.CONFLICT),                 // PERSONAL_SCHEDULE_LIMIT_EXCEEDED
            // SCHEDULE_002/003/012/014/015/016/021/030/032/033/041〜044/050/060〜067/090 は
            // 入力バリデーション・未使用定数のいずれかであり Severity.WARN/ERROR 既定が妥当と判定し変更なし。
            // F03.8 / 認可根治 Wave3-B12event: イベント本体・サブリソースの IDOR 秘匿。
            // Javadoc（EventScopeAccessGuard 等）は既に「404 で秘匿する」と明記していたが、
            // このマッピング未登録のため Severity.WARN 既定の 400 のままだった実装漏れを根治する。
            Map.entry("EVENT_001", HttpStatus.NOT_FOUND),                   // EVENT_NOT_FOUND（スコープ帰属不一致含む）
            Map.entry("EVENT_010", HttpStatus.NOT_FOUND),                   // TICKET_TYPE_NOT_FOUND（親子BOLA）
            Map.entry("EVENT_011", HttpStatus.NOT_FOUND),                   // REGISTRATION_NOT_FOUND（親子BOLA）
            Map.entry("EVENT_012", HttpStatus.NOT_FOUND),                   // TICKET_NOT_FOUND（親子BOLA）
            Map.entry("EVENT_014", HttpStatus.NOT_FOUND),                   // TIMETABLE_ITEM_NOT_FOUND（親子BOLA）
            Map.entry("EVENT_015", HttpStatus.NOT_FOUND),                   // INVITE_TOKEN_NOT_FOUND（親子BOLA）
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
            // ロットD追補: event の残り未登録分。存在秘匿系は 404（招待トークンは
            // INVITE_TOKEN_NOT_FOUND(EVENT_015) と流儀を揃え、不正/期限切れ/イベント不一致も同一コードで秘匿）、
            // 状態競合系（登録締切・満席・二重登録・使用済み・スラグ重複・ステータス遷移不正等）は 409。
            Map.entry("EVENT_002", HttpStatus.CONFLICT),                    // INVALID_STATUS_TRANSITION
            Map.entry("EVENT_003", HttpStatus.CONFLICT),                    // REGISTRATION_CLOSED
            Map.entry("EVENT_004", HttpStatus.CONFLICT),                    // CAPACITY_FULL
            Map.entry("EVENT_005", HttpStatus.CONFLICT),                    // ALREADY_REGISTERED
            Map.entry("EVENT_006", HttpStatus.CONFLICT),                    // TICKET_ALREADY_USED
            Map.entry("EVENT_007", HttpStatus.NOT_FOUND),                   // INVALID_INVITE_TOKEN（不在/失効/イベント不一致を同一コードで秘匿 → 404）
            Map.entry("EVENT_013", HttpStatus.NOT_FOUND),                   // CHECKIN_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("EVENT_016", HttpStatus.CONFLICT),                    // SLUG_ALREADY_EXISTS
            Map.entry("EVENT_017", HttpStatus.CONFLICT),                    // INVALID_REGISTRATION_STATUS
            Map.entry("EVENT_018", HttpStatus.CONFLICT),                    // INVALID_TICKET_STATUS
            Map.entry("EVENT_020", HttpStatus.CONFLICT),                    // TICKET_TYPE_SOLD_OUT
            Map.entry("EVENT_021", HttpStatus.CONFLICT),                    // ALREADY_RSVPED
            Map.entry("EVENT_022", HttpStatus.NOT_FOUND),                   // RSVP_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("EVENT_023", HttpStatus.CONFLICT),                    // RSVP_MODE_REQUIRED（イベントのモード設定との競合 → 409）
            Map.entry("EVENT_024", HttpStatus.CONFLICT),                    // ALREADY_DISMISSED
            // F03.5 シフト管理（Phase 11 第二陣で summary / remind 追加）
            Map.entry("SHIFT_001", HttpStatus.NOT_FOUND),                   // SHIFT_SCHEDULE_NOT_FOUND
            Map.entry("SHIFT_002", HttpStatus.NOT_FOUND),                   // SHIFT_SLOT_NOT_FOUND（越境404秘匿にも使用）
            Map.entry("SHIFT_012", HttpStatus.CONFLICT),                    // INVALID_SCHEDULE_STATUS
            Map.entry("SHIFT_019", HttpStatus.FORBIDDEN),                   // ACCESS_DENIED
            // 認可根治 Wave7: 自動割当の越境（他チームの runId / パスの scheduleId 不一致）を
            // 存在秘匿で 404 にするため（Severity.WARN 既定の 400 を上書き）
            Map.entry("SHIFT_024", HttpStatus.NOT_FOUND),                   // ASSIGNMENT_RUN_NOT_FOUND（越境404秘匿にも使用）
            Map.entry("SHIFT_030", HttpStatus.NOT_FOUND),                   // CHANGE_REQUEST_NOT_FOUND（越境404秘匿にも使用）
            // 認可根治 Wave6: 候補者選定の権限拒否は 403（Severity.WARN 既定の 400 を上書き）
            Map.entry("SHIFT_035", HttpStatus.FORBIDDEN),                   // CLAIMER_SELECT_DENIED
            // 認可監査 Wave6 ロットC: F03.5 シフト管理の残り未登録分。
            //  - SHIFT_003/004/005/020 は not-found → 404
            //  - SHIFT_022（勤務制約の管理権限なし）は明確な認可拒否 → 403
            //  - SHIFT_011/013/014/015/018/025/026/031/034 は状態競合（期限超過・ステータス不正・
            //    重複・楽観ロック競合・目視確認未了・既に手挙げ済み等）→ 409
            //    （SHIFT_018 OPTIMISTIC_LOCK_CONFLICT は兄弟 SHIFT_BUDGET_014 と同流儀で揃える）
            //  - SHIFT_036（連打防止スロットリング）はレート制限 → 429
            //  - SHIFT_017（SLOT_ASSIGNMENT_EXCEEDED）は既存番人
            //    GlobalExceptionHandlerTest.ClientErrorMustNotBe500#入力不備系は400 の badRequestCases に
            //    「PATCH リクエストボディの割当人数がスロット必要数を超過」という入力値検証として明示分類済み
            //    （ShiftSlotService.java:212-213 実測: 差分パッチ後の件数チェックであり、並行処理起因の
            //    資源競合ではない）。あえて既定の 400 のまま変更しない。
            Map.entry("SHIFT_003", HttpStatus.NOT_FOUND),
            Map.entry("SHIFT_004", HttpStatus.NOT_FOUND),
            Map.entry("SHIFT_005", HttpStatus.NOT_FOUND),
            Map.entry("SHIFT_011", HttpStatus.CONFLICT),
            Map.entry("SHIFT_013", HttpStatus.CONFLICT),
            Map.entry("SHIFT_014", HttpStatus.CONFLICT),
            Map.entry("SHIFT_015", HttpStatus.CONFLICT),
            Map.entry("SHIFT_018", HttpStatus.CONFLICT),
            Map.entry("SHIFT_020", HttpStatus.NOT_FOUND),
            Map.entry("SHIFT_022", HttpStatus.FORBIDDEN),
            Map.entry("SHIFT_025", HttpStatus.CONFLICT),
            Map.entry("SHIFT_026", HttpStatus.CONFLICT),
            Map.entry("SHIFT_031", HttpStatus.CONFLICT),
            Map.entry("SHIFT_034", HttpStatus.CONFLICT),
            Map.entry("SHIFT_036", HttpStatus.TOO_MANY_REQUESTS),
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
            // F03.9 チーム時間割（認可根治Wave2: IDOR対策で 404 統一。従来 WARN 既定の 400 のまま未マップだった欠陥を根治）
            Map.entry("TIMETABLE_001", HttpStatus.NOT_FOUND),                // TIMETABLE_NOT_FOUND
            Map.entry("TIMETABLE_002", HttpStatus.NOT_FOUND),                // TERM_NOT_FOUND
            Map.entry("TIMETABLE_003", HttpStatus.NOT_FOUND),                // SLOT_NOT_FOUND
            Map.entry("TIMETABLE_004", HttpStatus.NOT_FOUND),                // CHANGE_NOT_FOUND
            // F09.5 共用施設予約（認可根治Wave5 早馬: BOLA 対策で 404 統一。従来 WARN 既定の 400 のまま
            //   未マップだったため、越境 facilityId/bookingId が「存在するが 400」で存在を漏らしていた欠陥を根治）
            Map.entry("FACILITY_001", HttpStatus.NOT_FOUND),                 // FACILITY_NOT_FOUND（越境施設は存在秘匿）
            Map.entry("FACILITY_006", HttpStatus.NOT_FOUND),                 // BOOKING_NOT_FOUND（越境予約は存在秘匿）
            // 認可監査 Wave6 ロットC: F09.5 共用施設予約の残り未登録分。
            //  - FACILITY_003/005/017 は not-found → 404
            //  - FACILITY_002/004（施設名/備品名重複）・007（予約ステータス不正）・008（時間帯重複）・
            //    019（施設が無効）は状態競合 → 409
            Map.entry("FACILITY_002", HttpStatus.CONFLICT),
            Map.entry("FACILITY_003", HttpStatus.NOT_FOUND),
            Map.entry("FACILITY_004", HttpStatus.CONFLICT),
            Map.entry("FACILITY_005", HttpStatus.NOT_FOUND),
            Map.entry("FACILITY_007", HttpStatus.CONFLICT),
            Map.entry("FACILITY_008", HttpStatus.CONFLICT),
            Map.entry("FACILITY_017", HttpStatus.NOT_FOUND),
            Map.entry("FACILITY_019", HttpStatus.CONFLICT),
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
            // F04.2 チャット（認可根治 Wave6: 閲覧・参加・投稿の認可敷設に伴う status 明示。
            //   未マップだと Severity.WARN 既定の 400 になり、403/404 の秘匿が看板倒れになる）
            Map.entry("CHAT_001", HttpStatus.NOT_FOUND),                     // CHANNEL_NOT_FOUND（不在チャンネル → 404）
            Map.entry("CHAT_002", HttpStatus.NOT_FOUND),                     // MESSAGE_NOT_FOUND（不在メッセージ → 404）
            Map.entry("CHAT_005", HttpStatus.FORBIDDEN),                     // CHANNEL_ACCESS_DENIED（非メンバーの閲覧/投稿/参加 → 403）
            Map.entry("CHAT_006", HttpStatus.FORBIDDEN),                     // MESSAGE_EDIT_DENIED（他人のメッセージ編集 → 403）
            Map.entry("CHAT_007", HttpStatus.FORBIDDEN),                     // MESSAGE_DELETE_DENIED（他人のメッセージ削除 → 403）
            Map.entry("CHAT_013", HttpStatus.FORBIDDEN),                     // ROLE_CHANGE_DENIED（権限昇格の拒否 → 403）
            // F04.2 チャット 添付ファイル（F13 Phase 4-β）
            Map.entry("CHAT_015", HttpStatus.PAYLOAD_TOO_LARGE),             // ATTACHMENT_SIZE_EXCEEDED (UX ガード 500MB 超過)
            Map.entry("CHAT_019", HttpStatus.CONFLICT),                      // ATTACHMENT_QUOTA_EXCEEDED (F13 統合クォータ超過)
            // F04.2 Phase 11 第二陣 2-β: チャンネルアイコン Pre-signed URL
            Map.entry("CHAT_022", HttpStatus.PAYLOAD_TOO_LARGE),             // ICON_SIZE_EXCEEDED (2MB 超過)
            Map.entry("CHAT_023", HttpStatus.FORBIDDEN),                     // CHANNEL_ICON_PERMISSION_DENIED (OWNER/ADMIN ではない)
            // F05.5 ファイル共有（F13 Phase 4-epsilon）
            Map.entry("FILE_SHARING_001", HttpStatus.NOT_FOUND),             // FOLDER_NOT_FOUND（IDOR 秘匿・存在隠蔽 → 404）
            Map.entry("FILE_SHARING_002", HttpStatus.NOT_FOUND),             // FILE_NOT_FOUND（IDOR 秘匿・存在隠蔽 → 404。download-url 等で 400 化を防ぐ）
            Map.entry("FILE_SHARING_007", HttpStatus.NOT_FOUND),             // LINK_NOT_FOUND（公開リンク存在秘匿 → 404。総当りに存在を漏らさない）
            Map.entry("FILE_SHARING_011", HttpStatus.GONE),                  // LINK_EXPIRED（期限切れ公開リンク → 410 Gone。マスター確定仕様）
            Map.entry("FILE_SHARING_012", HttpStatus.FORBIDDEN),             // LINK_PASSWORD_INVALID（パスワード不一致 → 403）
            Map.entry("FILE_SHARING_016", HttpStatus.CONFLICT),              // STORAGE_QUOTA_EXCEEDED (F13 統合クォータ超過)
            Map.entry("FILE_SHARING_017", HttpStatus.FORBIDDEN),             // DOWNLOAD_DISABLED（DL 禁止フラグ → 403。閲覧は通し DL URL 発行のみ拒否）
            Map.entry("FILE_SHARING_018", HttpStatus.GONE),                  // LINK_INACTIVE（手動失効した公開リンク → 410 Gone）
            Map.entry("FILE_SHARING_019", HttpStatus.FORBIDDEN),             // LINK_DOWNLOAD_NOT_ALLOWED（DL 未許可リンク → 403）
            // FILE_SHARING_020 (LINK_EXPIRY_INVALID) は Severity.WARN 既定の 400 のまま（発行時バリデーション）
            // F02.3 プロジェクト管理（IDOR 対策で 404 統一）
            Map.entry("TODO_001", HttpStatus.NOT_FOUND),                     // PROJECT_NOT_FOUND（IDOR 秘匿 → 404）
            // F02.3.1 TODO カスタムステータスラベル
            Map.entry("TODO_070", HttpStatus.CONFLICT),                      // LABEL_NAME_DUPLICATED
            Map.entry("TODO_071", HttpStatus.CONFLICT),                      // LABEL_LIMIT_EXCEEDED
            Map.entry("TODO_072", HttpStatus.CONFLICT),                      // LABEL_IN_USE
            Map.entry("TODO_073", HttpStatus.FORBIDDEN),                     // SYSTEM_LABEL_IMMUTABLE（書き込み禁止リソース → 403）
            Map.entry("TODO_076", HttpStatus.NOT_FOUND),                     // STATUS_LABEL_NOT_FOUND (IDOR 対策)
            // ErrorCode ステータス写像是正ロットA: TODO ドメイン残り34件（他 EP と同じ不在秘匿/状態競合の作法に揃える）
            Map.entry("TODO_007", HttpStatus.NOT_FOUND),                     // MILESTONE_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TODO_012", HttpStatus.NOT_FOUND),                     // MILESTONE_NOT_IN_PROJECT（他プロジェクトのIDを指した越境を不在と同一視 → 404）
            Map.entry("TODO_015", HttpStatus.NOT_FOUND),                     // ASSIGNEE_NOT_FOUND（割当不在 → 404）
            Map.entry("TODO_016", HttpStatus.NOT_FOUND),                     // COMMENT_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TODO_050", HttpStatus.NOT_FOUND),                     // SHARED_MEMO_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TODO_060", HttpStatus.NOT_FOUND),                     // PERSONAL_MEMO_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TODO_017", HttpStatus.FORBIDDEN),                     // COMMENT_NOT_OWNER（存在は隠さず作成者以外を拒否 → 403）
            Map.entry("TODO_051", HttpStatus.FORBIDDEN),                     // SHARED_MEMO_NOT_OWNER（存在は隠さず作成者以外を拒否 → 403）
            Map.entry("TODO_002", HttpStatus.CONFLICT),                      // PROJECT_TITLE_DUPLICATE（同名重複 → 409）
            Map.entry("TODO_003", HttpStatus.CONFLICT),                      // PROJECT_LIMIT_EXCEEDED（スコープ件数上限 → 409。SCHEDULE_KEEP_010 と同じ作法）
            Map.entry("TODO_005", HttpStatus.CONFLICT),                      // PROJECT_ALREADY_COMPLETED（状態競合 → 409）
            Map.entry("TODO_006", HttpStatus.CONFLICT),                      // PROJECT_NOT_COMPLETED（状態競合 → 409）
            Map.entry("TODO_008", HttpStatus.CONFLICT),                      // MILESTONE_TITLE_DUPLICATE（同名重複 → 409）
            Map.entry("TODO_009", HttpStatus.CONFLICT),                      // MILESTONE_LIMIT_EXCEEDED（件数上限 → 409）
            Map.entry("TODO_014", HttpStatus.CONFLICT),                      // ASSIGNEE_ALREADY_EXISTS（割当重複 → 409）
            Map.entry("TODO_019", HttpStatus.CONFLICT),                      // MILESTONE_ALREADY_COMPLETED（状態競合 → 409）
            Map.entry("TODO_020", HttpStatus.CONFLICT),                      // MAX_DEPTH_EXCEEDED（既存階層深さとの競合 → 409）
            Map.entry("TODO_022", HttpStatus.CONFLICT),                      // CHILD_LIMIT_EXCEEDED（件数上限 → 409）
            Map.entry("TODO_032", HttpStatus.CONFLICT),                      // SCHEDULE_ALREADY_LINKED（連携重複 → 409）
            Map.entry("TODO_033", HttpStatus.CONFLICT),                      // TODO_ALREADY_LINKED（連携重複 → 409）
            Map.entry("TODO_052", HttpStatus.CONFLICT),                      // SHARED_MEMO_LIMIT_EXCEEDED（件数上限 → 409）
            Map.entry("TODO_053", HttpStatus.CONFLICT),                      // SHARED_MEMO_EDIT_EXPIRED（編集可能期間経過という状態競合 → 409）
            // TODO_004/011/013/018/021/030/031/040/074/075/080/081 は入力値・組み合わせのバリデーション
            // 違反であり Severity.WARN 既定の 400 が妥当と判定、変更なし（ロットA調査で確認済み）。
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
            // F17.1 村長コンソール + 募集カテゴリマスタ（VILLAGE_083〜086）
            Map.entry("VILLAGE_083", HttpStatus.NOT_FOUND),             // RECRUIT_CATEGORY_NOT_FOUND（IDOR 対策で 404）
            Map.entry("VILLAGE_084", HttpStatus.CONFLICT),              // RECRUIT_CATEGORY_NAME_DUPLICATED
            Map.entry("VILLAGE_085", HttpStatus.UNPROCESSABLE_ENTITY),  // RECRUIT_CATEGORY_LIMIT_EXCEEDED
            Map.entry("VILLAGE_086", HttpStatus.CONFLICT),              // RECRUIT_CATEGORY_IN_USE
            // F17.1 ②-4 村ニュースレター コメント/タグ/公開一覧 API（VILLAGE_088〜092）
            Map.entry("VILLAGE_088", HttpStatus.NOT_FOUND),             // NEWSLETTER_ISSUE_NOT_FOUND（IDOR 対策で 404 秘匿）
            Map.entry("VILLAGE_089", HttpStatus.CONFLICT),             // NEWSLETTER_ISSUE_VERSION_CONFLICT（楽観ロック）
            Map.entry("VILLAGE_090", HttpStatus.NOT_FOUND),             // NEWSLETTER_TAG_NOT_FOUND
            Map.entry("VILLAGE_091", HttpStatus.CONFLICT),             // NEWSLETTER_TAG_IN_USE
            Map.entry("VILLAGE_092", HttpStatus.CONFLICT),             // NEWSLETTER_TAG_DUPLICATE
            Map.entry("VILLAGE_093", HttpStatus.CONFLICT),             // NEWSLETTER_TAG_VERSION_CONFLICT（タグ楽観ロック）

            // F17.2 Wave1 ②寄合後半戦・④年輪（VILLAGE_094〜096 / VILLAGE_101）
            Map.entry("VILLAGE_094", HttpStatus.CONFLICT),             // MEETUP_NOT_CONFIRMED（PLANNING 中の出欠）
            Map.entry("VILLAGE_095", HttpStatus.CONFLICT),             // MEETUP_TODO_ALREADY_CLAIMED（割当済み claim）
            Map.entry("VILLAGE_096", HttpStatus.FORBIDDEN),            // MEETUP_TODO_NOT_ASSIGNEE（非手挙げ者の complete/release）
            Map.entry("VILLAGE_101", HttpStatus.FORBIDDEN),            // CALENDAR_LOG_FORBIDDEN（年輪の他人削除）
            // F17.2 Wave2 ③祭の参加レイヤー（VILLAGE_097・098・102）
            Map.entry("VILLAGE_097", HttpStatus.CONFLICT),             // FESTIVAL_RSVP_NOT_OPEN（SCHEDULED/ACTIVE 以外の RSVP）
            Map.entry("VILLAGE_098", HttpStatus.CONFLICT),             // FESTIVAL_LIVE_NOT_ACTIVE（ACTIVE 以外の実況タグ）
            Map.entry("VILLAGE_102", HttpStatus.CONFLICT),             // FESTIVAL_LIVE_POST_DUPLICATE（実況の二重タグ）
            // F17.2 追補 — 寄合定員（VILLAGE_103）
            Map.entry("VILLAGE_103", HttpStatus.CONFLICT),             // MEETUP_CAPACITY_FULL（満席で新規 GOING を拒否）
            // F17.3 村憲章（VILLAGE_104〜108）
            Map.entry("VILLAGE_104", HttpStatus.NOT_FOUND),           // CHARTER_ARTICLE_NOT_FOUND（IDOR 対策で 404 秘匿）
            Map.entry("VILLAGE_105", HttpStatus.CONFLICT),            // CHARTER_ARTICLE_VERSION_CONFLICT（層1 楽観ロック）
            Map.entry("VILLAGE_106", HttpStatus.CONFLICT),            // CHARTER_ORDER_VERSION_CONFLICT（層2 楽観ロック・PATCH order）
            Map.entry("VILLAGE_107", HttpStatus.NOT_FOUND),           // CHARTER_DRAFTER_NOT_FOUND（IDOR 対策で 404 秘匿）
            Map.entry("VILLAGE_108", HttpStatus.CONFLICT),            // CHARTER_DRAFTER_DUPLICATE（二重策定者登録）

            // F17 Phase 3-β — 村史（VILLAGE_075）
            Map.entry("VILLAGE_075", HttpStatus.NOT_FOUND),            // CHRONICLE_NOT_FOUND
            // F17 Phase 3-β — ご縁スコア（VILLAGE_076）
            Map.entry("VILLAGE_076", HttpStatus.NOT_FOUND),            // SERENDIPITY_NOT_FOUND
            // F17 Phase 3-β — 巡礼（VILLAGE_077）
            Map.entry("VILLAGE_077", HttpStatus.NOT_FOUND),            // PILGRIMAGE_NOT_FOUND
            // F17.2 Wave3 ⑤相性表示・⑥所属村一覧（VILLAGE_099〜100）
            Map.entry("VILLAGE_099", HttpStatus.NOT_FOUND),            // AFFINITY_NOT_PUBLIC_VILLAGE（内部予約・通常は 404 存在秘匿）
            Map.entry("VILLAGE_100", HttpStatus.FORBIDDEN),            // PROFILE_VILLAGES_FORBIDDEN（0件は一律 403・§9.4）

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
            Map.entry("ORG_062", HttpStatus.CONFLICT),                       // SLUG_ALREADY_TAKEN
            // F01.2 §5.9.5 slug リネーム: 他スコープ履歴に予約済み slug は 409（SLUG_RETIRED）
            Map.entry("TEAM_063", HttpStatus.CONFLICT),                      // SLUG_RETIRED（他チーム履歴予約）
            // ロットD追補: team の残り未登録分。organization と同型の兄弟コードのため同一方針で揃える
            // （ORG_002/003/005/006/007/042/044/048/050/051 と同じ判定基準）。
            Map.entry("TEAM_002", HttpStatus.CONFLICT),                      // チームはアーカイブ済み（操作不可の状態競合）
            Map.entry("TEAM_003", HttpStatus.CONFLICT),                      // 既にこのチームに所属
            Map.entry("TEAM_004", HttpStatus.FORBIDDEN),                     // ブロックされているため参加できない
            Map.entry("TEAM_005", HttpStatus.FORBIDDEN),                     // この操作を行う権限がありません
            Map.entry("TEAM_006", HttpStatus.CONFLICT),                      // 削除されていないため復元できない
            Map.entry("TEAM_042", HttpStatus.CONFLICT),                      // 役員並び替えリクエストが古い（楽観競合）
            Map.entry("TEAM_044", HttpStatus.CONFLICT),                      // カスタムフィールド並び替えリクエストが古い（楽観競合）
            Map.entry("TEAM_048", HttpStatus.FORBIDDEN),                     // ADMIN/DEPUTY_ADMIN以外は拒否
            Map.entry("TEAM_050", HttpStatus.NOT_FOUND),                     // 役員が見つからない（teamId 束縛・IDOR 秘匿 → 404）
            Map.entry("TEAM_051", HttpStatus.NOT_FOUND),                     // カスタムフィールドが見つからない（teamId 束縛・IDOR 秘匿 → 404）
            Map.entry("ORG_063", HttpStatus.CONFLICT),                       // SLUG_RETIRED（他組織履歴予約）
            // ロットD追補: organization の残り未登録分。権限不足は 403、名称重複・アーカイブ済み・
            // 既加入・並び替え競合等の状態競合は 409、役員/カスタムフィールド not found は
            // organizationId 束縛の IDOR 秘匿のため 404。
            Map.entry("ORG_002", HttpStatus.CONFLICT),                       // 組織名重複
            Map.entry("ORG_003", HttpStatus.CONFLICT),                       // 組織はアーカイブ済み（操作不可の状態競合）
            Map.entry("ORG_005", HttpStatus.FORBIDDEN),                      // この操作を行う権限がありません
            Map.entry("ORG_006", HttpStatus.CONFLICT),                       // 削除されていないため復元できない
            Map.entry("ORG_007", HttpStatus.CONFLICT),                       // 既にこの組織に所属
            Map.entry("ORG_042", HttpStatus.CONFLICT),                       // 役員並び替えリクエストが古い（楽観競合）
            Map.entry("ORG_044", HttpStatus.CONFLICT),                       // カスタムフィールド並び替えリクエストが古い（楽観競合）
            Map.entry("ORG_048", HttpStatus.FORBIDDEN),                      // ADMIN/DEPUTY_ADMIN以外は拒否
            Map.entry("ORG_050", HttpStatus.NOT_FOUND),                      // 役員が見つからない（organizationId 束縛・IDOR 秘匿 → 404）
            Map.entry("ORG_051", HttpStatus.NOT_FOUND),                      // カスタムフィールドが見つからない（organizationId 束縛・IDOR 秘匿 → 404）
            // F03.4 予約ライン/スロット/予約本体/営業時間附帯リソースの不在（IDOR 秘匿含む）→ 404。
            // ErrorCodeHttpStatusDeclarationGuardTest 是正（ロットA）: throw 元
            // （ReservationLineService/ReservationSlotService/ReservationService/
            //   ReservationBusinessHourService/EmergencyClosureService）を確認し、いずれも
            // findByIdAndTeamId 系の解決失敗のみで throw されるため、越境アクセスも同一コードに
            // 畳んで 404 で秘匿する（他ドメインの NOT_FOUND 系と同じ作法）。BUSINESS_HOURS_NOT_FOUND
            // （RESERVATION_010）は throw 元が存在しない未使用定数のため対象外（既定 400 のまま）。
            Map.entry("RESERVATION_001", HttpStatus.NOT_FOUND),              // LINE_NOT_FOUND
            Map.entry("RESERVATION_002", HttpStatus.NOT_FOUND),              // SLOT_NOT_FOUND
            Map.entry("RESERVATION_003", HttpStatus.NOT_FOUND),              // RESERVATION_NOT_FOUND
            Map.entry("RESERVATION_011", HttpStatus.NOT_FOUND),              // BLOCKED_TIME_NOT_FOUND
            Map.entry("RESERVATION_012", HttpStatus.NOT_FOUND),              // REMINDER_NOT_FOUND
            Map.entry("RESERVATION_016", HttpStatus.NOT_FOUND),              // CLOSURE_NOT_FOUND
            Map.entry("RESERVATION_017", HttpStatus.NOT_FOUND),              // CLOSURE_CONFIRMATION_NOT_FOUND
            // F03.4 予約ステータス不正操作（確定/キャンセル不可な状態での操作）→ 409。
            // ErrorCodeHttpStatusDeclarationGuardTest 是正（ロットA）: throw 元
            // （ReservationService.confirmReservation/cancelByAdmin/cancelByUser、
            //   ReservationGroupService）を確認し、いずれも isConfirmable()/isCancellable() の
            // 状態ガードのみで throw される状態遷移違反であり、入力不備ではない。
            Map.entry("RESERVATION_006", HttpStatus.CONFLICT),               // INVALID_RESERVATION_STATUS
            // F03.4 予約重複: 同一スロット×同一ユーザーに active 予約が既に存在 → 409（段階拡張 ⑧）
            // Severity.WARN 既定の 400 を上書き。リソース競合（重複作成）の意味論として 409 が正準。
            Map.entry("RESERVATION_013", HttpStatus.CONFLICT),               // DUPLICATE_RESERVATION
            // F03.4 予約スロット削除ガード: active 予約が紐づく枠の削除はオーファン化を招くため 409
            Map.entry("RESERVATION_020", HttpStatus.CONFLICT),               // SLOT_HAS_ACTIVE_RESERVATIONS
            // ErrorCode ステータス写像是正ロットA: SLOT_FULL/SLOT_CLOSED は対象スロットの
            // 現在の空き状況・受付状態という「状態」に起因する確保失敗であり、GROUP_SLOT_UNAVAILABLE
            // （RESERVATION_039・409）と同型の意味論。入力自体の不備ではないため 409 が正しい。
            Map.entry("RESERVATION_004", HttpStatus.CONFLICT),               // SLOT_FULL
            Map.entry("RESERVATION_005", HttpStatus.CONFLICT),               // SLOT_CLOSED
            // F03.4 予約認可ゲート: 非所属者が一般公開OFFのチームに予約 → 403（Severity.WARN 既定の 400 を上書き）
            Map.entry("RESERVATION_021", HttpStatus.FORBIDDEN),              // RESERVATION_PERMISSION_DENIED
            // F03.4 機能B 予約不可枠 409 ガード: overlap する active 予約が存在 → 409（Severity.WARN 既定の 400 を上書き）
            Map.entry("RESERVATION_027", HttpStatus.CONFLICT),               // UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS
            // F03.4 機能D 予約通知メール宛先フリーミアム: 有料必須 → 402、email 重複 → 409、不在 → 404。
            // 028（上限10件超過）は入力上限超過として WARN 既定の 400 のまま（個別 map 不要）。
            Map.entry("RESERVATION_029", HttpStatus.PAYMENT_REQUIRED),       // NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED
            Map.entry("RESERVATION_030", HttpStatus.CONFLICT),               // NOTIFY_RECIPIENT_DUPLICATE
            Map.entry("RESERVATION_031", HttpStatus.NOT_FOUND),              // NOTIFY_RECIPIENT_NOT_FOUND
            // F03.4.1 機能E 予約メニュー: 不在（IDOR 秘匿含む）→ 404。
            // 033（上限20件）/ 034（所要時間不正）/ 035（lineIds 不正）は WARN 既定の 400 のまま（個別 map 不要）。
            Map.entry("RESERVATION_032", HttpStatus.NOT_FOUND),              // MENU_NOT_FOUND
            // F03.4.2 機能F 週間テンプレート: 不在（IDOR 秘匿含む）→ 404、generate レート制限 → 429、
            // ライン削除ガード（active 予約あり）→ 409。037（上限500行）/038（ライン不一致）は WARN 既定の 400。
            Map.entry("RESERVATION_036", HttpStatus.NOT_FOUND),              // TEMPLATE_NOT_FOUND
            // F03.4.3 機能G 予約グループ: 確保失敗（満席/CLOSED・全ロールバック済み）→ 409、
            // グループ不在（IDOR 秘匿含む）→ 404。041（枠数上限）/042（単票操作拒否）/043（提供不可ライン）は
            // WARN 既定の 400 のまま（個別 map 不要）。
            Map.entry("RESERVATION_039", HttpStatus.CONFLICT),               // GROUP_SLOT_UNAVAILABLE
            Map.entry("RESERVATION_040", HttpStatus.NOT_FOUND),              // GROUP_NOT_FOUND
            Map.entry("RESERVATION_044", HttpStatus.TOO_MANY_REQUESTS),      // TEMPLATE_GENERATE_RATE_LIMITED
            Map.entry("RESERVATION_045", HttpStatus.CONFLICT),               // LINE_HAS_ACTIVE_RESERVATIONS
            // F03.4.5 §6.1 キャンセル待ち（048/049 は既定 400・046→404・047→409・050→429）
            Map.entry("RESERVATION_046", HttpStatus.NOT_FOUND),              // WAITLIST_ENTRY_NOT_FOUND（IDOR 秘匿）
            Map.entry("RESERVATION_047", HttpStatus.CONFLICT),               // WAITLIST_ALREADY_REGISTERED
            Map.entry("RESERVATION_050", HttpStatus.TOO_MANY_REQUESTS),      // WAITLIST_RATE_LIMITED
            // F03.4.5 §4 W2-2 定期予約不可枠（052=上限50行は既定400・051→404・027 は既存409を再利用）
            Map.entry("RESERVATION_051", HttpStatus.NOT_FOUND),              // RECURRING_BLOCKED_TIME_NOT_FOUND（IDOR秘匿）
            // F03.4.5 §6.4 W2-6 予約作成レートリミット（単枠・グループ共通バケット）
            Map.entry("RESERVATION_053", HttpStatus.TOO_MANY_REQUESTS),      // RESERVATION_CREATE_RATE_LIMITED
            // F06.5 アクティブリコール学習（IDOR 対策で 404、上限/範囲外は 400、楽観排他/マスク中編集/再輸出は 409）
            Map.entry("REFLECTION_001", HttpStatus.NOT_FOUND),              // NOT_FOUND（他人所有も IDOR 対策で 404）
            Map.entry("REFLECTION_002", HttpStatus.BAD_REQUEST),           // THEME_LIMIT_EXCEEDED
            Map.entry("REFLECTION_003", HttpStatus.BAD_REQUEST),           // REMINDER_LIMIT_EXCEEDED
            Map.entry("REFLECTION_004", HttpStatus.BAD_REQUEST),           // TARGET_DATE_OUT_OF_RANGE
            Map.entry("REFLECTION_005", HttpStatus.CONFLICT),              // VERSION_CONFLICT（楽観排他）
            Map.entry("REFLECTION_006", HttpStatus.CONFLICT),              // ENTRY_MASKED（マスク中直接 PUT）
            Map.entry("REFLECTION_007", HttpStatus.BAD_REQUEST),           // CONTENT_INVALID
            Map.entry("REFLECTION_008", HttpStatus.CONFLICT),              // ALREADY_EXPORTED（再輸出ブロック）
            // Phase 3: アーカイブ＆分類（§12）
            Map.entry("REFLECTION_009", HttpStatus.CONFLICT),              // ALREADY_ARCHIVED（再アーカイブ防止）
            Map.entry("REFLECTION_010", HttpStatus.CONFLICT),              // NOT_ARCHIVED（アクティブへの restore）
            Map.entry("REFLECTION_011", HttpStatus.BAD_REQUEST),          // BULK_ARCHIVE_NO_CONDITION（条件なし）
            Map.entry("REFLECTION_012", HttpStatus.BAD_REQUEST),          // PARENT_DEPTH_EXCEEDED（2階層超過）
            Map.entry("REFLECTION_013", HttpStatus.BAD_REQUEST),          // PARENT_SELF_REFERENCE（自己参照）
            Map.entry("REFLECTION_014", HttpStatus.BAD_REQUEST),          // PARENT_INVALID_STATE（archived/deleted 親）
            // Phase 4: 暗記カード／期間横断 単語帳（§13）
            Map.entry("REFLECTION_015", HttpStatus.BAD_REQUEST),          // DATE_RANGE_INVALID（期間幅 366 日超）
            // F02.12 Phase 4: Google Calendar Webhook 検証（Severity.WARN 既定 400 を上書き）
            Map.entry("GCAL_008", HttpStatus.NOT_FOUND),                  // GOOGLE_WEBHOOK_CHANNEL_NOT_FOUND → 404
            Map.entry("GCAL_009", HttpStatus.FORBIDDEN),                  // GOOGLE_WEBHOOK_TOKEN_INVALID → 403
            // F10.8 チーム/組織アクセス解析（TEAMANALYTICS_xxx）
            Map.entry("TEAMANALYTICS_001", HttpStatus.NOT_FOUND),         // 非メンバー/不在スコープ → 404（IDOR 秘匿）
            Map.entry("TEAMANALYTICS_002", HttpStatus.BAD_REQUEST),       // 日付範囲不正（dateFrom > dateTo）→ 400
            Map.entry("TEAMANALYTICS_003", HttpStatus.BAD_REQUEST),       // ビーコン body 不正（ENUM 外・絶対 URL 等）→ 400
            // F20.1 課金・エンタイトルメント基盤（ENTITLEMENT_xxx・設計書 02 §9）。
            // 402（支払えば解決）は 003 のみ。403 は 004/005。IDOR 秘匿は 007（404）。
            Map.entry("ENTITLEMENT_001", HttpStatus.NOT_FOUND),           // PLAN_NOT_FOUND
            Map.entry("ENTITLEMENT_002", HttpStatus.NOT_FOUND),           // FEATURE_NOT_FOUND
            Map.entry("ENTITLEMENT_003", HttpStatus.PAYMENT_REQUIRED),    // FEATURE_NOT_ENTITLED（購入導線あり・RESERVATION_029 と同型）
            Map.entry("ENTITLEMENT_004", HttpStatus.FORBIDDEN),           // FEATURE_FORBIDDEN_FOR_SCOPE
            Map.entry("ENTITLEMENT_005", HttpStatus.FORBIDDEN),           // SCOPE_FORBIDDEN（IDOR）
            Map.entry("ENTITLEMENT_006", HttpStatus.CONFLICT),            // CONTRACT_ALREADY_ACTIVE
            Map.entry("ENTITLEMENT_007", HttpStatus.NOT_FOUND),           // CONTRACT_NOT_FOUND（スコープ不一致は 404 秘匿）
            Map.entry("ENTITLEMENT_008", HttpStatus.UNPROCESSABLE_ENTITY),// ADDON_NOT_AVAILABLE
            Map.entry("ENTITLEMENT_009", HttpStatus.BAD_REQUEST),         // INVALID_SCOPE_KIND
            Map.entry("ENTITLEMENT_010", HttpStatus.BAD_REQUEST),         // PLAN_MASTER_VALIDATION_FAILED
            Map.entry("ENTITLEMENT_011", HttpStatus.CONFLICT),            // CONTRACT_NOT_CANCELLABLE
            Map.entry("ENTITLEMENT_012", HttpStatus.CONFLICT),            // PLAN_MASTER_IN_USE
            Map.entry("ENTITLEMENT_013", HttpStatus.CONFLICT),            // DUPLICATE_ENTITLEMENT（uk_ent_grant）
            Map.entry("ENTITLEMENT_014", HttpStatus.BAD_REQUEST),         // INVALID_CONTRACT_KIND（contractKind 不正）
            // F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）追補
            Map.entry("ENTITLEMENT_015", HttpStatus.BAD_GATEWAY),         // CHECKOUT_SESSION_FAILED（Stripe 呼び出し失敗 → 502）
            Map.entry("ENTITLEMENT_016", HttpStatus.CONFLICT),           // CONTRACT_PENDING_PAYMENT（PENDING スロット占有中）
            Map.entry("ENTITLEMENT_017", HttpStatus.CONFLICT),           // CONTRACT_CHANGE_REQUIRES_PAYMENT（有償が絡む changePlan 拒否・AC-44）
            // F20.3 ベータ特典（設計書 02 §8）。登録漏れは Severity 既定 400/500 にフォールバックする前科（#1279）ゆえ明示登録。
            Map.entry("BETA_PERK_001", HttpStatus.NOT_FOUND),            // GRANT_NOT_FOUND（IDOR 秘匿含む）
            Map.entry("BETA_PERK_002", HttpStatus.CONFLICT),            // GRANT_ALREADY_EXISTS（uk_bg_scope_phase）
            Map.entry("BETA_PERK_003", HttpStatus.UNPROCESSABLE_ENTITY),// ACTIVITY_CRITERIA_NOT_MET
            Map.entry("BETA_PERK_004", HttpStatus.BAD_REQUEST),         // BETA_PHASE_INVALID
            Map.entry("BETA_PERK_005", HttpStatus.CONFLICT),            // GRANT_ALREADY_REVOKED
            Map.entry("BETA_PERK_006", HttpStatus.CONFLICT),            // REVIEW_NOT_FLAGGED
            Map.entry("BETA_PERK_007", HttpStatus.UNPROCESSABLE_ENTITY),// GRANT_SCOPE_MISMATCH
            Map.entry("BETA_PERK_008", HttpStatus.UNPROCESSABLE_ENTITY),// EXTEND_NOT_APPLICABLE（INDIVIDUAL 無期限）
            Map.entry("BETA_PERK_009", HttpStatus.BAD_REQUEST),         // CRITERIA_VALIDATION_FAILED（無条件付与の防止）
            Map.entry("BETA_PERK_010", HttpStatus.NOT_FOUND),           // CRITERIA_NOT_FOUND（未定義/enabled=false）
            // 認可根治戦役 Wave 2 トランシェ2A #3: F09.15 succession（法的手続き・エスカレーション）は
            // (id, organizationId) 複合キーで取得するため、別テナントの ID 指定は IDOR 秘匿のため 404 とする
            // （Severity.WARN 既定の 400 を上書き）。
            Map.entry("SUCCESSION_016", HttpStatus.NOT_FOUND),           // ESCALATION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("SUCCESSION_021", HttpStatus.NOT_FOUND),           // LEGAL_FILING_NOT_FOUND（IDOR 秘匿 → 404）
            // ロットD追補: succession の残り未登録分。存在秘匿系（本人以外/他テナントの ID 指定を
            // 同一コードで返す BOLA 対策）は 404、状態競合は 409、権限不足は 403 に上書きする。
            Map.entry("SUCCESSION_001", HttpStatus.NOT_FOUND),           // RESIDENT_REGISTRY_NOT_FOUND（本人以外は秘匿 → 404）
            Map.entry("SUCCESSION_002", HttpStatus.NOT_FOUND),           // DWELLING_UNIT_NOT_FOUND（居住者経由の付随資源 → 404）
            Map.entry("SUCCESSION_003", HttpStatus.NOT_FOUND),           // COVENANT_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("SUCCESSION_005", HttpStatus.CONFLICT),            // COVENANT_ALREADY_SIGNED（多重署名の状態競合 → 409）
            Map.entry("SUCCESSION_007", HttpStatus.CONFLICT),            // COVENANT_ALREADY_REVOKED（状態競合 → 409）
            Map.entry("SUCCESSION_008", HttpStatus.FORBIDDEN),           // COVENANT_FORBIDDEN（本人/ADMIN以外 → 403）
            Map.entry("SUCCESSION_009", HttpStatus.NOT_FOUND),           // PRE_REGISTRATION_NOT_FOUND（organizationId 束縛・IDOR 秘匿 → 404）
            Map.entry("SUCCESSION_010", HttpStatus.NOT_FOUND),           // UNSEAL_REQUEST_NOT_FOUND（organizationId 束縛・IDOR 秘匿 → 404）
            Map.entry("SUCCESSION_011", HttpStatus.CONFLICT),            // PRE_REGISTRATION_NOT_SEALED（状態遷移前提未達 → 409）
            Map.entry("SUCCESSION_012", HttpStatus.CONFLICT),            // APPROVER_CONFLICT（三者別人要件違反 → 409）
            Map.entry("SUCCESSION_013", HttpStatus.CONFLICT),            // FIRST_APPROVER_REQUIRED（承認前提未達 → 409）
            // UNSEAL_EXPIRED_OR_INACTIVE/UNSEAL_ACCESS_DENIED は三層ガード（UnsealedAccessGuard）の
            // Layer1-3 で使われ、既存資源への一時的なアクセス拒否（時間切れ・権限不足）のため 403。
            Map.entry("SUCCESSION_014", HttpStatus.FORBIDDEN),           // UNSEAL_EXPIRED_OR_INACTIVE
            Map.entry("SUCCESSION_015", HttpStatus.FORBIDDEN),           // UNSEAL_ACCESS_DENIED
            Map.entry("SUCCESSION_017", HttpStatus.CONFLICT),            // ESCALATION_ALREADY_RESOLVED（状態競合 → 409）
            Map.entry("SUCCESSION_018", HttpStatus.CONFLICT),            // ESCALATION_FROZEN（凍結中の操作拒否 → 409）
            Map.entry("SUCCESSION_019", HttpStatus.CONFLICT),            // ESCALATION_ALREADY_FINAL_STAGE（状態競合 → 409）
            Map.entry("SUCCESSION_022", HttpStatus.CONFLICT),            // EVIDENCE_NOT_READY（前提未達の状態競合 → 409）
            // 認可根治戦役 Wave 2 トランシェ2B: F09.3 parking の *_NOT_FOUND は、対象エンティティが
            // 自スコープ外（BOLA）の場合にも同一コードで返す存在秘匿の要。Severity.WARN 既定の 400 のままだと
            // IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("PARKING_001", HttpStatus.NOT_FOUND),              // SPACE_NOT_FOUND
            Map.entry("PARKING_002", HttpStatus.NOT_FOUND),              // VEHICLE_NOT_FOUND
            Map.entry("PARKING_003", HttpStatus.NOT_FOUND),              // ASSIGNMENT_NOT_FOUND
            Map.entry("PARKING_004", HttpStatus.NOT_FOUND),              // APPLICATION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PARKING_005", HttpStatus.NOT_FOUND),              // LISTING_NOT_FOUND
            Map.entry("PARKING_006", HttpStatus.NOT_FOUND),              // VISITOR_RESERVATION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PARKING_007", HttpStatus.NOT_FOUND),              // WATCHLIST_NOT_FOUND
            Map.entry("PARKING_024", HttpStatus.NOT_FOUND),              // RECURRING_NOT_FOUND
            Map.entry("PARKING_025", HttpStatus.NOT_FOUND),              // SUBLEASE_NOT_FOUND
            Map.entry("PARKING_026", HttpStatus.NOT_FOUND),              // SUBLEASE_APPLICATION_NOT_FOUND（紐付け検証・IDOR 秘匿 → 404）
            // 認可根治戦役 Wave5: F03.7 queue の *_NOT_FOUND は、QueueAccessGuard が
            // 「対象エンティティが URL パスの scope 外（BOLA）」の場合にも同一コードで返す存在秘匿の要。
            // Severity.WARN 既定の 400 のままだと IDOR 秘匿の慣例に反するため 404 へ上書きする。
            Map.entry("QUEUE_001", HttpStatus.NOT_FOUND),                // CATEGORY_NOT_FOUND（scope 外 → 404 秘匿）
            Map.entry("QUEUE_002", HttpStatus.NOT_FOUND),                // COUNTER_NOT_FOUND（scope 外 → 404 秘匿）
            Map.entry("QUEUE_003", HttpStatus.NOT_FOUND),                // TICKET_NOT_FOUND（scope 外・他人所有 → 404 秘匿）
            Map.entry("QUEUE_008", HttpStatus.NOT_FOUND),                // QR_CODE_NOT_FOUND（scope 外 → 404 秘匿）
            // 認可根治戦役 Wave 2 トランシェ2B: F07.4 chart（要配慮個人情報：健康記録）の
            // NOT_FOUND 系は teamId を跨いだ存在秘匿のため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("CHART_001", HttpStatus.NOT_FOUND),                 // CHART_NOT_FOUND
            Map.entry("CHART_002", HttpStatus.NOT_FOUND),                 // PHOTO_NOT_FOUND
            Map.entry("CHART_003", HttpStatus.NOT_FOUND),                 // FORMULA_NOT_FOUND
            Map.entry("CHART_004", HttpStatus.NOT_FOUND),                 // CUSTOM_FIELD_NOT_FOUND
            Map.entry("CHART_005", HttpStatus.NOT_FOUND),                 // INTAKE_FORM_TEMPLATE_NOT_FOUND
            Map.entry("CHART_006", HttpStatus.NOT_FOUND),                 // RECORD_TEMPLATE_NOT_FOUND
            Map.entry("CHART_019", HttpStatus.NOT_FOUND),                 // INTAKE_FORM_NOT_FOUND
            // 認可根治戦役 Wave 2 トランシェ2B: F07.2 performance の *_NOT_FOUND は、対象エンティティが
            // 自チーム外（BOLA）の場合にも同一コードで返す存在秘匿の要。Severity.WARN 既定の 400 のままだと
            // IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("PERF_001", HttpStatus.NOT_FOUND),                 // METRIC_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PERF_002", HttpStatus.NOT_FOUND),                 // RECORD_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PERF_011", HttpStatus.NOT_FOUND),                 // SCHEDULE_NOT_FOUND（scheduleId スコープ整合 → 404）
            Map.entry("PERF_013", HttpStatus.NOT_FOUND),                 // ACTIVITY_NOT_FOUND
            Map.entry("PERF_014", HttpStatus.NOT_FOUND),                 // TEAM_NOT_FOUND
            // 認可根治戦役 Wave 2 トランシェ2B: F09.2 promotion（プロモーション・クーポン・セグメントプリセット）は
            // (id, scopeType, scopeId) 複合条件で取得するため、他スコープの ID 指定は IDOR 秘匿のため 404 とする
            // （Severity.WARN 既定の 400 を上書き）。
            Map.entry("PROMOTION_001", HttpStatus.NOT_FOUND),            // PROMOTION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROMOTION_005", HttpStatus.NOT_FOUND),            // COUPON_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROMOTION_007", HttpStatus.NOT_FOUND),            // DISTRIBUTION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROMOTION_010", HttpStatus.NOT_FOUND),            // DELIVERY_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROMOTION_011", HttpStatus.NOT_FOUND),            // PRESET_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROMOTION_015", HttpStatus.NOT_FOUND),            // BILLING_RECORD_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2C: F08.7 tournament の *_NOT_FOUND は、対象エンティティが
            // 親大会/親ディビジョン配下に無い（BOLA・divId/matchId/pId/templateId の親子束縛不一致）場合にも
            // 同一コードで返す存在秘匿の要。Severity.WARN 既定の 400 のままだと IDOR 秘匿の慣例
            // （TOUR_001 と同流儀）に反するため 404 へ上書きする。
            Map.entry("TOUR_002", HttpStatus.NOT_FOUND),                 // DIVISION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TOUR_003", HttpStatus.NOT_FOUND),                 // MATCH_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TOUR_013", HttpStatus.NOT_FOUND),                 // TEMPLATE_NOT_FOUND（org 束縛・IDOR 秘匿 → 404）
            Map.entry("TOUR_014", HttpStatus.NOT_FOUND),                 // PRESET_NOT_FOUND（存在しない ID → 404）
            Map.entry("TOUR_018", HttpStatus.NOT_FOUND),                 // PARTICIPANT_NOT_FOUND（div 束縛・IDOR 秘匿 → 404）
            Map.entry("TOUR_061", HttpStatus.NOT_FOUND),                 // MATCHDAY_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("TOUR_062", HttpStatus.NOT_FOUND),                 // FIXTURE_ROSTER_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2B: F07.3 equipment（備品管理）は
            // (id, teamId)/(id, organizationId) 複合キーで取得するため、他スコープの ID 指定は
            // IDOR 秘匿のため 404 とする（Severity.WARN 既定の 400 を上書き）。
            Map.entry("EQUIPMENT_001", HttpStatus.NOT_FOUND),            // ITEM_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("EQUIPMENT_002", HttpStatus.NOT_FOUND),            // ASSIGNMENT_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2C: F06.3 digest（タイムラインダイジェスト）の *_NOT_FOUND。
            // ID 直指定 URL（/timeline-digest/{id}）のため、不在 ID と越境 ID を区別しない存在秘匿の要
            // （Severity.WARN 既定の 400 を上書き）。
            Map.entry("DIGEST_011", HttpStatus.NOT_FOUND),               // DIGEST_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("DIGEST_014", HttpStatus.NOT_FOUND),               // CONFIG_NOT_FOUND → 404
            // 認可監査 Wave6 ロットC: F06.3 タイムラインダイジェストの残り未登録分。
            // DIGEST_007（同一期間ダイジェスト重複）/009（生成中の重複実行）/012（GENERATED 以外での
            // publish・discard）/013（再生成条件不成立）はいずれも状態競合 → 409。
            Map.entry("DIGEST_007", HttpStatus.CONFLICT),
            Map.entry("DIGEST_009", HttpStatus.CONFLICT),
            Map.entry("DIGEST_012", HttpStatus.CONFLICT),
            Map.entry("DIGEST_013", HttpStatus.CONFLICT),
            // 認可根治戦役 Wave 2 トランシェ2C: F09.6 directmail の *_NOT_FOUND は、対象エンティティが
            // 自スコープ外（BOLA）の場合にも (id, scopeType, scopeId) 複合フェッチで同一コードを返す存在秘匿の要。
            // Severity.WARN 既定の 400 のままだと IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("DM_001", HttpStatus.NOT_FOUND),                   // MAIL_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("DM_002", HttpStatus.NOT_FOUND),                   // TEMPLATE_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2C: line（LINE連携 BotConfig / SnsFeed）の *_NOT_FOUND は、
            // 対象エンティティが自スコープ外（BOLA）の場合にも同一コードで返す存在秘匿の要。
            // Severity.WARN 既定の 400 のまま だと IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("LINE_001", HttpStatus.NOT_FOUND),                 // BOT_CONFIG_NOT_FOUND
            Map.entry("LINE_007", HttpStatus.NOT_FOUND),                 // SNS_FEED_CONFIG_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2C: F01.4/F03.12 family の *_NOT_FOUND は、対象エンティティが
            // 自チーム外（BOLA）の場合にも同一コードで返す存在秘匿の要。Severity.WARN 既定の 400 のままだと
            // IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            // FAMILY_030（ケアリンク操作権限なし）は認可拒否のため 403 へ上書きする（児童 PII 防護）。
            Map.entry("FAMILY_008", HttpStatus.NOT_FOUND),               // COIN_TOSS_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FAMILY_011", HttpStatus.NOT_FOUND),               // SHOPPING_LIST_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FAMILY_013", HttpStatus.NOT_FOUND),               // SHOPPING_ITEM_NOT_FOUND（listId 紐付け検証・IDOR 秘匿 → 404）
            Map.entry("FAMILY_016", HttpStatus.NOT_FOUND),               // DUTY_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FAMILY_018", HttpStatus.NOT_FOUND),               // ANNIVERSARY_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FAMILY_025", HttpStatus.NOT_FOUND),               // CARE_LINK_NOT_FOUND（存在秘匿 → 404）
            Map.entry("FAMILY_029", HttpStatus.NOT_FOUND),               // 招待トークン不一致（存在秘匿 → 404）
            Map.entry("FAMILY_030", HttpStatus.FORBIDDEN),               // ケアリンク操作権限なし（当事者以外 → 403）
            // 認可監査 Wave6 ロットC: F01.4 family の残り未登録分。
            //  - FAMILY_003 ロール呼称変更不可（SYSTEM_ADMIN/GUEST）は書込禁止リソースへの操作拒否 → 403
            //    （TODO_073 SYSTEM_LABEL_IMMUTABLE と同じ流儀）
            //  - FAMILY_007 コイントス実行回数のレートリミット超過 → 429
            //  - FAMILY_009 コイントス共有済み・FAMILY_022 アーカイブ済みリストへのコピー・
            //    FAMILY_028 同一ペアのケアリンク重複・FAMILY_031 既にアクティブなケアリンクは状態競合 → 409
            //  - FAMILY_010 共有権限（実行者のみ）・FAMILY_015 削除権限（作成者のみ）は認可拒否 → 403
            //  - FAMILY_020 壁紙不在は単純な not-found → 404
            Map.entry("FAMILY_003", HttpStatus.FORBIDDEN),
            Map.entry("FAMILY_007", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("FAMILY_009", HttpStatus.CONFLICT),
            Map.entry("FAMILY_010", HttpStatus.FORBIDDEN),
            Map.entry("FAMILY_015", HttpStatus.FORBIDDEN),
            Map.entry("FAMILY_020", HttpStatus.NOT_FOUND),
            Map.entry("FAMILY_022", HttpStatus.CONFLICT),
            Map.entry("FAMILY_028", HttpStatus.CONFLICT),
            Map.entry("FAMILY_031", HttpStatus.CONFLICT),
            // 認可根治戦役 第2波 ロットA: F04.8 contact の *_NOT_FOUND は、対象が自分のスコープ外
            // （他ユーザーの連絡先・招待トークン・事前拒否・申請）の場合にも同一コードを返す存在秘匿の要。
            // Severity.WARN 既定の 400 では存在秘匿の契約にならないため 404 へ上書きする。
            // CONTACT_007（スコープ参照権限なし）は明確な認可拒否のため 403 へ上書きする。
            Map.entry("CONTACT_006", HttpStatus.NOT_FOUND),              // 申請が見つからない（存在秘匿 → 404）
            Map.entry("CONTACT_007", HttpStatus.FORBIDDEN),              // スコープ参照権限なし → 403
            Map.entry("CONTACT_010", HttpStatus.NOT_FOUND),              // 事前拒否設定が見つからない（存在秘匿 → 404）
            Map.entry("CONTACT_014", HttpStatus.NOT_FOUND),              // 招待トークンが見つからない（存在秘匿 → 404）
            Map.entry("CONTACT_015", HttpStatus.NOT_FOUND),              // 連絡先が見つからない（存在秘匿 → 404）
            // 認可根治戦役 Wave 2 トランシェ2C: F05.6 workflow（稟議/申請ワークフロー）は
            // entity 由来の scopeType/scopeId で認可判定するため、path/リクエストの scope 不一致・
            // 非所属者アクセスは同一コードで返す存在秘匿の要。Severity.WARN 既定の 400 のままだと
            // IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("WORKFLOW_001", HttpStatus.NOT_FOUND),             // TEMPLATE_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("WORKFLOW_002", HttpStatus.NOT_FOUND),             // REQUEST_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("WORKFLOW_005", HttpStatus.NOT_FOUND),             // COMMENT_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("WORKFLOW_006", HttpStatus.NOT_FOUND),             // ATTACHMENT_NOT_FOUND（IDOR 秘匿 → 404）
            // 承認判断は「指定承認者でない」という明確な認可拒否のため 403（Severity.WARN 既定の 400 を上書き）。
            Map.entry("WORKFLOW_009", HttpStatus.FORBIDDEN),             // NOT_APPROVER → 403
            // Wave3 member BOLA存在秘匿
            Map.entry("MEMBER_001", HttpStatus.NOT_FOUND),               // PAGE_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("MEMBER_002", HttpStatus.NOT_FOUND),               // SECTION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("MEMBER_003", HttpStatus.NOT_FOUND),               // PROFILE_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("MEMBER_004", HttpStatus.NOT_FOUND),               // FIELD_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave3 forms/disclosure BOLA存在秘匿: forms の *_NOT_FOUND は BOLA 存在秘匿のため 404、PDF 権限なしは 403。
            Map.entry("FORM_001", HttpStatus.NOT_FOUND),                 // TEMPLATE_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FORM_002", HttpStatus.NOT_FOUND),                 // SUBMISSION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("FORM_003", HttpStatus.NOT_FOUND),                 // PRESET_NOT_FOUND → 404
            Map.entry("FORM_014", HttpStatus.FORBIDDEN),                 // PDF_ACCESS_DENIED → 403
            // ロットD追補: forms の残り未登録分。テンプレート/提出の状態競合系（ステータス不正・
            // 未公開・締切超過・提出後編集不可・PDF未生成/生成不可）は 409。
            Map.entry("FORM_004", HttpStatus.CONFLICT),                  // INVALID_TEMPLATE_STATUS
            Map.entry("FORM_005", HttpStatus.CONFLICT),                  // INVALID_SUBMISSION_STATUS
            Map.entry("FORM_006", HttpStatus.CONFLICT),                  // TEMPLATE_NOT_PUBLISHED
            Map.entry("FORM_008", HttpStatus.CONFLICT),                  // EDIT_AFTER_SUBMIT_NOT_ALLOWED
            Map.entry("FORM_012", HttpStatus.CONFLICT),                  // TEMPLATE_DEADLINE_PASSED
            Map.entry("FORM_013", HttpStatus.CONFLICT),                  // PDF_NOT_GENERATED（前提未達 → 409）
            Map.entry("FORM_015", HttpStatus.CONFLICT),                  // PDF_GENERATION_NOT_ALLOWED（ステータス前提未達 → 409）
            // disclosure の DISCLOSURE_001/002 は cross-scope entity-mismatch 専用のため BOLA 存在秘匿の 404。
            Map.entry("DISCLOSURE_001", HttpStatus.NOT_FOUND),           // リソース不在 → 404
            Map.entry("DISCLOSURE_002", HttpStatus.NOT_FOUND),           // スコープ不一致（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave3-B1: payment の *_NOT_FOUND は itemId 越境等の BOLA 存在秘匿のため 404。
            Map.entry("PAYMENT_001", HttpStatus.NOT_FOUND),              // PAYMENT_ITEM_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PAYMENT_002", HttpStatus.NOT_FOUND),              // PAYMENT_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 第2波（金銭）: 会費領収書 EP の宣言と実挙動を揃える。
            // ReceiptController / ReceiptService は「払い手または受益者本人のみ取得可・第三者は拒否」と
            // 宣言しているが、両コードが未登録のため Severity.WARN 既定の 400 が返っていた。
            Map.entry("PAYMENT_029", HttpStatus.NOT_FOUND),              // MEMBER_PAYMENT_NOT_FOUND（存在秘匿 → 404）
            Map.entry("PAYMENT_030", HttpStatus.FORBIDDEN),              // PAYMENT_ACCESS_DENIED（払い手/受益者以外 → 403）
            // 認可根治戦役 第2波（金銭）: 領収書マイページは自分宛の領収書のみ取得可。
            // ReceiptMyService は findByIdAndRecipientUserId で宛先本人に絞って引き当てるため、
            // 他人の receiptId は「不在」と区別せず 404 で秘匿するのが宣言どおりの挙動。
            Map.entry("RECEIPT_002", HttpStatus.NOT_FOUND),              // RECEIPT_NOT_FOUND（宛先不一致も含め 404 秘匿）
            // 認可監査 Wave6 ロットC: F08.4 領収書発行の残り未登録分。
            //  - RECEIPT_001/003/021 は単純な not-found → 404
            //  - RECEIPT_005（発行者設定未登録）/008（既に無効化済み）/009（未無効化での再発行）/
            //    019（下書きではない）/024（PENDING でない）はいずれも状態前提違反 → 409
            Map.entry("RECEIPT_001", HttpStatus.NOT_FOUND),
            Map.entry("RECEIPT_003", HttpStatus.NOT_FOUND),
            Map.entry("RECEIPT_005", HttpStatus.CONFLICT),
            Map.entry("RECEIPT_008", HttpStatus.CONFLICT),
            Map.entry("RECEIPT_009", HttpStatus.CONFLICT),
            Map.entry("RECEIPT_019", HttpStatus.CONFLICT),
            Map.entry("RECEIPT_021", HttpStatus.NOT_FOUND),
            Map.entry("RECEIPT_024", HttpStatus.CONFLICT),
            // 認可根治戦役 Wave3-B3: moderation の createReReview は actionId 所有者検証(BOLA是正)で MODERATION_EXT_001、越境は 404。
            Map.entry("MODERATION_EXT_001", HttpStatus.NOT_FOUND),       // VIOLATION_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave3-B3: incident は entity 由来 scope で認可判定。ID 直指定 EP で scope 非所属は 404。
            Map.entry("INCIDENT_001", HttpStatus.NOT_FOUND),             // カテゴリ不在／越境（IDOR 秘匿 → 404）
            Map.entry("INCIDENT_002", HttpStatus.NOT_FOUND),             // インシデント不在／越境（IDOR 秘匿 → 404）
            Map.entry("INCIDENT_009", HttpStatus.NOT_FOUND),             // スケジュール不在／越境（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave3 トランシェB5: supporter/property/gallery の *_NOT_FOUND は BOLA 存在秘匿のため 404。
            Map.entry("SUPPORTER_003", HttpStatus.NOT_FOUND),            // 申請不在/越境（IDOR 秘匿 → 404）
            Map.entry("PROPERTY_001", HttpStatus.NOT_FOUND),             // パッケージ不在/越境（IDOR 秘匿 → 404）
            Map.entry("PROPERTY_005", HttpStatus.NOT_FOUND),             // 業者不在/越境（IDOR 秘匿 → 404）
            Map.entry("GALLERY_001", HttpStatus.NOT_FOUND),              // ALBUM_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("GALLERY_002", HttpStatus.NOT_FOUND),              // PHOTO_NOT_FOUND（IDOR 秘匿 → 404）
            // GALLERY_007（UPLOAD_NOT_ALLOWED）は member アップロード不許可アルバムへの明確な認可拒否のため 403。
            Map.entry("GALLERY_007", HttpStatus.FORBIDDEN),              // UPLOAD_NOT_ALLOWED → 403
            // 認可根治戦役 Wave3-B9: budget flat経路（transaction/category/fiscalYear/report）は
            // entity由来（またはfiscalYearId経由の親子鎖）scope認可。越境IDは既存の *_NOT_FOUND と
            // 同一コードで存在秘匿するため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("BUDGET_003", HttpStatus.NOT_FOUND),               // 年度不在／越境（IDOR 秘匿 → 404）
            Map.entry("BUDGET_006", HttpStatus.NOT_FOUND),               // 費目不在／越境（IDOR 秘匿 → 404）
            Map.entry("BUDGET_009", HttpStatus.NOT_FOUND),               // 取引不在／越境（IDOR 秘匿 → 404）
            Map.entry("BUDGET_010", HttpStatus.NOT_FOUND),               // 報告書不在／越境（IDOR 秘匿 → 404）
            Map.entry("BUDGET_021", HttpStatus.NOT_FOUND),               // 添付ファイル不在／越境（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave3-B9: membership 会員証停止/再開・CheckinLocation は entity 由来 scope 認可。
            Map.entry("MEMBERSHIP_001", HttpStatus.NOT_FOUND),           // 会員証不在（IDOR 秘匿 → 404）
            Map.entry("MEMBERSHIP_019", HttpStatus.NOT_FOUND),           // 拠点不在／越境（IDOR 秘匿 → 404）
            // 認可監査 Wave6 ロットC: F02.1 QR会員証機能の残り未登録分。
            //  - MEMBERSHIP_002（他人の会員証への getQrToken/regenerateQr アクセス）は
            //    MemberCardService.java 118-119/153-154行 実測で cardId 直指定＋所有者不一致検知のため、
            //    兄弟 MEMBERSHIP_001（会員証不在）と同一の存在秘匿 → 404（403 だと「その cardId は
            //    存在するが他人のもの」と漏らしてしまう）。
            //  - MEMBERSHIP_004（SUPPORTER によるQR再生成拒否）は明確な認可拒否 → 403
            //  - MEMBERSHIP_003/009/010/016/017/018 はいずれも状態競合（非ACTIVE・無効化済み・
            //    二重スキャン・既にSUSPENDED・REVOKED再有効化不可・非SUSPENDED再有効化不可）→ 409
            Map.entry("MEMBERSHIP_002", HttpStatus.NOT_FOUND),
            Map.entry("MEMBERSHIP_003", HttpStatus.CONFLICT),
            Map.entry("MEMBERSHIP_004", HttpStatus.FORBIDDEN),
            Map.entry("MEMBERSHIP_009", HttpStatus.CONFLICT),
            Map.entry("MEMBERSHIP_010", HttpStatus.CONFLICT),
            Map.entry("MEMBERSHIP_016", HttpStatus.CONFLICT),
            Map.entry("MEMBERSHIP_017", HttpStatus.CONFLICT),
            Map.entry("MEMBERSHIP_018", HttpStatus.CONFLICT),
            // 認可根治戦役 Wave3-B10: knowledgebase の revision/page 親子束縛（pageId/revisionId/
            // parentId/templateId/newParentId）は BOLA 存在秘匿のため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("KB_001", HttpStatus.NOT_FOUND),                   // PAGE_NOT_FOUND（親/移動先/revision所属page束縛も同一コード・IDOR秘匿→404）
            Map.entry("KB_007", HttpStatus.NOT_FOUND),                   // REVISION_NOT_FOUND（page束縛・IDOR秘匿→404）
            Map.entry("KB_010", HttpStatus.NOT_FOUND),                   // TEMPLATE_NOT_FOUND（scope束縛・IDOR秘匿→404）
            // 認可根治戦役 Wave3-B10: translation（content/assignment）の *_NOT_FOUND は
            // 対象ID直指定（translationId/assignmentId）に対する scope 束縛不一致（BOLA）を含む
            // 存在秘匿のため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("TRANSLATION_002", HttpStatus.NOT_FOUND),          // TRANSLATION_NOT_FOUND（scope束縛・IDOR秘匿→404）
            Map.entry("TRANSLATION_009", HttpStatus.NOT_FOUND),          // ASSIGNMENT_NOT_FOUND（translation/scope束縛・IDOR秘匿→404）
            // 認可根治戦役 Wave3-B7: cms（ブログ）の *_NOT_FOUND は、対象エンティティが自スコープ外
            // （BOLA・revisionId/shareId が postId 配下でない場合を含む）でも同一コードで返す存在秘匿の要。
            // Severity.WARN 既定の 400 のままだと IDOR 秘匿の慣例（他ドメイン同様）に反するため 404 へ上書きする。
            Map.entry("CMS_001", HttpStatus.NOT_FOUND),                  // POST_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("CMS_002", HttpStatus.NOT_FOUND),                  // TAG_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("CMS_003", HttpStatus.NOT_FOUND),                  // SERIES_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("CMS_004", HttpStatus.NOT_FOUND),                  // REVISION_NOT_FOUND（親post不一致BOLA → 404）
            Map.entry("CMS_019", HttpStatus.NOT_FOUND),                  // SHARE_NOT_FOUND（親post不一致BOLA → 404）
            // 認可監査 Wave6 ロットC: F06.1 CMS・ブログの残り未登録分。
            //  - CMS_006/008/010/013/021 は重複・状態遷移違反・既に公開済み・既にリアクション済み等の状態競合 → 409
            //  - CMS_012（ソーシャルプロフィール名義記事の共有不可）は書込禁止リソースへの操作拒否 → 403
            //    （TODO_073 SYSTEM_LABEL_IMMUTABLE と同じ流儀）
            //  - CMS_022（リアクション不在）/024（チーム不在）/025（組織不在）は not-found → 404
            //    （CMS_024/025 は兄弟 TEAM_001/ORG_001 と揃える）
            Map.entry("CMS_006", HttpStatus.CONFLICT),
            Map.entry("CMS_008", HttpStatus.CONFLICT),
            Map.entry("CMS_010", HttpStatus.CONFLICT),
            Map.entry("CMS_012", HttpStatus.FORBIDDEN),
            Map.entry("CMS_013", HttpStatus.CONFLICT),
            Map.entry("CMS_021", HttpStatus.CONFLICT),
            Map.entry("CMS_022", HttpStatus.NOT_FOUND),
            Map.entry("CMS_023", HttpStatus.CONFLICT),
            Map.entry("CMS_024", HttpStatus.NOT_FOUND),
            Map.entry("CMS_025", HttpStatus.NOT_FOUND),
            // 認可根治戦役 Wave3-B12notif: confirmable notification（F04.9）は notificationId↔pathスコープ
            // 突合の BOLA 対策で SCOPE_MISMATCH を新設・NOT_FOUND と同様に 404 秘匿する必要がある。
            // Severity.WARN 既定の 400 のままだと存在有無が漏れる（他ドメイン同様の慣例に合わせて上書き）。
            Map.entry("CONFIRMABLE_NOTIFICATION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("CONFIRMABLE_NOTIFICATION_SCOPE_MISMATCH", HttpStatus.NOT_FOUND),
            // 認可根治戦役 Wave7: テンプレートの templateId↔pathスコープ突合の BOLA 対策で
            // TEMPLATE_NOT_FOUND を存在秘匿の404に上書きする（CMS_004 と同様、不存在・スコープ
            // 不一致のいずれも同一コードで返す）。Severity.WARN 既定の 400 のままだと
            // 実在有無が漏れる。
            Map.entry("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND", HttpStatus.NOT_FOUND),
            // 認可根治戦役 Wave5: ticket（F08.5 回数券）の *_NOT_FOUND は、対象エンティティが自チーム外、
            // または顧客面で他人の所有物である場合にも同一コードで返す存在秘匿の要。
            // Severity.WARN 既定の 400 のままだと ID の実在有無が判別でき、購入者の存在が推測可能になる。
            Map.entry("TICKET_001", HttpStatus.NOT_FOUND),               // PRODUCT_NOT_FOUND（teamId 束縛・IDOR 秘匿 → 404）
            Map.entry("TICKET_002", HttpStatus.NOT_FOUND),               // BOOK_NOT_FOUND（teamId 束縛＋所有者不一致 → 404）
            Map.entry("TICKET_003", HttpStatus.NOT_FOUND),               // CONSUMPTION_NOT_FOUND（親book不一致BOLA → 404）
            Map.entry("TICKET_004", HttpStatus.NOT_FOUND),               // PAYMENT_NOT_FOUND（book 経由束縛・IDOR 秘匿 → 404）
            // 認可監査 Wave6 ロットC: F08.5 回数券の残り未登録分。
            // TICKET_007/008/011/012/015/016/018/019/020/021/022 はいずれも「現在の状態では実行できない」
            // 業務ルール違反（残数0・非ACTIVE・取消済み・期限超過・販売停止中等）のため状態競合 → 409。
            // TICKET_023(QR_TOKEN_INVALID)/024(QR_PAYLOAD_INVALID) は兄弟 JOB_QR_TOKEN_EXPIRED/
            // JOB_QR_SHORT_CODE_NOT_FOUND（いずれも400）と揃え、未登録のまま 400 で正しい。
            Map.entry("TICKET_007", HttpStatus.CONFLICT),
            Map.entry("TICKET_008", HttpStatus.CONFLICT),
            Map.entry("TICKET_011", HttpStatus.CONFLICT),
            Map.entry("TICKET_012", HttpStatus.CONFLICT),
            Map.entry("TICKET_015", HttpStatus.CONFLICT),
            Map.entry("TICKET_016", HttpStatus.CONFLICT),
            Map.entry("TICKET_018", HttpStatus.CONFLICT),
            Map.entry("TICKET_019", HttpStatus.CONFLICT),
            Map.entry("TICKET_020", HttpStatus.CONFLICT),
            Map.entry("TICKET_021", HttpStatus.CONFLICT),
            Map.entry("TICKET_022", HttpStatus.CONFLICT),
            // 認可根治戦役 Wave7: service（F07.1 カスタムフィールド定義・設定、テンプレート）は
            // teamId/organizationId 束縛で fetch した entity 由来スコープで認可する。他スコープの
            // ID を自スコープの scopeId で叩いた場合（BOLA）も同一コードで返す存在秘匿の要のため 404
            //（Severity.WARN 既定の 400 のままだと「404 で秘匿したつもり」の看板倒れになる）。
            Map.entry("SERVICE_RECORD_002", HttpStatus.NOT_FOUND),       // FIELD_NOT_FOUND（teamId 束縛・IDOR 秘匿 → 404）
            Map.entry("SERVICE_RECORD_003", HttpStatus.NOT_FOUND),       // TEMPLATE_NOT_FOUND（teamId/organizationId 束縛・IDOR 秘匿 → 404）
            // ロットD追補: service（F07.1 サービス履歴）の未登録分。RECORD_NOT_FOUND/ATTACHMENT_NOT_FOUND は
            // findByIdAndTeamId 等で teamId 束縛して fetch した entity 由来スコープであり、越境時も同一コードで
            // 返す存在秘匿の要のため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("SERVICE_RECORD_001", HttpStatus.NOT_FOUND),       // RECORD_NOT_FOUND（teamId 束縛・IDOR 秘匿 → 404）
            Map.entry("SERVICE_RECORD_004", HttpStatus.NOT_FOUND),       // ATTACHMENT_NOT_FOUND（recordId 束縛の親子BOLA → 404）
            Map.entry("SERVICE_RECORD_021", HttpStatus.NOT_FOUND),       // SETTINGS_NOT_FOUND（チーム設定未作成 → 404）
            // 既に確定済みの記録を再確定しようとする状態競合 → 409
            Map.entry("SERVICE_RECORD_010", HttpStatus.CONFLICT),        // ALREADY_CONFIRMED
            // ダッシュボード共有・リアクション機能が無効なチームでの操作拒否は権限不足ではなく
            // 機能フラグによる拒否だが、既存の FRIEND_FEATURE_DISABLED（SOCIAL_109）と流儀を揃え 403。
            Map.entry("SERVICE_RECORD_013", HttpStatus.FORBIDDEN),       // DASHBOARD_NOT_ENABLED
            Map.entry("SERVICE_RECORD_014", HttpStatus.FORBIDDEN),       // REACTION_NOT_ENABLED
            // 自分以外の記録へのリアクションは対象の存在を知った上での権限不足 → 403
            Map.entry("SERVICE_RECORD_015", HttpStatus.FORBIDDEN),       // NOT_OWN_RECORD
            // 認可根治戦役 Wave7: proxyvote（F08.3 議案の投票開始/終了）は motionId → session の
            // entity 由来スコープで認可する。存在しない／越境の議案・セッションは同一コードで
            // 秘匿するため 404（Severity.WARN 既定の 400 を上書き）。
            Map.entry("PROXY_VOTE_001", HttpStatus.NOT_FOUND),           // SESSION_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("PROXY_VOTE_002", HttpStatus.NOT_FOUND),           // MOTION_NOT_FOUND（IDOR 秘匿 → 404）
            // 認可根治戦役 Wave7: onboarding（メンバー本人の進捗詳細取得/ステップ完了）は
            // progress.userId と本人を突合し、不一致は同一コードで返す存在秘匿のため 404
            //（Severity.WARN 既定の 400 のままだと「404 で秘匿したつもり」の看板倒れになる）。
            Map.entry("ONBOARDING_003", HttpStatus.NOT_FOUND),           // PROGRESS_NOT_FOUND（本人以外は IDOR 秘匿 → 404）
            // ─────────────────────────────────────────────────────────────
            // エラーコード HTTP ステータス契約の全数分類（2026-07-29・関連 #2468）
            //
            // Severity.ERROR 既定の 500 を返していたコードのうち「サーバ側の障害ではなく
            // クライアント起因」と分類したものは、定義側の Severity を ERROR → WARN に正した
            // （＝既定 400）。件数上限・入力不備の類は本リポジトリの圧倒的多数が 400 であり
            //（「上限超過」系 154 件中 400 が 113 件・409 は 21 件）、既定 400 で揃うため
            // ここには登録していない。件数上限を 409 に倒すと、enum 定数名まで同一の兄弟コード
            //（例: RESERVATION_015 と SCHEDULE_008 の MAX_REMINDERS_EXCEEDED）と割れる。
            //
            // 下記 1 件のみ、既定 400 では表現できない契約があるため明示登録する。
            //
            // F03.11 市（募集）§5.2 / §17.5: 未払いのキャンセル料が残っている状態での申込は
            // 設計書が 402 Payment Required を契約として明示している（未払い決済リンクを返す前提）。
            // Severity.ERROR 既定の 500 のままでは「サーバ障害」に見え、支払い導線に繋がらなかった。
            Map.entry("RECRUITMENT_301", HttpStatus.PAYMENT_REQUIRED),   // CANCELLATION_PAYMENT_FAILED（未払いキャンセル料による申込ブロック）
            // ─────────────────────────────────────────────────────────────
            // 宣言と実挙動の一致（2026-07-30・#2468 / 番人 ErrorCodeHttpStatusDeclarationGuardTest）
            //
            // 以下は ErrorCode 定義側の Javadoc（および対応する設計書のエラー表）が返すべき
            // HTTP ステータスを明示しているのに本表へ未登録で、実際には Severity 既定
            // （WARN=400 / ERROR=500）が返っていたものである。とくに 404 を宣言している
            // コードが未登録のままだと「不在も越境も同一コードに畳んで存在を秘匿する」という
            // 設計意図が実現されない。以後の取りこぼしは番人テスト
            // {@link com.mannschaft.app.common.architecture.ErrorCodeHttpStatusDeclarationGuardTest}
            // が機械的に検出する。
            //
            // F02.6 お知らせウィジェット §「認可・可視性」: 不在／非帰属／不可視をすべて
            // ANNOUNCE_001 に畳んで存在を秘匿し、権限不足は ANNOUNCE_002 で 403 を返す契約。
            Map.entry("ANNOUNCE_001", HttpStatus.NOT_FOUND),             // 不在・非帰属・不可視を畳んだ存在秘匿 → 404
            Map.entry("ANNOUNCE_002", HttpStatus.FORBIDDEN),             // 操作権限なし（著者でも ADMIN でもない）→ 403
            Map.entry("ANNOUNCE_003", HttpStatus.CONFLICT),              // 同一コンテンツの重複登録 → 409
            Map.entry("ANNOUNCE_004", HttpStatus.CONFLICT),              // ピン留め上限（5件）到達 → 409
            Map.entry("ANNOUNCE_006", HttpStatus.NOT_FOUND),             // 対象コンテンツ不在 → 404
            Map.entry("ANNOUNCE_008", HttpStatus.NOT_FOUND),             // テンプレート不在 → 404
            Map.entry("ANNOUNCE_009", HttpStatus.FORBIDDEN),             // テンプレート操作権限なし → 403
            Map.entry("ANNOUNCE_010", HttpStatus.CONFLICT),              // テンプレート上限超過 → 409
            Map.entry("BROADCAST_003", HttpStatus.NOT_FOUND),            // 一斉配信テンプレート不在 → 404
            // 405 は handleMethodNotSupported が直接返しており本表を経由しないが、
            // BusinessException 経路で投げられた場合にも宣言どおり 405 になるよう登録する
            //（兄弟の COMMON_005 も同じ理由で登録済み）。
            Map.entry(CommonErrorCode.COMMON_004.getCode(), HttpStatus.METHOD_NOT_ALLOWED),
            // F09.14 重要事項説明書 §4 エラー表: 409/412/422/429/503 を契約として明示している。
            Map.entry("DISCLOSURE_003", HttpStatus.CONFLICT),            // バージョン競合（楽観ロック）→ 409
            Map.entry("DISCLOSURE_005", HttpStatus.PRECONDITION_FAILED), // property_history モジュール未有効 → 412
            Map.entry("DISCLOSURE_006", HttpStatus.UNPROCESSABLE_ENTITY),// 様式の有効期限切れ → 422
            Map.entry("DISCLOSURE_007", HttpStatus.UNPROCESSABLE_ENTITY),// 必須項目未入力 → 422
            Map.entry("DISCLOSURE_008", HttpStatus.UNPROCESSABLE_ENTITY),// 自動引用元データ取得不可 → 422
            Map.entry("DISCLOSURE_009", HttpStatus.TOO_MANY_REQUESTS),   // エクスポート頻度制限 → 429
            Map.entry("DISCLOSURE_010", HttpStatus.SERVICE_UNAVAILABLE), // 生成サービス一時障害 → 503（従来 500）
            Map.entry("DISCLOSURE_011", HttpStatus.UNPROCESSABLE_ENTITY),// 自動削除予定日の延長範囲違反 → 422
            Map.entry("DISCLOSURE_013", HttpStatus.UNPROCESSABLE_ENTITY),// カスタム様式の件数上限超過 → 422
            Map.entry("DISCLOSURE_014", HttpStatus.FORBIDDEN),           // システム提供様式の編集／削除拒否 → 403
            // F09.12 備品ランキング §エラー表: 404/409 を契約として明示している。
            // ERANK_001（初回バッチ未実行）は設計書が 503 を宣言しているが、5xx は
            // error_reports 記録・Slack エスカレーション経路に乗るため運用判断が必要で保留。
            Map.entry("ERANK_002", HttpStatus.CONFLICT),                 // 二重 opt-out → 409
            Map.entry("ERANK_003", HttpStatus.NOT_FOUND),                // opt-out 未設定で DELETE → 404
            Map.entry("ERANK_004", HttpStatus.NOT_FOUND),                // 除外設定不在 → 404
            Map.entry("ERANK_005", HttpStatus.CONFLICT),                 // 集計バッチの二重起動 → 409
            Map.entry("ERANK_006", HttpStatus.CONFLICT),                 // 除外設定の重複 → 409
            // F09.13 物件履歴台帳 §4 エラー表: 403/409/413/422/429 を契約として明示している。
            Map.entry("PROPERTY_002", HttpStatus.FORBIDDEN),             // 閲覧権限なし → 403
            Map.entry("PROPERTY_003", HttpStatus.CONFLICT),              // バージョン競合（楽観ロック）→ 409
            Map.entry("PROPERTY_006", HttpStatus.CONFLICT),              // 業者名重複 → 409
            Map.entry("PROPERTY_007", HttpStatus.UNPROCESSABLE_ENTITY),  // BudgetTransaction 連携エラー → 422
            Map.entry("PROPERTY_008", HttpStatus.UNPROCESSABLE_ENTITY),  // SharedFile が他スコープで紐付け不可 → 422
            Map.entry("PROPERTY_009", HttpStatus.PAYLOAD_TOO_LARGE),     // 添付ファイル数上限超過 → 413
            Map.entry("PROPERTY_010", HttpStatus.TOO_MANY_REQUESTS),     // エクスポート頻度制限 → 429
            // F03.11 市（募集）§9.10: 表示済みキャンセル料と実額の乖離は再試算を促す 409。
            Map.entry("RECRUITMENT_308", HttpStatus.CONFLICT),           // CANCELLATION_FEE_MISMATCH → 409
            // F03.11 市（募集）ErrorCodeHttpStatusDeclarationGuardTest 是正（ロットA）:
            // 募集本体・テンプレート・NO_SHOW記録・ペナルティの不在（IDOR 秘匿含む）→ 404。
            // 全 throw 元（RecruitmentListingService/RecruitmentParticipantService/
            // RecruitmentNoShowService/RecruitmentSubcategoryService/RecruitmentCancellationPolicyService/
            // RecruitmentTemplateService）を確認し、いずれも findById 系の解決失敗のみで throw される。
            // PENALTY_SETTING_NOT_FOUND（RECRUITMENT_312）は throw 元が存在しない未使用定数のため対象外。
            Map.entry("RECRUITMENT_001", HttpStatus.NOT_FOUND),          // LISTING_NOT_FOUND
            Map.entry("RECRUITMENT_313", HttpStatus.NOT_FOUND),          // TEMPLATE_NOT_FOUND
            Map.entry("RECRUITMENT_309", HttpStatus.NOT_FOUND),          // NO_SHOW_RECORD_NOT_FOUND
            Map.entry("RECRUITMENT_310", HttpStatus.NOT_FOUND),          // PENALTY_NOT_FOUND
            // RecruitmentNoShowService.dispute(): NO_SHOW 記録は findById で取得済み（存在確認後）で、
            // 本人以外のレコードを操作しようとした場合に VISIBILITY_DENIED を throw する。
            // 既定 400 のままだと「レコードは実在するが本人でない」ことが 404（不在）と区別できてしまい、
            // recordId の列挙で他人の NO_SHOW 記録の存在を判別できる IDOR となるため、
            // NOT_FOUND 系と同一の 404 に畳んで存在秘匿する。
            Map.entry("RECRUITMENT_003", HttpStatus.NOT_FOUND),          // VISIBILITY_DENIED（本人以外の NO_SHOW 記録操作を存在秘匿）
            // RecruitmentListingService.getListing(): DRAFT 募集は作成者/スコープ ADMIN のみ閲覧可。
            // 対象は findOrThrow 済み（存在は前提）で、権限不足のみを理由に拒否するため 403（F00 の
            // 「NOT_FOUND→404, deny→403」規約と同型）。
            Map.entry("RECRUITMENT_020", HttpStatus.FORBIDDEN),          // DRAFT_VIEW_DENIED
            // 状態遷移違反（不正な状態遷移・DRAFT申込不可・完了済み編集不可・重複申込/キャンセル/異議）→ 409。
            // throw 元（RecruitmentListingService/RecruitmentParticipantService/RecruitmentNoShowService/
            // RecruitmentSubcategoryService/RecruitmentPenaltyService）はいずれも現在の状態を理由に
            // 操作を拒否するガードであり、入力値自体の不備ではない。
            Map.entry("RECRUITMENT_100", HttpStatus.CONFLICT),           // INVALID_STATE_TRANSITION
            Map.entry("RECRUITMENT_102", HttpStatus.CONFLICT),           // ALREADY_CANCELLED
            Map.entry("RECRUITMENT_103", HttpStatus.CONFLICT),           // DRAFT_NOT_APPLICABLE
            Map.entry("RECRUITMENT_104", HttpStatus.CONFLICT),           // COMPLETED_NOT_EDITABLE
            Map.entry("RECRUITMENT_105", HttpStatus.CONFLICT),           // ALREADY_APPLIED
            Map.entry("RECRUITMENT_311", HttpStatus.CONFLICT),           // ALREADY_DISPUTED
            // ErrorCode ステータス写像是正ロットA: 短時間の申込多発はレート制限であり 429 が正しい
            // （他ドメインの *_RATE_LIMITED と同型。RecruitmentParticipantService.apply）。
            Map.entry("RECRUITMENT_208", HttpStatus.TOO_MANY_REQUESTS),  // APPLY_RATE_LIMIT_EXCEEDED
            // 以下は入力値の件数上限・締切超過であり、本表冒頭の「上限超過系は既定 400 に揃える」
            // 方針（本リポジトリの圧倒的多数が 400）に従い据え置く: RECRUITMENT_101
            // （DEADLINE_EXCEEDED、RESERVATION_026 CANCEL_DEADLINE_PASSED と同型）、
            // RECRUITMENT_106（WAITLIST_LIMIT_EXCEEDED、RESERVATION_049 と同型）。
            // F08.8 修繕計画: カンバン／カード／申し送りパック／任期はテナント・スコープ不一致を
            // 同一コードに畳んで存在を秘匿する設計のため 404（定義側 Javadoc が明示）。
            Map.entry("REPAIR_PLAN_017", HttpStatus.NOT_FOUND),          // KANBAN_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("REPAIR_PLAN_018", HttpStatus.NOT_FOUND),          // CARD_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("REPAIR_PLAN_020", HttpStatus.NOT_FOUND),          // PACK_NOT_FOUND（IDOR 秘匿 → 404）
            Map.entry("REPAIR_PLAN_021", HttpStatus.NOT_FOUND),          // TERM_NOT_FOUND（IDOR 秘匿 → 404）
            // F08.7.1 大会連絡スペース: 不在・非参加を同一コードに畳んで秘匿するため 404。
            Map.entry("TOUR_029", HttpStatus.NOT_FOUND),                 // CONTACT_SPACE_NOT_FOUND（IDOR 秘匿 → 404）
            // F17.1 村ニュースレター §4.2: 凍結済み号の集計値更新は状態遷移違反のため 409
            //（兄弟の VILLAGE_086 / VILLAGE_089 と流儀を揃える）。
            Map.entry("VILLAGE_087", HttpStatus.CONFLICT),               // NEWSLETTER_ISSUE_ALREADY_FROZEN → 409
            // F05.2 回覧板: 不在は存在秘匿のため 404、状態不整合・重複登録は 409、
            // 権限拒否は 403（Severity.WARN 既定の 400 を上書き）。CirculationErrorCode の Javadoc に対応。
            Map.entry("CIRCULATION_001", HttpStatus.NOT_FOUND),          // DOCUMENT_NOT_FOUND（スコープ不一致も畳む秘匿）
            Map.entry("CIRCULATION_002", HttpStatus.NOT_FOUND),          // RECIPIENT_NOT_FOUND（本人以外も畳む秘匿）
            Map.entry("CIRCULATION_003", HttpStatus.NOT_FOUND),          // ATTACHMENT_NOT_FOUND
            Map.entry("CIRCULATION_004", HttpStatus.NOT_FOUND),          // COMMENT_NOT_FOUND（他文書も畳む秘匿）
            Map.entry("CIRCULATION_005", HttpStatus.CONFLICT),           // INVALID_DOCUMENT_STATUS
            Map.entry("CIRCULATION_006", HttpStatus.CONFLICT),           // INVALID_RECIPIENT_STATUS
            Map.entry("CIRCULATION_007", HttpStatus.CONFLICT),           // DUPLICATE_RECIPIENT
            Map.entry("CIRCULATION_008", HttpStatus.CONFLICT),           // SEQUENTIAL_ORDER_VIOLATION
            Map.entry("CIRCULATION_009", HttpStatus.CONFLICT),           // DOCUMENT_ALREADY_STAMPED
            Map.entry("CIRCULATION_010", HttpStatus.FORBIDDEN),          // COMMENT_NOT_OWNED
            Map.entry("CIRCULATION_011", HttpStatus.CONFLICT),           // DOCUMENT_OVERDUE
            Map.entry("CIRCULATION_015", HttpStatus.CONFLICT),           // CORRECTION_WINDOW_EXPIRED
            Map.entry("CIRCULATION_016", HttpStatus.CONFLICT),           // NOT_STAMPED_CANNOT_CORRECT
            Map.entry("CIRCULATION_018", HttpStatus.CONFLICT),           // DELEGATION_ALREADY_EXISTS
            Map.entry("CIRCULATION_019", HttpStatus.FORBIDDEN),          // ADMIN_REQUIRED
            Map.entry("CIRCULATION_020", HttpStatus.CONFLICT),           // ATTACHMENT_NOT_DELETABLE
            Map.entry("CIRCULATION_021", HttpStatus.CONFLICT),           // EXPORT_NOT_AVAILABLE_NON_COMPLETED
            Map.entry("CIRCULATION_022", HttpStatus.CONFLICT),           // EXPORT_NOT_REQUESTED

            // ─────────────────────────────────────────────────────────────
            // 認可監査 Wave6 ロットB（ErrorCode HTTP ステータス写像是正）
            // F06.4 活動記録: 不在は 404、自分の投稿でない編集拒否は 403、
            // フィールド型の変更は既存フィールドとの状態競合のため 409（Severity.WARN 既定 400 を上書き）
            Map.entry("ACTIVITY_001", HttpStatus.NOT_FOUND),             // ACTIVITY_NOT_FOUND（DRAFT 非公開の秘匿にも使用）
            Map.entry("ACTIVITY_002", HttpStatus.NOT_FOUND),             // TEMPLATE_NOT_FOUND
            Map.entry("ACTIVITY_004", HttpStatus.NOT_FOUND),             // COMMENT_NOT_FOUND
            Map.entry("ACTIVITY_008", HttpStatus.FORBIDDEN),             // NOT_AUTHOR（自分の投稿以外は編集不可）
            Map.entry("ACTIVITY_016", HttpStatus.NOT_FOUND),             // PRESET_NOT_FOUND
            Map.entry("ACTIVITY_018", HttpStatus.CONFLICT),              // FIELD_TYPE_CHANGE_NOT_ALLOWED（既存フィールドとの型競合）

            // F02.2 ダッシュボード: チャットフォルダの不在/所有者不一致・アイテム不在は 404/403、
            // 同名フォルダ重複は 409（Severity.WARN 既定 400 を上書き）
            Map.entry("DASHBOARD_006", HttpStatus.NOT_FOUND),            // FOLDER_NOT_FOUND
            Map.entry("DASHBOARD_007", HttpStatus.FORBIDDEN),            // FOLDER_NOT_OWNED（存在は隠さず権限拒否）
            Map.entry("DASHBOARD_008", HttpStatus.CONFLICT),             // FOLDER_NAME_DUPLICATE
            Map.entry("DASHBOARD_016", HttpStatus.NOT_FOUND),            // FOLDER_ITEM_NOT_FOUND

            // F09.16 居住実態管理・見守り: 年次更新/訪問記録の不在は 404、閲覧権限拒否は 403、
            // クローズ済み・訂正期限超過は状態競合のため 409。RESIDENCE_STATUS_004 は他居住者の
            // residentRegistryId を指定した越境を「不在」として存在秘匿する（BOLA 対策）。
            Map.entry("RESIDENCE_STATUS_001", HttpStatus.NOT_FOUND),     // ANNUAL_REVIEW_NOT_FOUND
            Map.entry("RESIDENCE_STATUS_002", HttpStatus.CONFLICT),      // ANNUAL_REVIEW_ALREADY_CLOSED
            Map.entry("RESIDENCE_STATUS_003", HttpStatus.CONFLICT),      // ANNUAL_REVIEW_YEAR_CONFLICT
            Map.entry("RESIDENCE_STATUS_004", HttpStatus.NOT_FOUND),     // ANNUAL_REVIEW_RESPONSE_NOT_FOUND（他居住者IDの越境を存在秘匿）
            Map.entry("RESIDENCE_STATUS_007", HttpStatus.FORBIDDEN),     // SNAPSHOT_SELF_ACCESS_FORBIDDEN
            Map.entry("RESIDENCE_STATUS_008", HttpStatus.FORBIDDEN),     // DASHBOARD_ACCESS_FORBIDDEN
            Map.entry("RESIDENCE_STATUS_009", HttpStatus.FORBIDDEN),     // SNAPSHOT_ACCESS_FORBIDDEN
            Map.entry("RESIDENCE_STATUS_012", HttpStatus.NOT_FOUND),     // MONITORING_VISIT_NOT_FOUND
            Map.entry("RESIDENCE_STATUS_013", HttpStatus.CONFLICT),      // MONITORING_VISIT_UPDATE_EXPIRED（訂正期限超過）

            // F08.2 支払い管理: 二重支払い・二重返金・返金不可な状態・種別変更は状態競合のため 409
            Map.entry("PAYMENT_004", HttpStatus.CONFLICT),               // ALREADY_PAID
            Map.entry("PAYMENT_007", HttpStatus.CONFLICT),               // TYPE_IMMUTABLE（type 変更は既存記録との競合）
            Map.entry("PAYMENT_009", HttpStatus.CONFLICT),               // ALREADY_REFUNDED
            Map.entry("PAYMENT_010", HttpStatus.CONFLICT),               // MANUAL_PAYMENT_NOT_REFUNDABLE
            Map.entry("PAYMENT_011", HttpStatus.CONFLICT),               // PENDING_PAYMENT_NOT_REFUNDABLE
            Map.entry("PAYMENT_020", HttpStatus.CONFLICT),               // STRIPE_PAYMENT_ONLY（決済手段との不整合）

            // F08.3 議決権行使・委任状: 不在は 404、コメント削除権限拒否は 403、
            // ステータス前提違反・議案未確定・委任状態競合は 409（Severity.WARN 既定 400 を上書き）
            Map.entry("PROXY_VOTE_003", HttpStatus.NOT_FOUND),           // COMMENT_NOT_FOUND
            Map.entry("PROXY_VOTE_004", HttpStatus.NOT_FOUND),           // ATTACHMENT_NOT_FOUND
            Map.entry("PROXY_VOTE_005", HttpStatus.NOT_FOUND),           // DELEGATION_NOT_FOUND
            Map.entry("PROXY_VOTE_006", HttpStatus.NOT_FOUND),           // VOTE_NOT_FOUND
            Map.entry("PROXY_VOTE_015", HttpStatus.FORBIDDEN),           // NOT_COMMENT_OWNER
            Map.entry("PROXY_VOTE_020", HttpStatus.CONFLICT),            // STATUS_MUST_BE_DRAFT
            Map.entry("PROXY_VOTE_021", HttpStatus.CONFLICT),            // STATUS_MUST_BE_OPEN
            Map.entry("PROXY_VOTE_022", HttpStatus.CONFLICT),            // STATUS_MUST_BE_CLOSED
            Map.entry("PROXY_VOTE_023", HttpStatus.CONFLICT),            // STATUS_MUST_BE_CLOSED_OR_FINALIZED
            Map.entry("PROXY_VOTE_024", HttpStatus.CONFLICT),            // STATUS_MUST_BE_FINALIZED
            Map.entry("PROXY_VOTE_025", HttpStatus.CONFLICT),            // SESSION_NOT_UPDATABLE
            Map.entry("PROXY_VOTE_040", HttpStatus.CONFLICT),            // MEETING_MODE_ONLY
            Map.entry("PROXY_VOTE_041", HttpStatus.CONFLICT),            // MOTION_NOT_PENDING
            Map.entry("PROXY_VOTE_042", HttpStatus.CONFLICT),            // MOTION_NOT_VOTING
            Map.entry("PROXY_VOTE_043", HttpStatus.CONFLICT),            // NO_PENDING_MOTIONS
            Map.entry("PROXY_VOTE_044", HttpStatus.CONFLICT),            // NOT_ALL_MOTIONS_VOTED
            Map.entry("PROXY_VOTE_050", HttpStatus.CONFLICT),            // INCOMPLETE_VOTES
            Map.entry("PROXY_VOTE_051", HttpStatus.CONFLICT),            // NON_VOTING_MOTION_INCLUDED
            Map.entry("PROXY_VOTE_052", HttpStatus.CONFLICT),            // ALREADY_VOTED
            Map.entry("PROXY_VOTE_063", HttpStatus.CONFLICT),            // ALREADY_VOTED_CANNOT_DELEGATE
            Map.entry("PROXY_VOTE_064", HttpStatus.CONFLICT),            // ALREADY_DELEGATED
            Map.entry("PROXY_VOTE_065", HttpStatus.CONFLICT),            // DELEGATION_NOT_SUBMITTED
            Map.entry("PROXY_VOTE_066", HttpStatus.CONFLICT),            // DELEGATION_ALREADY_RESOLVED
            Map.entry("PROXY_VOTE_074", HttpStatus.CONFLICT),            // UPLOAD_NOT_ALLOWED（現在ステータスでアップロード不可）

            // 広告（AD_*）: 不在は 404、過去料金テーブル削除・請求書/増額申請のステータス不整合・
            // 終了済みクリエイティブの更新・キャンペーン不一致クリエイティブは状態競合のため 409
            Map.entry("AD_001", HttpStatus.NOT_FOUND),                   // アフィリエイト設定不在
            Map.entry("AD_005", HttpStatus.NOT_FOUND),                   // 広告主アカウント不在
            Map.entry("AD_008", HttpStatus.NOT_FOUND),                   // 料金テーブル不在
            Map.entry("AD_009", HttpStatus.CONFLICT),                    // 過去の料金テーブルは削除不可
            Map.entry("AD_011", HttpStatus.NOT_FOUND),                   // 条件に一致する料金が見つからない
            Map.entry("AD_013", HttpStatus.NOT_FOUND),                   // 請求書不在
            Map.entry("AD_014", HttpStatus.CONFLICT),                    // 請求書のステータスが操作に適合しない
            Map.entry("AD_016", HttpStatus.NOT_FOUND),                   // レポートスケジュール不在
            Map.entry("AD_017", HttpStatus.NOT_FOUND),                   // 増額申請不在
            Map.entry("AD_018", HttpStatus.CONFLICT),                    // 処理中の増額申請が既に存在
            Map.entry("AD_019", HttpStatus.CONFLICT),                    // 増額申請のステータスが操作に適合しない
            Map.entry("AD_024", HttpStatus.NOT_FOUND),                   // クリエイティブ不在
            Map.entry("AD_025", HttpStatus.CONFLICT),                    // 削除済みクリエイティブは更新不可
            Map.entry("AD_026", HttpStatus.NOT_FOUND),                   // キャンペーンとクリエイティブの不一致（越境の存在秘匿）

            // F09.3 駐車場区画管理: 不在は既存 001〜007/024〜026 に揃え、状態競合（占有中・重複・
            // ステータス不整合）は 409、自分の割当てでない操作は 403。SCOPE_MISMATCH は
            // 呼び出し元スコープに属さない区画IDの指定であり、既存の TEAM_001/PAYMENT_ITEM 系と
            // 同じ「越境は存在秘匿」の流儀で 404 に揃える。
            Map.entry("PARKING_009", HttpStatus.CONFLICT),               // SPACE_ALREADY_OCCUPIED
            Map.entry("PARKING_011", HttpStatus.CONFLICT),               // SPACE_NOT_VACANT
            Map.entry("PARKING_013", HttpStatus.CONFLICT),               // NOT_ACCEPTING_APPLICATIONS
            Map.entry("PARKING_014", HttpStatus.CONFLICT),               // APPLICATION_ALREADY_EXISTS
            Map.entry("PARKING_015", HttpStatus.CONFLICT),               // INVALID_APPLICATION_STATUS
            Map.entry("PARKING_018", HttpStatus.CONFLICT),               // TIME_SLOT_CONFLICT
            Map.entry("PARKING_020", HttpStatus.NOT_FOUND),              // SCOPE_MISMATCH（越境区画IDの存在秘匿）
            Map.entry("PARKING_021", HttpStatus.CONFLICT),               // PLATE_NUMBER_DUPLICATE
            Map.entry("PARKING_022", HttpStatus.CONFLICT),               // INVALID_LISTING_STATUS
            Map.entry("PARKING_023", HttpStatus.CONFLICT),               // INVALID_VISITOR_STATUS
            Map.entry("PARKING_027", HttpStatus.CONFLICT),               // INVALID_SUBLEASE_STATUS
            Map.entry("PARKING_028", HttpStatus.CONFLICT),               // INVALID_SUBLEASE_APPLICATION_STATUS
            Map.entry("PARKING_031", HttpStatus.FORBIDDEN),              // NOT_OWN_ASSIGNMENT（存在は隠さず権限拒否）

            // F02.5 ポイっとメモ: メモ/添付/音声同意の不在は既存 QM_010 の流儀（BOLA 秘匿）に揃え 404、
            // 変換済み・タグ使用中・同名重複は状態競合のため 409
            Map.entry("QM_001", HttpStatus.NOT_FOUND),                   // MEMO_NOT_FOUND（findByIdAndUserId で BOLA 秘匿）
            Map.entry("QM_003", HttpStatus.CONFLICT),                    // MEMO_ALREADY_CONVERTED
            Map.entry("QM_011", HttpStatus.CONFLICT),                    // TAG_NAME_DUPLICATE
            Map.entry("QM_013", HttpStatus.CONFLICT),                    // TAG_IN_USE
            Map.entry("QM_020", HttpStatus.NOT_FOUND),                   // ATTACHMENT_NOT_FOUND
            Map.entry("QM_030", HttpStatus.NOT_FOUND),                   // VOICE_CONSENT_NOT_FOUND

            // 認可監査 Wave6 ロットF: 登録ゼロだった未着手 enum の是正。throw 元の実コードを
            // 全て洗い、404=存在秘匿/不在、403=存在を隠さず権限拒否、409=状態競合、
            // 401=認証失敗の観点で判定した。入力検証・上限超過系は既定 400 のまま据え置き。
            //
            // F12.5 エラーレポート: 不在は 404。IGNORED 状態での工程変更拒否・GitHub Issue
            // 作成の重複ロック・二重作成防止は状態競合のため 409。
            Map.entry("ERROR_REPORT_001", HttpStatus.NOT_FOUND),            // ERROR_REPORT_NOT_FOUND
            Map.entry("ERROR_REPORT_005", HttpStatus.CONFLICT),             // IGNORED時の工程更新拒否
            Map.entry("ERROR_REPORT_009", HttpStatus.CONFLICT),             // GitHub Issue作成の重複ロック
            Map.entry("ERROR_REPORT_012", HttpStatus.CONFLICT),             // GitHub Issue二重作成防止

            // Webhook/外部API連携: エンドポイント不在は404。APIキー期限切れは認証失敗のため401。
            // WEBHOOK_005/007 はトークン/APIキーの管理系CRUD不在と受信認証失敗の両方で
            // 使われており意味が割れているため変更を見送る。
            Map.entry("WEBHOOK_001", HttpStatus.NOT_FOUND),                 // Webhookエンドポイント不在
            Map.entry("WEBHOOK_011", HttpStatus.UNAUTHORIZED),              // APIキー有効期限切れ（認証失敗）

            // F09.1 住民台帳: 不在は404、重複登録・退去済み・確認済み・編集不可等の状態競合は409。
            Map.entry("RESIDENT_001", HttpStatus.NOT_FOUND),                // DWELLING_UNIT_NOT_FOUND
            Map.entry("RESIDENT_002", HttpStatus.CONFLICT),                 // DUPLICATE_UNIT_NUMBER
            Map.entry("RESIDENT_003", HttpStatus.NOT_FOUND),                // RESIDENT_NOT_FOUND
            Map.entry("RESIDENT_004", HttpStatus.NOT_FOUND),                // DOCUMENT_NOT_FOUND
            Map.entry("RESIDENT_005", HttpStatus.NOT_FOUND),                // LISTING_NOT_FOUND
            Map.entry("RESIDENT_006", HttpStatus.CONFLICT),                 // DUPLICATE_INQUIRY
            Map.entry("RESIDENT_008", HttpStatus.CONFLICT),                 // ALREADY_MOVED_OUT
            Map.entry("RESIDENT_009", HttpStatus.CONFLICT),                 // ALREADY_VERIFIED
            Map.entry("RESIDENT_010", HttpStatus.NOT_FOUND),                // MY_UNIT_NOT_FOUND
            Map.entry("RESIDENT_011", HttpStatus.CONFLICT),                 // LISTING_NOT_EDITABLE

            // ゲーミフィケーション: 設定/ルール/バッジ不在は404。システムルールの変更拒否は
            // 存在を隠さず権限拒否のため403（サービス javadoc に既存の意図表記あり）。
            // バージョン不一致（楽観ロック）は409。スコープ不一致は越境の存在秘匿で404。
            Map.entry("GAMIFICATION_001", HttpStatus.NOT_FOUND),            // 設定不在
            Map.entry("GAMIFICATION_002", HttpStatus.NOT_FOUND),            // ポイントルール不在
            Map.entry("GAMIFICATION_003", HttpStatus.NOT_FOUND),            // バッジ不在
            Map.entry("GAMIFICATION_004", HttpStatus.FORBIDDEN),            // システムルールの変更拒否
            Map.entry("GAMIFICATION_006", HttpStatus.CONFLICT),             // バージョン不一致（楽観ロック）
            Map.entry("GAMIFICATION_008", HttpStatus.NOT_FOUND),            // スコープ不一致（越境の存在秘匿）

            // F05.3 電子印鑑: 印鑑/押印ログ不在は404。バリアント重複・取消済み・削除済みは409。
            Map.entry("SEAL_001", HttpStatus.NOT_FOUND),                    // SEAL_NOT_FOUND
            Map.entry("SEAL_002", HttpStatus.CONFLICT),                     // DUPLICATE_VARIANT
            Map.entry("SEAL_005", HttpStatus.NOT_FOUND),                    // STAMP_LOG_NOT_FOUND
            Map.entry("SEAL_006", HttpStatus.CONFLICT),                     // ALREADY_REVOKED
            Map.entry("SEAL_009", HttpStatus.CONFLICT),                     // SEAL_DELETED

            // F04.3 プッシュ通知: 通知/購読不在は404。購読の重複登録は409。
            Map.entry("NOTIFICATION_001", HttpStatus.NOT_FOUND),            // NOTIFICATION_NOT_FOUND
            Map.entry("NOTIFICATION_004", HttpStatus.NOT_FOUND),            // SUBSCRIPTION_NOT_FOUND
            Map.entry("NOTIFICATION_005", HttpStatus.CONFLICT),             // SUBSCRIPTION_ALREADY_EXISTS

            // 経営分析: アラートルール/スナップショット不在は404。バックフィル多重実行は409。
            Map.entry("ANALYTICS_001", HttpStatus.NOT_FOUND),               // アラートルール不在
            Map.entry("ANALYTICS_002", HttpStatus.NOT_FOUND),               // スナップショット不在
            Map.entry("ANALYTICS_003", HttpStatus.CONFLICT),                // バックフィル実行中の多重実行

            // F01.2 ロール・権限管理: ロール/権限グループ/パーミッション不在は404。招待トークンの
            // 無効/期限切れは既存の招待トークン系（EVENT_007/FAMILY_029/CONTACT_014 等）と流儀を
            // 揃え存在秘匿で404。最後の管理者の除名・変更拒否は状態競合で409。上位ロールの
            // ブロック拒否は存在を隠さず権限拒否のため403。
            Map.entry("ROLE_001", HttpStatus.NOT_FOUND),                    // ロール不在
            Map.entry("ROLE_002", HttpStatus.NOT_FOUND),                    // 招待トークン無効/期限切れ（存在秘匿）
            Map.entry("ROLE_004", HttpStatus.CONFLICT),                     // 最後の管理者の除名・変更拒否
            Map.entry("ROLE_005", HttpStatus.FORBIDDEN),                    // 上位ロールのブロック拒否
            Map.entry("ROLE_006", HttpStatus.NOT_FOUND),                    // 権限グループ不在
            Map.entry("ROLE_007", HttpStatus.NOT_FOUND),                    // パーミッション不在

            // デジタルサイネージ: 画面/スロット/トークン不在は404。アクセストークン検証失敗は
            // GCAL_009（GOOGLE_WEBHOOK_TOKEN_INVALID）と同じ流儀でアクセス拒否として403。
            Map.entry("SIGNAGE_001", HttpStatus.NOT_FOUND),                 // 画面不在
            Map.entry("SIGNAGE_002", HttpStatus.FORBIDDEN),                 // トークン無効/IP制限によるアクセス拒否
            Map.entry("SIGNAGE_003", HttpStatus.NOT_FOUND),                 // スロット不在
            Map.entry("SIGNAGE_005", HttpStatus.NOT_FOUND),                 // トークン不在

            // スキル・資格管理: 資格不在は404。重複登録・楽観ロック不一致・承認対象外ステータスは
            // 409。SKILL_001（名称重複／非アクティブカテゴリ／カテゴリ不在で意味が割れている）・
            // SKILL_003（スコープ不一致の存在秘匿と本人以外操作の権限拒否の両方に使われ意味が
            // 割れている）は変更を見送る。
            Map.entry("SKILL_002", HttpStatus.NOT_FOUND),                   // 資格不在
            Map.entry("SKILL_005", HttpStatus.CONFLICT),                    // 同一資格の重複登録
            Map.entry("SKILL_006", HttpStatus.CONFLICT),                    // バージョン不一致（楽観ロック）
            Map.entry("SKILL_007", HttpStatus.CONFLICT),                    // 承認対象外ステータスでの承認操作

            // F12.3 GDPR/個人情報管理: エクスポート処理中の多重実行・唯一のSYSTEM_ADMIN退会拒否は
            // 状態競合のため409。GDPR_003（不在／未完了／期限切れの3意味で共用）は変更を見送る。
            Map.entry("GDPR_002", HttpStatus.CONFLICT),                     // エクスポート処理中の多重実行
            Map.entry("GDPR_006", HttpStatus.CONFLICT)                      // 唯一のSYSTEM_ADMIN退会拒否
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
     * F20.1 402 details 追補: {@link FeatureNotEntitledException} 専用ハンドラ（金型:
     * {@link #handleMilestoneLocked}）。
     *
     * <p>共通の {@link ErrorResponse}/{@link ErrorResponse.ErrorDetail}/{@link BusinessException} は
     * 一切変更しない（AC-19 バイト不変）。{@link FeatureNotEntitledException} は {@link BusinessException} の
     * サブクラスだが、Spring は最も具体的な例外型のハンドラを優先して選択するため、本メソッドが
     * {@link #handleBusinessException} より優先して呼ばれる。403（{@code FEATURE_FORBIDDEN_FOR_SCOPE}）は
     * details を持たない素の {@link BusinessException} のままであり、本ハンドラは通らず従来どおり
     * {@link #handleBusinessException} を通る（AC-16）。</p>
     *
     * <p>4xx（402）のため error_reports への記録はしない（{@link #handleBusinessException} と同じ方針）。</p>
     */
    @ExceptionHandler(FeatureNotEntitledException.class)
    public ResponseEntity<FeatureNotEntitledErrorResponse> handleFeatureNotEntitled(
            FeatureNotEntitledException ex) {
        String message = resolveMessage(ex.getErrorCode());
        log.warn("FeatureNotEntitledException: code={}, featureKey={}",
                ex.getErrorCode().getCode(), ex.getDetails().getFeatureKey());
        FeatureNotEntitledErrorResponse body =
                new FeatureNotEntitledErrorResponse(ex.getErrorCode().getCode(), message, ex.getDetails());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
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
        // (1) 変換器が明示的に 404 を投げ、原因連鎖に残っているケースを honor する。
        ResponseStatusException rse = findResponseStatusExceptionCause(ex);
        boolean notFound = rse != null && rse.getStatusCode().value() == HttpStatus.NOT_FOUND.value();

        // (2) team/organization のスコープ識別子パス変数に非数値 slug が渡り、解決に失敗したケース。
        //     {@link ScopeSlugIdConverter} は不在 slug で 404 を投げるが、Spring の型変換フォールバック
        //     （既定 Long エディタ）が例外を握り潰して NumberFormatException 化するため、原因連鎖からは
        //     404 意図が失われる。ここでパス変数名と値から補完し、400 でなく 404 を返す。
        if (!notFound && isUnresolvedScopeSlug(ex)) {
            notFound = true;
        }

        if (notFound) {
            log.debug("スコープ識別子の解決失敗（404）: parameter={}, value={}", ex.getName(), ex.getValue());
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of(CommonErrorCode.COMMON_005));
        }
        log.warn("Type mismatch: parameter={}, value={}", ex.getName(), ex.getValue());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_001));
    }

    /** team / organization のスラッグ解決に使うパス変数名。 */
    private static final Set<String> SCOPE_ID_PATH_VARS = Set.of("organizationId", "orgId", "teamId");

    /**
     * スコープ識別子（team/organization）のパス変数に非数値 slug が渡り、解決不能だったかを判定する。
     * 数値であれば本来変換可能（＝別要因の 400）なので false。
     */
    private boolean isUnresolvedScopeSlug(MethodArgumentTypeMismatchException ex) {
        if (!SCOPE_ID_PATH_VARS.contains(ex.getName())) {
            return false;
        }
        Class<?> required = ex.getRequiredType();
        if (required != Long.class && required != long.class) {
            return false;
        }
        if (!(ex.getValue() instanceof String s)) {
            return false;
        }
        try {
            Long.parseLong(s);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * 例外の原因連鎖を辿り、最初に見つかった {@link ResponseStatusException} を返す。見つからなければ null。
     */
    private ResponseStatusException findResponseStatusExceptionCause(Throwable ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof ResponseStatusException rse) {
                return rse;
            }
            cause = cause.getCause();
        }
        return null;
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
     * 必須リクエストヘッダの欠落（例: F20.1 契約作成の {@code Idempotency-Key} 必須）。
     *
     * <p>{@code @RestControllerAdvice} の catch-all が先に拾って 500 になるのを防ぎ、
     * クライアントエラーとして 400 を返す（{@link MissingServletRequestParameterException} と同型）。</p>
     */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        log.warn("Missing header: {}", ex.getHeaderName());
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
     * 未マップAPIパスや static リソース不在（Spring Boot 3.x の
     * {@link NoResourceFoundException}）。
     *
     * <p>Spring Boot 3.x では、ディスパッチャが一致するハンドラを見つけられない場合に
     * {@code NoResourceFoundException} が投げられる。デフォルトでは catch-all
     * {@link #handleUnexpectedException} に落ちて 500 + HIGH 記録されてしまうため、
     * 明示的に 404 NOT_FOUND へマッピングし、エラー集約へは記録しない。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        log.debug("リソース未検出: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(CommonErrorCode.COMMON_005));
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
     * {@link ResponseStatusException} を、送出時の HTTP ステータスを保ったまま
     * プロジェクト共通の {@link ErrorResponse} 形式へ変換する。
     *
     * <p><b>なぜ必要か:</b> 本ハンドラが無いと {@code ResponseStatusException}
     * （{@code RuntimeException} の子孫）は catch-all の {@link #handleUnexpectedException} に落ち、
     * 本来の 4xx を失って 500 {@code COMMON_999}「システムエラーが発生しました」になる。
     * 実測でブログ画像の枚数上限超過（サービスは 422 を送出）がクライアントには 500 で届いており、
     * フロントエンドが「上限に達しました」と案内できず一律システムエラー扱いになっていた。</p>
     *
     * <p><b>code:</b> 独自形式を作らず、既存の {@link CommonErrorCode} へステータス単位で対応付ける
     * （{@link #handleBusinessException} と同じ envelope に揃えるため）。</p>
     *
     * <p><b>message:</b> {@link ResponseStatusException#getReason()} を優先する。reason は実装者が
     * 利用者向けに書いた日本語文言（例:「画像は 30 枚まで」）であり、汎用のコードメッセージより
     * 具体的で UX 上有用なため。reason が空なら {@link #resolveMessage} の多言語メッセージへ
     * フォールバックする。</p>
     *
     * <p><b>5xx の扱い:</b> reason は内部情報を含み得るため伏せ、{@code COMMON_999} の定型文を返す。
     * あわせて {@link #recordBackendException} へ severity=MEDIUM で記録する
     * （5xx のみ記録する {@link #handleBusinessException} の方針と同一）。</p>
     *
     * <p><b>優先順位:</b> Spring の {@code ExceptionHandlerMethodResolver} は例外型階層で最も近い
     * ハンドラを選ぶため、本ハンドラは {@code @ExceptionHandler(Exception.class)} より優先される
     * （宣言順には依存しない）。</p>
     *
     * <p><b>⚠️ 実装者への注意:</b> 本ハンドラの導入により、<b>4xx の {@code reason} は必ず
     * レスポンス本文としてクライアントへ公開される</b>（従来は 500 に潰れて外へ出なかった）。
     * {@code ResponseStatusException} を新規に書く際は、reason に内部状態・SQL・内部 ID・
     * 他ユーザーの情報などの機微情報を書かないこと。利用者にそのまま見せてよい文言のみを入れる。
     * 5xx の reason のみ本ハンドラが伏せる。</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex,
                                                                       HttpServletRequest request) {
        HttpStatusCode status = ex.getStatusCode();
        CommonErrorCode errorCode = resolveCommonErrorCode(status);

        if (status.is5xxServerError()) {
            log.error("ResponseStatusException(5xx): status={}", status.value(), ex);
            recordBackendException(ex, request, ErrorReportSeverity.MEDIUM);
            return ResponseEntity.status(status)
                    .headers(ex.getHeaders())
                    .body(ErrorResponse.of(errorCode));
        }

        String reason = ex.getReason();
        String message = (reason != null && !reason.isBlank()) ? reason : resolveMessage(errorCode);
        log.warn("ResponseStatusException: status={}, code={}, reason={}",
                status.value(), errorCode.getCode(), reason);
        return ResponseEntity.status(status)
                .headers(ex.getHeaders())
                .body(new ErrorResponse(
                        new ErrorResponse.ErrorDetail(errorCode.getCode(), message, List.of())));
    }

    /**
     * 既存ユニットテスト互換用 overload。
     */
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        return handleResponseStatusException(ex, null);
    }

    /**
     * HTTP ステータスに対応する共通エラーコードを返す。
     * 既存の意味づけ（COMMON_000=未認証 / 002=認可 / 003=競合 / 004=メソッド不一致 / 005=不在）に合わせ、
     * 該当しない 4xx は入力不備（COMMON_001）、5xx はシステムエラー（COMMON_999）へ寄せる。
     */
    private static CommonErrorCode resolveCommonErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> CommonErrorCode.COMMON_000;
            case 403 -> CommonErrorCode.COMMON_002;
            case 404 -> CommonErrorCode.COMMON_005;
            case 405 -> CommonErrorCode.COMMON_004;
            case 409 -> CommonErrorCode.COMMON_003;
            default -> status.is5xxServerError()
                    ? CommonErrorCode.COMMON_999
                    : CommonErrorCode.COMMON_001;
        };
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
     * <p><strong>{@link ErrorCode.Severity} の唯一の消費者はこのメソッドである。</strong>
     * ログレベルの決定には使われていない（{@link #handleBusinessException} は severity に
     * 関わらず常に {@code log.warn}）。したがって {@code Severity.ERROR} を付けることは
     * 実質的に「既定で HTTP 500 を返す」宣言と同義であり、クライアント起因のエラーに
     * 付けてはならない。500 になると {@code status.is5xxServerError()} 経由で
     * error_reports への記録と Slack エスカレーションまで走るため、入力不備のたびに
     * 障害アラートが鳴る。</p>
     *
     * <p>是正の手順（対処療法の禁止）:</p>
     * <ol>
     *   <li>クライアント起因なら、まず ErrorCode 定義側の {@code Severity} を WARN に正す
     *       （宣言箇所で意図が読めるようになる）。既定の 400 で妥当ならこれで足りる。</li>
     *   <li>既定の 400 では表現できない契約がある場合に限り {@link #ERROR_CODE_STATUS_MAP}
     *       へ明示登録する（不在の秘匿は 404・重複や状態遷移違反・楽観ロック競合は 409・
     *       支払いが必要なら 402 など）。なお「件数上限の超過」は本リポジトリでは 400 が
     *       圧倒的多数派であり、409 に倒すと同一概念の兄弟コードと割れるので注意する。</li>
     * </ol>
     * <p>「Severity.ERROR のまま STATUS_MAP に 4xx を足す」だけの直し方は、定義側に誤分類を
     * 残したまま症状だけを隠すため禁止する。</p>
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
