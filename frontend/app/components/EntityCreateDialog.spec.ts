import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EntityCreateDialog from './EntityCreateDialog.vue'

// Codex検分是正②: CMP-260901-1538 柱③-A の同名確認フロー（DUPNAME_001/DUPNAME_002）のunit spec。
// 対象: (a) 初回ボディと再送ボディの同一性＋fingerprint付与 (b) キャンセルで送信しない
// (c) 再度DUPNAME_001で新fingerprintに更新されたダイアログ再表示 (d) DUPNAME_002は共通ハンドラへ委譲

const apiMock = vi.fn()
const handleApiErrorMock = vi.fn()
const getFieldErrorsMock = vi.fn(() => ({}) as Record<string, string>)
const notificationSuccessMock = vi.fn()

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) =>
    params ? `${key}:${JSON.stringify(params)}` : key,
}))
mockNuxtImport('useApi', () => () => apiMock)
mockNuxtImport('useNotification', () => () => ({
  success: notificationSuccessMock,
  error: vi.fn(),
}))
mockNuxtImport('useErrorHandler', () => () => ({
  handleApiError: handleApiErrorMock,
  getFieldErrors: getFieldErrorsMock,
}))
mockNuxtImport('useMatchingApi', () => () => ({
  getPrefectures: vi.fn().mockResolvedValue({ data: [] }),
  getCities: vi.fn().mockResolvedValue({ data: [] }),
}))
mockNuxtImport('useTeamApi', () => () => ({
  checkTeamSlugAvailable: vi.fn().mockResolvedValue({ available: true }),
}))
mockNuxtImport('useOrganizationApi', () => () => ({
  checkOrganizationSlugAvailable: vi.fn().mockResolvedValue({ available: true }),
}))
mockNuxtImport('useAuthStore', () => () => ({
  currentUser: { id: 1 },
}))
mockNuxtImport('useFormDraft', () => () => ({
  clear: vi.fn(),
  restore: vi.fn(() => null),
}))

const stubs = {
  Dialog: {
    props: ['visible'],
    emits: ['update:visible'],
    template: '<section v-if="visible" role="dialog"><slot /><slot name="footer" /></section>',
  },
  Button: {
    props: ['label', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  InputText: true,
  Select: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      "<div class=\"select-stub\" :data-values=\"(options ?? []).map((o) => o.value ?? o.name).join(',')\" />",
  },
  Textarea: true,
  ToggleSwitch: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :id="inputId" />',
  },
}

/** DUPNAME_001（409）を模したエラーオブジェクト（ofetch FetchError 互換の data.error 形状）。 */
function dupname001Error(
  fingerprint: string,
  visibleCandidates: Array<{ id: string; name: string }>,
  hiddenCandidateCount: number,
) {
  return {
    data: {
      error: {
        code: 'DUPNAME_001',
        message: '同名の候補が見つかりました。内容を確認のうえ再送信してください',
        fieldErrors: [],
        details: {
          fingerprint,
          expiresAtEpochSecond: 9999999999,
          visibleCandidates,
          hiddenCandidateCount,
        },
      },
    },
  }
}

/** DUPNAME_002（409・ロック競合）を模したエラーオブジェクト。 */
function dupname002Error() {
  return {
    data: {
      error: {
        code: 'DUPNAME_002',
        message: '同時に同名の作成が競合しています。しばらくして再試行してください',
        fieldErrors: [],
      },
    },
  }
}

async function mountDialog() {
  return mountSuspended(EntityCreateDialog, {
    props: { entityType: 'organization', visible: true },
    global: { stubs },
  })
}

beforeEach(() => {
  apiMock.mockReset()
  handleApiErrorMock.mockReset()
  getFieldErrorsMock.mockReset()
  getFieldErrorsMock.mockReturnValue({})
  notificationSuccessMock.mockReset()
})

describe('EntityCreateDialog 同名確認フロー', () => {
  it('(a) 確認後の再送は初回ボディに confirmDuplicate/fingerprint を足しただけの同一ボディになる', async () => {
    apiMock.mockRejectedValueOnce(dupname001Error('fp-1', [{ id: '10', name: '重複組織' }], 0))
    apiMock.mockResolvedValueOnce({ data: { id: '20', name: 'x', slug: 'x' } })

    const wrapper = await mountDialog()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(true)

    const firstBody = apiMock.mock.calls[0]![1].body

    await wrapper.get('[data-testid="duplicate-name-confirm"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledTimes(2)
    const secondBody = apiMock.mock.calls[1]![1].body
    expect(secondBody).toEqual({
      ...firstBody,
      confirmDuplicate: true,
      duplicateNameFingerprint: 'fp-1',
    })
    // 確認応答成功後はダイアログが閉じる。
    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(false)
  })

  it('(b) 確認ダイアログで「やめる」を選んだ場合は再送しない', async () => {
    apiMock.mockRejectedValueOnce(dupname001Error('fp-1', [], 2))

    const wrapper = await mountDialog()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(true)

    await wrapper.get('[data-testid="duplicate-name-cancel"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(false)
    expect(apiMock).toHaveBeenCalledTimes(1)
  })

  it('(c) 確認再送がまた DUPNAME_001 になったら新しい fingerprint でダイアログを再表示する', async () => {
    apiMock.mockRejectedValueOnce(dupname001Error('fp-1', [{ id: '10', name: 'A' }], 0))
    apiMock.mockRejectedValueOnce(dupname001Error('fp-2', [{ id: '11', name: 'B' }], 3))
    apiMock.mockResolvedValueOnce({ data: { id: '30', name: 'x', slug: 'x' } })

    const wrapper = await mountDialog()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-testid="duplicate-name-confirm"]').trigger('click')
    await flushPromises()

    // 2回目の確認送信も 409 になったため、ダイアログは閉じずに新しい候補で再表示される。
    expect(apiMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('B')

    await wrapper.get('[data-testid="duplicate-name-confirm"]').trigger('click')
    await flushPromises()

    expect(apiMock).toHaveBeenCalledTimes(3)
    const thirdBody = apiMock.mock.calls[2]![1].body
    expect(thirdBody.duplicateNameFingerprint).toBe('fp-2')
  })

  it('(d) DUPNAME_002 は確認ダイアログを出さず共通エラーハンドラへ委譲する', async () => {
    apiMock.mockRejectedValueOnce(dupname002Error())

    const wrapper = await mountDialog()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="duplicate-name-confirm-dialog"]').exists()).toBe(false)
    expect(handleApiErrorMock).toHaveBeenCalledTimes(1)
    expect(handleApiErrorMock.mock.calls[0]![0]).toEqual(dupname002Error())
  })
})

// CMP-261010-1129: 組織作成の公開範囲・fieldErrors 表示・サポーター label 結線。
describe('EntityCreateDialog 組織作成の公開範囲とエラー表示 (CMP-261010-1129)', () => {
  const valuesOf = (w: Awaited<ReturnType<typeof mountDialog>>) =>
    w.get('[data-testid="entity-create-visibility"]').attributes('data-values')

  it('AC1: 組織の公開範囲選択肢は BE の組織列挙値 PUBLIC,PRIVATE のみ', async () => {
    const wrapper = await mountDialog()
    expect(valuesOf(wrapper)).toBe('PUBLIC,PRIVATE')
  })

  it('AC1: チームの公開範囲選択肢は従来のまま', async () => {
    const wrapper = await mountSuspended(EntityCreateDialog, {
      props: { entityType: 'team', visible: true },
      global: { stubs },
    })
    expect(valuesOf(wrapper)).toBe(
      'PUBLIC,GUESTS_AND_ABOVE,SUPPORTERS_AND_ABOVE,MEMBERS_AND_ABOVE',
    )
  })

  it('AC2: PRIVATE を選ぶと visibility=PRIVATE で POST される', async () => {
    apiMock.mockResolvedValueOnce({ data: { id: '1', name: 'x', slug: 'x' } })
    const wrapper = await mountDialog()
    wrapper
      .getComponent('[data-testid="entity-create-visibility"]')
      .vm.$emit('update:modelValue', 'PRIVATE')
    await flushPromises()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()
    expect(apiMock.mock.calls[0]![1].body.visibility).toBe('PRIVATE')
  })

  it('AC3: fieldErrors は対応入力が無い項目も含め全て画面に表示され、通知で握りつぶされない', async () => {
    apiMock.mockRejectedValueOnce({ data: { error: { code: 'COMMON_001', fieldErrors: [] } } })
    getFieldErrorsMock.mockReturnValue({
      name: 'ERR_NAME',
      visibility: 'ERR_VISIBILITY',
      orgType: 'ERR_ORGTYPE',
      unknownField: 'ERR_UNKNOWN',
    })
    const wrapper = await mountDialog()
    await wrapper.get('[data-testid="entity-create-submit"]').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    for (const m of ['ERR_NAME', 'ERR_VISIBILITY', 'ERR_ORGTYPE', 'ERR_UNKNOWN']) {
      expect(text).toContain(m)
    }
  })

  it('AC4: サポーター機能トグルの label は入力 id と結ばれている', async () => {
    const wrapper = await mountDialog()
    const forAttr = wrapper.get('[data-testid="entity-create-supporter-label"]').attributes('for')
    expect(forAttr).toBeTruthy()
    expect(wrapper.find(`input[id="${forAttr}"]`).exists()).toBe(true)
  })
})
