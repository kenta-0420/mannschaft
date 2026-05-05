import type { Ref } from 'vue'
import type { CorkboardGroupDetail } from '~/types/corkboard'

/**
 * コルクボードのセクション折りたたみ状態を localStorage と同期する composable。
 *
 * - localStorage キー: `corkboard:collapse:{boardId}`
 * - 各セクションごとに boolean を保持。`true` のとき折りたたみ。
 * - 値が無いセクションは DTO 側 `isCollapsed` を初期値とする。
 *
 * F09.8 Phase B 実装の `pages/corkboard/[id].vue` から切り出し（フロント技術的負債一掃 Phase 2）。
 *
 * @param boardId 表示中のボード ID（ref/computed）。値が変わると自動で再ロードする。
 */
export function useCorkboardCollapsedSections(boardId: Ref<number>) {
  /** セクション ID → 折りたたみ状態（true=折りたたみ） */
  const collapsedSections = ref<Record<number, boolean>>({})

  const storageKey = computed(() => `corkboard:collapse:${boardId.value}`)

  /** localStorage から復元する（壊れた値はサイレントに無視）。 */
  function loadCollapsedState(): void {
    if (typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(storageKey.value)
      if (!raw) {
        collapsedSections.value = {}
        return
      }
      const parsed: unknown = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') {
        const obj: Record<number, boolean> = {}
        for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
          const id = Number(k)
          if (Number.isFinite(id) && typeof v === 'boolean') {
            obj[id] = v
          }
        }
        collapsedSections.value = obj
      }
    } catch {
      // localStorage が壊れている等は無視
    }
  }

  /** 現在の折りたたみ状態を localStorage に保存する（QuotaExceeded 等は無視）。 */
  function persistCollapsedState(): void {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(
        storageKey.value,
        JSON.stringify(collapsedSections.value),
      )
    } catch {
      // QuotaExceeded 等は無視
    }
  }

  /** 指定セクションの折りたたみ状態を反転させ、localStorage に永続化する。 */
  function toggleSection(sectionId: number): void {
    collapsedSections.value = {
      ...collapsedSections.value,
      [sectionId]: !collapsedSections.value[sectionId],
    }
    persistCollapsedState()
  }

  /**
   * 指定セクションが折りたたまれているかを返す。
   * localStorage に値があればそれを優先、無ければ DTO 側 `isCollapsed` を使う。
   */
  function isSectionCollapsed(section: CorkboardGroupDetail): boolean {
    const local = collapsedSections.value[section.id]
    if (typeof local === 'boolean') return local
    return section.isCollapsed
  }

  return {
    collapsedSections,
    loadCollapsedState,
    persistCollapsedState,
    toggleSection,
    isSectionCollapsed,
  }
}
