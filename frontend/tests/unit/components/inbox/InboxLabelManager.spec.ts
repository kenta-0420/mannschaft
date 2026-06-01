import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import InboxLabelManager from '~/components/inbox/InboxLabelManager.vue'
import type { InboxLabel } from '~/types/inbox'

/**
 * F04.11 InboxLabelManager.vue のユニットテスト。
 *
 * 検分指摘 H1 の根治確認:
 * - createLabel が 409 を throw したとき、duplicate トーストが表示されダイアログが閉じないこと
 * - updateLabel が 409 を throw したとき、duplicate トーストが表示されダイアログが閉じないこと
 */

// ──────────────────────────────────────────────
// モック定義
// ──────────────────────────────────────────────

const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()

const storeMock = {
  labels: [] as InboxLabel[],
  labelsLoading: false,
  fetchLabels: vi.fn().mockResolvedValue(undefined),
  createLabel: vi.fn(),
  updateLabel: vi.fn(),
  deleteLabel: vi.fn(),
}

vi.mock('~/stores/useInboxStore', () => ({
  useInboxStore: () => storeMock,
}))

vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: notificationSuccessMock,
    error: notificationErrorMock,
  }),
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    captureQuiet: vi.fn(),
  }),
}))

function makeLabel(overrides: Partial<InboxLabel> = {}): InboxLabel {
  return {
    id: 'label-1',
    name: 'テストラベル',
    color: '#6366f1',
    icon: null,
    sortOrder: 0,
    ...overrides,
  }
}

/** VM への型付きアクセス用インターフェース */
interface LabelManagerVM {
  openCreateDialog: () => void
  openEditDialog: (l: InboxLabel) => void
  handleSave: () => Promise<void>
  confirmDelete: () => Promise<void>
  dialogVisible: boolean
  deleteDialogVisible: boolean
  form: { name: string; color: string; icon: string }
  deleteTarget: InboxLabel | null
}

// ──────────────────────────────────────────────
// テスト
// ──────────────────────────────────────────────

describe('InboxLabelManager.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    storeMock.labels = []
    storeMock.labelsLoading = false
    storeMock.fetchLabels.mockResolvedValue(undefined)
    storeMock.createLabel.mockReset()
    storeMock.updateLabel.mockReset()
    storeMock.deleteLabel.mockReset()
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
  })

  // ──────────────────────────────────────────────
  // 空状態・ローディング
  // ──────────────────────────────────────────────

  describe('初期表示', () => {
    it('ラベルが0件のときは空状態が表示される', async () => {
      storeMock.labels = []
      const wrapper = await mountSuspended(InboxLabelManager)
      const empty = wrapper.find('[data-testid="inbox-label-manager-empty"]')
      expect(empty.exists()).toBe(true)
    })

    it('ラベルが存在する場合は行が表示される', async () => {
      storeMock.labels = [makeLabel()]
      const wrapper = await mountSuspended(InboxLabelManager)
      const row = wrapper.find('[data-testid="inbox-label-manager-row-label-1"]')
      expect(row.exists()).toBe(true)
    })

    it('onMounted で fetchLabels が呼ばれない（labels が既に存在する場合）', async () => {
      storeMock.labels = [makeLabel()]
      storeMock.fetchLabels.mockClear()
      await mountSuspended(InboxLabelManager)
      expect(storeMock.fetchLabels).not.toHaveBeenCalled()
    })

    it('onMounted で fetchLabels が呼ばれる（labels が空の場合）', async () => {
      storeMock.labels = []
      storeMock.fetchLabels.mockClear()
      await mountSuspended(InboxLabelManager)
      expect(storeMock.fetchLabels).toHaveBeenCalled()
    })
  })

  // ──────────────────────────────────────────────
  // [H1] 作成時の 409 エラー（同名重複）
  // ──────────────────────────────────────────────

  describe('[H1] createLabel — 409 同名重複エラー', () => {
    it('createLabel が 409 エラーを throw したとき duplicate トーストが表示される', async () => {
      const duplicateError = { status: 409, data: { error: { code: 'LABEL_NAME_DUPLICATE' } } }
      storeMock.createLabel.mockRejectedValue(duplicateError)

      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openCreateDialog()
      await wrapper.vm.$nextTick()

      // フォームの値をVM経由で設定（Dialogコンポーネントは条件付きレンダリングのためDOMが存在しない場合がある）
      vm.form.name = '重複ラベル'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      // duplicate エラートーストが表示される
      expect(notificationErrorMock).toHaveBeenCalledWith(
        expect.stringContaining('A label with the same name already exists'),
      )
      // ダイアログが閉じていない
      expect(vm.dialogVisible).toBe(true)
    })

    it('createLabel が 409 エラーを throw したとき成功トーストは表示されない', async () => {
      const duplicateError = { status: 409, data: { error: { code: 'LABEL_NAME_DUPLICATE' } } }
      storeMock.createLabel.mockRejectedValue(duplicateError)

      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openCreateDialog()
      await wrapper.vm.$nextTick()

      vm.form.name = '重複ラベル'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      expect(notificationSuccessMock).not.toHaveBeenCalled()
    })
  })

  // ──────────────────────────────────────────────
  // [H1] 作成時の 422 エラー（上限超過）
  // ──────────────────────────────────────────────

  describe('[H1] createLabel — 422 上限超過エラー', () => {
    it('createLabel が 422 エラーを throw したとき limitReached トーストが表示される', async () => {
      const limitError = { status: 422, data: { error: { code: 'LABEL_LIMIT_EXCEEDED' } } }
      storeMock.createLabel.mockRejectedValue(limitError)

      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openCreateDialog()
      await wrapper.vm.$nextTick()

      vm.form.name = '新しいラベル'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      expect(notificationErrorMock).toHaveBeenCalledWith(
        expect.stringContaining('You can create up to 20 labels'),
      )
      // ダイアログが閉じていない
      expect(vm.dialogVisible).toBe(true)
    })
  })

  // ──────────────────────────────────────────────
  // [H1] 更新時の 409 エラー（同名重複）
  // ──────────────────────────────────────────────

  describe('[H1] updateLabel — 409 同名重複エラー', () => {
    it('updateLabel が 409 エラーを throw したとき duplicate トーストが表示されダイアログが閉じない', async () => {
      const duplicateError = { status: 409, data: { error: { code: 'LABEL_NAME_DUPLICATE' } } }
      storeMock.updateLabel.mockRejectedValue(duplicateError)

      const label = makeLabel()
      storeMock.labels = [label]
      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openEditDialog(label)
      await wrapper.vm.$nextTick()

      vm.form.name = '既存のラベル名'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      expect(notificationErrorMock).toHaveBeenCalledWith(
        expect.stringContaining('A label with the same name already exists'),
      )
      expect(vm.dialogVisible).toBe(true)
      expect(notificationSuccessMock).not.toHaveBeenCalled()
    })
  })

  // ──────────────────────────────────────────────
  // 正常系：作成成功
  // ──────────────────────────────────────────────

  describe('createLabel — 正常系', () => {
    it('createLabel 成功時に created トーストが表示されダイアログが閉じる', async () => {
      const newLabel = makeLabel({ id: 'label-new', name: '新ラベル' })
      storeMock.createLabel.mockResolvedValue(newLabel)

      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openCreateDialog()
      await wrapper.vm.$nextTick()

      vm.form.name = '新ラベル'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      expect(notificationSuccessMock).toHaveBeenCalledWith(
        expect.stringContaining('Label created'),
      )
      expect(vm.dialogVisible).toBe(false)
    })
  })

  // ──────────────────────────────────────────────
  // 正常系：更新成功
  // ──────────────────────────────────────────────

  describe('updateLabel — 正常系', () => {
    it('updateLabel 成功時に updated トーストが表示されダイアログが閉じる', async () => {
      storeMock.updateLabel.mockResolvedValue(undefined)

      const label = makeLabel()
      storeMock.labels = [label]
      const wrapper = await mountSuspended(InboxLabelManager)
      const vm = wrapper.vm as unknown as LabelManagerVM

      vm.openEditDialog(label)
      await wrapper.vm.$nextTick()

      vm.form.name = '更新ラベル'
      await wrapper.vm.$nextTick()

      await vm.handleSave()
      await wrapper.vm.$nextTick()

      expect(notificationSuccessMock).toHaveBeenCalledWith(
        expect.stringContaining('Label updated'),
      )
      expect(vm.dialogVisible).toBe(false)
    })
  })
})
