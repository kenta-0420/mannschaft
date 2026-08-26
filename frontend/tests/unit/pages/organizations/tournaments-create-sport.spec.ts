import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { ref } from 'vue'
import TournamentsPage from '~/pages/organizations/[slug]/tournaments/index.vue'
import { TOURNAMENT_SPORTS } from '~/types/tournament'

/**
 * F08.10 多競技対応（🟡-1b）: 大会作成フォームの競技（sport）選択ユニットテスト。
 *
 * 検証観点:
 *   SPORT-UI-001: 作成フォームの sport 既定値は SOCCER（送信ペイロードに乗る）
 *   SPORT-UI-002: sport を変更すると createTournament のペイロードに選択値が乗る
 *   SPORT-UI-003: 競技選択肢は生成型由来の 8 競技を網羅する
 *
 * 注: 作成ダイアログ（PrimeVue Dialog）は document.body へ teleport されるため、
 *     必須テキスト入力は document 経由で操作する（wrapper 配下には現れない）。
 */

const getTournaments = vi.fn()
const createTournament = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useTournamentApi', () => () => ({ getTournaments, createTournament }))
mockNuxtImport('useNotification', () => () => ({ success: notifySuccess, error: notifyError }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(true), loadPermissions: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

/** ネイティブ input に値を流し込み v-model を更新する（teleport 先の DOM 用）。 */
function setNativeInput(el: Element, value: string) {
  const input = el as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

async function openCreateDialog() {
  const wrapper = await mountSuspended(TournamentsPage)
  await flushPromises()
  const openBtn = wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.text().includes('大会を作成'))
  expect(openBtn).toBeTruthy()
  await openBtn!.trigger('click')
  await flushPromises()
  return wrapper
}

/** 必須項目（大会名・競技種目）を埋める。ダイアログ内 input の先頭 2 つが対象。 */
async function fillRequired(title: string, sportCategory: string) {
  const inputs = document.querySelectorAll<HTMLInputElement>('.p-inputtext')
  setNativeInput(inputs[0]!, title)
  setNativeInput(inputs[1]!, sportCategory)
  await flushPromises()
}

function findCreateButton(wrapper: VueWrapper) {
  return wrapper
    .findAllComponents({ name: 'Button' })
    .find((b) => b.text().includes('作成') && !b.text().includes('大会を作成'))
}

async function submit(wrapper: VueWrapper) {
  await findCreateButton(wrapper)!.trigger('click')
  await flushPromises()
}

describe('organizations/[slug]/tournaments.vue 大会作成 — sport 選択', () => {
  beforeEach(() => {
    getTournaments.mockReset()
    createTournament.mockReset()
    notifySuccess.mockReset()
    notifyError.mockReset()
    getTournaments.mockResolvedValue({ data: [] })
    createTournament.mockResolvedValue({ data: { id: 1 } })
  })

  it('SPORT-UI-001: 既定 sport=SOCCER が作成ペイロードに乗る', async () => {
    const wrapper = await openCreateDialog()
    await fillRequired('テスト大会', 'サッカー')
    await submit(wrapper)

    expect(createTournament).toHaveBeenCalledTimes(1)
    const [, payload] = createTournament.mock.calls[0]!
    expect(payload.sport).toBe('SOCCER')
  })

  it('SPORT-UI-002: 選択した sport が作成ペイロードに乗る', async () => {
    const wrapper = await openCreateDialog()
    await fillRequired('バレー大会', 'バレーボール')

    // PrimeVue Select の内部 DOM 操作は脆いため、update:modelValue 経由で選択を再現する。
    // ダイアログは teleport されるため data-testid を持つ Select コンポーネントを直接探す。
    const sportSelect = wrapper
      .findAllComponents({ name: 'Select' })
      .find((s) => s.attributes('data-testid') === 'create-sport-select')
    expect(sportSelect).toBeTruthy()
    sportSelect!.vm.$emit('update:modelValue', 'VOLLEYBALL')
    await flushPromises()

    await submit(wrapper)

    expect(createTournament).toHaveBeenCalledTimes(1)
    const [, payload] = createTournament.mock.calls[0]!
    expect(payload.sport).toBe('VOLLEYBALL')
  })

  it('SPORT-UI-003: 競技選択肢は生成型由来の 8 競技を網羅する', () => {
    expect(TOURNAMENT_SPORTS).toEqual([
      'SOCCER',
      'FUTSAL',
      'BASKETBALL',
      'VOLLEYBALL',
      'SHOGI',
      'GO',
      'FIGURE_SKATING',
      'GYMNASTICS',
    ])
  })
})
