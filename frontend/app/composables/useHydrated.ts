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
