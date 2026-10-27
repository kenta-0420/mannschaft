import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#imports'
import SystemAdminQuickLinks from './SystemAdminQuickLinks.vue'

describe('システム管理メニューの通常導線', () => {
  it('既存のメール送信管理タイトルからoutboxへ進める', async () => {
    const { $i18n } = useNuxtApp()
    await $i18n.setLocale('ja')
    const wrapper = await mountSuspended(SystemAdminQuickLinks)
    const links = wrapper.findAll('a[href="/system-admin/email-outbox"]')

    expect(links).toHaveLength(1)
    expect(links[0]!.text()).toBe($i18n.t('email_outbox.title'))
    expect(links[0]!.text()).toBe('メール送信キュー')
  })
})