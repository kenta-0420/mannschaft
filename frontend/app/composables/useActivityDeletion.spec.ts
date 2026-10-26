// @vitest-environment happy-dom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { ActivityDetailResponse } from '~/types/activity'
import { useActivityDeletion } from './useActivityDeletion'

const mocks = vi.hoisted(() => ({
  remove: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  success: vi.fn(),
  requireConfirmation: vi.fn(),
}))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('primevue/useconfirm', () => ({
  useConfirm: () => ({ require: mocks.requireConfirmation }),
}))
vi.mock('~/composables/useActivityApi', () => ({
  useActivityApi: () => ({ deleteActivity: mocks.remove }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: mocks.error }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: mocks.success }),
}))
vi.mock('#app/composables/router', () => ({ navigateTo: mocks.navigate }))

describe('useActivityDeletion', () => {
  const remove = mocks.remove
  const navigate = mocks.navigate
  const handleApiError = mocks.error
  const success = mocks.success
  let confirmation: { accept: () => Promise<void> } | undefined
  const requireConfirmation = mocks.requireConfirmation.mockImplementation(
    (options: { accept: () => Promise<void> }) => {
      confirmation = options
    },
  )
  const fixture = () =>
    ref({
      id: 7,
      canDelete: true,
      metadataOnly: false,
      scopeType: 'TEAM',
      scopePublicId: 'own-team',
    } as ActivityDetailResponse)
  beforeEach(() => {
    vi.clearAllMocks()
    confirmation = undefined
    remove.mockResolvedValue(undefined)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })
  it('取消は削除せず、確認文で元の予定が残ることを示す', () => {
    useActivityDeletion(fixture()).requestDelete()
    expect(requireConfirmation).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'activity.delete.confirm' }),
    )
    expect(remove).not.toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
  })
  it('認可能力がない利用者とmetadata閲覧者は削除を開始できない', () => {
    const record = fixture()
    record.value.canDelete = false
    useActivityDeletion(record).requestDelete()
    record.value.canDelete = true
    record.value.metadataOnly = true
    useActivityDeletion(record).requestDelete()
    expect(requireConfirmation).not.toHaveBeenCalled()
  })
  it('成功した活動DELETEだけで一覧へ戻り元予定APIを呼ばない', async () => {
    useActivityDeletion(fixture()).requestDelete()
    await confirmation!.accept()
    expect(remove).toHaveBeenCalledExactlyOnceWith(7)
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/teams/own-team/activities')
    expect(success).toHaveBeenCalledOnce()
  })
  it('送信中の二重確認受理を抑止する', async () => {
    let finish!: () => void
    remove.mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve
      }),
    )
    const deletion = useActivityDeletion(fixture())
    deletion.requestDelete()
    const first = confirmation!.accept()
    await confirmation!.accept()
    expect(deletion.deleting.value).toBe(true)
    expect(remove).toHaveBeenCalledOnce()
    finish()
    await first
    expect(deletion.deleting.value).toBe(false)
  })
  it('失敗は詳細を保持して標準エラー通知し再試行できる', async () => {
    const error = new Error('delete failed')
    remove.mockRejectedValueOnce(error)
    const record = fixture()
    const deletion = useActivityDeletion(record)
    deletion.requestDelete()
    await confirmation!.accept()
    expect(record.value.id).toBe(7)
    expect(handleApiError).toHaveBeenCalledWith(error, '活動記録削除')
    expect(navigate).not.toHaveBeenCalled()
    expect(deletion.deleting.value).toBe(false)
    deletion.requestDelete()
    await confirmation!.accept()
    expect(remove).toHaveBeenCalledTimes(2)
  })
  it('確認後の対象変更や権限喪失では削除しない', async () => {
    const record = fixture()
    useActivityDeletion(record).requestDelete()
    record.value = { ...record.value, id: 8 }
    await confirmation!.accept()
    expect(remove).not.toHaveBeenCalled()
  })
})
