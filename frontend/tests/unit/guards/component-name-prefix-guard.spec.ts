// @vitest-environment node
/**
 * 番人: pathPrefix:false 下で解決されない「ディレクトリ接頭辞付きコンポーネント名」の検出（CMP-261014-2034）。
 *
 * nuxt.config.ts は components: [{ path: '~/components', pathPrefix: false }] のため、
 * 自動登録名はファイル名のみ（components/friends/FriendFeedPostList.vue → <FriendFeedPostList>）。
 * <FriendsFriendFeedPostList> のような接頭辞付き名は Vue が解決できず、警告のみで無描画になる。
 * typecheck / lint / CI はこれを検知しないため、静的に走査して止める。
 *
 * 検出対象の参照形式:
 *  - テンプレートの PascalCase タグ（Lazy 接頭辞付きを含む）
 *  - resolveComponent('X') / resolveDynamicComponent('X')
 *  - :is="'X'" などの属性内の文字列リテラル、静的な is="X"
 * 接頭辞は components 配下のディレクトリ名の連続区間を連結したもの（alpha/beta/Gamma.vue → Alpha / Beta / AlphaBeta）。
 * 除外: 明示 import されたローカル名、HTML コメント・JS コメント内の記述。
 */
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, basename, relative, sep } from 'node:path'

interface Context {
  registered: Set<string>
  prefixes: Set<string>
}
interface Hit {
  name: string
  correct: string
}

/** ディレクトリ名（kebab/camel/snake）を PascalCase に変換する */
function toPascal(s: string): string {
  return s
    .split(/[-_]/)
    .filter(Boolean)
    .map(p => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')
}

/** components 配下の相対パス（区切りは / ）一覧から登録名と接頭辞候補を作る */
function buildContext(relPaths: string[]): Context {
  const registered = new Set<string>()
  const prefixes = new Set<string>()
  for (const rel of relPaths) {
    const segs = rel.split('/')
    registered.add(basename(segs[segs.length - 1] as string, '.vue'))
    const dirs = segs.slice(0, -1).map(toPascal)
    for (let i = 0; i < dirs.length; i++) {
      let acc = ''
      for (let j = i; j < dirs.length; j++) {
        acc += dirs[j]
        prefixes.add(acc)
      }
    }
  }
  return { registered, prefixes }
}

function stripComments(src: string, kind: 'html' | 'js'): string {
  if (kind === 'html') return src.replace(/<!--[\s\S]*?-->/g, '')
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:\w'"`])\/\/[^\n]*/g, '$1')
}

/** 解決される名前か。Lazy 接頭辞付きも登録名として許容する */
function judge(name: string, ctx: Context): Hit | null {
  if (ctx.registered.has(name)) return null
  const base = name.startsWith('Lazy') && name.length > 4 ? name.slice(4) : name
  if (ctx.registered.has(base)) return null
  for (const prefix of ctx.prefixes) {
    if (base.startsWith(prefix) && ctx.registered.has(base.slice(prefix.length))) {
      return { name, correct: base.slice(prefix.length) }
    }
  }
  return null
}

/** 明示 import されたローカル名を集める（import X / import { A as X } / import X, { Y }） */
function collectImportedNames(script: string): Set<string> {
  const names = new Set<string>()
  for (const m of script.matchAll(/import\s+([^'";]+?)\s+from\s+['"][^'"]+['"]/g)) {
    const clause = m[1] as string
    const braces = clause.match(/\{([^}]*)\}/)
    const head = clause.replace(/\{[^}]*\}/, '').replace(/type\s+/, '').replace(/,/g, ' ').trim()
    for (const n of head.split(/\s+/)) if (n && n !== '*' && n !== 'as') names.add(n)
    if (braces) {
      for (const part of (braces[1] as string).split(',')) {
        const local = part.trim().split(/\s+as\s+/).pop()?.trim()
        if (local) names.add(local)
      }
    }
  }
  return names
}

/** .vue 1ファイルの本文から誤参照を検出する */
function findMisreferencesInSource(src: string, ctx: Context): Hit[] {
  const scriptBlocks = [...src.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1] as string)
  const script = stripComments(scriptBlocks.join('\n'), 'js')
  const tpl = stripComments(
    src.replace(/<script\b[\s\S]*?<\/script>/g, '').replace(/<style\b[\s\S]*?<\/style>/g, ''),
    'html',
  )
  const imported = collectImportedNames(script)

  // タグ参照: 明示 import されたローカル名は束縛が優先されるので除外する
  const tagNames = new Set<string>()
  for (const m of tpl.matchAll(/<([A-Z][A-Za-z0-9]+)(?=[\s/>])/g)) tagNames.add(m[1] as string)
  // 文字列参照: 文字列は import 束縛では解決されない（実行時にグローバル登録名で引かれる）ので除外しない
  const stringNames = new Set<string>()
  for (const m of `${script}\n${tpl}`.matchAll(/(?:resolveComponent|resolveDynamicComponent)\(\s*['"`]([A-Z][A-Za-z0-9]+)['"`]/g)) {
    stringNames.add(m[1] as string)
  }
  for (const m of tpl.matchAll(/(?<![\w:-])(?:v-bind)?:is\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) {
    for (const name of renderedStrings((m[1] ?? m[2]) as string)) stringNames.add(name)
  }
  for (const m of tpl.matchAll(/(?<![\w:-])is\s*=\s*(?:"([A-Z][A-Za-z0-9]+)"|'([A-Z][A-Za-z0-9]+)')/g)) {
    stringNames.add((m[1] ?? m[2]) as string)
  }

  const hits: Hit[] = []
  const check = (name: string) => {
    const hit = judge(name, ctx)
    if (hit) hits.push(hit)
  }
  for (const name of tagNames) if (!imported.has(name)) check(name)
  for (const name of stringNames) check(name)
  return hits
}

/** 式の最上位にある三項演算子を { consequent, alternate } に分解する。なければ null */
function splitTernary(expr: string): { consequent: string, alternate: string } | null {
  let quote = ''
  let depth = 0
  let qIdx = -1
  let nested = 0
  for (let i = 0; i < expr.length; i++) {
    const c = expr[i] as string
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = ''
      continue
    }
    if (c === '\'' || c === '"' || c === '`') quote = c
    else if ('([{'.includes(c)) depth++
    else if (')]}'.includes(c)) depth--
    else if (c === '?' && depth === 0) {
      const next = expr[i + 1]
      if (next === '.' || next === '?') { i++; continue }
      if (qIdx < 0) qIdx = i
      else nested++
    }
    else if (c === ':' && depth === 0 && qIdx >= 0) {
      if (nested > 0) nested--
      else return { consequent: expr.slice(qIdx + 1, i), alternate: expr.slice(i + 1) }
    }
  }
  return null
}

/** :is の式から「描画結果になる位置」の PascalCase 文字列だけを取り出す（式全体のリテラル / 三項演算子の両枝） */
function renderedStrings(expr: string): string[] {
  const e = expr.trim()
  const literal = e.match(/^(['"`])([A-Z][A-Za-z0-9]+)\1$/)
  if (literal) return [literal[2] as string]
  const t = splitTernary(e)
  if (!t) return []
  return [...renderedStrings(t.consequent), ...renderedStrings(t.alternate)]
}

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else out.push(full)
  }
  return out
}

describe('findMisreferencesInSource（合成入力）', () => {
  const ctx = buildContext(['friends/FriendFeedPostList.vue', 'alpha/beta/Gamma.vue', 'common/PageHeader.vue'])
  const names = (src: string) => findMisreferencesInSource(src, ctx).map(h => h.name)

  it('検出する: 接頭辞付きタグ', () => {
    expect(names('<template><FriendsFriendFeedPostList /></template>')).toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出する: resolveComponent の文字列リテラル', () => {
    const src = '<script setup lang="ts">const X = resolveComponent(\'FriendsFriendFeedPostList\')</script><template><div/></template>'
    expect(names(src)).toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出する: :is の文字列リテラル（三項演算子内も）', () => {
    expect(names('<template><component :is="ok ? \'FriendsFriendFeedPostList\' : \'PageHeader\'" /></template>'))
      .toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出する: Lazy 接頭辞付きの誤参照', () => {
    expect(names('<template><LazyFriendsFriendFeedPostList /></template>')).toEqual(['LazyFriendsFriendFeedPostList'])
  })
  it('検出する: 多階層ディレクトリ連結の接頭辞（alpha/beta/Gamma.vue → AlphaBetaGamma）', () => {
    expect(names('<template><AlphaBetaGamma /></template>')).toEqual(['AlphaBetaGamma'])
  })
  it('検出する: v-bind:is と、単引用符で囲んだ属性値', () => {
    expect(names('<template><component v-bind:is="\'FriendsFriendFeedPostList\'" /></template>')).toEqual(['FriendsFriendFeedPostList'])
    expect(names('<template><component :is=\'"FriendsFriendFeedPostList"\' /></template>')).toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出する: 入れ子の三項演算子の枝・式全体が文字列リテラルの場合', () => {
    expect(names('<template><component :is="a ? \'PageHeader\' : b ? \'FriendsFriendFeedPostList\' : \'div\'" /></template>'))
      .toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出しない: :is の式中の比較値など、描画結果にならない文字列', () => {
    expect(names('<template><component :is="kind === \'FriendsFriendFeedPostList\' ? PageHeader : \'div\'" /></template>')).toEqual([])
    expect(names('<template><component :is="map[\'FriendsFriendFeedPostList\']" /></template>')).toEqual([])
  })
  it('検出する: import 済みでも resolveComponent / 文字列の :is は違反（文字列は import 束縛で解決されない）', () => {
    const imp = '<script setup lang="ts">\nimport FriendsFriendFeedPostList from \'~/x/Y.vue\'\nconst C = resolveComponent(\'FriendsFriendFeedPostList\')\n</script>'
    expect(names(`${imp}<template><div /></template>`)).toEqual(['FriendsFriendFeedPostList'])
    const imp2 = '<script setup lang="ts">\nimport FriendsFriendFeedPostList from \'~/x/Y.vue\'\n</script>'
    expect(names(`${imp2}<template><component :is="'FriendsFriendFeedPostList'" /></template>`)).toEqual(['FriendsFriendFeedPostList'])
  })
  it('検出しない: 正名・Lazy 付き正名・無関係な名前', () => {
    expect(names('<template><FriendFeedPostList /><LazyFriendFeedPostList /><Button /><NuxtLink /></template>')).toEqual([])
  })
  it('検出しない: 明示 import されたローカル名', () => {
    const src = '<script setup lang="ts">\nimport FriendsFriendFeedPostList from \'~/x/Y.vue\'\n</script><template><FriendsFriendFeedPostList /></template>'
    expect(names(src)).toEqual([])
  })
  it('検出しない: 別名 import（as）で付けたローカル名', () => {
    const src = '<script setup lang="ts">\nimport { Z as FriendsFriendFeedPostList } from \'~/x\'\n</script><template><FriendsFriendFeedPostList /></template>'
    expect(names(src)).toEqual([])
  })
  it('検出しない: HTML コメント・JS コメント内の記述', () => {
    const src = '<script setup lang="ts">\n// <FriendsFriendFeedPostList>\n/* resolveComponent(\'FriendsFriendFeedPostList\') */\n</script><template><!-- <FriendsFriendFeedPostList /> --><div /></template>'
    expect(names(src)).toEqual([])
  })
})

describe('コンポーネント名の接頭辞誤参照 番人 (pathPrefix:false)', () => {
  it('app 配下の .vue が、ディレクトリ名を前置した未登録のコンポーネント名を使っていない', () => {
    const appDir = join(process.cwd(), 'app')
    const componentsDir = join(appDir, 'components')
    const rels = walk(componentsDir)
      .filter(f => f.endsWith('.vue'))
      .map(f => relative(componentsDir, f).split(sep).join('/'))
    const ctx = buildContext(rels)
    const lines: string[] = []
    for (const file of walk(appDir).filter(f => f.endsWith('.vue'))) {
      for (const h of findMisreferencesInSource(readFileSync(file, 'utf8'), ctx)) {
        lines.push(`${relative(appDir, file)}: <${h.name}> → <${h.correct}>`)
      }
    }
    expect(lines.length, `誤参照 ${lines.length} 件:\n${lines.join('\n')}`).toBe(0)
  }, 120_000)
})
