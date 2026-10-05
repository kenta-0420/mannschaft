import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h, ref } from 'vue'
import type { Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { OrgDetail } from '~/composables/useOrgDetail'

/**
 * 詳細の取り直しの応答逆転を防ぐ試練（CMP-261004-1942・Codex 検分 P2）。
 *
 * 是正前の fetchOrg / fetchTeam は「応答時のスコープが要求時と同じか」しか見ていなかったため、
 * - 同一スコープで取り直し①（応援後・1人）が遅延中に取り直し②（解除後・0人）が先に反映されると、
 *   後着の①で 1 人に戻る
 * - 403 で詳細を閉じた後に、それより前に発行した要求の遅延 200 が詳細を再表示する
 * - A→B→A と切り替えたとき、最初の A の遅延応答が新しい A の表示を上書きする
 * ことが起きた。最後に発行した要求の応答だけを反映することを固定する。
 *
 * 組織は実物の useOrgDetail().fetchOrg を通す（モックは API 境界の useApi とエラー通知だけ）。
 * チームの fetchTeam はページ内関数で単体マウントが重いため、既存 spec と同じくソース上で固定する。
 */

const handleApiErrorMock = vi.fn()
const apiMock = vi.fn()

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: vi.fn(), error: vi.fn() }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const { useOrgDetail } = await import('~/composables/useOrgDetail')

function deferred<T = unknown>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function forbiddenError() {
  return Object.assign(new Error('Forbidden'), { statusCode: 403, status: 403, response: { status: 403 } })
}

function orgBody(slug: string, supporterCount: number): { data: OrgDetail } {
  return { data: { id: slug, numericId: 1, basicInfo: { name: slug }, social: { supporterCount } } as OrgDetail }
}

/** 詳細 GET を呼ばれた順に保留し、テスト側が任意の順で解決できるようにする。 */
const pending: Array<{ url: string, d: ReturnType<typeof deferred<unknown>> }> = []

async function mountOrgDetail(initialSlug: string) {
  let handle: { slug: Ref<string>, detail: ReturnType<typeof useOrgDetail> } | null = null
  const Harness = defineComponent({
    setup() {
      const slug = ref(initialSlug)
      handle = { slug, detail: useOrgDetail(slug) }
      return () => h('div')
    },
  })
  await mountSuspended(Harness)
  if (!handle) throw new Error('harness not initialised')
  return handle as { slug: Ref<string>, detail: ReturnType<typeof useOrgDetail> }
}

async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

describe('useOrgDetail.fetchOrg 最後の要求の応答だけを反映する（CMP-261004-1942）', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    apiMock.mockReset()
    pending.length = 0
    apiMock.mockImplementation((url: string) => {
      const d = deferred<unknown>()
      pending.push({ url, d })
      return d.promise
    })
  })

  it('同一スコープで取り直しが逆順に返っても、最後の要求の人数が残る', async () => {
    const { detail } = await mountOrgDetail('org-a')
    const first = detail.fetchOrg() // 応援後の取り直し①（1人）
    const second = detail.fetchOrg() // 解除後の取り直し②（0人）
    pending[1]!.d.resolve(orgBody('org-a', 0))
    await second
    pending[0]!.d.resolve(orgBody('org-a', 1))
    await first
    await settle()

    expect(detail.org.value?.social?.supporterCount).toBe(0)
    expect(detail.loading.value).toBe(false)
  })

  it('403 で詳細を閉じた後に、それより前の要求の遅延 200 が来ても再表示しない', async () => {
    const { detail } = await mountOrgDetail('org-a')
    const warm = detail.fetchOrg()
    pending[0]!.d.resolve(orgBody('org-a', 1))
    await warm
    expect(detail.org.value).not.toBeNull()

    const stale = detail.fetchOrg() // 解除前に発行された取り直し
    const latest = detail.fetchOrg() // 解除後の取り直し（403）
    pending[2]!.d.reject(forbiddenError())
    await latest
    expect(detail.org.value).toBeNull()

    pending[1]!.d.resolve(orgBody('org-a', 1))
    await stale
    await settle()
    expect(detail.org.value).toBeNull()
  })

  it('A→B→A と切り替えたとき、最初の A の遅延応答が新しい A の表示を上書きしない', async () => {
    const { slug, detail } = await mountOrgDetail('org-a')
    const oldA = detail.fetchOrg()
    slug.value = 'org-b'
    const b = detail.fetchOrg()
    slug.value = 'org-a'
    const newA = detail.fetchOrg()

    pending[2]!.d.resolve(orgBody('org-a', 0))
    await newA
    pending[1]!.d.resolve(orgBody('org-b', 5))
    await b
    pending[0]!.d.resolve(orgBody('org-a', 1))
    await oldA
    await settle()

    expect(pending.map(p => p.url)).toEqual([
      '/api/v1/organizations/org-a',
      '/api/v1/organizations/org-b',
      '/api/v1/organizations/org-a',
    ])
    expect(detail.org.value?.id).toBe('org-a')
    expect(detail.org.value?.social?.supporterCount).toBe(0)
    expect(detail.loading.value).toBe(false)
  })

  it('古い要求の失敗は通知しない（最後の要求だけがエラー処理の対象）', async () => {
    const { detail } = await mountOrgDetail('org-a')
    const stale = detail.fetchOrg()
    const latest = detail.fetchOrg()
    pending[1]!.d.resolve(orgBody('org-a', 2))
    await latest
    pending[0]!.d.reject(forbiddenError())
    await stale
    await settle()

    expect(detail.org.value?.social?.supporterCount).toBe(2)
    expect(handleApiErrorMock).not.toHaveBeenCalled()
  })
})

/**
 * チーム（pages/teams/[slug].vue の fetchTeam）も同じ連番ガードを持つことのソース固定。
 * slug 一致だけの判定へ戻ると、上の組織と同じ応答逆転が起きる。
 */
describe('pages/teams/[slug].vue fetchTeam の応答逆転ガード（CMP-261004-1942）', () => {
  const source = readFileSync(resolve(process.cwd(), 'app/pages/teams/[slug].vue'), 'utf8').replace(/\r\n/g, '\n')
  const fn = source.match(/async function fetchTeam\(\)[\s\S]*?\n\}\n/)?.[0] ?? ''

  it('要求ごとに連番を振り、最後の要求かつ同じ slug のときだけ反映する', () => {
    expect(fn).not.toBe('')
    expect(fn).toMatch(/const seq = \+\+fetchTeamSeq/)
    expect(fn).toMatch(/isCurrent = \(\) => seq === fetchTeamSeq && teamSlug\.value === requestedSlug/)
  })

  it('成功・失敗のどちらも isCurrent で判定してから反映する（slug 一致だけの判定に戻さない）', () => {
    expect(fn).toMatch(/await teamApi\.getTeam\(requestedSlug\)\s*\n\s*if \(!isCurrent\(\)\) return\s*\n\s*team\.value = result\.data/)
    expect(fn).toMatch(/catch \(error\) \{\s*\n\s*if \(!isCurrent\(\)\) return/)
    expect(fn).not.toMatch(/teamSlug\.value !== requestedSlug\) return/)
  })
})
