<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()

interface SettingsHubItem {
  label: string
  description: string
  icon: string
  to: string
}

/**
 * ハブに並べる項目の定義。
 *
 * ラベル・説明は i18n 必須（CLAUDE.md「UIに表示する文字列は直書き禁止」）。
 * 遷移先ページが独自のタイトル/セクション見出しキーを持つ場合はそのキーを再利用し、
 * 持たない場合のみ `settings.hub.*` に専用キーを置く（文言の二重管理を避けるため）。
 *
 * `computed` にしているのはロケール切替へ追従させるため（setup 時に t() を1回だけ
 * 呼ぶ旧実装では、言語を切り替えてもラベルが元の言語のまま残る）。
 */
const accountItem = computed<SettingsHubItem>(() => ({
  label: t('settings.account.page_title'),
  description: t('settings.hub.account.description'),
  icon: 'pi pi-user-edit',
  to: '/settings/account',
}))

const individualItems = computed<SettingsHubItem[]>(() => [
  {
    // account.vue の SettingsProfileSection と同じキー（settings.profile.section_title）を再利用。
    label: t('settings.profile.section_title'),
    description: t('settings.hub.profile.description'),
    icon: 'pi pi-user',
    to: '/settings/profile',
  },
  {
    label: t('settings.hub.security.label'),
    description: t('settings.hub.security.description'),
    icon: 'pi pi-shield',
    to: '/settings/security',
  },
  {
    // account.vue の SettingsEmailSection と同じキー。
    label: t('settings.email.section_title'),
    description: t('settings.hub.email.description'),
    icon: 'pi pi-envelope',
    to: '/settings/email',
  },
  {
    // password.vue 自身が使うキー（settings.password.section_title_change）を再利用。
    label: t('settings.password.section_title_change'),
    description: t('settings.hub.password.description'),
    icon: 'pi pi-lock',
    to: '/settings/password',
  },
  {
    // language.vue 自身が使うキー。
    label: t('settings.language.title'),
    description: t('settings.hub.language.description'),
    icon: 'pi pi-globe',
    to: '/settings/language',
  },
  {
    // account.vue の SettingsLoginHistorySection と同じキー。
    label: t('settings.login_history.section_title'),
    description: t('settings.hub.loginHistory.description'),
    icon: 'pi pi-history',
    to: '/settings/login-history',
  },
  {
    // linked-accounts.vue 自身が使うキー。
    label: t('settings.linked_accounts.page_title'),
    description: t('settings.hub.linkedAccounts.description'),
    icon: 'pi pi-link',
    to: '/settings/linked-accounts',
  },
  {
    // account.vue の SettingsAppearanceSection と同じキー。
    label: t('settings.appearance.section_title'),
    description: t('settings.hub.appearance.description'),
    icon: 'pi pi-palette',
    to: '/settings/appearance',
  },
  {
    // navigation.vue 自身が使うキー。
    label: t('settings.navigation.title'),
    description: t('settings.hub.navigation.description'),
    icon: 'pi pi-bars',
    to: '/settings/navigation',
  },
  {
    // dashboard-widgets.vue 自身がこのハブ用に用意しているキー。
    label: t('dashboard.widget_settings.settings_entry_label'),
    description: t('dashboard.widget_settings.settings_entry_description'),
    icon: 'pi pi-th-large',
    to: '/settings/dashboard-widgets',
  },
  {
    // account.vue の SettingsMemberCardSection と同じキー。
    label: t('settings.member_card.section_title'),
    description: t('settings.hub.memberCards.description'),
    icon: 'pi pi-id-card',
    to: '/settings/member-cards',
  },
  {
    // account.vue の SettingsSocialProfileSection と同じキー。
    label: t('settings.social_profile.section_title'),
    description: t('settings.hub.socialProfiles.description'),
    icon: 'pi pi-users',
    to: '/settings/social-profiles',
  },
  {
    // account.vue の SettingsSealSection と同じキー。
    label: t('settings.seal.section_title'),
    description: t('settings.hub.seals.description'),
    icon: 'pi pi-verified',
    to: '/settings/seals',
  },
  {
    // account.vue が通知タブに使うキー。
    label: t('settings.account.notification_settings'),
    description: t('settings.hub.notifications.description'),
    icon: 'pi pi-bell',
    to: '/settings/notifications',
  },
  {
    // calendar-sync.vue 自身が使うキー。
    label: t('settings.gcal.section_title'),
    description: t('settings.hub.calendarSync.description'),
    icon: 'pi pi-google',
    to: '/settings/calendar-sync',
  },
  {
    // contact-privacy.vue 自身が使うキー。
    label: t('contact_privacy.title'),
    description: t('settings.hub.contactPrivacy.description'),
    icon: 'pi pi-lock',
    to: '/settings/contact-privacy',
  },
  {
    // CMP-260909-1141 Phase 4: プロフィール公開設定。profile-visibility.vue 自身のキーを再利用。
    label: t('public.profileVisibility.title'),
    description: t('public.profileVisibility.description'),
    icon: 'pi pi-eye',
    to: '/settings/profile-visibility',
  },
  {
    // CMP-260909-1141 Phase 4: 保護者同意リンク管理。manage.vue と同じ台帳が使うキーを再利用。
    label: t('parental_consent.manage_title'),
    description: t('settings.hub.parentalConsentManage.description'),
    icon: 'pi pi-user-plus',
    to: '/parental-consent/manage',
  },
  {
    // contact-invite-tokens.vue 自身が使うキー。
    label: t('contact_invite.page_title'),
    description: t('settings.hub.contactInviteTokens.description'),
    icon: 'pi pi-link',
    to: '/settings/contact-invite-tokens',
  },
  {
    label: t('settings.hub.contactRequestBlocks.label'),
    description: t('settings.hub.contactRequestBlocks.description'),
    icon: 'pi pi-ban',
    to: '/settings/contact-request-blocks',
  },
  {
    // ad-preferences.vue 自身が使うキー。
    label: t('advertising.pages.settings_ad_preferences.title'),
    description: t('advertising.pages.settings_ad_preferences.description'),
    icon: 'pi pi-megaphone',
    to: '/settings/ad-preferences',
  },
  {
    // storage.vue 自身が使うキー。
    label: t('settings.storage.page_title'),
    description: t('settings.hub.storage.description'),
    icon: 'pi pi-database',
    to: '/settings/storage',
  },
  {
    // billing.vue 自身が使うキー。
    label: t('billing.manage.personalTitle'),
    description: t('settings.hub.billing.description'),
    icon: 'pi pi-credit-card',
    to: '/settings/billing',
  },
])

const searchQuery = ref('')
const showIndividual = useState('settings-show-individual', () => false)
const lastClickedTo = useState('settings-last-clicked', () => '')

const isSearching = computed(() => searchQuery.value.trim().length > 0)

function handleNavigate(to: string) {
  lastClickedTo.value = to
}

onMounted(() => {
  if (lastClickedTo.value) {
    nextTick(() => {
      const el = document.querySelector(`a[href="${lastClickedTo.value}"]`)
      el?.scrollIntoView({ block: 'center' })
    })
  }
})

function onEnter(el: Element) {
  const e = el as HTMLElement
  e.style.overflow = 'hidden'
  e.style.height = '0'
  e.style.opacity = '0'
  void e.offsetHeight
  e.style.transition = 'height 0.35s ease, opacity 0.3s ease'
  e.style.height = e.scrollHeight + 'px'
  e.style.opacity = '1'
}
function onAfterEnter(el: Element) {
  const e = el as HTMLElement
  e.style.overflow = ''
  e.style.height = 'auto'
  e.style.transition = ''
}
function onLeave(el: Element) {
  const e = el as HTMLElement
  e.style.overflow = 'hidden'
  e.style.height = e.scrollHeight + 'px'
  void e.offsetHeight
  e.style.transition = 'height 0.35s ease, opacity 0.3s ease'
  e.style.height = '0'
  e.style.opacity = '0'
}

const allItems = computed<SettingsHubItem[]>(() => [accountItem.value, ...individualItems.value])

const searchResults = computed(() => {
  const q = searchQuery.value.toLowerCase()
  return allItems.value.filter(
    (item) => item.label.toLowerCase().includes(q) || item.description.toLowerCase().includes(q),
  )
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="t('settings.hub.pageTitle')" />

    <IconField class="mb-6">
      <InputIcon class="pi pi-search" />
      <InputText v-model="searchQuery" :placeholder="t('settings.hub.searchPlaceholder')" class="w-full" />
    </IconField>

    <!-- 検索結果モード -->
    <template v-if="isSearching">
      <div class="space-y-3">
        <p v-if="searchResults.length === 0" class="py-8 text-center text-surface-400">
          {{ t('settings.hub.noSearchResults', { query: searchQuery }) }}
        </p>
        <NuxtLink
          v-for="item in searchResults"
          :key="item.to"
          :to="item.to"
          class="flex items-center gap-4 rounded-xl border-[3px] border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
          @click="handleNavigate(item.to)"
        >
          <div
            class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary"
          >
            <i :class="item.icon" class="text-xl" />
          </div>
          <div>
            <p class="font-medium">{{ item.label }}</p>
            <p class="text-sm text-surface-500">{{ item.description }}</p>
          </div>
          <i class="pi pi-chevron-right ml-auto text-surface-400" />
        </NuxtLink>
      </div>
    </template>

    <!-- 通常モード -->
    <template v-else>
      <!-- アカウント設定（メイン） -->
      <NuxtLink
        :to="accountItem.to"
        class="mb-6 flex items-center gap-4 rounded-xl border-2 border-primary/30 bg-primary/5 p-5 transition-shadow hover:shadow-md dark:bg-primary/10"
        @click="handleNavigate(accountItem.to)"
      >
        <div class="flex h-14 w-14 items-center justify-center rounded-xl bg-primary text-white">
          <i :class="accountItem.icon" class="text-2xl" />
        </div>
        <div>
          <p class="text-lg font-semibold">{{ accountItem.label }}</p>
          <p class="text-sm text-surface-500">{{ accountItem.description }}</p>
        </div>
        <i class="pi pi-chevron-right ml-auto text-primary" />
      </NuxtLink>

      <!-- 個別設定一覧（アコーディオン） -->
      <button
        class="flex w-full items-center justify-between rounded-xl border-[3px] border-surface-400 bg-surface-0 px-5 py-4 text-left transition-colors hover:bg-surface-50 dark:border-surface-500 dark:bg-surface-800 dark:hover:bg-surface-700"
        @click="showIndividual = !showIndividual"
      >
        <div class="flex items-center gap-3">
          <i class="pi pi-list text-surface-400" />
          <span class="font-medium">{{ t('settings.hub.individualListToggle') }}</span>
          <span class="text-sm text-surface-400">{{ t('settings.hub.itemCount', { count: individualItems.length }) }}</span>
        </div>
        <i
          class="pi transition-transform duration-200"
          :class="showIndividual ? 'pi-chevron-up' : 'pi-chevron-down'"
        />
      </button>

      <Transition @enter="onEnter" @after-enter="onAfterEnter" @leave="onLeave">
        <div v-if="showIndividual" class="mt-3 space-y-3">
          <NuxtLink
            v-for="item in individualItems"
            :key="item.to"
            :to="item.to"
            class="flex items-center gap-4 rounded-xl border-[3px] border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
            @click="handleNavigate(item.to)"
          >
            <div
              class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <i :class="item.icon" class="text-xl" />
            </div>
            <div>
              <p class="font-medium">{{ item.label }}</p>
              <p class="text-sm text-surface-500">{{ item.description }}</p>
            </div>
            <i class="pi pi-chevron-right ml-auto text-surface-400" />
          </NuxtLink>
        </div>
      </Transition>
    </template>
  </div>
</template>
