/**
 * 番人: pathPrefix:false 下で解決されない「ディレクトリ接頭辞付きコンポーネント名」の検出（CMP-261014-2034）。
 *
 * nuxt.config.ts は components: [{ path: '~/components', pathPrefix: false }] のため、
 * 自動登録名はファイル名のみ（components/friends/FriendFeedPostList.vue → <FriendFeedPostList>）。
 * <FriendsFriendFeedPostList> のような接頭辞付きタグは Vue が解決できず、警告のみで無描画になる。
 * typecheck / lint / CI はこれを検知しないため、静的に走査して止める。
 */
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, basename, relative } from 'node:path'

const APP_DIR = join(process.cwd(), 'app')
const COMPONENTS_DIR = join(APP_DIR, 'components')

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

/** ディレクトリ名（kebab/camel/snake）を PascalCase に変換する */
function toPascal(s: string): string {
  return s
    .split(/[-_]/)
    .filter(Boolean)
    .map(p => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')
}

const componentFiles = walk(COMPONENTS_DIR).filter(f => f.endsWith('.vue'))
const registered = new Set(componentFiles.map(f => basename(f, '.vue')))
const dirPrefixes = new Set<string>()
for (const f of componentFiles) {
  const rel = relative(COMPONENTS_DIR, f).split(/[\/]/).slice(0, -1)
  rel.forEach(seg => dirPrefixes.add(toPascal(seg)))
}

/** 検査対象: app 配下の全 .vue のテンプレート中の PascalCase タグ */
function findMisreferences(): { file: string, tag: string, correct: string }[] {
  const hits: { file: string, tag: string, correct: string }[] = []
  for (const file of walk(APP_DIR).filter(f => f.endsWith('.vue'))) {
    const src = readFileSync(file, 'utf8')
    const tpl = src.includes('<template') ? src.slice(src.indexOf('<template')) : ''
    const tags = new Set([...tpl.matchAll(/<([A-Z][A-Za-z0-9]+)(?=[\s/>])/g)].map(m => m[1] as string))
    for (const tag of tags) {
      if (registered.has(tag)) continue
      // <ディレクトリ名PascalCase><登録名> 形式のみ誤参照とみなす（PrimeVue 等の誤検知を避ける）
      for (const dir of dirPrefixes) {
        if (tag.startsWith(dir) && registered.has(tag.slice(dir.length))) {
          hits.push({ file: relative(APP_DIR, file), tag, correct: tag.slice(dir.length) })
          break
        }
      }
    }
  }
  return hits
}

describe('コンポーネント名の接頭辞誤参照 番人 (pathPrefix:false)', () => {
  it('ディレクトリ名を前置した未登録のコンポーネント名をテンプレートで使っていない', () => {
    const hits = findMisreferences()
    const detail = hits.map(h => `${h.file}: <${h.tag}> → <${h.correct}>`).join('\n')
    expect(hits.length, `誤参照 ${hits.length} 件:\n${detail}`).toBe(0)
  })
})
