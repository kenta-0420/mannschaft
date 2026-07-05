import { ref, watch, type Ref, type WatchSource } from 'vue'

/**
 * ADHD フレンドリー UX 基盤: フォーム下書き自動保存 composable。
 *
 * <p>金型 {@code ActionMemoInput.vue} / {@code useActionMemoStore.ts} の下書き実装
 * （localStorage キー・1秒 debounce・送信成功時のみクリア・onMounted 復元・
 * 「下書き保存しました」1.5秒フラッシュ）を汎用化したもの。TODO 作成ダイアログや
 * アンケート・活動記録など、入力途中で離脱しても書きかけを失わせたくない全フォームで使う。</p>
 *
 * <p>受け入れ条件（AC）との対応:</p>
 * <ul>
 *   <li>AC-1: 監視対象が変化してから約1秒後（debounce）に localStorage へ保存される</li>
 *   <li>AC-2: {@link UseFormDraftReturn#clear} で下書き削除。保存失敗・未送信時は保持</li>
 *   <li>AC-3: {@link UseFormDraftReturn#restore}（初期化時にも自動実行）で前回の下書きを復元できる</li>
 * </ul>
 *
 * <p>SSR 安全（{@code typeof window} ガード）。値は JSON 直列化するため、単純な文字列だけでなく
 * reactive なフォームオブジェクトも保存できる。</p>
 *
 * @typeParam T 下書きとして保存する値の型（文字列・オブジェクト・配列いずれも可）
 * @param key localStorage キー（呼び出し側が指定。例: {@code `todo-create-draft-${userId}`}）
 * @param options.debounceMs 保存までの debounce（既定 1000ms）
 * @param options.source 監視対象。省略時は返り値の {@link UseFormDraftReturn#draft} を監視する。
 *   既存の reactive なフォーム ref を渡すと、その変更を自動 watch して保存する
 * @param options.flashMs 「下書き保存しました」フラッシュの表示時間（既定 1500ms）
 * @param options.autoRestore 初期化時に自動で restore するか（既定 true）
 */
export interface UseFormDraftOptions<T> {
  debounceMs?: number
  flashMs?: number
  autoRestore?: boolean
  /**
   * 監視対象。省略時は返り値 draft を監視する。
   * フォーム側で既に ref/reactive を持っている場合はそれを渡して自動保存させる。
   */
  source?: WatchSource<T>
}

export interface UseFormDraftReturn<T> {
  /** 監視対象を省略した場合に使う下書き ref。フォームの v-model に直接繋げられる */
  draft: Ref<T | null>
  /** 現在値を即座に localStorage へ保存する（debounce を待たない） */
  save: (value: T) => void
  /** 下書きを削除する（送信成功時に呼ぶ想定） */
  clear: () => void
  /** localStorage から前回の下書きを読み出す。無ければ null */
  restore: () => T | null
  /** 直近で保存が行われたことを示すフラッシュフラグ（{@code flashMs} 後に false へ戻る） */
  savedFlash: Ref<boolean>
}

const isBrowser = (): boolean => typeof window !== 'undefined'

export function useFormDraft<T>(
  key: string,
  options: UseFormDraftOptions<T> = {},
): UseFormDraftReturn<T> {
  const { debounceMs = 1000, flashMs = 1500, autoRestore = true, source } = options

  const draft = ref<T | null>(null) as Ref<T | null>
  const savedFlash = ref(false)

  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let flashTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * localStorage から前回の下書きを読み出す。JSON パースに失敗した場合や未保存時は null。
   */
  function restore(): T | null {
    if (!isBrowser()) return null
    try {
      const raw = window.localStorage.getItem(key)
      if (raw === null) return null
      return JSON.parse(raw) as T
    } catch {
      return null
    }
  }

  /**
   * 値を即座に localStorage へ保存する。空（null / undefined / 空文字）の場合は削除する。
   */
  function save(value: T): void {
    if (!isBrowser()) return
    try {
      if (isEmptyDraft(value)) {
        window.localStorage.removeItem(key)
        return
      }
      window.localStorage.setItem(key, JSON.stringify(value))
      // 保存フラッシュ（金型の 1.5秒表示に倣う）
      savedFlash.value = true
      if (flashTimer) clearTimeout(flashTimer)
      flashTimer = setTimeout(() => {
        savedFlash.value = false
        flashTimer = null
      }, flashMs)
    } catch {
      // Storage quota / disabled — 対処療法で握り潰さず、書きかけ自体は呼び出し側の
      // ref に残っているので UX は破綻しない。保存できなかっただけなので silent とする。
    }
  }

  /**
   * 下書きを削除する。送信成功時に呼ぶ。savedFlash も即座に落とす。
   */
  function clear(): void {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    savedFlash.value = false
    if (!isBrowser()) return
    try {
      window.localStorage.removeItem(key)
    } catch {
      // ignore
    }
  }

  /**
   * 監視対象の変更を debounce して保存する。
   */
  const watched: WatchSource<T> = source ?? (draft as unknown as WatchSource<T>)
  watch(
    watched,
    (next) => {
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(() => {
        save(next as T)
        saveTimer = null
      }, debounceMs)
    },
    // flush: 'sync' — 値の変更と同時に debounce タイマーを張る。描画後 flush まで
    // 待つと「入力からちょうど1秒後に保存」という体感がずれるため sync が適切。
    { deep: true, flush: 'sync' },
  )

  // 初期化時に自動復元（source を渡さない draft モードのときのみ draft へ反映する。
  // source を渡す場合は呼び出し側が restore() の戻り値を自分でフォームへ流し込む）
  if (autoRestore) {
    const restored = restore()
    if (restored !== null && source === undefined) {
      draft.value = restored
    }
  }

  return { draft, save, clear, restore, savedFlash }
}

/**
 * 「保存する意味がない空の下書き」かどうかを判定する。
 * 空文字 / null / undefined / 空配列 / 全フィールドが空のオブジェクトは削除対象。
 */
function isEmptyDraft(value: unknown): boolean {
  if (value === null || value === undefined) return true
  if (typeof value === 'string') return value.length === 0
  if (Array.isArray(value)) return value.length === 0
  if (typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).every(
      (v) => v === null || v === undefined || v === '',
    )
  }
  return false
}
