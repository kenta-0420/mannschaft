import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import BroadcastWizard from '~/components/announcement/BroadcastWizard.vue'

/**
 * BroadcastWizard.vue: 使い方トグルの aria-controls が存在するパネル id を指すこと。
 */
beforeEach(() => {
  setActivePinia(createPinia())
})

describe('BroadcastWizard.vue ガイド開閉の aria-controls', () => {
  it('aria-controls が、実在するガイドパネルの id と一致する', async () => {
    await mountSuspended(BroadcastWizard, {
      props: { visible: true, scopeType: 'TEAM', scopeId: '1' },
      attachTo: document.body,
    })
    const toggle = document.body.querySelector('[data-testid="broadcast-guide-toggle"]')
    const panel = document.body.querySelector('[data-testid="broadcast-guide-panel"]')
    expect(toggle).not.toBeNull()
    expect(panel).not.toBeNull()
    const controls = toggle!.getAttribute('aria-controls')
    expect(controls).toBeTruthy()
    expect(panel!.id).toBe(controls)
    expect(document.getElementById(controls!)).toBe(panel)
  })
})
