import { useToast } from 'primevue/usetoast'

/**
 * PrimeVue の {@code ToastMessageOptions} は任意プロパティを実行時に保持して
 * {@code #message} スロットへ渡すが、型定義には {@code data} フィールドが無いため
 * ここで型拡張して結線する（{@code any} は使わず {@code unknown} で受ける）。
 */
declare module 'primevue/toast' {
  interface ToastMessageOptions {
    /** カスタムペイロード（Undo Toast では {@link UndoToastData}）。#message スロットで参照する */
    data?: unknown
  }
}

/**
 * ADHD フレンドリー UX 基盤: 「元に戻す」ボタン付き Toast composable。
 *
 * <p>破壊的操作（削除・完了・アーカイブ等）を実行したあと、「〇〇しました」メッセージと
 * 「元に戻す」ボタンを表示する。ボタンを押すと渡したコールバックが発火し、押されないまま
 * 一定時間（既定5秒）経過すると Toast は消え、コールバックは発火しない。
 * ADHD 傾向のユーザーが誤操作しても即座にリカバリできる安心感を提供する。</p>
 *
 * <p>受け入れ条件（AC）との対応:</p>
 * <ul>
 *   <li>AC-4: 「〇〇しました」メッセージ + 「元に戻す」ボタン付き Toast を表示。ボタン押下で
 *       コールバック発火。既定5秒押されなければ消え、コールバックは発火しない</li>
 * </ul>
 *
 * <p>PrimeVue の Toast はメッセージ本体にアクションボタンを持たないため、{@code toast.add()} の
 * {@code data} フィールドに {@link UndoToastData} を積み、グローバル {@code <Toast>}（app.vue）の
 * {@code #message} スロット（{@code AppToastMessage.vue}）でボタンを描画する。ボタン押下時は
 * {@link UndoToastData#onUndo} を呼び、{@code toast.remove()} で Toast を閉じる。</p>
 *
 * <p>既存の {@code useNotification} / 通常 Toast の挙動には一切干渉しない
 * （{@code data.undoAction} を持たないメッセージは従来通り描画される）。</p>
 */

/** Toast の data フィールドに載せる Undo 用ペイロード */
export interface UndoToastData {
  /** グローバル Toast テンプレートが Undo ボタンを描画する目印 */
  undoAction: true
  /** 「元に戻す」ボタンのラベル（i18n 済み文字列） */
  undoLabel: string
  /** Undo ボタン押下時に発火するコールバック */
  onUndo: () => void | Promise<void>
}

export interface UndoToastOptions {
  /** 「元に戻す」で発火するコールバック（必須） */
  onUndo: () => void | Promise<void>
  /** 見出し（例: 「削除しました」）。省略時は summary のみ */
  summary: string
  /** 補足テキスト（任意） */
  detail?: string
  /** 表示時間（ms）。この時間内に押されなければ Toast は消え、onUndo は発火しない。既定 5000 */
  life?: number
  /** 「元に戻す」ボタンのラベル。省略時は呼び出し側で i18n した文字列を渡すこと */
  undoLabel: string
  /** severity（既定 'info'） */
  severity?: 'success' | 'info' | 'warn' | 'error'
}

export interface UseUndoToastReturn {
  /** Undo ボタン付き Toast を表示する */
  showUndoToast: (options: UndoToastOptions) => void
}

export function useUndoToast(): UseUndoToastReturn {
  const toast = useToast()

  function showUndoToast(options: UndoToastOptions): void {
    const { onUndo, summary, detail, life = 5000, undoLabel, severity = 'info' } = options

    const data: UndoToastData = {
      undoAction: true,
      undoLabel,
      // AppToastMessage.vue が Undo ボタン押下時にこのコールバックを呼ぶ。
      // 押されなければ life 経過で Toast が自動消滅し、onUndo は発火しない（AC-4）。
      onUndo,
    }

    toast.add({
      severity,
      summary,
      detail,
      life,
      data,
    })
  }

  return { showUndoToast }
}
