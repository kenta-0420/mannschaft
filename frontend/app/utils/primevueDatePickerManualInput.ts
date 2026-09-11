/**
 * PrimeVue DatePicker の手入力（キーボード入力）欠陥の根治パッチ。CMP-260910-1557。
 *
 * ## 欠陥の構造
 * PrimeVue 4.5.4 の `DatePicker` は入力欄を
 * `<InputText :defaultValue="inputFieldValue" @input="onInput">` として描画する。
 * `InputText`（正確には `BaseEditableHolder`）は `defaultValue` の変化を
 * `d_value` に写し、テンプレートで `:value="d_value"` と DOM を完全制御している。
 *
 * その結果、1打鍵ごとに `onInput` がモデルを更新すると
 * `inputFieldValue`（`formatValue` によるゼロ詰め済みの整形文字列）が変わり、
 * Vue が「利用者がいま打っている途中の文字列」を整形済み文字列で上書きする。
 * さらに `DatePicker` の `updated()` が打鍵前のキャレット位置を復元するため、
 * 桁が1つずれた位置に次の文字が挿入される。
 *
 *   `2026/09/3` と打つ → モデルが 2026-09-03 に確定 → DOM が `2026/09/03` に
 *   書き換わり、キャレットは末尾の1文字前 → 次に `0` を打つと `2026/09/003`
 *   → 最終的に 2026-09-03 が無警告で保存される。
 *
 * ## 対処
 * `onInput` を差し替え、
 *  1. 打鍵された文字列を区切り文字・全角数字について正規化してから解釈する
 *     （`2026-09-30` のようなハイフン区切りが黙って空欄になるのを防ぐ）
 *  2. モデル更新に伴う DOM 書き換えのあと、利用者が実際に打った文字列と
 *     キャレット位置を復元する
 * の2点を行う。DatePicker のコンポーネント定義そのものを差し替えるため、
 * アプリ内の全 `<DatePicker>`（PrimeVue の自動インポート経由を含む）に一括で効く。
 */
import { nextTick } from 'vue'

/** 全角数字 → 半角数字。 */
const FULLWIDTH_DIGIT = /[０-９]/g
/** 日付の区切りとして利用者が打ちうる文字（時刻のコロンは含めない）。 */
const SEPARATOR_CANDIDATE = /[-./／・－‐]/g
/** 和暦表記でない「年月日」区切り。 */
const YEAR_MONTH = /[年月]/g
const DAY_SUFFIX = /日/g

/** `dateFormat`（例: `yy/mm/dd`）が使う区切り文字を取り出す。 */
export function resolveDateSeparator(dateFormat: string): string | null {
  const matched = dateFormat.match(/[^a-zA-Z]/)
  return matched ? matched[0] : null
}

/**
 * 手入力された文字列を `dateFormat` が期待する表記に正規化する。
 * 入力途中の文字列（`2026/09/3` など）は壊さない。
 */
export function normalizeManualDateInput(text: string, dateFormat: string): string {
  if (!text) return text

  const separator = resolveDateSeparator(dateFormat)
  let normalized = text.replace(FULLWIDTH_DIGIT, (char) =>
    String.fromCharCode(char.charCodeAt(0) - 0xfee0),
  )

  if (separator === null) return normalized

  normalized = normalized.replace(YEAR_MONTH, separator).replace(DAY_SUFFIX, '')
  normalized = normalized.replace(SEPARATOR_CANDIDATE, separator)

  return normalized
}

/** PrimeVue の DatePicker コンポーネント定義（必要な口だけを型で記述する）。 */
interface DatePickerInternals {
  input?: HTMLInputElement | null
  typeUpdate: boolean
  datePattern: string
  updateModelType: string
  $refs: Record<string, { $el?: { style?: CSSStyleDeclaration } } | undefined>
  parseValue(text: string): unknown
  isValidSelection(value: unknown): boolean
  formatValue(value: unknown): string
  updateModel(value: unknown): void
  updateCurrentMetaData(): void
  $emit(event: string, payload: unknown): void
}

interface PatchableComponent {
  methods?: Record<string, unknown>
  __mannschaftManualInputFixApplied?: boolean
}

/**
 * DatePicker コンポーネント定義に手入力の根治パッチを当てる。冪等（二重適用しない）。
 *
 * 引数を `unknown` で受けるのは、PrimeVue が公開する `DefineComponent<…>` 型が
 * オプショナルなプロパティしか持たない構造体（弱い型）と互換でないため
 * （TS2559）。ここでは実体がオプション定義オブジェクトであることを実行時に
 * 確かめてから扱う。`any` は使わない。
 */
export function applyDatePickerManualInputFix(component: unknown): void {
  if (typeof component !== 'object' || component === null) return

  const target = component as PatchableComponent
  if (!target.methods || target.__mannschaftManualInputFixApplied) return
  target.__mannschaftManualInputFixApplied = true

  target.methods.onInput = function (this: DatePickerInternals, event: Event): void {
    const target = event.target as HTMLInputElement
    const typed = target.value
    const caret = target.selectionStart ?? typed.length

    try {
      const clearIconStyle = this.$refs.clearIcon?.$el?.style
      if (clearIconStyle) {
        clearIconStyle.display = typed.length === 0 ? 'none' : 'block'
      }

      const value = this.parseValue(normalizeManualDateInput(typed, this.datePattern))

      if (this.isValidSelection(value)) {
        this.typeUpdate = true
        this.updateModel(this.updateModelType === 'string' ? this.formatValue(value) : value)
        this.updateCurrentMetaData()
      }
    } catch {
      // parseValue は入力途中の文字列に対して例外を投げる（PrimeVue の仕様）。
      // 途中状態ではモデルを更新しないだけで、打鍵が進めば確定する。
      // 例外を握りつぶしているのではなく「まだ日付として読めない」という正常系である。
    }

    // モデル更新に伴う InputText の DOM 再描画は、利用者が打った文字列を
    // 整形済み文字列で上書きしてしまう。打鍵内容とキャレットを取り戻す。
    void nextTick(() => {
      const input = this.input
      if (!input) return
      if (input.value !== typed) {
        input.value = typed
        input.setSelectionRange(caret, caret)
      }
    })

    this.$emit('input', event)
  }
}
