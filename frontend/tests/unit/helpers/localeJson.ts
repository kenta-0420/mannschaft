import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * `app/locales/ja/<namespace>.json` を素の JSON として読み、`t(key)` の代わりに
 * ドット区切りのキーで値を辿るヘルパー（Issue: 価格改定 実機E2E defects）。
 *
 * 手書き辞書（`Record<string, string>` を testごとにコピーする方式）は、locale.json から
 * キーが消えてもテストが緑のまま通ってしまう（モックがコードでなく「あるべき姿」を検証してしまう）。
 * これを避けるため、実ファイルを読み、キーが無ければ throw する。
 *
 * 参考: `tests/unit/composables/useUnsavedChangesGuard.spec.ts` の `loadJaCommonJson`（同じ作法）。
 */
export function loadJaLocaleJson(namespace: string): Record<string, unknown> {
  // vitest の root は frontend/（happy-dom/jsdom では import.meta.url が file スキームでないため cwd 基準）
  const path = resolve(process.cwd(), `app/locales/ja/${namespace}.json`)
  return JSON.parse(readFileSync(path, 'utf-8')) as Record<string, unknown>
}

/**
 * 複数の namespace の JSON をマージして、ドット区切りキーで値を取り出す `t` 相当の関数を作る。
 * キーが見つからない場合は throw する（key をそのまま返してテストを偽陽性緑にしない）。
 */
export function createJaLocaleT(namespaces: string[]): (key: string) => string {
  const merged: Record<string, unknown> = {}
  for (const ns of namespaces) {
    Object.assign(merged, loadJaLocaleJson(ns))
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
