// @vitest-environment nuxt
import { useNuxtApp } from '#app'
import { afterEach, describe, expect, it } from 'vitest'

// JSONの手動import/mockを使わず、実Nuxtのlazy locale読込でnamespaceの登録を確かめる。
afterEach(async () => {
 await useNuxtApp().$i18n.setLocale('ja')
}, 60_000)
describe('実Nuxtの6locale牧場namespace', () => {
 it.each(['ja', 'en', 'zh', 'ko', 'es', 'de'] as const)('%sで牧場のtitleと卵stageを解決する', async locale => {
  const i18n = useNuxtApp().$i18n
  await i18n.setLocale(locale)
  expect(i18n.te('ranch.title', locale)).toBe(true)
  expect(i18n.te('ranch.stage.EGG', locale)).toBe(true)
  expect(i18n.t('ranch.title')).not.toBe('ranch.title')
 }, 60_000)
})
