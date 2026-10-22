/**
 * CMP-261012-0315 フレンドチームのフォロー先指定 — FriendFollowDialog の試練（red）。
 *
 * Dialog → TeamFriendFollowButton → useFriendTeamsApi.follow / useTeamCrud.searchTeams → useApi の
 * 実経路を通す（モックは useApi・useI18n・useNotification・useErrorHandler と、テレポートを避けるための
 * PrimeVue Dialog の外枠だけ）。
 *
 * 実装側が満たすべき契約:
 * - props は従来どおり `teamId: string`（＝自チーム slug）/ `modelValue: boolean`。
 * - 経路1（名前検索）: `data-testid="friend-follow-search-input"`（<input> 自身、またはその中に <input> を
 *   含む要素。PrimeVue AutoComplete 可）。入力の trim 後が 2 文字以上のとき、最後の入力から 300ms の
 *   debounce 後に `GET /api/v1/teams/search?keyword=<入力>&page=0&size=10` を 1 回呼ぶ
 *   （`useTeamCrud().searchTeams({ keyword, page: 0, size: 10 })` で可）。1 文字以下では呼ばない。
 * - 候補は 1 件ごとに `data-testid="friend-follow-candidate"` の要素で描画し、チーム名と slug を両方表示する
 *   （オーバーレイでテレポートしてよい。テストは document.body から探す）。候補のクリックで選択。
 * - 候補 0 件: `data-testid="friend-follow-no-results"` に `friends.follow_dialog.no_results` と
 *   `friends.follow_dialog.slug_input_hint`（slug 直接入力の案内）を表示する。
 * - 検索の 5xx・通信断: `data-testid="friend-follow-search-error"` に `friends.follow_dialog.search_error` を表示し、
 *   0 件案内（friend-follow-no-results）は出さない。`data-testid="friend-follow-search-retry"` のクリックで
 *   同じキーワードを再検索し、成功すればエラー表示を消して候補を出す。
 * - 経路2（slug 直接入力）: `data-testid="friend-follow-slug-input"`（<input> または <input> を含む要素）。
 *   非公開で候補に出ないチーム向け。入力値（trim 後）を targetTeamSlug として送る。
 * - 送信ボタン: TeamFriendFollowButton に `data-testid="friend-follow-submit"` を渡す（<button> に載る）。
 *   送信 body は `{ targetTeamSlug, comment? }`。数値 targetTeamId は送らない。
 * - 自チーム（選択候補の slug または直接入力の slug が props.teamId と一致）: 送信ボタンを disabled にし、
 *   `data-testid="friend-follow-self-warning"` に `friends.errors.self_follow` を表示する。
 * - 成功時は `success` を emit して閉じる（update:modelValue=false）。失敗時は閉じず、入力を保持する。
 */
import { defineComponent, nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import FriendFollowDialog from '~/components/friends/FriendFollowDialog.vue'

const { mockApi, notifySuccess, notifyError, handleApiError } = vi.hoisted(() => ({
  mockApi: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useApi', () => () => mockApi)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError, warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))

/** PrimeVue Dialog のテレポートを避けるための外枠スタブ（中身のスロットはそのまま描画する）。 */
const DialogStub = defineComponent({
  props: { visible: { type: Boolean, default: false } },
  emits: ['update:visible'],
  template: '<div v-if="visible" data-testid="friend-follow-dialog"><slot /><slot name="footer" /></div>',
})

const OWN_SLUG = 'my-team'

function team(slug: string, name: string, id: number) {
  return {
    id, slug, name, nickname1: null, iconUrl: null, prefecture: null, city: null,
    template: 'SPORTS', memberCount: 10, supporterEnabled: true, teamFriendCount: 0, supporterCount: 0,
  }
}

const RIVAL = team('rival-fc', '雷鳴FC', 20)
const OWN = team(OWN_SLUG, 'マイチーム', 10)

function paged(items: unknown[]) {
  return { data: items, meta: { page: 0, size: 10, total: items.length, totalPages: items.length ? 1 : 0 } }
}

const FOLLOW_RESPONSE = {
  followId: 100, followerTeamId: 10, followedTeamId: 20, mutual: false,
  teamFriendId: null, establishedAt: null, isPublic: null, createdAt: '2026-10-12T03:15:00', retryAfterSeconds: null,
}

type ApiCall = [string, { method?: string, body?: Record<string, unknown> } | undefined]

let searchHandler: (url: string) => Promise<unknown>
let followHandler: (url: string, opts: unknown) => Promise<unknown>

function isSearch(url: unknown) {
  return typeof url === 'string' && url.startsWith('/api/v1/teams/search')
}
function searchCalls() {
  return (mockApi.mock.calls as ApiCall[]).filter(([url]) => isSearch(url))
}
function followCalls() {
  return (mockApi.mock.calls as ApiCall[]).filter(([url]) => typeof url === 'string' && url.endsWith('/friends/follow'))
}

function q(selector: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(selector)
}
function qa(selector: string): HTMLElement[] {
  return Array.from(document.body.querySelectorAll<HTMLElement>(selector))
}
function inputOf(testid: string): HTMLInputElement {
  const el = q(`[data-testid="${testid}"]`)
  expect(el, `data-testid="${testid}" が描画されていない`).not.toBeNull()
  const input = el!.tagName === 'INPUT' ? el : el!.querySelector('input')
  expect(input, `data-testid="${testid}" の中に <input> が無い`).not.toBeNull()
  return input as HTMLInputElement
}
async function typeInto(testid: string, value: string) {
  const input = inputOf(testid)
  input.focus()
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
function submitButton(): HTMLButtonElement {
  const el = q('[data-testid="friend-follow-submit"]')
  expect(el, 'data-testid="friend-follow-submit" が描画されていない').not.toBeNull()
  const button = el!.tagName === 'BUTTON' ? el : el!.querySelector('button')
  expect(button).not.toBeNull()
  return button as HTMLButtonElement
}
function click(el: HTMLElement) {
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
  el.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}
/** debounce（300ms）を経過させ、API の解決まで流す。 */
async function settleSearch(ms = 300) {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
  await nextTick()
}

async function mountDialog(initialSlug?: string) {
  const wrapper = await mountSuspended(FriendFollowDialog, {
    props: { teamId: OWN_SLUG, modelValue: true, initialSlug },
    attachTo: document.body,
    global: { stubs: { Dialog: DialogStub } },
  })
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  return wrapper
}

describe('FriendFollowDialog（CMP-261012-0315）', () => {
  let wrapper: Awaited<ReturnType<typeof mountDialog>> | null = null

  beforeEach(() => {
    mockApi.mockReset()
    notifySuccess.mockReset()
    notifyError.mockReset()
    handleApiError.mockReset()
    searchHandler = async () => paged([RIVAL])
    followHandler = async () => ({ data: FOLLOW_RESPONSE })
    mockApi.mockImplementation((url: string, opts: unknown) =>
      isSearch(url) ? searchHandler(url) : followHandler(url, opts))
  })

  afterEach(() => {
    vi.useRealTimers()
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  it('AC-8: 名前を入力すると候補（名前＋slug）が出て、選んで送信すると body に targetTeamSlug が入る', async () => {
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', '雷鳴')
    await settleSearch()

    expect(searchCalls()).toHaveLength(1)
    const params = new URL(searchCalls()[0]![0], 'http://localhost').searchParams
    expect(params.get('keyword')).toBe('雷鳴')
    expect(params.get('size')).toBe('10')

    const candidates = qa('[data-testid="friend-follow-candidate"]')
    expect(candidates).toHaveLength(1)
    expect(candidates[0]!.textContent).toContain('雷鳴FC')
    expect(candidates[0]!.textContent).toContain('rival-fc')

    click(candidates[0]!)
    await flushPromises()
    expect(submitButton().disabled).toBe(false)

    click(submitButton())
    await flushPromises()

    expect(followCalls()).toHaveLength(1)
    const [url, options] = followCalls()[0]!
    expect(url).toBe(`/api/v1/teams/${OWN_SLUG}/friends/follow`)
    expect(options?.body?.targetTeamSlug).toBe('rival-fc')
    expect(options?.body).not.toHaveProperty('targetTeamId')
    expect(wrapper.emitted('success')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false])
  })

  it('AC-9: 1文字では検索 API を呼ばない', async () => {
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', '雷')
    await settleSearch(1000)

    expect(searchCalls()).toHaveLength(0)
  })

  it('AC-9: debounce が効く（300ms 以内の連続入力は最後の1回だけ検索する）', async () => {
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', 'ra')
    await vi.advanceTimersByTimeAsync(100)
    await typeInto('friend-follow-search-input', 'rai')
    await vi.advanceTimersByTimeAsync(100)
    await typeInto('friend-follow-search-input', 'raim')
    await vi.advanceTimersByTimeAsync(299)
    expect(searchCalls(), '最後の入力から 300ms 経つまでは呼ばない').toHaveLength(0)

    await settleSearch(1)
    expect(searchCalls()).toHaveLength(1)
    expect(new URL(searchCalls()[0]![0], 'http://localhost').searchParams.get('keyword')).toBe('raim')
  })

  it('AC-9: 候補0件のとき「見つからない」案内と slug 直接入力の案内が出る', async () => {
    searchHandler = async () => paged([])
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', 'ひみつ')
    await settleSearch()

    const noResults = q('[data-testid="friend-follow-no-results"]')
    expect(noResults).not.toBeNull()
    expect(noResults!.textContent).toContain('friends.follow_dialog.no_results')
    expect(noResults!.textContent).toContain('friends.follow_dialog.slug_input_hint')
    expect(q('[data-testid="friend-follow-search-error"]')).toBeNull()
    // PrimeVue の hide() は setTimeout(0) で閉じる（スクリーンリーダー対策）。fake timer を進めてから確認する
    await vi.advanceTimersByTimeAsync(10)
    expect(inputOf('friend-follow-search-input').getAttribute('aria-expanded'), '0件のときも標準の空結果オーバーレイを開かず、自前案内だけを出す').toBe('false')
    expect(q('[data-testid="friend-follow-slug-input"]'), 'slug 直接入力欄は常に使える').not.toBeNull()
  })

  it('AC-19: 候補に出ない slug を直接入力して送信できる', async () => {
    wrapper = await mountDialog()

    await typeInto('friend-follow-slug-input', 'secret-team')
    await flushPromises()
    expect(submitButton().disabled).toBe(false)

    click(submitButton())
    await flushPromises()

    expect(followCalls()).toHaveLength(1)
    expect(followCalls()[0]![1]?.body?.targetTeamSlug).toBe('secret-team')
    expect(followCalls()[0]![1]?.body).not.toHaveProperty('targetTeamId')
  })

  it('入力が無い間は送信ボタンが無効', async () => {
    wrapper = await mountDialog()
    expect(submitButton().disabled).toBe(true)
  })

  it('AC-10: 候補から自チームを選ぶと送信ボタンが無効になり、案内が出る', async () => {
    searchHandler = async () => paged([OWN, RIVAL])
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', 'チーム')
    await settleSearch()
    const own = qa('[data-testid="friend-follow-candidate"]').find(el => el.textContent?.includes(OWN_SLUG))
    expect(own, '自チームも候補には出る（選んだ時点で弾く）').toBeTruthy()
    click(own!)
    await flushPromises()

    expect(submitButton().disabled).toBe(true)
    expect(q('[data-testid="friend-follow-self-warning"]')?.textContent).toContain('friends.errors.self_follow')
  })

  it('AC-10: slug 直接入力で自チームを指定すると送信ボタンが無効になり、案内が出る', async () => {
    wrapper = await mountDialog()

    await typeInto('friend-follow-slug-input', OWN_SLUG)
    await flushPromises()

    expect(submitButton().disabled).toBe(true)
    expect(q('[data-testid="friend-follow-self-warning"]')?.textContent).toContain('friends.errors.self_follow')
    click(submitButton())
    await flushPromises()
    expect(followCalls()).toHaveLength(0)
  })

  it('AC-11: 送信に失敗してもダイアログは閉じず、入力は保持される', async () => {
    followHandler = async () => {
      throw Object.assign(new Error('Not Found'), { statusCode: 404 })
    }
    wrapper = await mountDialog()

    await typeInto('friend-follow-slug-input', 'rival-fc')
    await flushPromises()
    click(submitButton())
    await flushPromises()

    expect(followCalls()).toHaveLength(1)
    expect(handleApiError).toHaveBeenCalled()
    expect(wrapper.emitted('success')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue') ?? []).not.toContainEqual([false])
    expect(q('[data-testid="friend-follow-dialog"]')).not.toBeNull()
    expect(inputOf('friend-follow-slug-input').value).toBe('rival-fc')
    expect(submitButton().disabled, '再送できる').toBe(false)
  })

  it.each([
    ['5xx', () => Promise.reject(Object.assign(new Error('Internal Server Error'), { statusCode: 500 }))],
    ['通信断', () => Promise.reject(new TypeError('Failed to fetch'))],
  ])('AC-20: 検索の %s は0件と区別したエラー表示になり、再検索で復旧する', async (_label, fail) => {
    let attempt = 0
    searchHandler = () => {
      attempt += 1
      return attempt === 1 ? fail() : Promise.resolve(paged([RIVAL]))
    }
    wrapper = await mountDialog()

    await typeInto('friend-follow-search-input', '雷鳴')
    await settleSearch()

    const error = q('[data-testid="friend-follow-search-error"]')
    expect(error).not.toBeNull()
    expect(error!.textContent).toContain('friends.follow_dialog.search_error')
    expect(q('[data-testid="friend-follow-no-results"]'), 'エラーを0件と混同しない').toBeNull()
    // PrimeVue の hide() は setTimeout(0) で閉じる（スクリーンリーダー対策）。fake timer を進めてから確認する
    await vi.advanceTimersByTimeAsync(10)
    expect(inputOf('friend-follow-search-input').getAttribute('aria-expanded'), '検索失敗中は AutoComplete 標準のオーバーレイを開かない（エラー表示・再試行ボタンを隠すため）').toBe('false')

    const retry = q('[data-testid="friend-follow-search-retry"]')
    expect(retry).not.toBeNull()
    click(retry!)
    await settleSearch()

    expect(searchCalls()).toHaveLength(2)
    expect(new URL(searchCalls()[1]![0], 'http://localhost').searchParams.get('keyword')).toBe('雷鳴')
    expect(q('[data-testid="friend-follow-search-error"]')).toBeNull()
    expect(qa('[data-testid="friend-follow-candidate"]')).toHaveLength(1)
  })
  it('AC17: 再フォロー初期slugを表示し画面操作だけで同じ相手を送信できる', async () => {
    wrapper = await mountDialog('rival-fc')
    expect(inputOf('friend-follow-slug-input').value).toBe('rival-fc')
    expect(submitButton().disabled).toBe(false)
    click(submitButton())
    await flushPromises()
    expect(followCalls()[0]![1]!.body!.targetTeamSlug).toBe('rival-fc')
    expect(wrapper.emitted('success')).toHaveLength(1)
  })

  it('AC18: 409 SOCIAL_102を親へ伝播して閉じる', async () => {
    followHandler = async () => { throw { statusCode: 409, data: { error: { code: 'SOCIAL_102' } } } }
    wrapper = await mountDialog('rival-fc')
    click(submitButton())
    await flushPromises()
    expect(wrapper.emitted('alreadyFollowing')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

})
