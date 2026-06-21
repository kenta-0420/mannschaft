import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { MembershipResponse, VillageResponse } from '~/types/village'

/**
 * F17.1 村機能 — 村詳細「永続シェル」用 provide/inject コンテキスト。
 *
 * # 背景
 *  従来は 9 つの村タブ（bulletin/timeline/lobby/members/calendar/festivals/
 *  match-recruits/meetups/chronicles）がそれぞれ独立ページとして
 *  `useVillageApi().getVillage(id)` で村を再フェッチし、VillageHeader を各自描画して
 *  いた。そのためタブ遷移のたびに全体が再マウント＆再フェッチされ、loading の白画面が
 *  出ていた。
 *
 *  これを「永続シェル方式（SPA）」に改めるため、親レイアウトルート
 *  `pages/villages/[id].vue` で村データ・メンバーシップ・権限・各種アクションを 1 度だけ
 *  解決し、子タブ（`<NuxtPage>`）へ provide する。子は本コンテキストを inject して使う。
 *
 * # 親子関係
 *  - 親: `pages/villages/[id].vue`（VillageHeader 常駐 + 村取得 + provide）
 *  - 子: `pages/villages/[id]/*.vue`（各タブのパネル本体のみ）
 *
 * # 設計上の配慮
 *  - `inject(key)!` の非 null アサーション散在を避けるため、未 provide 時に明示的に
 *    throw する `useVillageContext()` アクセサを公開する。壊れたら静かに undefined を
 *    返すよりも、開発時に即座に発見できる方が安全。
 *  - VillageHeader.vue は `VillageResponse & { monshoR2Key?: string | null }` の交差型
 *    （L31）を受け取るため、本コンテキストの village も同じ交差型 `VillageWithMonsho`
 *    を採用する。
 */

/**
 * VillageHeader が受け取る村レスポンスの交差型（村紋 r2Key を optional で追加）。
 * VillageHeader.vue L31 と一致させる。
 */
export type VillageWithMonsho = VillageResponse & { monshoR2Key?: string | null }

/** 親が provide する権限フラグ群。 */
export interface VillagePerms {
  /** 自分が村人（メンバー）か。 */
  isMember: boolean
  /** 管理権限（村長 HEADMAN または長老 ELDER）を持つか。 */
  isAdmin: boolean
  /** 村長（HEADMAN）か。 */
  isHeadman: boolean
  /** 自分の村内ロール（未所属時は null）。 */
  myRole: string | null
}

/**
 * 村詳細シェルが子タブへ共有するコンテキスト。
 */
export interface VillageContext {
  /** 村本体（取得前/エラー時は null）。 */
  village: Ref<VillageWithMonsho | null>
  /** 村再取得（参加・退村・ピン操作後の状態同期に使う）。 */
  refresh: () => Promise<void>
  /** 自分のメンバーシップ（退村時に id が必要・未所属時は null）。 */
  myMembership: Ref<MembershipResponse | null>
  /** 権限フラグ群（village.myRole 由来）。 */
  perms: ComputedRef<VillagePerms>
  /** ログイン中ユーザーの id（未ログイン時は null）。 */
  currentUserId: ComputedRef<number | null>
}

/**
 * 村詳細コンテキストの InjectionKey。親 `pages/villages/[id].vue` のみが provide する。
 * `Symbol` に説明文を付けて Vue Devtools 上での判別を容易にする。
 */
export const VillageContextKey: InjectionKey<VillageContext> = Symbol('VillageContext')

/**
 * 親 `pages/villages/[id].vue` でコンテキストを provide する。
 *
 * @param ctx 親で構築した {@link VillageContext}
 */
export function provideVillageContext(ctx: VillageContext): void {
  provide(VillageContextKey, ctx)
}

/**
 * 子タブから村詳細コンテキストを取得する。
 *
 * @throws 親 `pages/villages/[id].vue`（= `<NuxtPage>` の外）で呼ばれた場合に明示的に
 *         例外を投げる。
 */
export function useVillageContext(): VillageContext {
  const ctx = inject(VillageContextKey)
  if (!ctx) {
    throw new Error(
      'VillageContext not provided. Render inside pages/villages/[id].vue (<NuxtPage>).',
    )
  }
  return ctx
}
