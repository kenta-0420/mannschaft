/**
 * ハイドレーション完了を表す composable。
 *
 * 目的: **SSR で配信済みの HTML に対して、Vue のイベントハンドラがまだ結合されていない窓を塞ぐため**。
 *
 * SSR されたページは「HTML は既に描画されているが JS バンドルの読み込み・ハイドレーションが
 * 未完了」という状態を必ず経由する。この窓の間、`<form @submit.prevent="...">` の
 * `@submit.prevent` はまだ DOM に結合されていないため、ユーザーが送信ボタンを押すと
 * ブラウザ標準のフォーム送信が走ってしまい、ページが再読み込みされて入力内容が失われる。
 *
 * 送信ボタンに `:disabled="!hydrated"` を付けることでこの窓を塞ぐ。
 * HTML 仕様上、フォームの既定送信ボタン（最初の submit ボタン）が disabled のときは
 * Enter キーによる暗黙の送信（implicit submission）も発生しないため、
 * クリック経路と Enter 経路の両方を 1 つの指定で塞げる。
 *
 * 状態はモジュール共有ではなく **呼び出したコンポーネントごとのローカル ref** とする。
 * ハイドレーションは個々のコンポーネント単位で完了するため、
 * 「別のコンポーネントが先に mount された」ことをもって自分のハンドラが結合済みとは言えない。
 * また、モジュールレベルの共有状態は SSR でリクエストをまたいで漏れる危険がある。
 *
 * @example
 * ```vue
 * const hydrated = useHydrated()
 * // <Button type="submit" :disabled="!hydrated" />
 * ```
 *
 * @returns ハイドレーション完了で `true` になる読み取り専用の ref（SSR 中は常に `false`）
 */

import { ref, readonly, onMounted, type Ref } from 'vue'

export function useHydrated(): Readonly<Ref<boolean>> {
  const hydrated = ref(false)

  onMounted(() => {
    hydrated.value = true
  })

  return readonly(hydrated)
}

/**
 * ハイドレーション前に DOM が既に持っている input の値を読み取る。
 *
 * 目的: **ハイドレーションがユーザーの入力済みの値を空で上書きして消すのを防ぐため**。
 *
 * SSR された HTML が表示されてからハイドレーションが完了するまでの間に input へ入った値は、
 * リアクティブ状態（`ref('')`）が DOM をパッチした時点で空文字に上書きされて消える。
 * ページはリロードされないため気づきにくいが、入力内容は確実に失われる。
 * **ハイドレーション時点で DOM が既に持っている値こそが真実であり、
 * リアクティブ状態の側がそれを取り込むのが正しい。**
 *
 * **ブラウザのパスワードマネージャによる自動入力はまったく同じ経路を通る。**
 * 自動入力はハイドレーション前に DOM へ直接値を書き込むため、この取り込みが無いと
 * 回線やビルドが遅いときに保存済みのメールアドレスとパスワードが黙って消える
 * （「保存したパスワードが効かない」という種類の苦情になる）。
 *
 * **必ず `<script setup>` のセットアップ時に呼ぶこと。`onMounted` では遅い。**
 * セットアップは当該コンポーネントの DOM パッチより前に走るため上書き前の値が読めるが、
 * `onMounted` が走る時点では既にリアクティブ状態による上書きが済んでいる。
 *
 * 読み取りは**初回ハイドレーション時のみ**行う（`isHydrating` で判定）。
 * SPA 内遷移で到達した場合は対象要素が存在しない、あるいは別ページの残骸を拾うため、
 * 無条件に読むと事故になる。
 *
 * ---
 * **開発時に出る Hydration mismatch 警告について（既知・意図どおり）**
 *
 * 事前入力があった場合に限り、開発ビルドで
 * `[Vue warn]: Hydration class mismatch` / `Hydration attribute mismatch` が出る。
 * サーバーは空の input を描画したのに、クライアントは取り込んだ値を持つ input を描画するため、
 * 両者が食い違うのは**この修正の性質上避けられない**（値を捨てれば警告も消えるが、それは不具合そのもの）。
 * Vue はクライアント側の描画に合わせて DOM をパッチするため、値は正しく保持される。
 *
 * この警告は開発ビルド専用で、本番ビルドには存在しない
 * （警告を出す `propHasMismatch` は `process.env.NODE_ENV !== 'production'` ガード下にあり、
 * 本番バンドルからは完全に除去される。実際に本番ビルド成果物に当該コードが無いことを確認済み）。
 * 事前入力が無い通常の経路では警告は 1 件も出ない（対照実験で確認済み）。
 * **警告を消すために本関数を外さないこと。** 消えるのは警告ではなくユーザーの入力である。
 *
 * @example
 * ```ts
 * const email = ref(readPrefilledInputValue('email'))
 * ```
 *
 * @param id 対象 input の DOM id
 * @returns ハイドレーション前に入っていた値。該当なし・SSR・SPA 内遷移では空文字
 */
export function readPrefilledInputValue(id: string): string {
  // サーバー側に DOM は存在しない
  if (import.meta.server) return ''
  // 初回ハイドレーション以外（SPA 内遷移）では読まない
  if (!useNuxtApp().isHydrating) return ''

  const element: HTMLInputElement | null = document.querySelector<HTMLInputElement>(`#${CSS.escape(id)}`)
  if (!(element instanceof HTMLInputElement)) return ''

  return element.value
}

/**
 * id を持たない複数の input で 1 つの値を構成するウィジェット（PrimeVue `InputOtp` 等）から、
 * ハイドレーション前に入っていた値を連結して読み取る。
 *
 * 動機・タイミングの制約・`isHydrating` ガードの理由は {@link readPrefilledInputValue} と同一。
 * `InputOtp` は桁ごとに id を持たない input を並べて描画するため、id では読めずセレクタで読む。
 * （`InputOtp` は `modelValue` を `split('')` して各桁へ配る実装なので、
 * 連結した文字列をそのまま初期値として渡せば正しく各桁に復元される）
 *
 * @param selector 対象 input 群に一致する CSS セレクタ
 * @returns 各 input の値を DOM 順に連結した文字列。SSR・SPA 内遷移では空文字
 */
export function readPrefilledInputGroupValue(selector: string): string {
  if (import.meta.server) return ''
  if (!useNuxtApp().isHydrating) return ''

  const elements = document.querySelectorAll(selector)
  let value = ''
  for (const element of elements) {
    if (element instanceof HTMLInputElement) value += element.value
  }

  return value
}
