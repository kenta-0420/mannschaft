import { computed } from 'vue'
import { useWindowSize } from '@vueuse/core'

/**
 * デスクトップ幅（>= 768px, Tailwind の md ブレークポイント）かどうかを返す共通 composable。
 *
 * チャット等のレスポンシブ単一ペイン切替で「デスクトップ=2ペイン / モバイル=単一ペイン」の
 * 判定閾値を FE 全体で統一するために切り出した（従来 pages/chat/index.vue に直書きしていた
 * `width.value >= 768` を正準化）。
 *
 * SSR 時は useWindowSize の初期値（Infinity）により isDesktop=true となり、
 * ハイドレーション後に実ビューポート幅へ追随する（既存 /chat の挙動と一致）。
 */
export function useIsDesktop() {
  const { width } = useWindowSize()
  const isDesktop = computed(() => width.value >= 768)
  return { isDesktop, width }
}
