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
    VILLAGE_LOBBY_THREAD_CREATED(AuditEventCategory.VILLAGE);

    private final AuditEventCategory category;
}
