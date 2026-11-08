// @vitest-environment node
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { TERM_HAS_TIMETABLES_CODE, parseTermApiError } from './timetableTermError'

const appDir = resolve(__dirname, '..')

describe('parseTermApiError', () => {
  it('400 の fieldErrors を欄名ごとに取り出す', () => {
    const r = parseTermApiError({
      response: { status: 400 },
      data: { error: { code: 'COMMON_001', fieldErrors: [{ field: 'academicYear', message: '必須です' }] } },
    })
    expect(r.status).toBe(400)
    expect(r.fieldErrors).toEqual({ academicYear: '必須です' })
  })

  it('409 TIMETABLE_022 の errorCode を取り出す', () => {
    const r = parseTermApiError({
      statusCode: 409,
      data: { error: { code: 'TIMETABLE_022' } },
    })
    expect(r.code).toBe(TERM_HAS_TIMETABLES_CODE)
    expect(r.fieldErrors).toEqual({})
  })

  it('想定外の形でも落ちない', () => {
    expect(parseTermApiError(null).fieldErrors).toEqual({})
    expect(parseTermApiError(new Error('x')).code).toBeUndefined()
  })
})

describe('学期画面の翻訳キー', () => {
  const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de']

  it('6言語すべてに新設キーと dialog / label が最上位で存在する', () => {
    for (const l of locales) {
      const d = JSON.parse(readFileSync(join(appDir, 'locales', l, 'common.json'), 'utf-8'))
      expect(d.timetable.term_academic_year_required, l).toBeTruthy()
      expect(d.timetable.term_has_timetables, l).toBeTruthy()
      expect(d.dialog.error, l).toBeTruthy()
      expect(d.label.actions, l).toBeTruthy()
      expect(d.label.status, l).toBeTruthy()
    }
  })

  it('common.dialog.* / common.label.* の誤参照が残っていない', () => {
    const bad: string[] = []
    const walk = (dir: string) => {
      for (const n of readdirSync(dir)) {
        const p = join(dir, n)
        if (statSync(p).isDirectory()) {
          if (n !== 'locales') walk(p)
        } else if (/\.(vue|ts)$/.test(n) && !n.endsWith('.spec.ts')) {
          if (/['"]common\.(dialog|label)\./.test(readFileSync(p, 'utf-8'))) bad.push(p)
        }
      }
    }
    walk(appDir)
    expect(bad).toEqual([])
  }, 60_000)
})
