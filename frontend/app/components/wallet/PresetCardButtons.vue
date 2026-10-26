<script setup lang="ts">
import type { PointCardProvider } from '~/types/pointCard'
import { getContrastColor, getInitialAvatarColor, getInitialChar } from '~/utils/pointCardColor'

/**
 * F18 ウォレット — カード追加フォームのプリセットボタン横スクロール。
 *
 * <p>設計書 §7.2 / §8.2 / §6 に基づき、`GET /api/v1/point-cards/providers` で取得した
 * 運営マスタの先頭 10 件（is_active=true）を横並び表示する。タップで親に
 * `select` イベントを emit し、親は displayName / defaultBarcodeFormat / providerId を
 * フォームに事前充填する。</p>
 *
 * <p>マスタが空でもエラーにせず、何も表示しない（自由入力フォームは下に常設されている前提）。
 * 読み込み中はスケルトンを表示する。</p>
 *
 * @emit select 親フォームに事前充填させるためのプロバイダー情報
 */

const props = withDefaults(
  defineProps<{
    /** 最大表示件数（設計書では 10〜15 社想定。Phase 1 は 10 固定） */
    maxCount?: number
  }>(),
  {
    maxCount: 10,
  },
)

const emit = defineEmits<{
  select: [provider: PointCardProvider]
}>()

const { t: _t } = useI18n() // eslint 抑制用に保持（i18n キー追加時用）
void _t

const walletApi = useWalletApi()
const providers = ref<PointCardProvider[]>([])
const loading = ref(true)
const loadError = ref(false)

/** 最大 maxCount 件まで切り出す */
const visibleProviders = computed(() => providers.value.slice(0, props.maxCount))

async function loadProviders() {
  loading.value = true
  loadError.value = false
  try {
    const all = await walletApi.listProviders()
    providers.value = all.filter((p) => p.isActive)
  }
  catch (error) {
    // 読み込み失敗はサイレントフォールバック（自由入力フォームは下に存在するため）。
    // ただしログには残す（根治治療の観点から症状は隠さない）。
    console.error('[PresetCardButtons] failed to load providers', error)
    loadError.value = true
  }
  finally {
    loading.value = false
  }
}

function onSelect(provider: PointCardProvider) {
  emit('select', provider)
}

function backgroundOf(p: PointCardProvider): string {
  return p.brandColor ?? getInitialAvatarColor(p.displayName)
}

function foregroundOf(p: PointCardProvider): string {
  if (p.brandColor) return getContrastColor(p.brandColor)
  // HSL（彩度 60%/明度 50%）は中明度のため、視認性確保で白固定
  return '#FFFFFF'
}

onMounted(loadProviders)
</script>

<template>
  <div class="preset-buttons">
    <!-- 読み込み中: スケルトン 5 個 -->
    <div v-if="loading" class="preset-buttons__row" aria-hidden="true">
      <div
        v-for="i in 5"
        :key="`skel-${i}`"
        class="preset-buttons__skel"
      />
    </div>

    <!-- 読み込み完了: 1 件以上ある場合のみ描画 -->
    <div
      v-else-if="visibleProviders.length > 0"
      class="preset-buttons__row"
      role="list"
    >
      <button
        v-for="provider in visibleProviders"
        :key="provider.id"
        type="button"
        role="listitem"
        class="preset-buttons__btn"
        :style="{ backgroundColor: backgroundOf(provider), color: foregroundOf(provider) }"
        :aria-label="provider.displayName"
        @click="onSelect(provider)"
      >
        <img
          v-if="provider.logoUrl"
          :src="provider.logoUrl"
          :alt="provider.displayName"
          class="preset-buttons__logo"
        >
        <span v-else class="preset-buttons__initial">
          {{ getInitialChar(provider.displayName) }}
        </span>
        <span class="preset-buttons__label">{{ provider.displayName }}</span>
      </button>
    </div>

    <!-- 読み込みエラー: 自由入力に誘導する旨は親 UI に任せて、ここではメッセージのみ -->
    <p v-else-if="loadError" class="preset-buttons__error" role="status">
      {{ $t('wallet.errors.load_failed') }}
    </p>
    <!-- loaded だが 0 件: 何も表示しない（自由入力フォームのみで運用） -->
  </div>
</template>

<style scoped>
.preset-buttons {
  width: 100%;
}
.preset-buttons__row {
  display: flex;
  gap: 0.5rem;
  overflow-x: auto;
  padding: 0.25rem 0 0.5rem;
  scroll-snap-type: x mandatory;
  -webkit-overflow-scrolling: touch;
}
.preset-buttons__btn {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  min-width: 88px;
  max-width: 112px;
  padding: 0.625rem 0.5rem;
  border-radius: 0.75rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  cursor: pointer;
  scroll-snap-align: start;
  transition: transform 0.1s ease;
}
.preset-buttons__btn:hover,
.preset-buttons__btn:focus-visible {
  transform: translateY(-1px);
  outline: none;
  box-shadow: 0 0 0 2px var(--p-primary-color, #3b82f6);
}
.preset-buttons__logo {
  width: 40px;
  height: 40px;
  object-fit: contain;
  background: #fff;
  border-radius: 0.375rem;
}
.preset-buttons__initial {
  font-size: 1.5rem;
  font-weight: 700;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.preset-buttons__label {
  font-size: 0.75rem;
  font-weight: 600;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.preset-buttons__skel {
  flex: 0 0 auto;
  width: 96px;
  height: 80px;
  border-radius: 0.75rem;
  background: linear-gradient(90deg, #f3f4f6 0%, #e5e7eb 50%, #f3f4f6 100%);
  background-size: 200% 100%;
  animation: preset-skel-shimmer 1.4s ease-in-out infinite;
}
@keyframes preset-skel-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.preset-buttons__error {
  color: var(--p-text-muted-color, #6b7280);
  font-size: 0.8125rem;
  text-align: center;
  margin: 0.5rem 0;
}
</style>
