import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useUndoToast, type UndoToastData } from '~/composables/useUndoToast'

/**
 * useUndoToast のユニットテスト。
 *
 * 受け入れ条件（AC）とテストの対応:
 * - AC-4: 「〇〇しました」メッセージ + 「元に戻す」ボタン付き Toast を表示。ボタン押下で
 *   コールバック発火。既定5秒押されなければ消え、コールバックは発火しない。
 *
 * PrimeVue の Toast はグローバル <Toast>（app.vue）の #message スロット
 * （AppToastMessage.vue）でボタンを描画するため、composable の責務は
 * 「toast.add に undoAction ペイロードを正しく積むこと」と「ボタン押下で onUndo が
 * 発火し、押されなければ発火しないこと」の検証となる。
 */

// PrimeVue useToast のモック
const toastAdd = vi.fn()
const toastRemove = vi.fn()
vi.mock('primevue/usetoast', () => ({
  useToast: () => ({ add: toastAdd, remove: toastRemove }),
}))

beforeEach(() => {
  toastAdd.mockReset()
  toastRemove.mockReset()
})

describe('useUndoToast', () => {
  it('AC-4: summary と undo データ付きの Toast を toast.add で表示する（既定 life=5000）', () => {
    const { showUndoToast } = useUndoToast()
    const onUndo = vi.fn()

    showUndoToast({
      summary: '削除しました',
      undoLabel: '元に戻す',
      onUndo,
    })

    expect(toastAdd).toHaveBeenCalledTimes(1)
    const arg = toastAdd.mock.calls[0]![0]
    expect(arg.summary).toBe('削除しました')
    expect(arg.life).toBe(5000)
    expect(arg.severity).toBe('info')

    const data = arg.data as UndoToastData
    expect(data.undoAction).toBe(true)
    expect(data.undoLabel).toBe('元に戻す')
    expect(typeof data.onUndo).toBe('function')
  })

  it('AC-4: data.onUndo を呼ぶと渡したコールバックが発火する（ボタン押下相当）', async () => {
    const { showUndoToast } = useUndoToast()
    const onUndo = vi.fn()

    showUndoToast({ summary: '完了にしました', undoLabel: '元に戻す', onUndo })

    const data = toastAdd.mock.calls[0]![0].data as UndoToastData
    // AppToastMessage のボタン押下で data.onUndo が呼ばれる挙動を模倣
    await data.onUndo()
    expect(onUndo).toHaveBeenCalledTimes(1)
  })

  it('AC-4: ボタンを押さなければ（onUndo を呼ばなければ）コールバックは発火しない', () => {
    const { showUndoToast } = useUndoToast()
    const onUndo = vi.fn()

    showUndoToast({ summary: 'アーカイブしました', undoLabel: '元に戻す', onUndo })

    // Toast を表示しただけ。life 経過で自動消滅する（PrimeVue 側）。onUndo は未発火。
    expect(onUndo).not.toHaveBeenCalled()
  })

  it('AC-4: life / detail / severity を上書きできる', () => {
    const { showUndoToast } = useUndoToast()

    showUndoToast({
      summary: 'アーカイブしました',
      detail: '3件をアーカイブしました',
      undoLabel: 'Undo',
      severity: 'success',
      life: 8000,
      onUndo: vi.fn(),
    })

    const arg = toastAdd.mock.calls[0]![0]
    expect(arg.detail).toBe('3件をアーカイブしました')
    expect(arg.severity).toBe('success')
    expect(arg.life).toBe(8000)
  })
})
