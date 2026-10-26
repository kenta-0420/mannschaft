import type {
  IndependenceStatusResponse,
  SwitchableChildrenResponse,
  ChildCalendarEntry,
  ChildAttendanceStats,
  GuardianChildMembershipsResponse,
  GuardianChildAnnouncementsResponse,
  GuardianChildProxyActionsResponse,
} from '~/types/guardianship'

/**
 * F08.9 後見切替 API の型付きラッパー（設計書 02 §2.1）。
 *
 * 認証ユーザー（保護者）視点。エンドポイントは BE PR #P3a / #1342 main 済み。
 * - 切替可能な子の一覧: GET /api/v1/me/guardianship/switchable-children
 * - 後見切替開始: POST /api/v1/me/guardianship/switch
 * - 後見切替終了: DELETE /api/v1/me/guardianship/switch
 * - 自立移行状況: GET /api/v1/me/guardianship/children/{childUserId}/independence-status
 * - 引き継ぎ開始: POST /api/v1/me/guardianship/children/{childUserId}/handover/initiate
 *
 * F08.9 件2（保護者による子データ閲覧専用見守り・05_guardian_child_view.md・BE #2158 main済）:
 * - 子の予定: GET /api/v1/me/guardianship/children/{childUserId}/schedules?from=&to=
 * - 子の出欠状況: GET /api/v1/me/guardianship/children/{childUserId}/attendance/stats?from=&to=
 * - 子の所属: GET /api/v1/me/guardianship/children/{childUserId}/memberships
 * - 子のお知らせ: GET /api/v1/me/guardianship/children/{childUserId}/announcements?page=&size=
 * - 子の代理操作履歴（件3・代理マーク土台）: GET /api/v1/me/guardianship/children/{childUserId}/proxy-actions
 *   認可は evaluateSwitch 再利用（12歳未満のみ許可。リンク無し/12歳以上は 403）。
 *
 * BE は全レスポンスを ApiResponse（{ data: ... }）でラップする。
 */
export function useGuardianshipApi() {
  const api = useApi()

  /**
   * 認証ユーザー（保護者）が後見切替できる子と、年齢到達で封印された子を取得する。
   * P5 加入 UI では children（switchAllowed=true）を受益者の選択肢に使う。
   */
  async function listSwitchableChildren() {
    return api<{ data: SwitchableChildrenResponse }>(
      '/api/v1/me/guardianship/switchable-children',
    )
  }

  /**
   * 後見切替を開始する（BE 検証 + 監査ログのみ。ヘッダはクライアント側で管理）。
   * 成功後は guardianshipSwitchStore.startSwitch() を呼ぶこと。
   */
  async function startSwitch(childUserId: number): Promise<void> {
    await api('/api/v1/me/guardianship/switch', {
      method: 'POST',
      body: { childUserId },
    })
  }

  /**
   * 後見切替を終了する。
   * 成功後は guardianshipSwitchStore.endSwitch() を呼ぶこと。
   */
  async function endSwitch(childUserId: number): Promise<void> {
    await api('/api/v1/me/guardianship/switch', {
      method: 'DELETE',
      query: { childUserId },
    })
  }

  /**
   * 子の自立移行ステータスを取得する。
   * sealDate（封印境界日）・stageKey・passwordSet を返す。
   */
  async function getIndependenceStatus(childUserId: number) {
    return api<{ data: IndependenceStatusResponse }>(
      `/api/v1/me/guardianship/children/${childUserId}/independence-status`,
    )
  }

  /**
   * 子への引き継ぎ（独立準備）を開始する。
   * childEmail を渡すとそのアドレスへ設定リンクを送る（省略可）。
   */
  async function initiateHandover(childUserId: number, childEmail?: string): Promise<void> {
    await api(`/api/v1/me/guardianship/children/${childUserId}/handover/initiate`, {
      method: 'POST',
      body: childEmail ? { childEmail } : {},
    })
  }

  /**
   * ① 子の今後の予定（横断カレンダー）を取得する。閲覧専用（見守り・件2）。
   * from/to は**オフセット付き** ISO-8601 文字列（例: "2026-08-04T00:00:00+09:00"）。
   * ナイーブな "YYYY-MM-DDTHH:mm:ss" を渡してはならない（Issue #2508。深夜0時にTZが切り替わる
   * America/Santiago 等で非存在時刻となり、範囲の下端が1時間欠ける）。
   * 呼び出し側は `useDatetime()` の `buildDayStartStr` / `buildDayEndStr` などで組み立てること。
   * 12歳以上（AGE_LOCKED）・リンク無し（LINK_NOT_FOUND）は 403（呼び出し側で errorCode 判定）。
   */
  async function getChildSchedules(childUserId: number, from: string, to: string) {
    return api<{ data: ChildCalendarEntry[] }>(
      `/api/v1/me/guardianship/children/${childUserId}/schedules`,
      { query: { from, to } },
    )
  }

  /** ② 子の出席率統計を取得する。閲覧専用（見守り・件2）。 */
  async function getChildAttendanceStats(childUserId: number, from: string, to: string) {
    return api<{ data: ChildAttendanceStats }>(
      `/api/v1/me/guardianship/children/${childUserId}/attendance/stats`,
      { query: { from, to } },
    )
  }

  /** ③ 子の所属チーム/組織を取得する。閲覧専用（見守り・件2）。 */
  async function getChildMemberships(childUserId: number) {
    return api<{ data: GuardianChildMembershipsResponse }>(
      `/api/v1/me/guardianship/children/${childUserId}/memberships`,
    )
  }

  /** ④ 子のお知らせ受信（掲示板スレッド）をページングで取得する。閲覧専用（見守り・件2）。 */
  async function getChildAnnouncements(childUserId: number, page = 0, size = 20) {
    return api<{ data: GuardianChildAnnouncementsResponse }>(
      `/api/v1/me/guardianship/children/${childUserId}/announcements`,
      { query: { page, size } },
    )
  }

  /**
   * 代理操作履歴（件3・代理マーク土台）を取得する。subject=子 の代理入力記録のみ新しい順。
   * 「子の代わりに誰が・どの機能で・何を・どの入力元で行ったか」を保護者へ透明化する。
   */
  async function getChildProxyActions(childUserId: number) {
    return api<{ data: GuardianChildProxyActionsResponse }>(
      `/api/v1/me/guardianship/children/${childUserId}/proxy-actions`,
    )
  }

  return {
    listSwitchableChildren,
    startSwitch,
    endSwitch,
    getIndependenceStatus,
    initiateHandover,
    getChildSchedules,
    getChildAttendanceStats,
    getChildMemberships,
    getChildAnnouncements,
    getChildProxyActions,
  }
}
