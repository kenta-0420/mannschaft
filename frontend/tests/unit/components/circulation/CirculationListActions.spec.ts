import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import CirculationList from '~/components/circulation/CirculationList.vue'
import type { CirculationDocumentListItem } from '~/types/circulation'
import jaCirculation from '~/locales/ja/circulation.json'

/**
 * CMP-260930-1537: 回覧の取消・削除（BE 実装済み）を一覧から実行できること。
 *
 * - 取消: 管理権限あり かつ DRAFT / ACTIVE のときのみ表示
 * - 削除: 管理権限あり かつ DRAFT / CANCELLED のときのみ表示
 * - 押すと確認ダイアログ → API → 一覧再取得。失敗は握りつぶさず利用者へ表示
 * - チーム・組織の両スコープで同じコンポーネントが動く
 */

const mockList = vi.fn()
const mockCancel = vi.fn()
const mockDelete = vi.fn()
const mockNotifySuccess = vi.fn()
const mockHandleApiError = vi.fn()
let confirmAccept: (() => void | Promise<void>) | null = null

mockNuxtImport('useCirculationApi', () => () => ({
  listScopedCirculations: mockList,
  cancelScopedCirculation: mockCancel,
  deleteScopedCirculation: mockDelete,
}))
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  showSuccess: mockNotifySuccess,
  info: vi.fn(),
  error: vi.fn(),
  showError: vi.fn(),
}))
mockNuxtImport('useConfirm', () => () => ({
  require: (options: { accept: () => void | Promise<void> }) => {
    confirmAccept = options.accept
  },
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mockHandleApiError }))

function item(id: number, status: CirculationDocumentListItem['status']): CirculationDocumentListItem {
  return {
    id,
    scopeType: 'TEAM',
    scopeId: 12,
    title: `回覧${id}`,
    body: null,
    status,
    createdBy: 1,
    createdByName: 'A',
    dueDate: null,
    stampDisplayStyle: 'DEFAULT',
    totalRecipientCount: 2,
    stampedCount: 0,
    createdAt: '2026-10-01T00:00:00',
    updatedAt: '2026-10-01T00:00:00',
  }
}

const ALL = [item(1, 'DRAFT'), item(2, 'ACTIVE'), item(3, 'COMPLETED'), item(4, 'CANCELLED')]

async function mountList(canManage: boolean, scopeType: 'TEAM' | 'ORGANIZATION' = 'TEAM') {
  mockList.mockResolvedValue({ data: ALL, meta: { page: 0, size: 20, total: 4, totalPages: 1 } })
  const wrapper = await mountSuspended(CirculationList, {
    props: { scopeType, scopeId: 'slug-x', canManage },
    global: {
      plugins: [createI18n({ legacy: false, locale: 'ja', fallbackLocale: false, messages: { ja: jaCirculation } })],
    },
  })
  await new Promise(resolve => setTimeout(resolve, 0))
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountList>>
const exists = (w: Wrapper, id: string) => w.find(`[data-testid="${id}"]`).exists()

beforeEach(() => {
  mockList.mockReset()
  mockCancel.mockReset().mockResolvedValue(undefined)
  mockDelete.mockReset().mockResolvedValue(undefined)
  mockNotifySuccess.mockReset()
  mockHandleApiError.mockReset()
  confirmAccept = null
})

describe('CirculationList 取消・削除', () => {
  it('管理権限ありのとき状態に応じたボタンだけを表示する', async () => {
    const w = await mountList(true)
    expect(exists(w, 'circulation-cancel-1')).toBe(true) // DRAFT
    expect(exists(w, 'circulation-cancel-2')).toBe(true) // ACTIVE
    expect(exists(w, 'circulation-cancel-3')).toBe(false) // COMPLETED
    expect(exists(w, 'circulation-cancel-4')).toBe(false) // CANCELLED
    expect(exists(w, 'circulation-delete-1')).toBe(true) // DRAFT
    expect(exists(w, 'circulation-delete-2')).toBe(false) // ACTIVE
    expect(exists(w, 'circulation-delete-3')).toBe(false) // COMPLETED
    expect(exists(w, 'circulation-delete-4')).toBe(true) // CANCELLED
  })

  it('管理権限なしの者にはボタンを一切表示しない', async () => {
    const w = await mountList(false)
    expect(w.findAll('[data-testid^="circulation-cancel-"]')).toHaveLength(0)
    expect(w.findAll('[data-testid^="circulation-delete-"]')).toHaveLength(0)
  })

  it('取消: 確認前は API を呼ばず、確認後に取消して一覧を再取得する', async () => {
    const w = await mountList(true)
    await w.find('[data-testid="circulation-cancel-2"]').trigger('click')
    expect(mockCancel).not.toHaveBeenCalled()
    mockList.mockClear()
    await confirmAccept?.()
    expect(mockCancel).toHaveBeenCalledWith('team', '12', 2)
    expect(mockList).toHaveBeenCalledTimes(1)
    expect(mockNotifySuccess).toHaveBeenCalledTimes(1)
  })

  it('削除: 確認後に削除して一覧を再取得する（組織スコープでも同じ部品で動く）', async () => {
    const w = await mountList(true, 'ORGANIZATION')
    await w.find('[data-testid="circulation-delete-4"]').trigger('click')
    expect(mockDelete).not.toHaveBeenCalled()
    mockList.mockClear()
    await confirmAccept?.()
    expect(mockDelete).toHaveBeenCalledWith('organization', '12', 4)
    expect(mockList).toHaveBeenCalledTimes(1)
  })

  it('API 失敗は握りつぶさずエラー処理へ渡し、成功扱いにしない', async () => {
    const w = await mountList(true)
    await w.find('[data-testid="circulation-cancel-1"]').trigger('click')
    const err = new Error('boom')
    mockCancel.mockRejectedValue(err)
    await confirmAccept?.()
    expect(mockHandleApiError).toHaveBeenCalledWith(err, expect.any(String))
    expect(mockNotifySuccess).not.toHaveBeenCalled()
  })
})
