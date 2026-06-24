/**
 * テーマ FOUC（ちらつき）根治プラグイン — universal（SSR + client 両方で実行）
 *
 * 【問題】
 *   SSR では localStorage が読めないため、クライアント JS 実行前の初回 HTML には
 *   `dark` クラスが存在しない。JS 実行後に appearance.client.ts が localStorage
 *   を読んで dark クラスを付与するが、その間に LIGHT でレンダリングされる
 *   "フラッシュ" が発生する。
 *
 * 【根治方針】
 *   - persistToStorage 時に cookie（`appearance`）に JSON を鏡写しする。
 *   - 本プラグインは SSR 実行時に cookie を `useCookie()` で読み込み、
 *     `useHead({ htmlAttrs })` で `<html>` に `class` と `style` を注入する。
 *   - Nuxt が SSR 出力 HTML を生成する際、htmlAttrs が最初から反映されるため、
 *     ブラウザ初回 paint からダークモードで描画される。
 *   - inline script は CSP（nuxt-security の nonce ベース）と相性が悪いため使わない。
 *
 * 【競合回避】
 *   appearance.client.ts（localStorage から loadFromStorage を呼ぶ）は引き続き
 *   クライアント側 localStorage→store→DOM クラス更新を担う。
 *   本プラグイン（universal）は SSR 時のみ `<html>` 属性を確定させ、
 *   クライアント時は store を cookie から初期化するだけ（DOM 操作は client プラグインに委ねる）。
 *
 * @see app/plugins/appearance.client.ts — localStorage 正本からの読み込み
 * @see app/stores/useAppearanceStore.ts — persistToStorage で cookie に鏡写し
 */
export default defineNuxtPlugin(() => {
  // cookie は `useCookie` で読む（SSR でも request cookie から取得される）
  const rawCookie = useCookie<string | null>('appearance', {
    // JSON 文字列をそのまま扱うため encode/decode は identity にする
    // （persistToStorage で encodeURIComponent 済み）
    decode: (val: string) => decodeURIComponent(val),
    encode: (val: string) => encodeURIComponent(val),
    default: () => null,
  })

  // JSON をパースしてテーマ情報を取得
  let theme: 'LIGHT' | 'DARK' = 'LIGHT'
  let bgColor = '#f3efe0'
  let darkBgColor = '#18181b'

  if (rawCookie.value) {
    try {
      const parsed = JSON.parse(rawCookie.value) as Record<string, unknown>
      const parsedTheme = parsed.theme
      if (parsedTheme === 'LIGHT' || parsedTheme === 'DARK') {
        theme = parsedTheme
      }
      if (typeof parsed.bgColor === 'string' && parsed.bgColor) {
        bgColor = parsed.bgColor
      }
      if (typeof parsed.darkBgColor === 'string' && parsed.darkBgColor) {
        darkBgColor = parsed.darkBgColor
      }
    }
    catch {
      // パース失敗時はデフォルト（LIGHT）のまま
    }
  }

  const isDark = theme === 'DARK'
  const resolvedBgColor = isDark ? darkBgColor : bgColor

  // SSR 出力 HTML の <html> に class と --bg-color を最初から注入する。
  // これにより初回 paint 時から正しいテーマで描画され FOUC が消える。
  // クライアント側では Vue の SSR hydration 後も htmlAttrs は維持される。
  useHead({
    htmlAttrs: {
      // ダークモード: 'dark p-dark'、ライトモード: '' (クラス付与なし)
      class: isDark ? 'dark p-dark' : '',
      style: `--bg-color: ${resolvedBgColor}`,
    },
  })

  // SSR 側での store 初期化（クライアントでは appearance.client.ts が localStorage から上書き）
  if (import.meta.server) {
    const appearanceStore = useAppearanceStore()
    appearanceStore.theme = theme
    appearanceStore.bgColor = bgColor
    appearanceStore.darkBgColor = darkBgColor
  }
})
