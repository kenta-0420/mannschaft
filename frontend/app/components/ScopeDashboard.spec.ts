// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { createI18n } from 'vue-i18n'
import { computed, nextTick, ref } from 'vue'
import ScopeDashboard from './ScopeDashboard.vue'
import type { ViewerRole } from '~/types/dashboard'
import ja from '~/locales/ja/dashboard.json'
import en from '~/locales/en/dashboard.json'
import zh from '~/locales/zh/dashboard.json'
import ko from '~/locales/ko/dashboard.json'
import es from '~/locales/es/dashboard.json'
import de from '~/locales/de/dashboard.json'

const { featureVisible, reorderAll } = vi.hoisted(() => ({ featureVisible: vi.fn((_key?: string) => true), reorderAll: vi.fn() }))
const widgetKeys = ref(['timeline', 'chat', 'blog'])
const individuallyHidden = ref<string[]>([])
const displayGrid = ref(false)
mockNuxtImport('useScopeFeatureVisibility', () => () => ({
  isVisible: featureVisible, ready: ref(true), loading: ref(false), error: ref(null), refresh: vi.fn(),
}))
mockNuxtImport('useDashboardWidgets', () => () => ({
  sortedWidgets: computed(() => widgetKeys.value.map(key => ({ key, labelKey: key, descriptionKey: key, icon: '', scope: ['team', 'organization'] }))),
  visibleWidgets: computed(() => (displayGrid.value ? widgetKeys.value.filter(key => !individuallyHidden.value.includes(key)) : []).map(key => ({ key, labelKey: key, descriptionKey: key, icon: '', scope: ['team', 'organization'] }))),
  isVisible: vi.fn(),
  toggleWidget: vi.fn(),
  reorder: reorderAll,
  ready: ref(true),
}))
mockNuxtImport('useSpotlightApi', () => () => ({ fetchContent: vi.fn() }))

const locales = { ja, en, zh, ko, es, de }
const organizationHints = {
  ja: 'あなたはこの組織のメンバーではないため、一部のウィジェットは非表示になっています',
  en: 'Some widgets are hidden because you are not a member of this organization',
  zh: '由于您不是此组织的成员，部分小组件已隐藏',
  ko: '이 조직의 멤버가 아니므로 일부 위젯이 숨겨져 있습니다',
  es: 'Algunos widgets están ocultos porque no eres miembro de esta organización',
  de: 'Einige Widgets sind ausgeblendet, da Sie kein Mitglied dieser Organisation sind',
}
const teamHints = {
  ja: 'あなたはこのチームのメンバーではないため、一部のウィジェットは非表示になっています',
  en: 'You are not a member of this team, so some widgets are hidden',
  zh: '您不是该团队的成员，部分小部件已被隐藏',
  ko: '이 팀의 멤버가 아니므로 일부 위젯이 숨겨집니다',
  es: 'No eres miembro de este equipo, por lo que algunos widgets están ocultos',
  de: 'Du bist kein Mitglied dieses Teams, daher sind einige Widgets ausgeblendet',
}
type ScopeType = 'personal' | 'team' | 'organization'

function mountDashboard(
  scopeType: ScopeType,
  options: { locale?: keyof typeof locales; scopeId?: string; viewerRole?: ViewerRole } = {},
) {
  return mount(ScopeDashboard, {
    shallow: true,
    props: {
      scopeType,
      scopeId: options.scopeId ?? 'public-scope',
      viewerRole: options.viewerRole ?? 'PUBLIC',
    },
    global: {
      plugins: [
        createI18n({
          legacy: false,
          locale: options.locale ?? 'ja',
          fallbackLocale: false,
          messages: locales,
        }),
      ],
      stubs: {
        Message: {
          emits: ['close'],
          template:
            '<aside data-testid="public-hint"><slot /><button data-testid="dismiss" @click="$emit(\'close\')" /></aside>',
        },
        Button: true,
        DashboardScopeAccordion: { props: ['widgets'], template: '<div><slot :widgets="widgets" /></div>' },
        DashboardWidgetCard: { template: '<article><slot /></article>' },
        DashboardConfigDialog: true,
      },
    },
  })
}

describe('ScopeDashboard 公開閲覧者案内', () => {
  beforeEach(() => { localStorage.clear(); featureVisible.mockReset().mockReturnValue(true); reorderAll.mockReset(); widgetKeys.value = ['timeline', 'chat', 'blog']; individuallyHidden.value = []; displayGrid.value = false })

  it.each(Object.keys(locales) as (keyof typeof locales)[])(
    '%s: 組織の案内を実翻訳で表示する',
    (locale) => {
      const wrapper = mountDashboard('organization', { locale })

      expect(wrapper.get('[data-testid="public-hint"]').text()).toBe(organizationHints[locale])
    },
  )

  it.each(Object.keys(locales) as (keyof typeof locales)[])(
    '%s: チームの既存案内を維持する',
    (locale) => {
      const wrapper = mountDashboard('team', { locale })

      expect(wrapper.get('[data-testid="public-hint"]').text()).toBe(teamHints[locale])
    },
  )

  it('設定dialogだけを絞り並べ替え時も非表示機能の元順序を保持する', async () => {
    featureVisible.mockImplementation(key => key !== 'chat')
    const wrapper = mountDashboard('team', { viewerRole: 'MEMBER' })
    const dialog = wrapper.findComponent({ name: 'DashboardConfigDialog' })
    expect(dialog.props('widgets').map((widget: { key: string }) => widget.key)).toEqual(['timeline', 'blog'])
    dialog.vm.$emit('reorder', 1, 0)
    await nextTick()
    expect(reorderAll).toHaveBeenCalledWith(2, 0)
  })

  it('個人ダッシュボードには案内を表示しない', () => {
    const wrapper = mountDashboard('personal')

    expect(wrapper.find('[data-testid="public-hint"]').exists()).toBe(false)
  })

  it('通常グリッドの並べ替えは個人非表示と機能非表示が混在しても対象キーを維持する', async () => {
    displayGrid.value = true
    widgetKeys.value = ['timeline', 'chat', 'blog', 'bulletin']
    individuallyHidden.value = ['timeline']
    featureVisible.mockImplementation(key => key !== 'chat')
    const wrapper = mountDashboard('team', { viewerRole: 'MEMBER' })
    await wrapper.get('[data-widget-key="bulletin"]').trigger('dragstart')
    await wrapper.get('[data-widget-key="blog"]').trigger('drop')
    expect(reorderAll).toHaveBeenCalledWith(3, 2)
  })

  it.each<ViewerRole>(['SYSTEM_ADMIN', 'ADMIN', 'DEPUTY_ADMIN', 'MEMBER', 'SUPPORTER'])(
    '%sには案内を表示しない',
    (viewerRole) => {
      const wrapper = mountDashboard('organization', { viewerRole })

      expect(wrapper.find('[data-testid="public-hint"]').exists()).toBe(false)
    },
  )

  it('閉じる状態は再マウント後も同scopeだけに適用する', async () => {
    const wrapper = mountDashboard('organization')
    await wrapper.get('[data-testid="dismiss"]').trigger('click')

    expect(wrapper.find('[data-testid="public-hint"]').exists()).toBe(false)
    expect(localStorage.getItem('dashboard-public-hint-dismissed:organization:public-scope')).toBe(
      '1',
    )
    wrapper.unmount()
    const remounted = mountDashboard('organization')
    await nextTick()
    expect(remounted.find('[data-testid="public-hint"]').exists()).toBe(false)
    expect(
      mountDashboard('organization', { scopeId: 'another-scope' })
        .find('[data-testid="public-hint"]')
        .exists(),
    ).toBe(true)
    expect(mountDashboard('team').find('[data-testid="public-hint"]').exists()).toBe(true)
  })
})
