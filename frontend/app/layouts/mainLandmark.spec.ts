// @vitest-environment node
import { readFileSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * AppShell が <main> を持つため、その内側に載るレイアウトは <main> を重ねない（a11y）。
 * AppShell の外で自前の <main> を持つ public・landing だけ除外する。
 */
const OUTSIDE_APP_SHELL = new Set(['public.vue', 'landing.vue'])
const layouts = readdirSync(__dirname).filter((f) => f.endsWith('.vue') && !OUTSIDE_APP_SHELL.has(f))

describe.each(layouts)('layouts/%s', (name) => {
  it('<main> を含まない', () => {
    const src = readFileSync(resolve(__dirname, name), 'utf8')
    expect(src).not.toMatch(/<\/?main[\s>]/)
  })
})
