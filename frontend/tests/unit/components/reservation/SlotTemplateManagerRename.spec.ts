// @vitest-environment node
// 本テストは fs 走査のみで Nuxt ランタイムを必要としない。
// 既定の 'nuxt' 環境は setupNuxt() の beforeAll フックが重く hookTimeout を超えるため、
// このファイルだけ素の node 環境で実行する（写経元: pageheader-backbutton-coexistence.spec.ts）。
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join } from 'node:path'

/**
 * SlotTemplateManager リネーム番人（F03.4.5 W2-1第二隊・AC-FE12）。
 *
 * 「今すぐ枠を作成」ボタン・weeks Select 撤去＋テンプレ保存＝同期自動生成統合に伴い、
 * SlotTemplateManager.vue は WeeklyScheduleManager.vue へ発展的にリネームされた
 * （F03.4.5 §3.2/§4.5・W2-1第一隊）。旧名の参照（import 文・テンプレートタグ・同名ファイル）が
 * app 配下の実コードへ巻き戻し・取り違えで復活していないことを機械的に固定する
 * （コメント内の「旧 SlotTemplateManager」等の経緯説明は履歴の記録として意図的に対象外とする）。
 */

// vitest の実行 CWD は frontend ルート。app/ ディレクトリが見つかる場所まで上方向に探索する
// （写経元 pageheader-backbutton-coexistence.spec.ts と同一パターン）。
function resolveFrontendDir(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (existsSync(join(dir, 'app', 'components', 'reservation', 'WeeklyScheduleManager.vue'))) return dir
    const parent = join(dir, '..')
    if (parent === dir) break
    dir = parent
  }
  return process.cwd()
}

const frontendDir = resolveFrontendDir()
const appDir = join(frontendDir, 'app')

const targetExtensions = new Set(['.vue', '.ts'])
const skipDirs = new Set(['node_modules', '.nuxt', '.output', 'dist'])

function collectFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (skipDirs.has(entry)) continue
    const full = join(dir, entry)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      collectFiles(full, acc)
    }
    else if (targetExtensions.has(entry.slice(entry.lastIndexOf('.')))) {
      acc.push(full)
    }
  }
  return acc
}

describe('SlotTemplateManager リネーム番人（AC-FE12）', () => {
  it('前提: WeeklyScheduleManager.vue が実在する（app ディレクトリ解決の裏取り）', () => {
    expect(existsSync(join(appDir, 'components', 'reservation', 'WeeklyScheduleManager.vue'))).toBe(true)
  })

  it('AC-FE12: SlotTemplateManager という同名ファイルが app 配下に存在しない', () => {
    const files = collectFiles(appDir)
    const hit = files.find(f => f.endsWith('SlotTemplateManager.vue') || f.endsWith('SlotTemplateManager.ts'))
    expect(hit, `残存ファイル: ${hit}`).toBeUndefined()
  })

  it('AC-FE12: SlotTemplateManager への import/テンプレートタグ参照が app 配下の実コードに残らない（コメント中の経緯言及は対象外）', () => {
    const files = collectFiles(appDir)
    const offenders: string[] = []
    // import 文 or テンプレートタグ（<SlotTemplateManager ... > / </SlotTemplateManager>）のみを検出する。
    // コメント内の「旧 SlotTemplateManager」等の経緯説明は意図的に対象外
    // （死蔵コメントではなく履歴の記録として残置を許容する設計判断）。
    const referencePattern = /\bimport\s+[^;\n]*\bSlotTemplateManager\b|<\/?SlotTemplateManager[\s/>]/

    for (const file of files) {
      const content = readFileSync(file, 'utf8')
      const lines = content.split('\n')
      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) continue
        if (referencePattern.test(line)) {
          offenders.push(`${file}: ${trimmed}`)
          break
        }
      }
    }

    expect(offenders, `残存参照: ${offenders.join(', ')}`).toHaveLength(0)
  })
})
