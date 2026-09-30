import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * `frontend/nuxt.config.ts` の `i18n.locales` から `code: 'ja'` の `files` 一覧を読み取る。
 *
 * アプリ実行時の `@nuxtjs/i18n` は、ここに列挙されたファイルだけを ja ロケールとして読み込み・
 * マージする。テスト側でこの一覧を手書きで二重管理すると、nuxt.config.ts への追加を反映し忘れて
 * 「本番では読めるがテストでは throw する / 本番では読めないのにテストは通る」という食い違いが
 * 起きる（登録漏れが検出できなくなる）。そのためここでは nuxt.config.ts 自体をパースして
 * ファイル一覧を取得し、単一の正本（nuxt.config.ts）から常に導出する。
 */
function readJaLocaleFileList(): string[] {
  const nuxtConfigPath = resolve(process.cwd(), 'nuxt.config.ts')
  const source = readFileSync(nuxtConfigPath, 'utf-8')

  // `code: 'ja'` を含むロケール定義ブロックを探し、その中の files: [...] を取り出す。
  // (他ロケール code: 'en' 等の files ブロックまで拾わないよう、'ja' の出現位置から
  //  直後の files: [ ... ] だけを抜く)
  const jaLocaleIndex = source.indexOf(`code: 'ja'`)
  if (jaLocaleIndex === -1) {
    throw new Error(`nuxt.config.ts に code: 'ja' のロケール定義が見つかりません: ${nuxtConfigPath}`)
  }
  const filesStart = source.indexOf('files:', jaLocaleIndex)
  if (filesStart === -1) {
    throw new Error(`nuxt.config.ts の ja ロケール定義に files: が見つかりません: ${nuxtConfigPath}`)
  }
  const arrayStart = source.indexOf('[', filesStart)
  const arrayEnd = source.indexOf(']', arrayStart)
  const arrayBody = source.slice(arrayStart + 1, arrayEnd)

  const files: string[] = []
  for (const line of arrayBody.split('\n')) {
    const match = line.match(/'([^']+)'/)
    if (match) {
      files.push(match[1])
    }
  }
  if (files.length === 0) {
    throw new Error(`nuxt.config.ts の ja ロケール files が空です: ${nuxtConfigPath}`)
  }
  return files
}

/**
 * `app/locales/<file>`（`ja/xxx.json` または `ja/xxx.ts`）を素のオブジェクトとして読む。
 *
 * `.ts` ファイル（`ja/recruitment.ts` 等）は `export default { ... }` の形式で、値自体は
 * 妥当な JSON 構造なので、prefix を取り除いて JSON.parse する。
 */
function readLocaleFile(relativePath: string): Record<string, unknown> {
  const path = resolve(process.cwd(), `app/locales/${relativePath}`)
  // 一部の locale ファイル（ja/reservation.json, ja/admin_console.json 等）は UTF-8 BOM 付きで
  // 保存されている。JSON.parse は先頭の BOM を許容しないため、実行時 i18n と同様に読めるよう
  // ここで明示的に取り除く。
  const raw = readFileSync(path, 'utf-8').replace(/^\uFEFF/, '')
  if (relativePath.endsWith('.ts')) {
    const jsonBody = raw.replace(/^\s*export\s+default\s*/, '').trim()
    return JSON.parse(jsonBody) as Record<string, unknown>
  }
  return JSON.parse(raw) as Record<string, unknown>
}

/**
 * `app/locales/ja/<namespace>.json`（または `.ts`）を素の JSON として読み、`t(key)` の代わりに
 * ドット区切りのキーで値を辿るヘルパー（Issue: 価格改定 実機E2E defects）。
 *
 * 手書き辞書（`Record<string, string>` を testごとにコピーする方式）は、locale.json から
 * キーが消えてもテストが緑のまま通ってしまう（モックがコードでなく「あるべき姿」を検証してしまう）。
 * これを避けるため、実ファイルを読み、キーが無ければ throw する。
 *
 * 参考: `tests/unit/composables/useUnsavedChangesGuard.spec.ts` の `loadJaCommonJson`（同じ作法）。
 */
export function loadJaLocaleJson(namespace: string): Record<string, unknown> {
  return readLocaleFile(`ja/${namespace}.json`)
}

/**
 * vue-i18n / @nuxtjs/i18n がロケールファイルを合成する規則は深いマージであり、単純な
 * `Object.assign`（トップレベルの浅いマージ）ではない。実際、`common.json` が
 * `systemAdmin.logs.quickLink` を持ち、`system_admin_batch.json` 等も別の
 * `systemAdmin.xxx` を持つ（同じトップレベルキー `systemAdmin` を複数ファイルが共有する）。
 * 浅いマージだとファイル登録順で後から読んだ方が `systemAdmin` オブジェクト全体を上書きし、
 * 先に読んだ方のキー（`quickLink` 等）が消えてしまう。これを避けるため深いマージにする。
 */
function deepMerge(target: Record<string, unknown>, source: Record<string, unknown>): void {
  for (const key of Object.keys(source)) {
    const sourceValue = source[key]
    const targetValue = target[key]
    if (
      typeof sourceValue === 'object' &&
      sourceValue !== null &&
      !Array.isArray(sourceValue) &&
      typeof targetValue === 'object' &&
      targetValue !== null &&
      !Array.isArray(targetValue)
    ) {
      deepMerge(targetValue as Record<string, unknown>, sourceValue as Record<string, unknown>)
    } else {
      target[key] = sourceValue
    }
  }
}

/**
 * ja ロケールの全メッセージ（`nuxt.config.ts` の `i18n.locales` に登録された ja の `files` 全て）を
 * マージした `t` 相当の関数を作る。
 *
 * 引数 `namespaces` は後方互換のために残すが、実際にはアプリ実行時の i18n と同じ解決規則
 * （nuxt.config.ts に登録されたファイル全てをマージする）を忠実に再現するため無視する。
 * 呼び出し側が使う namespace だけを部分的に渡すと、画面が実際に使う他のファイルのキーで
 * 「無いはずのキー」として誤って throw してしまう（このヘルパーの当初の不具合そのもの）。
 * 一方で、nuxt.config.ts に登録されていないファイルのキーはここでも読まないため、
 * 「未登録キーで throw する」という本来の目的（登録漏れの検出）は維持される。
 */
export function createJaLocaleT(namespaces?: string[]): (key: string) => string {
  void namespaces // 後方互換のためのシグネチャ。実際の解決は nuxt.config.ts の登録一覧に従う。

  const merged: Record<string, unknown> = {}
  for (const file of readJaLocaleFileList()) {
    deepMerge(merged, readLocaleFile(file))
  }

  return (key: string): string => {
    const parts = key.split('.')
    let cur: unknown = merged
    for (const part of parts) {
      if (typeof cur !== 'object' || cur === null || !(part in (cur as Record<string, unknown>))) {
        throw new Error(`locale key not found: ${key}`)
      }
      cur = (cur as Record<string, unknown>)[part]
    }
    if (typeof cur !== 'string') {
      throw new Error(`locale key does not resolve to a string: ${key}`)
    }
    return cur
  }
}
