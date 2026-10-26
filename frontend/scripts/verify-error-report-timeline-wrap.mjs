/**
 * CMP-261022-0338: 実Vue・実DynamicScrollerのブラウザ描画回帰。
 * 実行: node scripts/verify-error-report-timeline-wrap.mjs（frontendで実行）
 * 認証/APIは対象外。モックなし実機E2Eとは別の限定レンダリング試験。
 */
import assert from 'node:assert/strict'
import { readFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { build } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'
import postcss from 'postcss'
import tailwindcss from 'tailwindcss'
import loadConfig from 'tailwindcss/loadConfig.js'
import { chromium } from '@playwright/test'

const component = resolve('app/components/systemAdmin/errorReport/ErrorReportTimelineView.vue')
const source = await readFile(component, 'utf8')
const css = await postcss([
  tailwindcss({
    ...loadConfig(resolve('tailwind.config.ts')),
    content: [{ raw: source, extension: 'vue' }],
  }),
]).process('@tailwind base; @tailwind utilities;', { from: undefined })
const bundled = await build({
  stdin: {
    resolveDir: process.cwd(),
    loader: 'js',
    contents: `
      import { createApp, h, reactive, computed, nextTick } from 'vue'
      import Timeline from '${component.replaceAll('\\', '/')}'
      import Button from 'primevue/button'
      globalThis.computed = computed
      globalThis.useI18n = () => ({ t: key => key })
      globalThis.useDatetime = () => ({ formatDateTime: value => value })
      const state = reactive({ items: [], loading: false, hasMore: false })
      globalThis.timelineState = state
      globalThis.timelineLoads = 0
      globalThis.timelineTick = nextTick
      const app = createApp({ render: () => h(Timeline, {
        ...state, onLoadMore: () => globalThis.timelineLoads++,
      }) })
      app.component('Button', Button)
      app.mount('#app')
    `,
  },
  tsconfigRaw: {},
  bundle: true,
  write: false,
  format: 'iife',
  outfile: 'timeline.js',
  plugins: [
    {
      name: 'actual-vue-component',
      setup(builder) {
        builder.onLoad({ filter: /\.vue$/ }, async ({ path }) => {
          const { descriptor } = parse(await readFile(path, 'utf8'), { filename: path })
          return {
            contents: compileScript(descriptor, { id: 'timeline-wrap', inlineTemplate: true })
              .content,
            loader: 'ts',
          }
        })
      },
    },
  ],
})
await mkdir('test-results/timeline-wrap-limited', { recursive: true })
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
const errors = []
page.on('pageerror', (error) => errors.push(error.message))
const item = (content) => ({
  type: 'ACTIVITY',
  activityType: 'COMMENT_ADDED',
  content,
  occurredAt: '2026-10-26T09:00:00',
  actorName: '管理者',
})
async function mount(items, extras = {}) {
  await page.evaluate(
    async ({ items, extras }) => {
      Object.assign(globalThis.timelineState, { items, loading: false, hasMore: false }, extras)
      await globalThis.timelineTick()
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    },
    { items, extras },
  )
}
async function geometry(content) {
  return page.evaluate((content) => {
    const el = [...document.querySelectorAll('[data-active="true"] .whitespace-pre-wrap')].find(
      (el) => el.textContent === content,
    )
    if (!el) return null
    const box = el.getBoundingClientRect()
    const range = document.createRange()
    range.selectNodeContents(el)
    const rects = [...range.getClientRects()]
    return {
      width: el.clientWidth,
      scrollWidth: el.scrollWidth,
      height: box.height,
      lines: rects.length,
      outside: rects.filter(
        (rect) =>
          rect.left < box.left - 1 || rect.right > box.right + 1 || rect.bottom > box.bottom + 1,
      ).length,
    }
  }, content)
}
async function readable(content, label) {
  await page.waitForFunction(
    (content) =>
      [...document.querySelectorAll('[data-active="true"] .whitespace-pre-wrap')].some(
        (el) => el.textContent === content,
      ),
    content,
  )
  let metrics
  // ResizeObserverによる仮想行の高さ再計測後の矩形を確認する。
  for (let attempt = 0; attempt < 60; attempt++) {
    metrics = await geometry(content)
    if (metrics && metrics.outside === 0 && metrics.scrollWidth <= metrics.width + 1) break
    await page.evaluate(() => new Promise(requestAnimationFrame))
  }
  console.log(label, JSON.stringify(metrics))
  assert.ok(
    metrics && metrics.outside === 0 && metrics.scrollWidth <= metrics.width + 1,
    label + ': 本文が横にはみ出す',
  )
  assert.ok(metrics.lines > 1, label + ': 長文が複数行に折り返される')
}
try {
  await page.setContent(
    '<title>限定コンポーネント描画検証（認証・API対象外）</title><main id="app" style="padding:16px"></main>',
  )
  for (const file of bundled.outputFiles.filter((file) => file.path.endsWith('.css')))
    await page.addStyleTag({ content: file.text })
  await page.addStyleTag({ content: css.css })
  await page.addScriptTag({
    content: bundled.outputFiles.find((file) => file.path.endsWith('.js')).text,
  })
  const comments = [
    ['ASCII2000', 'A'.repeat(2000)],
    ['URL2000', 'https://example.test/' + 'path'.repeat(495)],
    ['混在2000', ('日本語ABChttps://example.test/' + 'x'.repeat(80)).repeat(20).slice(0, 2000)],
  ]
  for (const [label, content] of comments) {
    await mount([item(content), { ...item('追加取得'), occurredAt: '2026-10-26T09:01:00' }])
    for (const width of [390, 360, 390]) {
      await page.setViewportSize({ width, height: 844 })
      await readable(content, label + '/' + width)
      if (label === 'ASCII2000')
        await page.screenshot({ path: 'test-results/timeline-wrap-limited/' + width + '.png' })
      await page.evaluate(() => {
        const scroller = document.querySelector('.vue-recycle-scroller')
        scroller.scrollTop = scroller.scrollHeight
      })
      await page.getByText('追加取得', { exact: true }).waitFor({ state: 'visible' })
      await page.evaluate(
        () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
      )
      const overlap = await page.evaluate(() => {
        const cards = [...document.querySelectorAll('.vue-recycle-scroller__item-view')]
          .filter((el) => el.querySelector('[data-active="true"]'))
          .map((el) => el.querySelector('.rounded-lg').getBoundingClientRect())
          .sort((a, b) => a.top - b.top)
        return cards.some((box, i) => i > 0 && box.top < cards[i - 1].bottom - 1)
      })
      assert.equal(overlap, false, '長文後の履歴と重ならない')
      const tailVisible = await page.evaluate((content) => {
        const el = [...document.querySelectorAll('[data-active="true"] .whitespace-pre-wrap')].find(
          (el) => el.textContent === content,
        )
        if (!el) return false
        const range = document.createRange()
        range.setStart(el.firstChild, content.length - 1)
        range.setEnd(el.firstChild, content.length)
        const tail = range.getBoundingClientRect()
        const viewport = document.querySelector('.vue-recycle-scroller').getBoundingClientRect()
        return tail.top >= viewport.top - 1 && tail.bottom <= viewport.bottom + 1
      }, content)
      assert.equal(tailVisible, true, 'スクロールで本文末尾の文字へ到達できる')
      await page.evaluate(() => {
        document.querySelector('.vue-recycle-scroller').scrollTop = 0
      })
    }
  }
  const multiline = '先頭行\n\n末尾行'
  await mount([item(multiline)])
  await page.locator('[data-active="true"]').getByText(multiline, { exact: true }).waitFor()
  const spacing = await page.locator('[data-active="true"] .whitespace-pre-wrap').evaluate((el) => {
    const range = document.createRange()
    const text = el.firstChild
    range.setStart(text, 0)
    range.setEnd(text, 3)
    const first = range.getBoundingClientRect().top
    range.setStart(text, 5)
    range.setEnd(text, 8)
    return {
      delta: range.getBoundingClientRect().top - first,
      line: parseFloat(getComputedStyle(el).lineHeight),
    }
  })
  assert.equal(spacing.delta, spacing.line * 2, '改行と空行を保持')
  await mount([item(null), { ...item(''), occurredAt: '2026-10-26T09:01:00' }])
  assert.equal(
    await page.locator('[data-active="true"] .whitespace-pre-wrap').count(),
    0,
    'null/空文字は本文なし',
  )
  await mount([])
  await page.getByText('error_report.timeline.no_items', { exact: true }).waitFor()
  await mount([item('追加取得')], { hasMore: true })
  await page.getByRole('button', { name: 'error_report.timeline.load_more' }).click()
  assert.ok((await page.evaluate(() => globalThis.timelineLoads)) > 0, '追加取得イベント')
  await mount([item('追加取得')], { hasMore: true, loading: true })
  assert.equal(await page.getByRole('button').count(), 0, 'loading中は追加取得ボタンなし')
  assert.deepEqual(errors, [], 'ブラウザ実行エラーなし')
  console.log('PASS: 折返し・リサイズ・空/null・空行・仮想高さ・追加取得表示')
} finally {
  await browser.close()
}
