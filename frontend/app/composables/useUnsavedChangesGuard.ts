import { computed, getCurrentScope, onScopeDispose, ref, toValue } from 'vue'
import type { ComputedRef, MaybeRefOrGetter } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
// 周囲の composable は Nuxt の auto-import で useI18n を使うが、ここでは明示 import する。
// この composable は Nuxt app context を持たない happy-dom 環境のユニットテストからも
// 読み込まれるため、auto-import の解決に依存させない。
import { useI18n } from 'vue-i18n'

/**
 * 未保存の変更がある状態での離脱に警告を出す「離脱ガード」composable。
 *
 * <p>プロフィール編集（{@code /settings/profile}）のように、入力途中で離脱すると
 * 書きかけが失われる画面に付ける。ルート遷移（{@code onBeforeRouteLeave}）と
 * ブラウザの離脱（{@code beforeunload}）の両方を押さえる。</p>
 *
 * <p>受け入れ条件（AC）との対応:</p>
 * <ul>
 *   <li>AC-1: 初期値スナップショットと現在値が異なるとき {@link UseUnsavedChangesGuardReturn#isDirty} が true になる</li>
 *   <li>AC-2: 保存成功後に {@link UseUnsavedChangesGuardReturn#markAsSaved} を呼ぶとスナップショットが更新され、警告が出なくなる</li>
 *   <li>AC-3: dirty なときだけルート離脱の確認と {@code beforeunload} の警告が発火する</li>
 * </ul>
 *
 * <p>文言は i18n 必須（既定キー {@link UNSAVED_CHANGES_MESSAGE_KEY}）。
 * SSR 安全（{@code typeof window} ガード）。</p>
 *
 * @typeParam T 監視するフォーム値の型（オブジェクト・配列・プリミティブいずれも可）
 */

/** 既定の警告文言の i18n キー */
export const UNSAVED_CHANGES_MESSAGE_KEY = 'common.unsavedChanges.confirmLeave'

export interface UseUnsavedChangesGuardOptions<T> {
  /**
   * 警告文言。省略時は i18n の {@link UNSAVED_CHANGES_MESSAGE_KEY} を引く。
   * i18n に依存させたくない呼び出し（テスト等）では明示的に渡す。
   */
  message?: MaybeRefOrGetter<string>
  /** ガードを有効にするか（既定 true）。読み込み中は false にする等の用途 */
  enabled?: MaybeRefOrGetter<boolean>
  /**
   * 初期スナップショットを setup 時に取らず、最初の {@link UseUnsavedChangesGuardReturn#resetBaseline}
   * （または {@link UseUnsavedChangesGuardReturn#markAsSaved}）まで遅らせるか（既定 false）。
   *
   * <p>初期値をサーバーから非同期に取る画面で true にする。スナップショットを張るまで
   * {@code isDirty} が false に固定されるため、「読み込み完了フラグを下ろす順序」に
   * 依存せず、無入力なのに dirty と判定される窓が構造的に消える。</p>
   *
   * <p>{@code enabled} とは抑止の理由が別なので併用してよい
   * （{@code enabled}=「今はガードしたくない」／こちらは「まだ基準値が無い」）。</p>
   */
  deferInitialSnapshot?: boolean
  /** 確認ダイアログ。既定は {@code window.confirm} */
  confirm?: (message: string) => boolean
  /** スナップショットの直列化方法（既定 {@code JSON.stringify}） */
  serialize?: (value: T) => string
}

export interface UseUnsavedChangesGuardReturn<T> {
  /** 初期値スナップショットと現在値が異なるか */
  isDirty: ComputedRef<boolean>
  /** 現在値を新しいスナップショットとして採用する（保存成功後に呼ぶ） */
  markAsSaved: () => void
  /**
   * スナップショットを張り直す。値を渡さなければ現在値を使う
   * （初期データの非同期取得が終わった直後に呼ぶ想定）
   */
  resetBaseline: (value?: T) => void
  /**
   * 離脱してよいかを判定する。dirty なら確認ダイアログを出し、その結果を返す。
   * ルート離脱ガードの実体であり、独自の遷移（プログラム遷移など）からも呼べる
   */
  confirmLeave: () => boolean
}

const isBrowser = (): boolean => typeof window !== 'undefined'

/**
 * JSON.stringify の replacer。null / undefined を空文字に正規化する。
 * サーバーが null を返すフィールド（未設定の表示名など）を入力欄で空にしただけで
 * 永久に dirty になるのを防ぐ。
 *
 * <p><b>平坦な文字列フォームを前提とする。</b> 素の {@code JSON.stringify} では
 * {@code {a: undefined, b: 1}} と {@code {b: 1}} は同値だが、この replacer を通すと
 * 前者は {@code {"a":"","b":1}} になり非同値になる。逆に「未設定（null）」と
 * 「空文字」を区別したい値（クリア済みの日付など）を入れ子で持つフォームでは、
 * 差分が黙って潰れる。そうした形を扱う呼び出し元は {@code serialize} を明示せよ。</p>
 */
function normalizeNullish(_key: string, value: unknown): unknown {
  return value === null || value === undefined ? '' : value
}

export function useUnsavedChangesGuard<T>(
  source: MaybeRefOrGetter<T>,
  options: UseUnsavedChangesGuardOptions<T> = {},
): UseUnsavedChangesGuardReturn<T> {
  const {
    message,
    enabled = true,
    deferInitialSnapshot = false,
    confirm,
    // 既定の直列化。null/undefined は空文字に寄せる
    // （nickname が null のサーバー値と、入力欄で空にした '' を同一視するため）
    serialize = (value: T): string => JSON.stringify(value, normalizeNullish) ?? '',
  } = options

  const baseline = ref<string>(deferInitialSnapshot ? '' : serialize(toValue(source)))

  /** 比較の基準となるスナップショットが確定しているか（deferInitialSnapshot 用） */
  const hasBaseline = ref<boolean>(!deferInitialSnapshot)

  const isDirty = computed<boolean>(() => {
    // 基準値がまだ無い＝比較しようがない。読み込み中の抑止（enabled）とは別の理由
    if (!hasBaseline.value) return false
    if (!toValue(enabled)) return false
    return serialize(toValue(source)) !== baseline.value
  })

  function resetBaseline(value?: T): void {
    baseline.value = serialize(value === undefined ? toValue(source) : value)
    hasBaseline.value = true
  }

  function markAsSaved(): void {
    resetBaseline()
  }

  // useI18n() は setup 実行中でなければ使えないため、
  // 離脱ガードのコールバック内ではなくここで t を捕まえておく。
  const translate = message === undefined ? useI18n().t : null

  /** 文言の解決。message 未指定なら i18n から引く（直書き禁止のため） */
  function resolveMessage(): string {
    if (message !== undefined) return toValue(message)
    return translate ? translate(UNSAVED_CHANGES_MESSAGE_KEY) : ''
  }

  function askConfirm(): boolean {
    const text = resolveMessage()
    if (confirm) return confirm(text)
    if (!isBrowser()) return true
    return window.confirm(text)
  }

  function confirmLeave(): boolean {
    if (!isDirty.value) return true
    return askConfirm()
  }

  // ブラウザの離脱（リロード・タブを閉じる・外部リンク）。
  // 解除できない状況（effect scope 外）では登録もしない。
  // 登録だけして解除できないと、リスナーが window に残って source getter を
  // 握り続け、無関係な全ページで離脱警告が出るリークになる。
  if (isBrowser()) {
    if (!getCurrentScope()) {
      throw new Error(
        'useUnsavedChangesGuard はコンポーネントの setup（effect scope 内）で呼ぶこと。'
        + ' scope 外では beforeunload リスナーを解除できずリークする。',
      )
    }
    const onBeforeUnload = (event: BeforeUnloadEvent): void => {
      if (!isDirty.value) return
      // 現代ブラウザは preventDefault だけで確認ダイアログが出る
      event.preventDefault()
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    onScopeDispose(() => {
      window.removeEventListener('beforeunload', onBeforeUnload)
    })
  }

  // Nuxt（vue-router）のルート離脱
  onBeforeRouteLeave(() => confirmLeave())

  return { isDirty, markAsSaved, resetBaseline, confirmLeave }
}
