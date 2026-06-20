import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join, sep } from 'node:path'

/**
 * 併存ガードテスト（AC-11・恒久の再発防止）。
 *
 * PageHeader にデフォルト ON の戻るボタンを統合したため、各ページ/コンポーネントが
 * 個別に <BackButton> を貼る運用は不要になった。同一ファイル内に <PageHeader> と
 * <BackButton> の両方が含まれていると戻るリンクが二重描画されるため、これを禁止する。
 *
 * 走査対象: app/pages/ ** / *.vue および app/components/ ** / *.vue
 * 除外: PageHeader.vue 自身（内部で BackButton を使うため正当）
 *
 * 注意: 本テストは基盤フェーズ時点では多数のページが両方を含むため RED（失敗）になる。
 * 後続フェーズで各ページから個別 <BackButton> を撤去して green 化する。
 */

// このテストファイル → frontend/app へ解決
const frontendDir = fileURLToPath(new URL('../..', import.meta.url))
const appDir = join(frontendDir, 'app')

// 除外する allowlist（frontend からの相対パス、posix 区切り）
const ALLOWLIST = new Set<string>(['app/components/PageHeader.vue'])

function collectVueFiles(baseDir: string): string[] {
  if (!existsSync(baseDir)) return []
  return readdirSync(baseDir, { recursive: true, withFileTypes: true })
    .filter((d) => d.isFile() && d.name.endsWith('.vue'))
    .map((d) => join(d.parentPath ?? (d as unknown as { path: string }).path, d.name))
}

function toRelPosix(absPath: string): string {
  return absPath
    .slice(frontendDir.length)
    .split(sep)
    .join('/')
    .replace(/^\/+/, '')
}

describe('PageHeader / BackButton 併存ガード', () => {
  it('同一ファイルに <PageHeader> と <BackButton> を併存させない', () => {
    const targets = [
      ...collectVueFiles(join(appDir, 'pages')),
      ...collectVueFiles(join(appDir, 'components')),
    ]

    const offenders: string[] = []
    for (const abs of targets) {
      const rel = toRelPosix(abs)
      if (ALLOWLIST.has(rel)) continue
      const src = readFileSync(abs, 'utf-8')
      const hasPageHeader = /<PageHeader\b/.test(src)
      const hasBackButton = /<BackButton\b/.test(src)
      if (hasPageHeader && hasBackButton) {
        offenders.push(rel)
      }
    }

    const message =
      `PageHeader と BackButton を併存させているファイルが ${offenders.length} 件あります。\n` +
      `PageHeader はデフォルトで戻るボタンを描画するため、個別の <BackButton> を撤去してください` +
      `（戻り先指定が必要なら PageHeader の :back-to を使う / 不要なら :back="false"）。\n` +
      offenders.map((f) => `  - ${f}`).join('\n')

    expect(offenders, message).toEqual([])
  })
})
