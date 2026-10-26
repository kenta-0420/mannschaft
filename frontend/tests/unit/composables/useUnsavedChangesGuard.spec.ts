// @vitest-environment happy-dom
// Nuxt app context は使わず、vue-router / vue-i18n を素で装着して検証する。
// nuxt 環境（mountSuspended）はこの worktree では setupNuxt が完走しないため、
// 同種の spec と同じ happy-dom を用いる。
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, ref, nextTick } from 'vue'
import type { VueWrapper } from '@vue/test-utils'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import type { Router } from 'vue-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createI18n } from 'vue-i18n'
// 型（i18n スキーマ拡張）が6言語すべてを要求するため、common.json を全言語読み込む
import jaCommon from '~/locales/ja/common.json'
import enCommon from '~/locales/en/common.json'
import zhCommon from '~/locales/zh/common.json'
import koCommon from '~/locales/ko/common.json'
import esCommon from '~/locales/es/common.json'
import deCommon from '~/locales/de/common.json'
import {
  useUnsavedChangesGuard,
  UNSAVED_CHANGES_MESSAGE_KEY,
  type UseUnsavedChangesGuardOptions,
  type UseUnsavedChangesGuardReturn,
} from '~/composables/useUnsavedChangesGuard'

/**
 * useUnsavedChangesGuard のユニットテスト（Issue #2857）。
 *
 * 受け入れ条件（AC）とテストの対応:
 * - AC-1: 初期値スナップショットとの差分で isDirty が立つ
 * - AC-2: 保存後（markAsSaved / resetBaseline）はスナップショットが更新され警告が出ない
 * - AC-3: dirty なときだけルート離脱（onBeforeRouteLeave 実発火）と beforeunload が警告する
 * - AC-4: 警告文言は既定で i18n（common.unsavedChanges.confirmLeave）から解決される
 * - AC-5: deferInitialSnapshot 指定時は最初の resetBaseline まで dirty にならない
 *         （初期読込中の偽陽性を、ページ側の記述順序に依存せず構造的に防ぐ）
 */

interface Form {
  nickname: string | null
  phoneNumber: string
}

/**
 * マウントしたハーネスは各テストの終わりに必ず破棄する。
 * beforeunload リスナーは window（テスト間で共有）に付くため、
 * 破棄を忘れると前のテストの dirty なフォームが次のテストに漏れる。
 */
const mountedWrappers: VueWrapper[] = []

afterEach(() => {
  while (mountedWrappers.length > 0) {
    mountedWrappers.pop()?.unmount()
  }
})

interface Harness {
  wrapper: VueWrapper
  api: UseUnsavedChangesGuardReturn<Form>
  router: Router
}

/**
 * composable を router-view の子コンポーネントの setup 内で動かすハーネス。
 * onBeforeRouteLeave が matched route に実際に登録される構成にしてあるので、
 * router.push() でガードの実発火を検証できる。
 */
async function mountGuard(
  form: ReturnType<typeof ref<Form>>,
  options: UseUnsavedChangesGuardOptions<Form> = {},
): Promise<Harness> {
  let api: UseUnsavedChangesGuardReturn<Form> | null = null
  const guardedPage = defineComponent({
    setup() {
      api = useUnsavedChangesGuard<Form>(() => form.value as Form, options)
      return () => h('div', 'guarded')
    },
  })
  const otherPage = defineComponent({ setup: () => () => h('div', 'other') })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: guardedPage },
      { path: '/other', component: otherPage },
    ],
  })
  const i18n = createI18n({
    legacy: false,
    locale: 'ja',
    messages: {
      ja: jaCommon,
      en: enCommon,
      zh: zhCommon,
      ko: koCommon,
      es: esCommon,
      de: deCommon,
    },
  })

  await router.push('/')
  await router.isReady()

  const wrapper = mount(defineComponent({ setup: () => () => h(RouterView) }), {
    global: { plugins: [router, i18n] },
  })
  await nextTick()
  mountedWrappers.push(wrapper)
  if (!api) throw new Error('composable が初期化されていない')
  return { wrapper, api: api as UseUnsavedChangesGuardReturn<Form>, router }
}

interface JaCommonJson {
  common: { unsavedChanges: { title: string; confirmLeave: string } }
}

/** app/locales/ja/common.json を素の JSON として読む（i18n のプリコンパイルを迂回する） */
function loadJaCommonJson(): JaCommonJson {
  // vitest の root は frontend/（happy-dom では import.meta.url が file スキームでないため cwd 基準）
  const path = resolve(process.cwd(), 'app/locales/ja/common.json')
  return JSON.parse(readFileSync(path, 'utf-8')) as JaCommonJson
}

describe('useUnsavedChangesGuard', () => {
  const testMessage = { message: 'テスト用の警告文言' } as const

  it('AC-1: 初期値と同じなら isDirty は false、変更すると true になる', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '090-0000-0000' })
    const { api } = await mountGuard(form, testMessage)

    expect(api.isDirty.value).toBe(false)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)

    // 元の値に戻せば dirty も解消する（スナップショット比較であることの確認）
    form.value = { ...form.value, nickname: '太郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(false)
  })

  it('AC-1: enabled が false のあいだは isDirty が立たない（読み込み中の誤検知防止）', async () => {
    const form = ref<Form>({ nickname: '', phoneNumber: '' })
    const enabled = ref(false)
    const { api } = await mountGuard(form, { ...testMessage, enabled: () => enabled.value })

    form.value = { ...form.value, nickname: '入力中' }
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    enabled.value = true
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-5: deferInitialSnapshot 中は初期読込で値が入っても dirty にならない（順序非依存）', async () => {
    // 初期読込前の空フォーム。enabled は最初から true にして、
    // 「loading を下ろす順序」に依存しないことを示す
    const form = ref<Form>({ nickname: '', phoneNumber: '' })
    const { api } = await mountGuard(form, { ...testMessage, deferInitialSnapshot: true })

    // サーバーから初期値が届いた（＝ユーザーは何も入力していない）
    form.value = { nickname: '太郎', phoneNumber: '090-0000-0000' }
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    // ブラウザ離脱の警告も出ない
    const duringLoad = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(duringLoad)
    expect(duringLoad.defaultPrevented).toBe(false)

    // スナップショットを張った後は通常どおり dirty を検知する
    api.resetBaseline()
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-5: deferInitialSnapshot 中はルート離脱ガードも確認を出さない', async () => {
    const form = ref<Form>({ nickname: '', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => false)
    const { router } = await mountGuard(form, {
      ...testMessage,
      deferInitialSnapshot: true,
      confirm,
    })

    form.value = { nickname: '太郎', phoneNumber: '090-0000-0000' }
    await nextTick()

    await router.push('/other')
    expect(confirm).not.toHaveBeenCalled()
    expect(router.currentRoute.value.path).toBe('/other')
  })

  it('AC-1: サーバー値が null のフィールドを空にしただけでは dirty にならない', async () => {
    const form = ref<Form>({ nickname: null, phoneNumber: '' })
    const { api } = await mountGuard(form, testMessage)

    // 入力欄に触って空にすると空文字になるが、意味は未設定のまま
    form.value = { ...form.value, nickname: '' }
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    form.value = { ...form.value, nickname: '太郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-2: markAsSaved でスナップショットが更新され isDirty が false に戻る', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const { api } = await mountGuard(form, testMessage)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)

    api.markAsSaved()
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    // 保存後にさらに編集すれば再び dirty になる
    form.value = { ...form.value, phoneNumber: '080-1111-2222' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-2: resetBaseline に保存済みの値を渡すとその値が基準になる', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const { api } = await mountGuard(form, testMessage)

    // サーバーに保存されたのは nickname のみ、というケース
    api.resetBaseline({ nickname: '太郎', phoneNumber: '' })
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    api.resetBaseline({ nickname: '花子', phoneNumber: '' })
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-3: clean ならルート離脱ガードは素通りする（onBeforeRouteLeave 実発火）', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => false)
    const { router } = await mountGuard(form, { ...testMessage, confirm })

    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/other')
    expect(confirm).not.toHaveBeenCalled()
  })

  it('AC-3: dirty で確認を拒否するとルート遷移が中止される（onBeforeRouteLeave 実発火）', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => false)
    const { api, router } = await mountGuard(form, { ...testMessage, confirm })

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)

    const failure = await router.push('/other')
    expect(confirm).toHaveBeenCalledWith('テスト用の警告文言')
    expect(failure).toBeTruthy()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('AC-3: dirty でも確認を承諾すればルート遷移する（onBeforeRouteLeave 実発火）', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => true)
    const { router } = await mountGuard(form, { ...testMessage, confirm })

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()

    await router.push('/other')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.path).toBe('/other')
  })

  it('AC-3: dirty のときだけ beforeunload が preventDefault される', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    await mountGuard(form, testMessage)

    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()

    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
  })

  it('AC-3: アンマウント後は beforeunload の警告が出ない（リスナー解除）', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const { wrapper } = await mountGuard(form, testMessage)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    wrapper.unmount()
    mountedWrappers.length = 0

    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })

  it('AC-4: message 未指定なら既定文言が i18n から解決される（直書きしない）', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => false)
    // message を渡さない = 本番と同じ経路（useI18n().t）を通す
    const { router } = await mountGuard(form, { confirm })

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    await router.push('/other')

    // ロケールファイルを素の JSON として読み直す。
    // import 経由の JSON は @intlify/unplugin-vue-i18n がメッセージ AST に
    // プリコンパイルするため、期待値の文字列としては使えない。
    const expected = loadJaCommonJson().common.unsavedChanges.confirmLeave
    expect(confirm).toHaveBeenCalledWith(expected)
    // ロケールファイルから引けていることの裏取り（空文字やキー名そのままではない）
    expect(expected).toContain('保存されていない変更があります')
    expect(confirm).not.toHaveBeenCalledWith(UNSAVED_CHANGES_MESSAGE_KEY)
  })
})
