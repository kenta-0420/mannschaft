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
  <div class="wallet-page">
    <header class="wallet-page__header">
      <h1 class="wallet-page__title">{{ t('wallet.title') }}</h1>
      <NuxtLink
        to="/wallet/settings"
        class="wallet-page__settings-btn"
        :aria-label="t('wallet.actions.settings')"
      >
        ⚙
      </NuxtLink>
    </header>

    <div class="wallet-page__tabs" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'cards'"
        class="wallet-page__tab"
        :class="{ 'wallet-page__tab--active': activeTab === 'cards' }"
        @click="activeTab = 'cards'"
      >
        {{ t('wallet.tabs.cards') }}
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="activeTab === 'groups'"
        class="wallet-page__tab"
        :class="{ 'wallet-page__tab--active': activeTab === 'groups' }"
        @click="activeTab = 'groups'"
      >
        {{ t('wallet.tabs.groups') }}
      </button>
    </div>

    <!-- ===== Cards タブ ===== -->
    <section v-if="activeTab === 'cards'" class="wallet-page__section">
      <InputText
        v-model="searchQuery"
        class="w-full"
        :placeholder="t('wallet.actions.search')"
        :aria-label="t('wallet.actions.search')"
      />

      <div v-if="loadingCards" class="wallet-page__loading">…</div>

      <template v-else-if="cards.length === 0">
        <div class="wallet-page__empty">
          <p class="wallet-page__empty-title">{{ t('wallet.card.no_cards') }}</p>
          <p class="wallet-page__empty-hint">{{ t('wallet.card.no_cards_hint') }}</p>
        </div>
      </template>

      <template v-else-if="filteredCards.length === 0">
        <div class="wallet-page__empty">
          <p>{{ t('wallet.card.no_matches', { query: searchQuery }) }}</p>
        </div>
      </template>

      <template v-else>
        <div v-if="favoriteCards.length > 0" class="wallet-page__group">
          <h2 class="wallet-page__group-title">
            {{ t('wallet.card.favorites_section') }}
          </h2>
          <ul class="wallet-page__list">
            <li v-for="card in favoriteCards" :key="card.id">
              <CardTile :card="card" />
            </li>
          </ul>
        </div>

        <div v-if="otherCards.length > 0" class="wallet-page__group">
          <h2 v-if="favoriteCards.length > 0" class="wallet-page__group-title">
            {{ t('wallet.card.all_section') }}
          </h2>
          <ul class="wallet-page__list">
            <li v-for="card in otherCards" :key="card.id">
              <CardTile :card="card" />
            </li>
          </ul>
        </div>
      </template>

      <NuxtLink
        to="/wallet/cards/new"
        class="wallet-page__fab"
        :aria-label="t('wallet.actions.add_card')"
      >
        ＋
      </NuxtLink>
    </section>

    <!-- ===== Groups タブ ===== -->
    <section v-else class="wallet-page__section">
      <div v-if="loadingGroups" class="wallet-page__loading">…</div>

      <template v-else-if="groups.length === 0">
        <div class="wallet-page__empty">
          <p class="wallet-page__empty-title">{{ t('wallet.group.no_groups') }}</p>
          <p class="wallet-page__empty-hint">{{ t('wallet.group.no_groups_hint') }}</p>
        </div>
      </template>

      <ul v-else class="wallet-page__list">
        <li v-for="group in groups" :key="group.id">
          <GroupTile :group="group" />
        </li>
      </ul>

      <NuxtLink to="/wallet/groups/new" class="wallet-page__new-group">
        ＋ {{ t('wallet.actions.new_group') }}
      </NuxtLink>
    </section>

    <TermsAcceptModal v-model="showTerms" mode="consent" @accepted="onTermsAccepted" />
  </div>
</template>

<style scoped>
.wallet-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 1rem;
  position: relative;
  min-height: calc(100vh - 64px);
}
.wallet-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.wallet-page__title {
  font-size: 1.5rem;
  font-weight: 700;
  margin: 0;
}
.wallet-page__settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--p-surface-100, #f3f4f6);
  text-decoration: none;
  color: inherit;
  font-size: 1.25rem;
}
.wallet-page__tabs {
  display: flex;
  gap: 0.25rem;
  border-bottom: 1px solid var(--p-surface-200, #e5e7eb);
  margin-bottom: 1rem;
}
.wallet-page__tab {
  flex: 1;
  padding: 0.75rem;
  background: none;
  border: none;
  cursor: pointer;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  border-bottom: 2px solid transparent;
}
.wallet-page__tab--active {
  color: var(--p-primary-color, #3b82f6);
  border-bottom-color: var(--p-primary-color, #3b82f6);
}
.wallet-page__section {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.wallet-page__loading,
.wallet-page__empty {
  text-align: center;
  padding: 3rem 1rem;
  color: var(--p-text-muted-color, #6b7280);
}
.wallet-page__empty-title {
  font-weight: 600;
  font-size: 1rem;
  margin: 0 0 0.5rem;
}
.wallet-page__empty-hint {
  font-size: 0.875rem;
  margin: 0;
}
.wallet-page__group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.wallet-page__group-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0.5rem 0 0;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.wallet-page__list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.wallet-page__fab {
  position: fixed;
  right: 1.5rem;
  bottom: 1.5rem;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--p-primary-color, #3b82f6);
  color: #fff;
  text-decoration: none;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.75rem;
  box-shadow: 0 10px 20px -5px rgba(0, 0, 0, 0.3);
}
.wallet-page__new-group {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0.75rem 1rem;
  border: 1px dashed var(--p-surface-300, #d1d5db);
  border-radius: 0.75rem;
  text-decoration: none;
  color: var(--p-text-color, #111827);
  font-weight: 600;
  margin-top: 0.5rem;
}
</style>
