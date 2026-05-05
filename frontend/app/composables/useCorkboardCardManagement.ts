import type { Ref } from 'vue'
import { ref, computed } from 'vue'
import { useToast } from 'primevue/usetoast'
import type { CorkboardDetail, CorkboardCardDetail } from '~/types/corkboard'

/**
 * F09.8 Phase C リファクタリング: カード CRUD・アーカイブ管理 composable。
 *
 * `[id].vue` から以下のロジックを抽出した:
 *  - カード作成・編集モーダルの開閉制御 (editorMode / editorTarget / editorVisible)
 *  - カード削除 (confirmDelete / doDelete)
 *  - アーカイブ切り替え (toggleArchive)
 *
 * 呼び出し元は `board` Ref を渡し、操作結果はその Ref に反映される（楽観的更新またはリロード）。
 * モーダル保存完了後の `load()` 呼び出しは、呼び出し元から `onSaved` コールバックとして渡す設計にする。
 */
export function useCorkboardCardManagement(
  board: Ref<CorkboardDetail | null>,
  boardId: Ref<number>,
) {
  const {
    deleteCard: apiDeleteCard,
    archiveCard: apiArchiveCard,
  } = useCorkboardApi()
  const { captureQuiet } = useErrorReport()
  const toast = useToast()
  const { t } = useI18n()
  const { confirmAction } = useConfirmDialog()

  // ----- モーダル開閉・モード制御 -----

  /** `null` のとき非表示。create / edit を 1 つの state で制御する。 */
  const editorMode = ref<'create' | 'edit' | null>(null)
  const editorTarget = ref<CorkboardCardDetail | null>(null)

  /** editorMode が null でないときモーダルを表示する computed（双方向）。 */
  const editorVisible = computed({
    get: () => editorMode.value !== null,
    set: (v: boolean) => {
      if (!v) {
        editorMode.value = null
        editorTarget.value = null
      }
    },
  })

  /**
   * create 時にモーダルへ渡す初期座標（既存カードと重ならない適度な位置）。
   * 既存カードの右下に少しずらした位置をデフォルトにする（重なり回避の簡易ヒューリスティック）。
   */
  const editorDefaultPosition = computed(() => {
    let x = 40
    let y = 40
    for (const c of (board.value?.cards ?? [])) {
      if (c.positionX + 40 > x) x = c.positionX + 40
      if (c.positionY + 40 > y) y = c.positionY + 40
    }
    return { x: Math.min(x, 1000), y: Math.min(y, 600) }
  })

  /** カード作成モードでモーダルを開く。 */
  function openCreate() {
    editorTarget.value = null
    editorMode.value = 'create'
  }

  /** カード編集モードでモーダルを開く。 */
  function openEdit(card: CorkboardCardDetail) {
    editorTarget.value = card
    editorMode.value = 'edit'
  }

  // ----- カード削除 -----

  /** 削除確認ダイアログを表示し、承認されたら doDelete を呼ぶ。 */
  function confirmDelete(card: CorkboardCardDetail) {
    confirmAction({
      header: t('corkboard.confirm.deleteTitle'),
      message: t('corkboard.confirm.deleteMessage'),
      onAccept: () => doDelete(card),
    })
  }

  /** 削除 API を呼び出し、成功時はローカル状態からカードを除去する。 */
  async function doDelete(card: CorkboardCardDetail) {
    try {
      await apiDeleteCard(boardId.value, card.id)
      if (board.value) {
        board.value = {
          ...board.value,
          cards: board.value.cards.filter((c) => c.id !== card.id),
        }
      }
      toast.add({
        severity: 'success',
        summary: t('corkboard.toast.deleteSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardCardManagement: カード削除失敗' })
      toast.add({
        severity: 'error',
        summary: t('corkboard.toast.deleteError'),
        life: 3500,
      })
    }
  }

  // ----- アーカイブ切り替え -----

  /** アーカイブ状態を切り替え、成功時はローカル状態を最新 DTO で置換する。 */
  async function toggleArchive(card: CorkboardCardDetail) {
    const next = !card.isArchived
    try {
      const res = await apiArchiveCard(boardId.value, card.id, next)
      if (board.value) {
        board.value = {
          ...board.value,
          cards: board.value.cards.map((c) => (c.id === card.id ? res.data : c)),
        }
      }
      toast.add({
        severity: 'success',
        summary: next
          ? t('corkboard.toast.archiveSuccess')
          : t('corkboard.toast.unarchiveSuccess'),
        life: 2500,
      })
    } catch (e) {
      captureQuiet(e, { context: 'useCorkboardCardManagement: アーカイブ切替失敗' })
      toast.add({
        severity: 'error',
        summary: next
          ? t('corkboard.toast.archiveError')
          : t('corkboard.toast.unarchiveError'),
        life: 3500,
      })
    }
  }

  return {
    editorMode,
    editorTarget,
    editorVisible,
    editorDefaultPosition,
    openCreate,
    openEdit,
    confirmDelete,
    doDelete,
    toggleArchive,
  }
}
