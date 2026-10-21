// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { pageHeaderSubtitleViolations } from './pageHeaderSubtitleGuard'

const sfc = (body: string) => '<template>' + body + '</template>'

describe('CMP-261014-2035 PageHeader副題AST番人', () => {
  it.each([
    ['G1 直下p', '<PageHeader><p class="text-sm">副題</p></PageHeader>'],
    ['G2 #default', '<PageHeader><template #default><p>副題</p></template></PageHeader>'],
    ['G3 v-slot:default', '<PageHeader><template v-slot:default><span class="text-sm text-surface-400">副題</span></template></PageHeader>'],
    ['G8 再帰ラッパー', '<PageHeader><template v-if="show"><section><span class="text-sm">副題</span></section></template></PageHeader>'],
    ['G9 装飾なしtext-xs', '<PageHeader><span class="text-xs text-surface-400">副題</span></PageHeader>'],
    ['div内補間', '<PageHeader><div>{{ subtitle }}</div></PageHeader>'],
  ])('%sを違反として検出する', (_name, template) => {
    expect(pageHeaderSubtitleViolations(sfc(template))).toHaveLength(1)
  })

  it.each([
    ['G4 surveys型バッジ', '<PageHeader><span class="rounded px-2 text-xs">受付中</span></PageHeader>'],
    ['G5 専用バッジ', '<PageHeader><Tag /><JobStatusBadge /></PageHeader>'],
    ['G6 名前付きスロット', '<PageHeader><template #actions><p>操作</p></template><template #subtitle><p>副題</p></template></PageHeader>'],
    ['G6 自己終了の外側', '<PageHeader /><p class="text-sm">副題</p>'],
    ['G10 text-sm装飾バッジ', '<PageHeader><span class="text-sm rounded px-2 bg-green-100">受付中</span></PageHeader>'],
    ['v-ifの中の名前付きslot', '<PageHeader><template #actions><section><p>操作</p></section></template></PageHeader>'],
  ])('%sは許可する', (_name, template) => {
    expect(pageHeaderSubtitleViolations(sfc(template))).toEqual([])
  })

  it('G7 実リポジトリの既定スロット内に副題を置かない（移行前は8件・対象9画面）', () => {
    const app = resolve(process.cwd(), 'app')
    const violations: string[] = []
    let checked = 0
    function scan(dir: string): void {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = resolve(dir, entry.name)
        if (entry.isDirectory()) scan(path)
        else if (entry.name.endsWith('.vue')) {
          const source = readFileSync(path, 'utf8')
          if (!source.includes('PageHeader') && !source.includes('page-header')) continue
          checked += 1
          for (const violation of pageHeaderSubtitleViolations(source)) violations.push(relative(app, path) + ':' + violation.line + ' ' + violation.source)
        }
      }
    }
    scan(app)
    expect(checked, '対象を0件走査して合格にしない').toBeGreaterThan(0)
    expect(violations).toEqual([])
  })
})
