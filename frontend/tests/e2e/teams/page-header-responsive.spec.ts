import { readFileSync } from 'node:fs'
import { test, expect } from '@playwright/test'
import { installFriendContractFixture } from './friend-contract-fixture'
import { headerGroupsAreOneRow, rectFits } from '../../unit/helpers/pageHeaderGeometry'

const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const pages = [
  ['folders', '/teams/1/friend-folders', 'folders'],
  ['exports', '/teams/1/friend-forward-exports', 'forward_exports'],
  ['feed', '/teams/1/friend-feed', 'friend_feed'],
  ['team-advertiser', '/teams/1/advertiser/campaigns/7', 'campaign'],
  ['org-advertiser', '/organizations/test-org/advertiser/campaigns/7', 'campaign'],
] as const

for (const width of [360, 639, 640, 767, 768, 1280]) {
  for (const locale of locales) {
    for (const [name, route, key] of pages) {
      test((width < 768 ? 'P6' : 'P7') + ' ' + name + ' ' + locale + ' ' + width, async ({ page }, testInfo) => {
        await page.setViewportSize({ width, height: 800 })
        const fixture = await installFriendContractFixture(page, locale)
        const messages = JSON.parse(readFileSync('app/locales/' + locale + '/friends.json', 'utf8')) as Record<string, { title: string }>
        await page.goto(route)
        const heading = page.locator('h1').last()
        await expect(heading).toHaveText(key === 'campaign' ? 'Baseline Campaign' : messages[key]!.title)
        await page.evaluate(() => document.fonts.ready)
        const measurement = await heading.evaluate(h1 => {
          const rect = (element: Element) => {
            const r = element.getBoundingClientRect()
            return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height }
          }
          const row = h1.parentElement!
          const root = row.parentElement!
          const actions = row.lastElementChild?.tagName === 'DIV' ? row.lastElementChild : null
          const subtitle = root.querySelector('[data-testid="page-header-subtitle"]')
          const buttons = Array.from(actions?.querySelectorAll('button') ?? [])
          const text = [h1, ...(subtitle ? [subtitle] : []), ...buttons.flatMap(button => Array.from(button.querySelectorAll('.p-button-label')))]
          const overflowing = text.filter(el => !el.classList.contains('truncate') && el.clientWidth > 0 && el.scrollWidth > el.clientWidth + 1).map(el => ({ text: el.textContent, scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }))
          return {
            root: rect(root), title: rect(h1), subtitle: subtitle ? rect(subtitle) : null,
            buttons: buttons.map(button => ({ text: button.textContent, rect: rect(button) })),
            titleItems: Array.from(row.children).map(rect).filter(r => r.height > 0),
            actionItems: Array.from(actions?.children ?? []).map(rect).filter(r => r.height > 0),
            actionInputs: Array.from(actions?.querySelectorAll('input') ?? []).map(rect),
            documentWidth: document.documentElement.scrollWidth, viewportWidth: document.documentElement.clientWidth,
            overflowing,
          }
        })
        await testInfo.attach('header-measurement', { body: JSON.stringify(measurement, null, 2), contentType: 'application/json' })
        expect(fixture.unexpected).toEqual([])
        if (width < 768) {
          expect(measurement.documentWidth).toBeLessThanOrEqual(measurement.viewportWidth)
          expect(rectFits(measurement.title, measurement.root, width)).toBe(true)
          if (measurement.subtitle) expect(rectFits(measurement.subtitle, measurement.root, width)).toBe(true)
          for (const item of [...measurement.actionItems, ...measurement.actionInputs]) expect(rectFits(item, measurement.root, width)).toBe(true)
          for (const button of measurement.buttons) {
            expect(rectFits(button.rect, measurement.root, width), button.text ?? '').toBe(true)
            expect(button.rect.width).toBeGreaterThanOrEqual(44)
            expect(button.rect.height).toBeGreaterThanOrEqual(44)
          }
          expect(measurement.overflowing).toEqual([])
        }
        else expect(headerGroupsAreOneRow(measurement.titleItems, measurement.actionItems)).toBe(true)
      })
    }
  }
}

for (const locale of locales) {
  test('C3 placeholder fits at 360px ' + locale, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    const fixture = await installFriendContractFixture(page, locale)
    await page.goto('/teams/1/nav')
    await page.getByTestId('friend-follow-button').click()
    const input = page.locator('#friend-follow-search')
    await expect(input).toBeVisible()
    const measure = await input.evaluate(element => {
      const input = element as HTMLInputElement
      const style = getComputedStyle(input)
      const context = document.createElement('canvas').getContext('2d')!
      context.font = style.font
      return { textWidth: context.measureText(input.placeholder).width, inputWidth: input.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) }
    })
    expect(measure.textWidth).toBeLessThanOrEqual(measure.inputWidth)
    await expect(page.getByTestId('friend-follow-search-hint')).toBeVisible()
    expect(fixture.unexpected).toEqual([])
  })
}
