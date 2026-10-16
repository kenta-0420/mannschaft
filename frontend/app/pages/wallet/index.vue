<script setup lang="ts">
import CardTile from '~/components/wallet/CardTile.vue'
import GroupTile from '~/components/wallet/GroupTile.vue'
import TermsAcceptModal from '~/components/wallet/TermsAcceptModal.vue'
import type {
  PointCardGroupListItem,
  PointCardUserSettings,
  UserPointCardListItem,
} from '~/types/pointCard'

/**
 * F18 ウォレットホーム。
 *
 * <p>共通会員QR・チームポイントは外部カードのopt-inから独立して表示する。
 * 外部カードの開始操作で規約を表示し、同意完了で `updateSettings` を呼ぶ。</p>
 *
 * <p>カード / グループの 2 タブ構成。カードタブは検索バー + お気に入りセクション +
 * 全カードセクション。グループタブは GroupTile のリスト。</p>
 *
 * <p>4B フェーズで `/wallet/cards/new` / `/wallet/groups/new` を実装するため、
 * 本フェーズではナビゲーションのみで実体ページは未整備（FAB ボタンの遷移先は確保済み）。</p>
 */

definePageMeta({
  middleware: ['auth'],
})

const { t } = useI18n()
const walletApi = useWalletApi()
const { handleApiError } = useErrorHandler()

// ─────────────────────────────────────────────
// State
// ─────────────────────────────────────────────

const activeTab = ref<'cards' | 'groups'>('cards')

const settings = ref<PointCardUserSettings | null>(null)
const cards = ref<UserPointCardListItem[]>([])
const groups = ref<PointCardGroupListItem[]>([])

const loadingSettings = ref(true)
const loadingCards = ref(false)
const loadingGroups = ref(false)
const settingsError = shallowRef<unknown>(null)
const cardsError = shallowRef<unknown>(null)
const groupsError = shallowRef<unknown>(null)

const showTerms = ref(false)
const showGuide = ref(false)
const searchQuery = ref('')

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

/** ウォレット機能が有効かつ規約同意済みかを判定 */
const isWalletReady = computed(
  () =>
    !settingsError.value &&
    settings.value?.isEnabled === true &&
    settings.value?.termsAcceptedAt != null,
)

/** 検索フィルタ後のカード（displayName / providerDisplayName 部分一致） */
const filteredCards = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return cards.value
  return cards.value.filter((card) => {
    const name = card.displayName.toLowerCase()
    const provider = card.providerDisplayName?.toLowerCase() ?? ''
    return name.includes(q) || provider.includes(q)
  })
})

const favoriteCards = computed(() => filteredCards.value.filter((c) => c.favorite))
const otherCards = computed(() => filteredCards.value.filter((c) => !c.favorite))

// ─────────────────────────────────────────────
// Data loading
// ─────────────────────────────────────────────

async function loadSettings() {
  loadingSettings.value = true
  settingsError.value = null
  try {
    settings.value = await walletApi.getSettings()
  } catch (error) {
    settingsError.value = error
    handleApiError(error)
  } finally {
    loadingSettings.value = false
  }
}

async function loadCards() {
  if (!isWalletReady.value) return
  loadingCards.value = true
  cardsError.value = null
  try {
    cards.value = await walletApi.listCards()
  } catch (error) {
    cardsError.value = error
    handleApiError(error)
  } finally {
    loadingCards.value = false
  }
}

async function loadGroups() {
  if (!isWalletReady.value) return
  loadingGroups.value = true
  groupsError.value = null
  try {
    groups.value = await walletApi.listGroups()
  } catch (error) {
    groupsError.value = error
    handleApiError(error)
  } finally {
    loadingGroups.value = false
  }
}

// ─────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────

async function onTermsAccepted(termsVersion: string) {
  try {
    settings.value = await walletApi.updateSettings({
      isEnabled: true,
      termsVersion,
    })
    await Promise.all([loadCards(), loadGroups()])
  } catch (error) {
    handleApiError(error)
  }
}

// 規約モーダルが閉じられたが同意がない場合、ウォレット機能は使えない
// （ユーザーが ESC や cancel で閉じた状態でも UI 上は空のまま表示される）

// ─────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────

async function loadPage() {
  await loadSettings()
  if (!settingsError.value && isWalletReady.value) {
    await Promise.all([loadCards(), loadGroups()])
  }
}
onMounted(loadPage)

watch(activeTab, (newTab) => {
  if (!isWalletReady.value) return
  if (newTab === 'cards' && cards.value.length === 0 && !loadingCards.value) {
    loadCards()
  } else if (newTab === 'groups' && groups.value.length === 0 && !loadingGroups.value) {
    loadGroups()
  }
})
</script>

<template>
  <div>
    <div class="mx-auto max-w-[720px] px-4 pb-8">
      <PageHeader :title="t('wallet.title')" help @help="showGuide = true">
        <template #actions>
          <NuxtLink v-if="isWalletReady && activeTab === 'cards'" to="/wallet/cards/new">
            <Button :label="t('wallet.actions.add_card')" icon="pi pi-plus" size="small" />
          </NuxtLink>
          <NuxtLink
            to="/wallet/settings"
            class="inline-flex h-9 w-9 items-center justify-center rounded-full text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-700"
            :aria-label="t('wallet.actions.settings')"
          >
            <i class="pi pi-cog text-lg" aria-hidden="true" />
          </NuxtLink>
        </template>
      </PageHeader>
      <LoyaltyWalletPanel />
      <PageLoading v-if="loadingSettings" />
      <DashboardErrorState v-else-if="settingsError" :error="settingsError" @retry="loadPage" />
      <SectionCard v-else-if="!isWalletReady" :title="t('wallet.title')" class="mb-4">
        <Button class="min-h-11" :label="t('loyalty.externalCards')" @click="showTerms = true" />
      </SectionCard>
      <div
        v-if="isWalletReady"
        class="mb-4 flex gap-1 border-b border-surface-200 dark:border-surface-700"
        role="tablist"
      >
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'cards'"
          class="flex-1 border-b-2 border-transparent px-3 py-3 text-sm font-semibold text-surface-500 transition-colors"
          :class="{
            'border-primary-500 text-primary-600 dark:text-primary-400': activeTab === 'cards',
          }"
          @click="activeTab = 'cards'"
        >
          {{ t('wallet.tabs.cards') }}
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeTab === 'groups'"
          class="flex-1 border-b-2 border-transparent px-3 py-3 text-sm font-semibold text-surface-500 transition-colors"
          :class="{
            'border-primary-500 text-primary-600 dark:text-primary-400': activeTab === 'groups',
          }"
          @click="activeTab = 'groups'"
        >
          {{ t('wallet.tabs.groups') }}
        </button>
      </div>

      <!-- ===== Cards タブ ===== -->
      <section v-if="isWalletReady && activeTab === 'cards'" class="flex flex-col gap-4">
        <InputText
          v-model="searchQuery"
          class="w-full"
          :placeholder="t('wallet.actions.search')"
          :aria-label="t('wallet.actions.search')"
        />

        <div v-if="loadingCards" class="py-12 text-center text-surface-400">…</div>
        <DashboardErrorState v-else-if="cardsError" :error="cardsError" @retry="loadPage" />

        <template v-else-if="cards.length === 0">
          <div class="py-12 text-center text-surface-500">
            <p class="mb-1 text-base font-semibold">{{ t('wallet.card.no_cards') }}</p>
            <p class="text-sm">{{ t('wallet.card.no_cards_hint') }}</p>
          </div>
        </template>

        <template v-else-if="filteredCards.length === 0">
          <div class="py-12 text-center text-surface-500">
            <p>{{ t('wallet.card.no_matches', { query: searchQuery }) }}</p>
          </div>
        </template>

        <template v-else>
          <div v-if="favoriteCards.length > 0" class="flex flex-col gap-2">
            <h2 class="mt-2 text-xs font-semibold uppercase tracking-widest text-surface-400">
              {{ t('wallet.card.favorites_section') }}
            </h2>
            <ul class="flex flex-col gap-2" style="list-style: none; margin: 0; padding: 0">
              <li v-for="card in favoriteCards" :key="card.id">
                <CardTile :card="card" />
              </li>
            </ul>
          </div>

          <div v-if="otherCards.length > 0" class="flex flex-col gap-2">
            <h2
              v-if="favoriteCards.length > 0"
              class="mt-2 text-xs font-semibold uppercase tracking-widest text-surface-400"
            >
              {{ t('wallet.card.all_section') }}
            </h2>
            <ul class="flex flex-col gap-2" style="list-style: none; margin: 0; padding: 0">
              <li v-for="card in otherCards" :key="card.id">
                <CardTile :card="card" />
              </li>
            </ul>
          </div>
        </template>
      </section>

      <!-- ===== Groups タブ ===== -->
      <section v-else-if="isWalletReady" class="flex flex-col gap-4">
        <div v-if="loadingGroups" class="py-12 text-center text-surface-400">…</div>
        <DashboardErrorState v-else-if="groupsError" :error="groupsError" @retry="loadPage" />

        <template v-else-if="groups.length === 0">
          <div class="py-12 text-center text-surface-500">
            <p class="mb-1 text-base font-semibold">{{ t('wallet.group.no_groups') }}</p>
            <p class="text-sm">{{ t('wallet.group.no_groups_hint') }}</p>
          </div>
        </template>

        <ul v-else class="flex flex-col gap-2" style="list-style: none; margin: 0; padding: 0">
          <li v-for="group in groups" :key="group.id">
            <GroupTile :group="group" />
          </li>
        </ul>

        <NuxtLink
          to="/wallet/groups/new"
          class="mt-2 inline-flex items-center justify-center rounded-xl border border-dashed border-surface-300 px-4 py-3 font-semibold text-surface-700 no-underline dark:border-surface-600 dark:text-surface-200"
        >
          ＋ {{ t('wallet.actions.new_group') }}
        </NuxtLink>
      </section>

      <TermsAcceptModal v-model="showTerms" mode="consent" @accepted="onTermsAccepted" />
      <WalletGuideModal v-model:visible="showGuide" />
    </div>
  </div>
</template>
