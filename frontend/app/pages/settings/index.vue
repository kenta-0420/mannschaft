<script setup lang="ts">
definePageMeta({
  middleware: 'auth',
})

const accountItem = {
  label: 'アカウント設定',
  description: '全設定をひとつの画面で管理',
  icon: 'pi pi-user-edit',
  to: '/settings/account',
}

const individualItems = [
  {
    label: 'プロフィール',
    description: 'プロフィール情報・パスワードの管理',
    icon: 'pi pi-user',
    to: '/settings/profile',
  },
  {
    label: 'セキュリティ',
    description: '2FA・セッション管理・セキュリティキー',
    icon: 'pi pi-shield',
    to: '/settings/security',
  },
  {
    label: 'メールアドレス変更',
    description: 'メールアドレスの変更',
    icon: 'pi pi-envelope',
    to: '/settings/email',
  },
  {
    label: 'パスワード変更',
    description: 'パスワードの変更・設定',
    icon: 'pi pi-lock',
    to: '/settings/password',
  },
  {
    label: '言語・タイムゾーン',
    description: '表示言語・タイムゾーンの設定',
    icon: 'pi pi-globe',
    to: '/settings/language',
  },
  {
    label: 'ログイン履歴',
    description: 'ログイン・認証の履歴',
    icon: 'pi pi-history',
    to: '/settings/login-history',
  },
  {
    label: 'アカウント連携',
    description: 'OAuth・LINE連携管理',
    icon: 'pi pi-link',
    to: '/settings/linked-accounts',
  },
  {
    label: '外観',
    description: 'テーマ・背景色・表示設定',
    icon: 'pi pi-palette',
    to: '/settings/appearance',
  },
  {
    label: 'QR会員証',
    description: '会員証の表示・チェックイン履歴',
    icon: 'pi pi-id-card',
    to: '/settings/member-cards',
  },
  {
    label: 'ソーシャルプロフィール',
    description: '匿名プロフィール・フォロー管理',
    icon: 'pi pi-users',
    to: '/settings/social-profiles',
  },
  {
    label: '電子印鑑',
    description: '印鑑の表示・スコープ別設定',
    icon: 'pi pi-verified',
    to: '/settings/seals',
  },
  {
    label: '通知',
    description: '通知の受け取り設定',
    icon: 'pi pi-bell',
    to: '/settings/notifications',
  },
  {
    label: 'Google Calendar',
    description: 'カレンダー同期・iCal連携',
    icon: 'pi pi-google',
    to: '/settings/calendar-sync',
  },
  {
    label: '連絡先プライバシー',
    description: '検索・DM受信・オンライン状態の公開範囲',
    icon: 'pi pi-lock',
    to: '/settings/contact-privacy',
  },
  {
    label: '招待URL管理',
    description: '連絡先追加用の招待URLを発行・管理',
    icon: 'pi pi-link',
    to: '/settings/contact-invite-tokens',
  },
  {
    label: '申請事前拒否リスト',
    description: '特定ユーザーからの連絡先申請を拒否',
    icon: 'pi pi-ban',
    to: '/settings/contact-request-blocks',
  },
]

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

const allItems = [accountItem, ...individualItems]

const searchResults = computed(() => {
  const q = searchQuery.value.toLowerCase()
  return allItems.filter(
    (item) => item.label.toLowerCase().includes(q) || item.description.toLowerCase().includes(q),
  )
})
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="設定" />

    <IconField class="mb-6">
      <InputIcon class="pi pi-search" />
      <InputText v-model="searchQuery" placeholder="設定を検索..." class="w-full" />
    </IconField>

    <!-- 検索結果モード -->
    <template v-if="isSearching">
      <div class="space-y-3">
        <p v-if="searchResults.length === 0" class="py-8 text-center text-surface-400">
          「{{ searchQuery }}」に一致する設定が見つかりませんでした
        </p>
        <NuxtLink
          v-for="item in searchResults"
          :key="item.to"
          :to="item.to"
          class="flex items-center gap-4 rounded-xl border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
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
        class="flex w-full items-center justify-between rounded-xl border-2 border-surface-400 bg-surface-0 px-5 py-4 text-left transition-colors hover:bg-surface-50 dark:border-surface-500 dark:bg-surface-800 dark:hover:bg-surface-700"
        @click="showIndividual = !showIndividual"
      >
        <div class="flex items-center gap-3">
          <i class="pi pi-list text-surface-400" />
          <span class="font-medium">個別設定一覧</span>
          <span class="text-sm text-surface-400">（{{ individualItems.length }}項目）</span>
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
            class="flex items-center gap-4 rounded-xl border-2 border-surface-400 bg-surface-0 p-4 transition-shadow hover:shadow-md dark:border-surface-500 dark:bg-surface-800"
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
