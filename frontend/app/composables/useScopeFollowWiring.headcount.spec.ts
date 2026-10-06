import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FollowStatusApi } from './useFollowSelfStatus'

/**
 * 応援（フォロー）・解除の後にスコープ詳細（サポーター人数）を取り直す結線の試練
 * （CMP-261004-1942）。
 *
 * 是正前は applySupporter が follow→getStatus で followStatus だけを、cancelSupporter が
 * unfollow→NONE→権限再取得だけを行い、チーム/組織本体を取り直さないため、
 * ヘッダの「サポーター ◯人」が応援・解除で変わらなかった。
 *
 * 本ファイルは composable 単体（useScopeFollowWiring ＋ 実物の useFollowSelfStatus /
 * useRoleAccess）の振る舞いを固定する。モックは API 境界（follow/unfollow/getStatus と
 * useApi）とエラー通知だけ。ヘッダの実描画まで通す結合は
 * `useScopeFollowWiring.headcountRender.spec.ts` を参照。
 *
 * 詳細の取り直し関数は `refreshDetail` オプションで渡す（チーム=fetchTeam、組織=fetchOrg）。
 */

const handleApiErrorMock = vi.fn()
const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()
const apiMock = vi.fn()

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: notificationErrorMock }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const { useScopeFollowWiring } = await import('./useScopeFollowWiring')
const { useFollowSelfStatus } = await import('./useFollowSelfStatus')
const { useRoleAccess } = await import('./useRoleAccess')

function deferred<T = unknown>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function perms(roleName: string | null) {
  return { data: { roleName, permissions: roleName ? [`perm.${roleName}`] : [] } }
}

function makeFollowApi(overrides: Partial<FollowStatusApi> = {}): FollowStatusApi {
  return {
    follow: vi.fn().mockResolvedValue({}),
    unfollow: vi.fn().mockResolvedValue({}),
    getStatus: vi.fn().mockResolvedValue({ data: { status: 'APPROVED' } }),
    ...overrides,
  } as FollowStatusApi
}

function setup(followApi: FollowStatusApi, refreshDetail: () => Promise<unknown>) {
  const slug = ref('team-a')
  const roleAccess = useRoleAccess('team', slug)
  const wiring = useScopeFollowWiring({
    scopeSlug: slug,
    api: followApi,
    roleAccess: { roleName: roleAccess.roleName, loadPermissions: roleAccess.loadPermissions },
    refreshDetail,
  })
  return { slug, wiring }
}

/** 応援/解除の「失敗」として扱われた痕跡（エラー通知）が無いことを確認する。 */
function expectNotTreatedAsFollowFailure() {
  const contexts = handleApiErrorMock.mock.calls.map(call => call[1])
  expect(contexts).not.toContain('サポーター申請')
  expect(contexts).not.toContain('サポーター解除')
}

describe('useScopeFollowWiring 応援・解除後の人数取り直し（CMP-261004-1942）', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
    apiMock.mockReset()
    apiMock.mockResolvedValue(perms(null))
  })

  // AC-1/AC-2（composable 部分）
  it('AC-1: 応援が成功したら詳細（人数）の取り直しを1回呼ぶ', async () => {
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi()
    const { wiring } = setup(followApi, refreshDetail)

    await wiring.applySupporter()

    expect(followApi.follow).toHaveBeenCalledWith('team-a')
    expect(refreshDetail).toHaveBeenCalledTimes(1)
  })

  it('AC-1: 解除が成功したら詳細（人数）の取り直しを1回呼ぶ', async () => {
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi()
    const { wiring } = setup(followApi, refreshDetail)
    await wiring.fetchFollowStatus()

    await wiring.cancelSupporter()

    expect(followApi.unfollow).toHaveBeenCalledWith('team-a')
    expect(refreshDetail).toHaveBeenCalledTimes(1)
  })

  // AC-3
  it('AC-3: follow API が失敗したら人数の取り直しを呼ばない', async () => {
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ follow: vi.fn().mockRejectedValue(new Error('boom')) })
    const { wiring } = setup(followApi, refreshDetail)

    await wiring.applySupporter()

    expect(refreshDetail).not.toHaveBeenCalled()
    expect(handleApiErrorMock).toHaveBeenCalledTimes(1)
  })

  it('AC-3: unfollow API が失敗したら人数の取り直しを呼ばない', async () => {
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ unfollow: vi.fn().mockRejectedValue(new Error('boom')) })
    const { wiring } = setup(followApi, refreshDetail)
    await wiring.fetchFollowStatus()

    await wiring.cancelSupporter()

    expect(refreshDetail).not.toHaveBeenCalled()
    expect(wiring.followStatus.value).toBe('APPROVED')
  })

  // AC-4
  it('AC-4: follow 成功・その後の status GET 失敗でも、人数の取り直しは呼ぶ', async () => {
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ getStatus: vi.fn().mockRejectedValue(new Error('status down')) })
    const { wiring } = setup(followApi, refreshDetail)

    await wiring.applySupporter()

    expect(followApi.follow).toHaveBeenCalledTimes(1)
    expect(refreshDetail).toHaveBeenCalledTimes(1)
    // follow 自体は成功しているので「サポーター申請」の失敗としては通知しない。
    expect(handleApiErrorMock.mock.calls.map(call => call[1])).not.toContain('サポーター申請')
  })

  it('AC-4: 応援成功後の人数取り直しが失敗しても、応援の失敗（エラー通知・状態の巻き戻し）として扱わない', async () => {
    const refreshDetail = vi.fn().mockRejectedValue(new Error('detail down'))
    const followApi = makeFollowApi()
    const { wiring } = setup(followApi, refreshDetail)

    await wiring.applySupporter()

    expect(refreshDetail).toHaveBeenCalledTimes(1)
    expect(wiring.followStatus.value).toBe('APPROVED')
    expect(wiring.followLoading.value).toBe(false)
    expect(notificationSuccessMock).toHaveBeenCalledWith('common.scopeShell.supporter_registered')
    expectNotTreatedAsFollowFailure()
  })

  it('AC-4: 解除成功後の人数取り直しが失敗しても、解除の失敗として扱わずダイアログを閉じる', async () => {
    const refreshDetail = vi.fn().mockRejectedValue(new Error('detail down'))
    const followApi = makeFollowApi()
    const { wiring } = setup(followApi, refreshDetail)
    await wiring.fetchFollowStatus()
    wiring.showCancelSupporterConfirm.value = true

    await wiring.cancelSupporter()

    expect(refreshDetail).toHaveBeenCalledTimes(1)
    expect(wiring.followStatus.value).toBe('NONE')
    expect(wiring.showCancelSupporterConfirm.value).toBe(false)
    expect(notificationSuccessMock).toHaveBeenCalledWith('common.scopeShell.supporter_canceled')
    expectNotTreatedAsFollowFailure()
  })

  // AC-5
  it('AC-5: A で応援中に B へ切り替わったら、A の follow 遅延完了で人数の取り直しを呼ばない', async () => {
    const follow = deferred()
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ follow: vi.fn().mockReturnValue(follow.promise) })
    const { slug, wiring } = setup(followApi, refreshDetail)

    const applying = wiring.applySupporter()
    slug.value = 'team-b'
    follow.resolve({})
    await applying

    expect(refreshDetail).not.toHaveBeenCalled()
  })

  it('AC-5: A で応援→status GET 待機中に B へ切り替わったら、A の遅延完了で人数の取り直しを呼ばない', async () => {
    const status = deferred<{ data: { status: 'APPROVED' } }>()
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ getStatus: vi.fn().mockReturnValue(status.promise) })
    const { slug, wiring } = setup(followApi, refreshDetail)

    const applying = wiring.applySupporter()
    await vi.waitFor(() => expect(followApi.getStatus).toHaveBeenCalledTimes(1))
    slug.value = 'team-b'
    status.resolve({ data: { status: 'APPROVED' } })
    await applying

    expect(refreshDetail).not.toHaveBeenCalled()
  })

  it('AC-5: A で解除中に B へ切り替わったら、A の unfollow 遅延完了で人数の取り直しを呼ばない', async () => {
    const unfollow = deferred()
    const refreshDetail = vi.fn().mockResolvedValue(undefined)
    const followApi = makeFollowApi({ unfollow: vi.fn().mockReturnValue(unfollow.promise) })
    const { slug, wiring } = setup(followApi, refreshDetail)
    await wiring.fetchFollowStatus()

    const cancelling = wiring.cancelSupporter()
    slug.value = 'team-b'
    unfollow.resolve({})
    await cancelling

    expect(refreshDetail).not.toHaveBeenCalled()
  })

  // AC-11（composable 部分）: 解除後の取り直しが 403 でも詰まらない。
  it('AC-11: 解除成功後の人数取り直しが 403 で失敗しても、操作中表示を解いてダイアログを閉じる', async () => {
    const forbidden = Object.assign(new Error('Forbidden'), { statusCode: 403, response: { status: 403 } })
    const refreshDetail = vi.fn().mockRejectedValue(forbidden)
    const followApi = makeFollowApi()
    const { wiring } = setup(followApi, refreshDetail)
    await wiring.fetchFollowStatus()
    wiring.showCancelSupporterConfirm.value = true

    await wiring.cancelSupporter()

    expect(refreshDetail).toHaveBeenCalledTimes(1)
    expect(wiring.followLoading.value).toBe(false)
    expect(wiring.showCancelSupporterConfirm.value).toBe(false)
    expect(wiring.followStatus.value).toBe('NONE')
    expectNotTreatedAsFollowFailure()
  })
})

/**
 * useFollowSelfStatus が「変更 API が成功したか」を呼び出し側へ返す（CMP-261004-1942）。
 * 戻り値の型・名前は実装に委ね、真偽として判定できることだけを固定する。
 */
describe('useFollowSelfStatus 変更 API の成否を呼び出し側へ返す（CMP-261004-1942）', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
  })

  it('applySupporter: follow 成功なら真を返す', async () => {
    const core = useFollowSelfStatus(makeFollowApi())
    expect(await core.applySupporter('team-a')).toBeTruthy()
  })

  it('applySupporter: follow 成功・status GET 失敗でも真を返す（変更は成立している）', async () => {
    const core = useFollowSelfStatus(makeFollowApi({ getStatus: vi.fn().mockRejectedValue(new Error('x')) }))
    expect(await core.applySupporter('team-a')).toBeTruthy()
  })

  it('applySupporter: follow 失敗なら偽を返す', async () => {
    const core = useFollowSelfStatus(makeFollowApi({ follow: vi.fn().mockRejectedValue(new Error('x')) }))
    const result = await core.applySupporter('team-a')
    expect(result).not.toBeUndefined()
    expect(result).toBeFalsy()
  })

  it('cancelSupporter: unfollow 成功なら真、失敗なら偽を返す', async () => {
    const ok = useFollowSelfStatus(makeFollowApi())
    expect(await ok.cancelSupporter('team-a', async () => ({ ok: true }))).toBeTruthy()

    const ng = useFollowSelfStatus(makeFollowApi({ unfollow: vi.fn().mockRejectedValue(new Error('x')) }))
    expect(await ng.cancelSupporter('team-a', async () => ({ ok: true }))).toBeFalsy()
  })
})

/** 両シェルページが人数の取り直し関数（fetchTeam / fetchOrg）を結線していることの固定。 */
describe.each([
  ['app/pages/organizations/[slug].vue', 'fetchOrg'],
  ['app/pages/teams/[slug].vue', 'fetchTeam'],
])('%s の人数取り直し結線（CMP-261004-1942）', (file, fetchName) => {
  const source = readFileSync(resolve(process.cwd(), file), 'utf8').replace(/\r\n/g, '\n')

  it(`useScopeFollowWiring に refreshDetail として ${fetchName} を渡している`, () => {
    const block = source.match(/useScopeFollowWiring\(\{[\s\S]*?\n\}\)/)?.[0] ?? ''
    expect(block).toMatch(new RegExp(`refreshDetail:\\s*${fetchName}\\b`))
  })
})

/**
 * AC-11（チーム）: 解除後の取り直しが 403 になったら保護された詳細表示を閉じる。
 *
 * 既存シェルの 403 の扱い: `team` が null のとき（loading 完了後）シェルは
 * 「読み込みエラー（common.scopeShell.load_error_*）＋再試行＋ダッシュボードへ戻る」の
 * フォールバック面を出す（pages/teams/[slug].vue のテンプレート v-else 節）。
 * 初回読み込みの 403 はこの面になるが、是正前の fetchTeam は catch で team を残すため、
 * 解除後の取り直しが 403 でも閲覧権を失った詳細がそのまま表示され続けた。
 * チームの fetchTeam はページ内関数で単体マウントが重いため、ソース上で固定する。
 */
describe('pages/teams/[slug].vue fetchTeam の 403 処理（AC-11・CMP-261004-1942）', () => {
  const source = readFileSync(resolve(process.cwd(), 'app/pages/teams/[slug].vue'), 'utf8').replace(/\r\n/g, '\n')

  it('AC-11: fetchTeam の取得失敗が 403 のとき team を null にして保護された詳細を閉じる', () => {
    const fn = source.match(/async function fetchTeam\(\)[\s\S]*?\n\}\n/)?.[0] ?? ''
    expect(fn).not.toBe('')
    expect(fn).toMatch(/catch[\s\S]*403[\s\S]*team\.value = null/)
  })
})
