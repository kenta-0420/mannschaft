// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createPinia, defineStore } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import en from '~/locales/en/contact.json'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import ChatPage from './index.vue'

mockNuxtImport(
  'useChatTabsStore',
  () => () =>
    defineStore('chat-qr-test', {
      state: () => ({ tabs: [], activeTabId: null }),
      actions: { async restore() {} },
    })(),
)
mockNuxtImport('useIsDesktop', () => () => ({ isDesktop: ref(false) }))
mockNuxtImport('useNotification', () => () => ({ warn: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ query: {} }))
mockNuxtImport('useRouter', () => () => ({ replace: vi.fn() }))

describe('チャットから自分の連絡先QRへの導線', () => {
  it('モバイルでサイドバーを開かずQR管理へ移動できる', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [] })
    const wrapper = mount(ChatPage, {
      global: {
        plugins: [
          createPinia(),
          router,
          createI18n({
            legacy: false,
            locale: 'en',
            messages: { ja: {}, en, zh: {}, ko: {}, es: {}, de: {} },
          }),
        ],
        stubs: {
          NuxtLink: RouterLink,
          ChatTabBar: true,
          ChatChannelList: true,
          ChatCreateDialog: true,
          ChatAddTabDropdown: true,
          ChatTabContextMenu: true,
          ContactSearchDialog: true,
        },
      },
    })
    expect(wrapper.find('aside').exists()).toBe(false)
    const link = wrapper.get('header a[href="/settings/contact-invite-tokens"]')
    expect(link.text()).toMatch(/QR/)
  })
})
