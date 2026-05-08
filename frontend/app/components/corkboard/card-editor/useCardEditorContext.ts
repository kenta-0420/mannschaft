import type { InjectionKey, Ref } from 'vue'
import type { useCardEditorForm } from '~/composables/useCardEditorForm'

/**
 * F09.8 Phase 4 (フロント技的負債): CardEditorModal を C 案ハイブリッド分割した際の
 * provide/inject 用 InjectionKey とアクセサ関数群。
 *
 * # 責務
 *  - 親 `CardEditorModal.vue` で 1 度だけ呼び出した `useCardEditorForm()` の結果を、
 *    型別の子フィールドコンポーネントから安全に参照できるようにする。
 *  - 親で保持しているモード値 (`'create' | 'edit'`) を子から参照可能にする。
 *
 * # 親子関係
 *  - 親: `CardEditorModal.vue` （Dialog 殻 + composable 呼出 + provide）
 *  - 子: `card-editor/CardEditorCommonFields.vue`,
 *        `card-editor/CardEditorReferenceFields.vue`,
 *        `card-editor/CardEditorMemoFields.vue`,
 *        `card-editor/CardEditorUrlFields.vue`,
 *        `card-editor/CardEditorSectionHeaderFields.vue`
 *
 * # 設計上の配慮
 *  - `CardEditorContext` は `ReturnType<typeof useCardEditorForm>` 型推論で composable の
 *    変更に追従させる。composable に新フィールドを足しても自動で型に反映される。
 *  - `Symbol('CardEditorContext')` のように **明示的な記述** を入れて、Vue Devtools 上で
 *    どの provide が表示されているか判別しやすくする。
 *  - inject 失敗時のエラー文に `Wrap in <CardEditorModal>.` を含め、後の運用者が即座に
 *    親階層の不足に気付けるようにする。
 */

export type CardEditorContext = ReturnType<typeof useCardEditorForm>

/**
 * `useCardEditorForm()` の戻り値全体を共有する InjectionKey。
 * 親 `CardEditorModal.vue` のみが provide する。
 */
export const CardEditorContextKey: InjectionKey<CardEditorContext> = Symbol('CardEditorContext')

export type CardEditorMode = Ref<'create' | 'edit'>

/**
 * 編集モード（create / edit）を共有する InjectionKey。
 * 子コンポーネントは disabled 制御等で参照する。
 */
export const CardEditorModeKey: InjectionKey<CardEditorMode> = Symbol('CardEditorMode')

/**
 * 子コンポーネントから CardEditor の form context を取得する。
 *
 * @throws 親 `<CardEditorModal>` の外で呼ばれた場合は明示的に例外を投げる。
 *         壊れたら静かに `undefined` を返すよりも、開発時に即座に発見できる方が安全。
 */
export function useCardEditorContext(): CardEditorContext {
  const ctx = inject(CardEditorContextKey)
  if (!ctx) {
    throw new Error(
      'CardEditorContext not provided. Wrap in <CardEditorModal>.',
    )
  }
  return ctx
}

/**
 * 子コンポーネントから CardEditor のモード（create / edit）を取得する。
 *
 * @throws 親 `<CardEditorModal>` の外で呼ばれた場合は明示的に例外を投げる。
 */
export function useCardEditorMode(): CardEditorMode {
  const m = inject(CardEditorModeKey)
  if (!m) {
    throw new Error(
      'CardEditorMode not provided. Wrap in <CardEditorModal>.',
    )
  }
  return m
}
