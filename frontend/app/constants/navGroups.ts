import type { NavGroupKey } from '~/types/nav'

/**
 * サイドバー化 Phase1: グローバルナビのグループ定義。
 *
 * `nav_features` マスタの key（V9/V125/V133 で投入された18件）＋固定/条件付き項目
 * （dashboard・proxy-desk・system-admin・sync）を、プロトタイプ（sidebar-prototype.html）
 * の6グループへ振り分ける対応表。
 *
 * - 表示順は NAV_GROUP_ORDER の並びを正とする（home → connections → living → work →
 *   account → admin → other）。
 * - 対応表に無い未知の key は 'other'（その他）グループへフォールバックする
 *   （クラッシュ・項目消失を防ぐため。将来 nav_features に新規 key が追加されても、
 *   このファイルの更新漏れで項目が消えることはない）。
 */

export const NAV_GROUP_ORDER: NavGroupKey[] = [
  'home',
  'connections',
  'living',
  'work',
  'account',
  'admin',
  'other',
]

export const NAV_GROUP_LABEL_KEYS: Record<NavGroupKey, string> = {
  home: 'global_nav.group.home',
  connections: 'global_nav.group.connections',
  living: 'global_nav.group.living',
  work: 'global_nav.group.work',
  account: 'global_nav.group.account',
  admin: 'global_nav.group.admin',
  other: 'global_nav.group.other',
}

/**
 * item.key → groupKey 対応表。
 *
 * 内訳:
 * - home: 固定ダッシュボード、カレンダー、インボックス
 * - connections: タイムライン、チャット、ブログ、村
 * - living: 市、求人、マッチング、予約確認、ポイントカード
 * - work: TODO、シフト管理、マイシフト、マイファイル
 * - account: マイページ、Q&A、設定、同期（個人データ状態の一部として account に分類）
 * - admin: 代理入力デスク、SYSTEM（条件付き表示の管理系項目）
 */
const NAV_ITEM_GROUP_MAP: Record<string, NavGroupKey> = {
  // 固定項目
  dashboard: 'home',

  // nav_features マスタ（V9投入分）
  calendar: 'home',
  settings: 'account',
  todo: 'work',
  'shift-management': 'work',
  timeline: 'connections',
  chat: 'connections',
  'my-shift': 'work',
  'my-page': 'account',
  qa: 'account',
  villages: 'connections',
  blog: 'connections',

  // nav_features マスタ（V125投入分）
  reservations: 'living',
  wallet: 'living',
  inbox: 'home',
  market: 'living',
  jobs: 'living',
  matching: 'living',

  // nav_features マスタ（V133投入分）
  'my-files': 'work',

  // 条件付き項目（default.vue 108-131行のロジックを移植した合流先）
  'proxy-desk': 'admin',
  'system-admin': 'admin',
  sync: 'account',
}

/** 未知キーは 'other' へフォールバックする */
export function resolveNavGroup(key: string): NavGroupKey {
  return NAV_ITEM_GROUP_MAP[key] ?? 'other'
}
