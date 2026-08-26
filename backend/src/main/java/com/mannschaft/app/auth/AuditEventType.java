package com.mannschaft.app.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuditEventType {

    // ─── AUTH ───────────────────────────────────────────────
    LOGIN_SUCCESS(AuditEventCategory.AUTH),
    LOGIN_FAILED(AuditEventCategory.AUTH),
    WEBAUTHN_LOGIN(AuditEventCategory.AUTH),
    WEBAUTHN_LOGIN_FAILED(AuditEventCategory.AUTH),
    WEBAUTHN_CREDENTIAL_REGISTERED(AuditEventCategory.AUTH),
    WEBAUTHN_CREDENTIAL_REMOVED(AuditEventCategory.AUTH),
    LOGOUT(AuditEventCategory.AUTH),
    LOGOUT_SESSION(AuditEventCategory.AUTH),
    LOGOUT_ALL_SESSIONS(AuditEventCategory.AUTH),
    TOKEN_REUSE_DETECTED(AuditEventCategory.AUTH),
    DEVICE_FINGERPRINT_MISMATCH(AuditEventCategory.AUTH),
    NEW_DEVICE_LOGIN(AuditEventCategory.AUTH),

    // ─── ACCOUNT ────────────────────────────────────────────
    USER_REGISTERED(AuditEventCategory.ACCOUNT),
    OAUTH_USER_REGISTERED(AuditEventCategory.ACCOUNT),
    EMAIL_VERIFIED(AuditEventCategory.ACCOUNT),
    PASSWORD_RESET_REQUESTED(AuditEventCategory.ACCOUNT),
    PASSWORD_RESET_COMPLETED(AuditEventCategory.ACCOUNT),
    ACCOUNT_LOCKED(AuditEventCategory.ACCOUNT),
    PASSWORD_CHANGED(AuditEventCategory.ACCOUNT),
    PASSWORD_SETUP(AuditEventCategory.ACCOUNT),
    EMAIL_CHANGE_REQUESTED(AuditEventCategory.ACCOUNT),
    EMAIL_CHANGED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_REQUESTED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_CANCELLED(AuditEventCategory.ACCOUNT),
    WITHDRAWAL_COMPLETED(AuditEventCategory.ACCOUNT),
    PENDING_USER_CLEANED_UP(AuditEventCategory.ACCOUNT),
    /** Phase F: SYSTEM_ADMIN が GDPR ドメインパージを手動で retry した。 */
    DOMAIN_PURGE_RETRIED(AuditEventCategory.ACCOUNT),
    /** F01.10 履歴書/職務経歴書の正式出力。 */
    RESUME_EXPORTED(AuditEventCategory.ACCOUNT),
    /** F01.10 証明写真アップロード。 */
    RESUME_PHOTO_UPLOADED(AuditEventCategory.ACCOUNT),
    /** F01.10 出力レート制限到達。 */
    RESUME_EXPORT_RATE_LIMITED(AuditEventCategory.ACCOUNT),

    // ─── OAUTH ──────────────────────────────────────────────
    OAUTH_LINK_REQUESTED(AuditEventCategory.OAUTH),
    OAUTH_LINKED(AuditEventCategory.OAUTH),
    OAUTH_UNLINKED(AuditEventCategory.OAUTH),

    // ─── MFA ────────────────────────────────────────────────
    MFA_ENABLED(AuditEventCategory.MFA),
    MFA_DISABLED(AuditEventCategory.MFA),
    MFA_BACKUP_CODES_REGENERATED(AuditEventCategory.MFA),
    MFA_RECOVERY_REQUESTED(AuditEventCategory.MFA),
    MFA_RECOVERY_COMPLETED(AuditEventCategory.MFA),

    // ─── ADMIN_ACTION ────────────────────────────────────────
    USER_FROZEN(AuditEventCategory.ADMIN_ACTION),
    USER_UNFROZEN(AuditEventCategory.ADMIN_ACTION),
    ACCOUNT_UNLOCKED(AuditEventCategory.ADMIN_ACTION),

    // ─── LIFECYCLE ───────────────────────────────────────────
    USER_ARCHIVED(AuditEventCategory.LIFECYCLE),
    USER_UNARCHIVED(AuditEventCategory.LIFECYCLE),
    TEAM_ARCHIVED(AuditEventCategory.LIFECYCLE),
    TEAM_UNARCHIVED(AuditEventCategory.LIFECYCLE),
    ORGANIZATION_ARCHIVED(AuditEventCategory.LIFECYCLE),
    ORGANIZATION_UNARCHIVED(AuditEventCategory.LIFECYCLE),

    // ─── TEAM (Phase 2+) ─────────────────────────────────────
    TEAM_MEMBER_INVITED(AuditEventCategory.TEAM),
    TEAM_MEMBER_JOINED(AuditEventCategory.TEAM),
    TEAM_MEMBER_ROLE_CHANGED(AuditEventCategory.TEAM),
    TEAM_MEMBER_REMOVED(AuditEventCategory.TEAM),
    TEAM_CREATED(AuditEventCategory.TEAM),
    TEAM_DELETED(AuditEventCategory.TEAM),
    TEAM_MEMBER_BLOCKED(AuditEventCategory.TEAM),
    TEAM_MEMBER_UNBLOCKED(AuditEventCategory.TEAM),
    TEAM_INVITE_TOKEN_CREATED(AuditEventCategory.TEAM),

    // ─── ORGANIZATION (Phase 2+) ──────────────────────────────
    ORGANIZATION_CREATED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_DELETED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_JOINED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_ROLE_CHANGED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_REMOVED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_BLOCKED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_MEMBER_UNBLOCKED(AuditEventCategory.ORGANIZATION),
    ORGANIZATION_INVITE_TOKEN_CREATED(AuditEventCategory.ORGANIZATION),

    // ─── PAYMENT (Phase 3+) ───────────────────────────────────
    PAYMENT_COMPLETED(AuditEventCategory.PAYMENT),
    PAYMENT_REFUNDED(AuditEventCategory.PAYMENT),
    /** F08.9 P8: 支払い明細 CSV をエクスポートした（チーム ADMIN 操作）。 */
    PAYMENT_CSV_EXPORTED(AuditEventCategory.PAYMENT),

    // ─── SCHEDULE (Phase 3+) ──────────────────────────────────
    SCHEDULE_CREATED(AuditEventCategory.SCHEDULE),
    SCHEDULE_UPDATED(AuditEventCategory.SCHEDULE),

    // ─── TODO (F10.3+) ────────────────────────────────────────
    TODO_STATUS_LABEL_CREATED(AuditEventCategory.TODO),
    TODO_STATUS_LABEL_UPDATED(AuditEventCategory.TODO),
    TODO_STATUS_LABEL_DELETED(AuditEventCategory.TODO),
    TODO_HANDED_OFF(AuditEventCategory.TODO),

    // ─── REPAIR_PLAN (F08.8+) ─────────────────────────────────
    SCENARIO_CREATED(AuditEventCategory.REPAIR_PLAN),
    SCENARIO_UPDATED(AuditEventCategory.REPAIR_PLAN),
    SCENARIO_LOCKED_FOR_PROPOSAL(AuditEventCategory.REPAIR_PLAN),
    SCENARIO_PROPOSAL_CONVERTED(AuditEventCategory.REPAIR_PLAN),
    BID_CARD_CREATED(AuditEventCategory.REPAIR_PLAN),
    BID_CARD_MOVED(AuditEventCategory.REPAIR_PLAN),
    BID_DEADLINE_OPENED(AuditEventCategory.REPAIR_PLAN),
    BID_VENDOR_SELECTED(AuditEventCategory.REPAIR_PLAN),
    PACK_GENERATED(AuditEventCategory.REPAIR_PLAN),
    PACK_DOWNLOADED(AuditEventCategory.REPAIR_PLAN),
    TIMELINE_EXPORTED(AuditEventCategory.REPAIR_PLAN),
    EXTERNAL_AGENT_DELEGATION_GRANTED(AuditEventCategory.REPAIR_PLAN),
    EXTERNAL_AGENT_DELEGATION_REVOKED(AuditEventCategory.REPAIR_PLAN),
    PLAN_ITEM_CSV_IMPORTED(AuditEventCategory.REPAIR_PLAN),
    // F08.8 Phase 1 案5: 修繕計画項目 CRUD
    PLAN_ITEM_CREATED(AuditEventCategory.REPAIR_PLAN),
    PLAN_ITEM_UPDATED(AuditEventCategory.REPAIR_PLAN),
    PLAN_ITEM_DELETED(AuditEventCategory.REPAIR_PLAN),

    // ─── NOTIFICATION_CREDIT (F09.13+) ────────────────────────
    /** 通知クレジット購入完了 */
    NOTIFICATION_CREDIT_PURCHASED(AuditEventCategory.PAYMENT),
    /** 通知クレジット有効期限失効 */
    NOTIFICATION_CREDIT_EXPIRED(AuditEventCategory.PAYMENT),
    /** 通知クレジット残高低下警告 */
    NOTIFICATION_CREDIT_LOW_BALANCE(AuditEventCategory.PAYMENT),

    // ─── RESIDENT (F09.15 居住者死亡管理 / F09.16 居住実態管理) ─────────
    DEATH_STATUS_CHANGED_TO_SUSPECTED(AuditEventCategory.RESIDENT),
    DEATH_STATUS_CHANGED_TO_CONFIRMED(AuditEventCategory.RESIDENT),
    DEATH_STATUS_CHANGED_TO_CANCELLED(AuditEventCategory.RESIDENT),
    OCCUPANCY_STATUS_CHANGED(AuditEventCategory.RESIDENT),

    // ─── SUCCESSION (F09.15 入居時誓約 / 事前登録 / 封緘解除) ─────────
    /** 入居時誓約 PDF を発行（PDF 生成 + 内部署名トークン付与）。 */
    COVENANT_ISSUED(AuditEventCategory.SUCCESSION),
    /** 入居時誓約に署名（同意項目を確認した上で succession_covenants へ INSERT）。 */
    COVENANT_SIGNED(AuditEventCategory.SUCCESSION),
    /** 入居時誓約を撤回（revoked_at セット）。 */
    COVENANT_REVOKED(AuditEventCategory.SUCCESSION),

    // ─── SUCCESSION (F09.15 S5/S6 エスカレーション・法的手続き) ─────────
    /** 5 段階エスカレーションを新規起票。 */
    ESCALATION_CREATED(AuditEventCategory.SUCCESSION),
    /** エスカレーションを次のステージへ昇格。 */
    ESCALATION_ADVANCED(AuditEventCategory.SUCCESSION),
    /** エスカレーションを凍結（弁護士介入等）。 */
    ESCALATION_FROZEN(AuditEventCategory.SUCCESSION),
    /** エスカレーションを解決（支払完了・死亡確認・手動クローズ等）。 */
    ESCALATION_RESOLVED(AuditEventCategory.SUCCESSION),
    /** 死亡疑い（STAGE_4）バッチによる自動起票。 */
    DEATH_SUSPECTED_AUTO_TRIGGERED(AuditEventCategory.SUCCESSION),
    /** 法的手続きレコードを起票（申立書 PDF 生成 + S3 アップロード）。 */
    LEGAL_FILING_CREATED(AuditEventCategory.SUCCESSION),
    /** 証拠 ZIP パッケージを生成（S3 アップロード + SHA-256 記録）。 */
    EVIDENCE_PACKAGE_BUILT(AuditEventCategory.SUCCESSION),
    /** 証拠 ZIP の Pre-signed ダウンロード URL を発行。 */
    EVIDENCE_PACKAGE_DOWNLOADED(AuditEventCategory.SUCCESSION),

    // ─── POINT_CARD (F18 個人ポイントカードウォレット) ─────────────
    /** 個人ポイントカードを発行（barcode_value 暗号化 + ownership_token 付与）。 */
    POINT_CARD_CREATED(AuditEventCategory.POINT_CARD),
    /** 個人ポイントカードを削除（物理削除またはアーカイブ）。 */
    POINT_CARD_DELETED(AuditEventCategory.POINT_CARD),
    /** 個人ポイントカードを閲覧（復号値の表示）。 */
    POINT_CARD_VIEWED(AuditEventCategory.POINT_CARD),
    /** ポイントカードグループ（フォルダ）を作成。 */
    POINT_CARD_GROUP_CREATED(AuditEventCategory.POINT_CARD),
    /** ポイントカードグループ（フォルダ）を削除。 */
    POINT_CARD_GROUP_DELETED(AuditEventCategory.POINT_CARD),
    /** ポイントカードウォレット設定を更新（並び順 / 表示設定など）。 */
    POINT_CARD_SETTINGS_UPDATED(AuditEventCategory.POINT_CARD),
    /** 自店発行プロバイダーを作成（Phase 2 organization スコープ）。 */
    POINT_CARD_PROVIDER_CREATED(AuditEventCategory.POINT_CARD),
    /** 自店発行プロバイダーの設定を更新。 */
    POINT_CARD_PROVIDER_UPDATED(AuditEventCategory.POINT_CARD),
    /** 自店発行プロバイダーを停止（is_active=false）。 */
    POINT_CARD_PROVIDER_DEACTIVATED(AuditEventCategory.POINT_CARD),
    /** 自店スタンプを押印（顧客カードへの delta 反映）。 */
    POINT_CARD_STAMP_ISSUED(AuditEventCategory.POINT_CARD),
    /** F18 Phase 3 — 残高型カードへの入金（CHARGE）。 */
    POINT_CARD_BALANCE_CHARGED(AuditEventCategory.POINT_CARD),
    /** F18 Phase 3 — 残高型カードの利用（SPENT）。 */
    POINT_CARD_BALANCE_SPENT(AuditEventCategory.POINT_CARD),
    /** F18 Phase 3 — 残高型カードの返金（REFUND、refund_of_event_id で元 event を参照）。 */
    POINT_CARD_BALANCE_REFUNDED(AuditEventCategory.POINT_CARD),
    /** F18 Phase 5 — fuzzy match 再マッチバッチを実行（夜間バッチで provider_id=NULL カードを再評価）。 */
    POINT_CARD_REMATCH_BATCH_EXECUTED(AuditEventCategory.POINT_CARD),

    // ─── VILLAGE (F17 村機能 Phase 1) ───────────────────────────
    // TODO(F17 Phase 1): 各 Village Service の主要メソッドへ
    //   auditLogService.record(...) 呼び出しを追加する作業は別軍議で対応する。
    //   本 commit では enum 種別の追加のみ。
    /** 村を新規作成（承認後）。 */
    VILLAGE_CREATED(AuditEventCategory.VILLAGE),
    /** 村の設定を更新（名前 / 説明 / ガイドライン等）。 */
    VILLAGE_UPDATED(AuditEventCategory.VILLAGE),
    /** 村を永久凍結（運営判断による archive）。 */
    VILLAGE_ARCHIVED(AuditEventCategory.VILLAGE),
    /** 村メンバーが参加（任意の subject_type）。 */
    VILLAGE_MEMBER_JOINED(AuditEventCategory.VILLAGE),
    /** 村メンバーが退村。 */
    VILLAGE_MEMBER_LEFT(AuditEventCategory.VILLAGE),
    /** 村メンバーを BAN（村長・長老による）。 */
    VILLAGE_MEMBER_BANNED(AuditEventCategory.VILLAGE),
    /** 村内ロールを付与（HEADMAN / ELDER 任命）。 */
    VILLAGE_ROLE_GRANTED(AuditEventCategory.VILLAGE),
    /** 村内ロールを剥奪。 */
    VILLAGE_ROLE_REVOKED(AuditEventCategory.VILLAGE),
    /** 村内通報を提出。 */
    VILLAGE_REPORT_FILED(AuditEventCategory.VILLAGE),
    /** 村内通報を処理（解決 / 却下）。 */
    VILLAGE_REPORT_RESOLVED(AuditEventCategory.VILLAGE),
    /** 村作成申請を提出。 */
    VILLAGE_CREATION_REQUEST_SUBMITTED(AuditEventCategory.VILLAGE),
    /** 村作成申請を審査（承認 / 却下）。 */
    VILLAGE_CREATION_REQUEST_REVIEWED(AuditEventCategory.VILLAGE),
    /** 村参加申請を提出。 */
    VILLAGE_JOIN_REQUEST_SUBMITTED(AuditEventCategory.VILLAGE),
    /** 村参加申請を審査（承認 / 却下）。 */
    VILLAGE_JOIN_REQUEST_REVIEWED(AuditEventCategory.VILLAGE),
    /** 村にチーム名義で投稿（POSTED_AS=TEAM）。 */
    VILLAGE_POSTED_AS_TEAM(AuditEventCategory.VILLAGE),
    /** 村に組織名義で投稿（POSTED_AS=ORGANIZATION）。 */
    VILLAGE_POSTED_AS_ORGANIZATION(AuditEventCategory.VILLAGE),
    /** 村ニックネームを変更。 */
    VILLAGE_NICKNAME_CHANGED(AuditEventCategory.VILLAGE),
    /** お気に入り村をピン留め。 */
    VILLAGE_PINNED(AuditEventCategory.VILLAGE),
    /** お気に入り村のピンを解除。 */
    VILLAGE_UNPINNED(AuditEventCategory.VILLAGE),
    /** 井戸端会議の日次スレッドをバッチで自動生成。 */
    VILLAGE_LOBBY_THREAD_CREATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 2 — 村お祭りを作成。 */
    VILLAGE_FESTIVAL_CREATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 2 — 村お祭りを更新。 */
    VILLAGE_FESTIVAL_UPDATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 2 — 村お祭りを開催開始（バッチ自動遷移 SCHEDULED→ACTIVE）。 */
    VILLAGE_FESTIVAL_ACTIVATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 2 — 村お祭りを終了（バッチ自動遷移 ACTIVE→ENDED）。 */
    VILLAGE_FESTIVAL_ENDED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 2 — 村お祭りを中止（村長/長老の判断）。 */
    VILLAGE_FESTIVAL_CANCELLED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 寄合を作成。 */
    VILLAGE_MEETUP_CREATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 寄合を更新。 */
    VILLAGE_MEETUP_UPDATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 寄合の確定（PLANNING → CONFIRMED、幹事による日付確定）。 */
    VILLAGE_MEETUP_CONFIRMED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 寄合を中止（幹事の判断）。 */
    VILLAGE_MEETUP_CANCELLED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 寄合への投票（新規/変更）。 */
    VILLAGE_MEETUP_VOTED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave1 ②寄合後半戦 — 出欠の登録/変更（upsert・設計書 §16.2）。 */
    VILLAGE_MEETUP_ATTENDANCE_SET(AuditEventCategory.VILLAGE),
    /** F17.2 Wave1 ②寄合後半戦 — 宿題 TODO の手挙げ（claim・設計書 §16.2）。 */
    VILLAGE_MEETUP_TODO_CLAIMED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave1 ②寄合後半戦 — 宿題 TODO の完了（complete・設計書 §16.2）。 */
    VILLAGE_MEETUP_TODO_COMPLETED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave1 ②寄合後半戦 — 宿題 TODO の手放し（release・設計書 §16.2）。 */
    VILLAGE_MEETUP_TODO_RELEASED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave2 ③祭の参加レイヤー — 参加表明（RSVP）の登録/変更（upsert・設計書 §16.2）。 */
    VILLAGE_FESTIVAL_RSVP_SET(AuditEventCategory.VILLAGE),
    /** F17.2 Wave2 ③祭の参加レイヤー — 参加表明（RSVP）の取消（設計書 §16.2）。 */
    VILLAGE_FESTIVAL_RSVP_CANCELLED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave2 ③祭の参加レイヤー — 実況投稿の紐付け（live-post タグ・設計書 §16.2）。 */
    VILLAGE_FESTIVAL_LIVE_POST_TAGGED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave2 ③祭の参加レイヤー — 祭 ENDED 時の村史（行事アーカイブ）自動編纂（設計書 §16.2）。 */
    VILLAGE_FESTIVAL_ARCHIVED(AuditEventCategory.VILLAGE),
    /** F17.2 Wave2 ①行事→村フィード還流 — 行事のシステム名義自動投稿を作成（設計書 §16.2）。 */
    VILLAGE_EVENT_SYSTEM_POSTED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 村史（月次ダイジェスト）を生成（バッチ）。 */
    VILLAGE_CHRONICLE_GENERATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — ご縁スコア更新（日次バッチによる加算反映）。 */
    VILLAGE_SERENDIPITY_UPDATED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β — 巡礼推薦を訪問した。 */
    VILLAGE_PILGRIMAGE_VISITED(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β-E — 村ニュースレター配信（週次/月次バッチ実行）。 */
    VILLAGE_NEWSLETTER_SENT(AuditEventCategory.VILLAGE),
    /** F17.1 Phase 3-β-E — 村ニュースレター opt-out（ユーザー自身の操作）。 */
    VILLAGE_NEWSLETTER_OPT_OUT(AuditEventCategory.VILLAGE),
    /** F17.1 ②-2 — 村ニュースレター号を集計・凍結（集計バッチによる snapshot 確定）。 */
    VILLAGE_NEWSLETTER_ISSUE_FROZEN(AuditEventCategory.VILLAGE),
    /** F17.2 ⑤ — 加入前相性クエリ（差分攻撃の事後検知用・§8.4 緩和3）。 */
    VILLAGE_AFFINITY_QUERIED(AuditEventCategory.VILLAGE),
    /** F17.2 ⑥ — 所属村一覧の公開トグルを切替（本人操作・§9.3）。 */
    VILLAGE_MEMBERSHIP_PROFILE_VISIBILITY_CHANGED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 初回制定（最初の条追加で charter 自動生成・§4.5/§16.2）。 */
    VILLAGE_CHARTER_ENACTED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 条を追加（§16.2）。 */
    VILLAGE_CHARTER_ARTICLE_ADDED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 条の本文/付則を更新（§16.2）。 */
    VILLAGE_CHARTER_ARTICLE_UPDATED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 条を論理削除（§16.2）。 */
    VILLAGE_CHARTER_ARTICLE_DELETED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 条の並び替え（PATCH order・§16.2）。 */
    VILLAGE_CHARTER_REORDERED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 策定者を追加（§16.2）。 */
    VILLAGE_CHARTER_DRAFTER_ADDED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 策定者を削除（§16.2）。 */
    VILLAGE_CHARTER_DRAFTER_REMOVED(AuditEventCategory.VILLAGE),
    /** F17.3 村憲章 — 「改正を確定」（last_revised_at 更新＋履歴追記・§8.2/§16.2）。 */
    VILLAGE_CHARTER_REVISED(AuditEventCategory.VILLAGE),

    // ─── FORM (F05.7 書類テンプレート・フォームビルダー) ──────────
    /** F05.7 Phase 11 第四陣 4-B — フォーム提出 PDF を生成（Thymeleaf + Flying Saucer + R2 アップロード）。 */
    FORM_PDF_GENERATED(AuditEventCategory.FORM),
    /** F05.7 Phase 11 第四陣 4-B — テンプレート単位の未提出者リマインドを送信。 */
    FORM_TEMPLATE_REMIND(AuditEventCategory.FORM),
    /** F05.7 Phase 11 第四陣 4-B — テンプレートを複製。 */
    FORM_TEMPLATE_DUPLICATED(AuditEventCategory.FORM),
    /** F05.7 Phase 11 第四陣 4-B — 提出一覧 CSV をエクスポート。 */
    FORM_SUBMISSIONS_CSV_EXPORTED(AuditEventCategory.FORM),
    // ─── SHIFT (F03.5 シフト管理) ──────────────────────────────
    /** 管理者がシフト希望未提出者に手動でリマインドを送信した。 */
    SHIFT_MANUAL_REMINDER_SENT(AuditEventCategory.SHIFT),

    // ─── SECURITY_RATE_LIMIT (F15.4 組織内チーム検索) ───────────
    /** 組織内チーム検索 API がレート制限に到達した（429 応答）。 */
    TEAM_SEARCH_RATE_LIMITED(AuditEventCategory.SECURITY_RATE_LIMIT),
    /** F15.4 Phase 5-α: 店舗詳細 Public API がレート制限に到達した（429 応答）。 */
    PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED(AuditEventCategory.SECURITY_RATE_LIMIT),
    /**
     * F19.1 Phase 1: 公開ページ API（{@code /api/v1/public/(teams|organizations)/...}）が
     * レート制限に到達した（429 応答）。F15.4 Phase 5-α の {@link #PUBLIC_TEAM_DETAIL_RATE_LIMIT_EXCEEDED}
     * を内包・上位化し、posts / events サブパスを含む全公開エンドポイントの上限超過を統一記録する。
     */
    PUBLIC_API_RATE_LIMIT_EXCEEDED(AuditEventCategory.SECURITY_RATE_LIMIT),

    // ─── CIRCULATION (F05.2 Phase 11 第三陣 3-B) ─────────────────
    /** 押印を訂正した（受信者本人）。 */
    CIRCULATION_STAMP_CORRECTED(AuditEventCategory.CIRCULATION),
    /** 押印を委任した（受信者→代理人）。 */
    CIRCULATION_STAMP_DELEGATED(AuditEventCategory.CIRCULATION),
    /** ADMIN が受信者を強制スキップした。 */
    CIRCULATION_RECIPIENT_SKIPPED(AuditEventCategory.CIRCULATION),
    /** DRAFT 文書から添付ファイルを削除した。 */
    CIRCULATION_ATTACHMENT_DELETED(AuditEventCategory.CIRCULATION),

    /** 押印済み証跡 PDF エクスポートを要求した（Phase 11 4-C）。 */
    CIRCULATION_EXPORT_REQUESTED(AuditEventCategory.CIRCULATION),

    /** 押印済み証跡 PDF エクスポートを生成完了した（Phase 11 4-C）。 */
    CIRCULATION_EXPORT_GENERATED(AuditEventCategory.CIRCULATION),

    // ─── EMAIL_OUTBOX SYSTEM_ADMIN (F09.18 Phase 18-d) ─────────────────
    /** SYSTEM_ADMIN がメール outbox 詳細を閲覧した。 */
    SYSTEM_ADMIN_EMAIL_OUTBOX_VIEWED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が DEAD_LETTER をリトライキューに戻した。 */
    SYSTEM_ADMIN_EMAIL_OUTBOX_RETRIED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が PENDING メールをキャンセルした。 */
    SYSTEM_ADMIN_EMAIL_OUTBOX_CANCELLED(AuditEventCategory.ADMIN_ACTION),

    // ─── BULLETIN (F05.1 掲示板モデレーション) ──────────────────
    /** 他者の掲示板スレッドを削除した（管理者/DEPUTY による削除）。本人削除は記録しない。 */
    BULLETIN_THREAD_DELETED(AuditEventCategory.BULLETIN),
    /** 他者の掲示板返信を削除した（管理者/DEPUTY による削除）。本人削除は記録しない。 */
    BULLETIN_REPLY_DELETED(AuditEventCategory.BULLETIN),
    /** 保管庫フォルダを作成した（設計書 F05.1 §6）。 */
    BULLETIN_ARCHIVE_FOLDER_CREATED(AuditEventCategory.BULLETIN),
    /** 保管庫フォルダを更新・移動した（設計書 F05.1 §6）。 */
    BULLETIN_ARCHIVE_FOLDER_UPDATED(AuditEventCategory.BULLETIN),
    /** 保管庫フォルダを削除した（設計書 F05.1 §6）。 */
    BULLETIN_ARCHIVE_FOLDER_DELETED(AuditEventCategory.BULLETIN),
    /** スレッドの保管庫フォルダ振り分けを変更した（設計書 F05.1 §6）。 */
    BULLETIN_THREAD_ARCHIVE_FOLDER_CHANGED(AuditEventCategory.BULLETIN),
    /** 掲示板の添付ファイルを削除した（本人 or モデレーター/ADMIN）。 */
    BULLETIN_ATTACHMENT_DELETED(AuditEventCategory.BULLETIN),

    // ─── TOURNAMENT (F08.7.1 連絡スペース) ──────────────────
    /** 大会・ディビジョン連絡スペースの公開設定を変更した（誰が・いつ・どのスペースを公開/非公開に）。 */
    TOURNAMENT_CONTACT_SPACE_VISIBILITY_UPDATED(AuditEventCategory.TOURNAMENT),

    // ─── TOURNAMENT (F08.7.1/05 試合メンバー表) ──────────────────
    /** 自チームの試合メンバー表を提出した（誰が・いつ・どの試合の自チーム分を／§5 提出監査）。 */
    TOURNAMENT_ROSTER_SUBMITTED(AuditEventCategory.TOURNAMENT),
    /** 主催組織 ADMIN が試合のメンバー表提出締切を設定/変更した。 */
    TOURNAMENT_ROSTER_DEADLINE_UPDATED(AuditEventCategory.TOURNAMENT),

    // ─── NAV_SETTINGS (F20.1 ナビゲーションバーカスタマイズ) ─────────────
    /** ユーザーが個人ナビ設定を更新した。 */
    NAV_SETTINGS_UPDATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN がナビ項目マスタを作成した。 */
    NAV_FEATURE_CREATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN がナビ項目マスタを更新した。 */
    NAV_FEATURE_UPDATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN がナビ項目マスタを削除した。 */
    NAV_FEATURE_DELETED(AuditEventCategory.ADMIN_ACTION),

    // ─── DASHBOARD_SCOPE_TAB (F22.1 横スワイプ・ダッシュボード) ─────────────
    /** ユーザーがチーム/組織タグの表示順を更新した。 */
    DASHBOARD_SCOPE_TAB_ORDER_UPDATED(AuditEventCategory.ADMIN_ACTION),

    // ─── FEE_POLICY (F22.1 市・統一決済 R2 手数料パターン管理) ─────────────
    /** SYSTEM_ADMIN が手数料パターンを作成した。 */
    FEE_POLICY_CREATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が手数料パターンを更新した。 */
    FEE_POLICY_UPDATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が手数料パターンを無効化した。 */
    FEE_POLICY_DISABLED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が手数料パターン割当を作成した。 */
    FEE_POLICY_ASSIGNMENT_CREATED(AuditEventCategory.ADMIN_ACTION),
    /** SYSTEM_ADMIN が手数料パターン割当を解除した。 */
    FEE_POLICY_ASSIGNMENT_DELETED(AuditEventCategory.ADMIN_ACTION),

    // ─── RECRUITMENT_CANCELLATION_FEE (F03.11.1 募集キャンセル料の徴収) ─────────────
    /**
     * F03.11.1 募集キャンセル料が免除された（受取先側の管理者または SYSTEM_ADMIN による）。
     * 免除は金銭債権を消す操作であり、誰がいつ何円を消したかを後から追える記録が唯一の手がかりになる。
     */
    RECRUITMENT_CANCELLATION_FEE_WAIVED(AuditEventCategory.ADMIN_ACTION),

    // ─── GUARDIANSHIP_SWITCH (F08.9 P3c 後見切替セッション) ─────────────
    /**
     * 保護者が子として後見切替セッションを開始した（acting-as 開始・03_security §3.2 二重記録）。
     * userId=保護者 / targetUserId=子。proxy_input_records にも併せて追記する。
     */
    GUARDIANSHIP_SWITCH_STARTED(AuditEventCategory.PAYMENT),
    /**
     * 後見切替セッションを終了した（acting-as 終了・本人へ復帰）。
     * userId=保護者 / targetUserId=子。
     */
    GUARDIANSHIP_SWITCH_ENDED(AuditEventCategory.PAYMENT),

    /**
     * 保護者が子の自立移行の引き継ぎ（パスワード設定リンク送付）を開始した（F08.9 P3c-2・02_api §2.3）。
     * userId=保護者 / targetUserId=子。childEmail を新規登録した場合は metadata に記録する。
     */
    GUARDIANSHIP_HANDOVER_INITIATED(AuditEventCategory.PAYMENT),

    // ─── PAYMENT_REQUEST (F08.9 P7 協会→加盟チーム請求) ─────────────
    /**
     * 協会(ORG)が加盟チーム(TEAM)への請求を発行した（DRAFT 起票・F08.9 P7・02_api §7）。
     * userId=発行者(協会 ADMIN) / metadata に paymentRequestId・payerTeamId・faceAmount。
     */
    PAYMENT_REQUEST_CREATED(AuditEventCategory.PAYMENT),
    /**
     * 協会が請求を配信した（DRAFT → SENT・確認必須通知一斉配信・F08.9 P7 第二波・02_api §7）。
     * userId=操作者(協会 ADMIN) / metadata に paymentRequestId・confirmableNotificationId・recipientCount。
     */
    PAYMENT_REQUEST_SENT(AuditEventCategory.PAYMENT),
    /**
     * 協会が請求を取消した（DRAFT/SENT → CANCELLED・F08.9 P7・02_api §7）。
     * userId=操作者(協会 ADMIN) / metadata に paymentRequestId。
     */
    PAYMENT_REQUEST_CANCELLED(AuditEventCategory.PAYMENT),
    /**
     * チーム ADMIN が協会請求を支払った（PAID・案3 立替課金・F08.9 P7・02_api §7）。
     * userId=操作者(チーム ADMIN) / teamId=請求先チーム / metadata に paymentRequestId・escrowId・advanceId。
     */
    PAYMENT_REQUEST_PAID(AuditEventCategory.PAYMENT),
    /**
     * チーム ADMIN が立替金の精算を確認した（PENDING → SETTLED・F08.9 P7・02_api §7）。
     * userId=確認者(チーム ADMIN) / teamId=チーム / metadata に advanceId・paymentRequestId。
     */
    PAYMENT_ADVANCE_SETTLED(AuditEventCategory.PAYMENT),

    // ─── MATCH (F08.10 試合記録・分析) ──────────────────────────────
    /**
     * 試合スコアを確定した（メタ更新・03 §C.7）。
     * metadata に matchId・操作者・teamId・before/after（home/away/PK スコア）を含める。
     */
    MATCH_SCORE_FINALIZED(AuditEventCategory.MATCH),
    /**
     * 試合の status を遷移した（COMPLETED / CANCELLED / POSTPONED 等・03 §C.7）。
     * metadata に matchId・before/after status を含める。
     */
    MATCH_STATUS_CHANGED(AuditEventCategory.MATCH),
    /**
     * 記録モードを切替えた（公式戦⇔共同記録・has_scorekeeper 変更・03 §C.7）。
     */
    MATCH_RECORDING_MODE_CHANGED(AuditEventCategory.MATCH),
    /**
     * 記録係（scorekeeper_user_id）を変更した（03 §C.7）。
     */
    MATCH_SCOREKEEPER_CHANGED(AuditEventCategory.MATCH);

    private final AuditEventCategory category;
}
