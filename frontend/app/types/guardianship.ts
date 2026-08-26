/**
 * F08.9 P3a/P5 後見切替（guardianship）の手書き型定義。
 *
 * BE DTO（SwitchableChildDto / BlockedChildDto / SwitchableChildrenResponse）と
 * camelCase 1:1 で対応する。生成型（types/generated）が整備されたら段階移行する。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §2.1
 *
 * ---
 *
 * F08.9 件2（保護者による子データ閲覧専用見守り・05_guardian_child_view.md）の
 * API req/res 型は生成型（openapi-typescript・#2158 で再生成済み）を優先使用し、
 * ここでは薄い別名としてのみ再エクスポートする（手書き型でのBE型二重持ちを避ける）。
 */
import type { components } from '~/types/generated'

/** ① 子の予定（横断カレンダー）1件。schedule ドメイン既存 DTO の再利用（生成型そのまま）。 */
export type ChildCalendarEntry = components['schemas']['CalendarEntryResponse']
/** ② 子の出席率統計。schedule ドメイン既存 DTO の再利用（生成型そのまま）。 */
export type ChildAttendanceStats = components['schemas']['AttendanceStatsResponse']
/** ③ 子の所属チーム/組織レスポンス。 */
export type GuardianChildMembershipsResponse = components['schemas']['GuardianChildMembershipsResponse']
/** ③ スコープ参照（チーム/組織どちらも scopeId + name の同一形）。 */
export type GuardianScopeRef = components['schemas']['ScopeRef']
/** ④ 子のお知らせ受信レスポンス（ページング）。 */
export type GuardianChildAnnouncementsResponse = components['schemas']['GuardianChildAnnouncementsResponse']
/** ④ お知らせ 1 件（掲示板スレッドの縮約）。 */
export type GuardianAnnouncementItem = components['schemas']['AnnouncementItem']
/** 代理履歴（件3）レスポンス。subject=子 の代理入力記録のみ。 */
export type GuardianChildProxyActionsResponse = components['schemas']['GuardianChildProxyActionsResponse']
/** 代理入力 1 件。 */
export type GuardianProxyActionItem = components['schemas']['ProxyActionItem']

/**
 * 切替可能な子（後見切替が許可される段階の子）。
 * BE: SwitchableChildDto に 1:1 対応（GET /api/v1/me/guardianship/switchable-children の children[]）。
 */
export interface SwitchableChild {
  /** 子（受益者）のユーザー ID。 */
  childUserId: number
  /** 子の表示名（UI 表示用）。 */
  displayName: string | null
  /** 年齢段階の i18n ラベルキー（日本＝elementary）。 */
  stageKey: string | null
  /** 常に true（切替可能な子のみがこのリストに入る）。 */
  switchAllowed: boolean
}

/**
 * 封印された子（保護者リンクはあるが年齢ポリシーで後見切替できない子）。
 * BE: BlockedChildDto に 1:1 対応（blockedChildren[]）。
 */
export interface BlockedChild {
  /** 子（受益者）のユーザー ID。 */
  childUserId: number
  /** 子の表示名（UI 表示用）。 */
  displayName: string | null
  /** 年齢段階の i18n ラベルキー。 */
  stageKey: string | null
  /** 常に false（封印された子）。 */
  switchAllowed: boolean
}

/**
 * 切替可能な子の一覧レスポンス。
 * BE: SwitchableChildrenResponse に 1:1 対応。
 */
export interface SwitchableChildrenResponse {
  /** 切替可能（switchAllowed=true）な子の一覧。 */
  children: SwitchableChild[]
  /** 封印（switchAllowed=false）された子の一覧。 */
  blockedChildren: BlockedChild[]
}

/**
 * 自立移行ステータスレスポンス。
 * BE: IndependenceStatusResponse に 1:1 対応。
 * GET /api/v1/me/guardianship/children/{childUserId}/independence-status
 */
export interface IndependenceStatusResponse {
  /** 対象の子のユーザー ID。 */
  childUserId: number
  /** 年齢段階の i18n ラベルキー（例: elementary, junior_high）。null の場合は不明。 */
  stageKey: string | null
  /** 後見切替が許可されているか（false の場合は既に自立段階）。 */
  switchAllowed: boolean
  /** 切替封印境界日（YYYY-MM-DD 形式）。 */
  sealDate: string
  /** 子が自分でパスワードを設定済みかどうか。 */
  passwordSet: boolean
}
