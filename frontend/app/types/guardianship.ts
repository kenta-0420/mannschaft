/**
 * F08.9 P3a/P5 後見切替（guardianship）の手書き型定義。
 *
 * BE DTO（SwitchableChildDto / BlockedChildDto / SwitchableChildrenResponse）と
 * camelCase 1:1 で対応する。生成型（types/generated）が整備されたら段階移行する。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §2.1
 */

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
