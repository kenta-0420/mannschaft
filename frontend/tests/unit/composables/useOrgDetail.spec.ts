import { describe, expect, it, vi, beforeEach } from 'vitest'
import { defineComponent, ref, h } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { useOrgDetail } from '~/composables/useOrgDetail'

/**
 * F01.2.1 8-C — 親シェルの所属チーム取得（useOrgDetail.fetchOrgTeams）。実装を通して検証する（親の再取得をモックしきらない）。
 *
 *   UD-01 再取得が前後して返っても、最後に発行した要求の応答だけを反映する（①②の順に発行し ②→① の順に返る）
 *   UD-02 組織が切り替わったら、古い組織の応答は捨てる
 *   UD-03 取得失敗は握りつぶさず orgTeamsError を立て、直前の一覧を空に差し替えない。次の成功でエラーが消える
 *   UD-04 取得中は orgTeamsLoading が立ち、最新の要求が終わるまで降りない
 */

const getTeamsInOrg = vi.fn()

mockNuxtImport('useOrganizationApi', () => () => ({ getTeamsInOrg }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn(), handleError: vi.fn() }))

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function team(slug: string) {
  return { id: 1, slug, name: slug, nickname1: null, iconUrl: null, template: 'GENERAL', memberCount: 1 }
}

async function setup(initialId = 'org-1') {
  const orgId = ref(initialId)
  let api!: ReturnType<typeof useOrgDetail>
  await mountSuspended(defineComponent({
    setup() {
      api = useOrgDetail(orgId)
      return () => h('div')
    },
  }))
  return { orgId, api }
}

describe('useOrgDetail.fetchOrgTeams', () => {
  beforeEach(() => {
    getTeamsInOrg.mockReset()
  })

  it('UD-01: 再取得が ②→① の順に返っても、最後に発行した要求（②）の応答だけを反映する', async () => {
    const first = deferred<{ data: unknown[] }>()
    const second = deferred<{ data: unknown[] }>()
    getTeamsInOrg.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { api } = await setup()
    const p1 = api.fetchOrgTeams()
    const p2 = api.fetchOrgTeams()
    second.resolve({ data: [team('new-team')] })
    await p2
    first.resolve({ data: [team('old-team')] })
    await p1
    await flushPromises()
    expect(api.orgTeams.value.map(t => t.slug)).toEqual(['new-team'])
  })

  it('UD-02: 組織が切り替わったら、古い組織の応答は捨てる', async () => {
    const old = deferred<{ data: unknown[] }>()
    getTeamsInOrg.mockReturnValueOnce(old.promise)
    const { orgId, api } = await setup('org-old')
    const p = api.fetchOrgTeams()
    orgId.value = 'org-new'
    old.resolve({ data: [team('old-org-team')] })
    await p
    await flushPromises()
    expect(api.orgTeams.value).toEqual([])
  })

  it('UD-03: 取得失敗は orgTeamsError を立て、直前の一覧を空に差し替えない。次の成功でエラーが消える', async () => {
    getTeamsInOrg.mockResolvedValueOnce({ data: [team('keep')] })
    const { api } = await setup()
    await api.fetchOrgTeams()
    expect(api.orgTeamsError.value).toBe(false)

    getTeamsInOrg.mockRejectedValueOnce(new Error('500'))
    await api.fetchOrgTeams()
    expect(api.orgTeamsError.value).toBe(true)
    expect(api.orgTeams.value.map(t => t.slug)).toEqual(['keep'])

    getTeamsInOrg.mockResolvedValueOnce({ data: [team('keep'), team('more')] })
    await api.fetchOrgTeams()
    expect(api.orgTeamsError.value).toBe(false)
    expect(api.orgTeams.value).toHaveLength(2)
  })

  it('UD-04: 取得中は orgTeamsLoading が立ち、最新の要求が終わるまで降りない', async () => {
    const first = deferred<{ data: unknown[] }>()
    const second = deferred<{ data: unknown[] }>()
    getTeamsInOrg.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { api } = await setup()
    const p1 = api.fetchOrgTeams()
    const p2 = api.fetchOrgTeams()
    expect(api.orgTeamsLoading.value).toBe(true)
    first.resolve({ data: [] })
    await p1
    // ①が終わっても、より新しい②が残っている間は取得中のまま
    expect(api.orgTeamsLoading.value).toBe(true)
    second.resolve({ data: [] })
    await p2
    expect(api.orgTeamsLoading.value).toBe(false)
  })
})
