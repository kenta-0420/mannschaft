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
 * <p>設計書 §8.1 / §7.1 に基づき、初回アクセス時または規約未同意の場合は
 * `TermsAcceptModal` を強制表示し、同意完了で `updateSettings` を呼び有効化する。</p>
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

const showTerms = ref(false)
const showGuide = ref(false)
const searchQuery = ref('')

// ─────────────────────────────────────────────
// Computed
// ─────────────────────────────────────────────

/** ウォレット機能が有効かつ規約同意済みかを判定 */
const isWalletReady = computed(
  () => settings.value?.isEnabled === true && settings.value?.termsAcceptedAt != null,
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
  try {
    settings.value = await walletApi.getSettings()
    if (!isWalletReady.value) {
      showTerms.value = true
    }
  } catch (error) {
    console.error('[wallet] failed to load settings', error)
  } finally {
    loadingSettings.value = false
  }
}

async function loadCards() {
  if (!isWalletReady.value) return
  loadingCards.value = true
  try {
    cards.value = await walletApi.listCards()
  } catch (error) {
    console.error('[wallet] failed to load cards', error)
  } finally {
    loadingCards.value = false
  }
}

async function loadGroups() {
  if (!isWalletReady.value) return
  loadingGroups.value = true
  try {
    groups.value = await walletApi.listGroups()
  } catch (error) {
    console.error('[wallet] failed to load groups', error)
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
    console.error('[wallet] failed to accept terms', error)
  }
}

// 規約モーダルが閉じられたが同意がない場合、ウォレット機能は使えない
// （ユーザーが ESC や cancel で閉じた状態でも UI 上は空のまま表示される）

// ─────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────

onMounted(async () => {
  await loadSettings()
  if (isWalletReady.value) {
    await Promise.all([loadCards(), loadGroups()])
  }
})

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
      <PageHeader :title="t('wallet.title')" :back="false" help @help="showGuide = true">
        <template #actions>
          <NuxtLink
            to="/wallet/settings"
            class="inline-flex h-9 w-9 items-center justify-center rounded-full text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-300 dark:hover:bg-surface-700"
            :aria-label="t('wallet.actions.settings')"
          >
            <i class="pi pi-cog text-lg" aria-hidden="true" />
          </NuxtLink>
        </template>
      </PageHeader>
      <div
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
      <section v-if="activeTab === 'cards'" class="flex flex-col gap-4">
        <InputText
          v-model="searchQuery"
          class="w-full"
          :placeholder="t('wallet.actions.search')"
          :aria-label="t('wallet.actions.search')"
        />

        <div v-if="loadingCards" class="py-12 text-center text-surface-400">…</div>

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

        <NuxtLink
          to="/wallet/cards/new"
          class="fixed bottom-6 right-6 flex h-14 w-14 items-center justify-center rounded-full bg-primary-500 text-2xl text-white shadow-xl"
          :aria-label="t('wallet.actions.add_card')"
        >
          ＋
        </NuxtLink>
      </section>

      <!-- ===== Groups タブ ===== -->
      <section v-else class="flex flex-col gap-4">
        <div v-if="loadingGroups" class="py-12 text-center text-surface-400">…</div>

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
