/**
 * Konami コマンド（↑↑↓↓←→←→BA）を検出するcomposable。
 *
 * セキュリティ・品質要件:
 * - e.isComposing でIMEガード（deprecated の e.keyCode === 229 禁止）
 * - コールバックはコマンド完成時に1回だけ呼ばれる
 * - コンポーネントのunmount時にイベントリスナーを自動解除
 */

import { ref, readonly, onMounted, onUnmounted } from 'vue'

const KONAMI_SEQUENCE = [
  'ArrowUp', 'ArrowUp',
  'ArrowDown', 'ArrowDown',
  'ArrowLeft', 'ArrowRight',
  'ArrowLeft', 'ArrowRight',
  'KeyB', 'KeyA',
] as const

export function useKonamiCommand(onSuccess: () => void) {
  const progress = ref(0)

  function handleKeydown(e: KeyboardEvent) {
    // IME入力中は無視
    if (e.isComposing) return

    const expected = KONAMI_SEQUENCE[progress.value]
    if (e.code === expected) {
      progress.value++
      if (progress.value === KONAMI_SEQUENCE.length) {
        progress.value = 0
        onSuccess()
      }
    } else {
      // 最初から振り出しに戻す（ただしArrowUpが再入力された場合は1にリセット）
      progress.value = e.code === KONAMI_SEQUENCE[0] ? 1 : 0
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', handleKeydown)
  })

  onUnmounted(() => {
    window.removeEventListener('keydown', handleKeydown)
  })

  return { progress: readonly(progress) }
}
