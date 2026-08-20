// @vitest-environment happy-dom
// 検査対象は Nuxt app context を必要としない純粋な composable（警告文言は明示的に渡す）。
// 共通 setup が document を参照するため、同種の spec と同じ happy-dom を用いる。
import { describe, it, expect, vi, afterEach } from 'vitest'
import { defineComponent, h, ref, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import {
  useUnsavedChangesGuard,
  UNSAVED_CHANGES_MESSAGE_KEY,
  type UseUnsavedChangesGuardReturn,
} from '~/composables/useUnsavedChangesGuard'

/**
 * useUnsavedChangesGuard のユニットテスト（Issue #2857）。
 *
 * 受け入れ条件（AC）とテストの対応:
 * - AC-1: 初期値スナップショットとの差分で isDirty が立つ
 * - AC-2: 保存後（markAsSaved / resetBaseline）はスナップショットが更新され警告が出ない
 * - AC-3: dirty なときだけルート離脱の確認と beforeunload の警告が発火する
 */

interface Form {
  nickname: string
  phoneNumber: string
}

/**
 * マウントしたハーネスは各テストの終わりに必ず破棄する。
 * beforeunload リスナーは window（テスト間で共有）に付くため、
 * 破棄を忘れると前のテストの dirty なフォームが次のテストに漏れる。
 */
const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  while (mountedWrappers.length > 0) {
    mountedWrappers.pop()?.unmount()
  }
})

/**
 * composable をコンポーネントの setup 内で動かすためのハーネス。
 * ライフサイクル（beforeunload の登録・解除）を実物どおりに走らせる。
 */
async function mountGuard(
  form: ReturnType<typeof ref<Form>>,
  options: Parameters<typeof useUnsavedChangesGuard<Form>>[1] = {},
) {
  let api: UseUnsavedChangesGuardReturn<Form> | null = null
  const component = defineComponent({
    setup() {
      api = useUnsavedChangesGuard<Form>(() => form.value as Form, {
        message: 'テスト用の警告文言',
        ...options,
      })
      return () => h('div')
    },
  })
  const wrapper = mount(component)
  mountedWrappers.push(wrapper)
  if (!api) throw new Error('composable が初期化されていない')
  return { wrapper, api: api as UseUnsavedChangesGuardReturn<Form> }
}

describe('useUnsavedChangesGuard', () => {
  it('AC-1: 初期値と同じなら isDirty は false、変更すると true になる', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '090-0000-0000' })
    const { api } = await mountGuard(form)

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
    const { api } = await mountGuard(form, { enabled: () => enabled.value })

    form.value = { ...form.value, nickname: '入力中' }
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    enabled.value = true
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-2: markAsSaved でスナップショットが更新され isDirty が false に戻る', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const { api } = await mountGuard(form)

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
    const { api } = await mountGuard(form)

    // サーバーに保存されたのは nickname のみ、というケース
    api.resetBaseline({ nickname: '太郎', phoneNumber: '' })
    await nextTick()
    expect(api.isDirty.value).toBe(false)

    api.resetBaseline({ nickname: '花子', phoneNumber: '' })
    await nextTick()
    expect(api.isDirty.value).toBe(true)
  })

  it('AC-3: dirty のときだけ離脱ガードが確認ダイアログを出す', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    const confirm = vi.fn<(message: string) => boolean>(() => false)
    const { api } = await mountGuard(form, { confirm })

    // クリーンな状態では確認せず通す
    expect(api.confirmLeave()).toBe(true)
    expect(confirm).not.toHaveBeenCalled()

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    expect(api.isDirty.value).toBe(true)

    // dirty なら確認する。拒否されたら遷移させない
    expect(api.confirmLeave()).toBe(false)
    expect(confirm).toHaveBeenCalledWith('テスト用の警告文言')

    // 承諾されたら遷移させる
    confirm.mockReturnValue(true)
    expect(api.confirmLeave()).toBe(true)
  })

  it('AC-3: dirty のときだけ beforeunload が preventDefault される', async () => {
    const form = ref<Form>({ nickname: '太郎', phoneNumber: '' })
    await mountGuard(form)

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
    const { wrapper } = await mountGuard(form)

    form.value = { ...form.value, nickname: '次郎' }
    await nextTick()
    wrapper.unmount()
    mountedWrappers.length = 0

    const afterUnmount = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(afterUnmount)
    expect(afterUnmount.defaultPrevented).toBe(false)
  })

  it('既定の警告文言は i18n キーで引かれる（直書きしない）', () => {
    expect(UNSAVED_CHANGES_MESSAGE_KEY).toBe('common.unsavedChanges.confirmLeave')
  })
})
