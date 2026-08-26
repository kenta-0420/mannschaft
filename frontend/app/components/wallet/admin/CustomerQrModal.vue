<script setup lang="ts">
/**
 * F18 Phase 2 — 顧客追加用 QR コード表示モーダル。
 *
 * <p>設計書: docs/features/F18_point_card_wallet.md §3.3 UC-8 / §12.2
 *
 * <p>店頭掲示用に QR を大きく表示する。{@code deepLinkUrl}（mannschaft://...）を QR にエンコード
 * し、Web フォールバック URL も別途テキストで提示する。
 *
 * <p>QR 描画は共有コンポーネント {@link QrCodeImage} に委譲する（誤り訂正 H・ブランド緑モジュール・
 * 中央ブランドバッジを共通化）。エンコード対象は事業者発行の URL のみで XSS 観点は無い。
 *
 * @prop visible モーダル表示状態（v-model）
 * @prop qr     CustomerQrResponse
 */
import type { CustomerQrResponse } from '~/types/orgPointCard'

interface Props {
  visible: boolean
  qr: CustomerQrResponse | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
}>()

const { t } = useI18n()

/** QR にエンコードするディープリンク URL。未表示なら null。 */
const qrValue = ref<string | null>(null)
const renderError = ref<string | null>(null)
const copiedDeep = ref(false)
const copiedWeb = ref(false)
/** ダウンロード用に QrCodeImage の生成済み SVG を取得する参照。 */
const qrImageRef = ref<{ getSvg: () => string } | null>(null)

watch(
  () => [props.visible, props.qr?.providerId],
  () => {
    if (props.visible && props.qr) {
      renderError.value = null
      qrValue.value = props.qr.deepLinkUrl
    } else {
      qrValue.value = null
    }
  },
  { immediate: true },
)

/** QrCodeImage の描画失敗を受けてエラー表示する（握りつぶさない）。 */
function onQrError(message: string) {
  renderError.value = t('wallet.admin.customer_qr.render_failed')
  console.warn('[CustomerQrModal] QR render failed', message)
}

async function copy(text: string, target: 'deep' | 'web') {
  try {
    await navigator.clipboard.writeText(text)
    if (target === 'deep') {
      copiedDeep.value = true
      setTimeout(() => (copiedDeep.value = false), 2000)
    } else {
      copiedWeb.value = true
      setTimeout(() => (copiedWeb.value = false), 2000)
    }
  } catch (e) {
    console.warn('[CustomerQrModal] clipboard write failed', e)
  }
}

function close() {
  emit('update:visible', false)
}

function downloadSvg() {
  const svg = qrImageRef.value?.getSvg()
  if (!svg || !props.qr) return
  const blob = new Blob([svg], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `customer-qr-${props.qr.providerId}.svg`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div
    v-if="visible"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
    role="dialog"
    aria-modal="true"
    @click.self="close"
  >
    <div class="w-full max-w-md rounded-xl bg-white shadow-xl dark:bg-surface-900">
      <header class="flex items-center justify-between border-b border-surface-200 px-4 py-3 dark:border-surface-700">
        <h2 class="text-lg font-semibold">
          {{ t('wallet.admin.customer_qr.title') }}
        </h2>
        <button
          type="button"
          class="rounded p-1 hover:bg-surface-100 dark:hover:bg-surface-800"
          :aria-label="t('wallet.admin.actions.close')"
          @click="close"
        >
          <span aria-hidden="true">&times;</span>
        </button>
      </header>

      <div class="space-y-4 p-4">
        <p class="text-sm text-surface-600 dark:text-surface-300">
          {{ t('wallet.admin.customer_qr.instruction') }}
        </p>

        <!-- プロバイダー名 -->
        <div v-if="qr" class="text-center text-base font-medium">
          {{ qr.displayName }}
        </div>

        <!-- QR コード本体 -->
        <div class="flex justify-center">
          <div
            class="relative aspect-square w-full max-w-[320px] rounded-lg border border-surface-300 bg-white p-3 dark:border-surface-600"
            role="img"
            :aria-label="t('wallet.admin.customer_qr.title')"
          >
            <QrCodeImage
              v-if="qrValue"
              ref="qrImageRef"
              class="!w-full"
              :value="qrValue"
              :size="512"
              @error="onQrError"
            />
            <div
              v-else
              class="flex h-full items-center justify-center text-sm text-surface-500"
            >
              {{ renderError ?? t('wallet.admin.customer_qr.loading') }}
            </div>
          </div>
        </div>

        <!-- ディープリンク -->
        <div v-if="qr">
          <p class="mb-1 text-xs font-medium text-surface-600 dark:text-surface-400">
            {{ t('wallet.admin.customer_qr.deep_link') }}
          </p>
          <div class="flex items-center gap-2">
            <code class="flex-1 truncate rounded bg-surface-100 px-2 py-1 text-xs dark:bg-surface-800">
              {{ qr.deepLinkUrl }}
            </code>
            <button
              type="button"
              class="rounded border border-surface-300 px-2 py-1 text-xs hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
              @click="copy(qr.deepLinkUrl, 'deep')"
            >
              {{ copiedDeep ? t('wallet.admin.actions.copied') : t('wallet.admin.actions.copy') }}
            </button>
          </div>
        </div>

        <!-- Web URL -->
        <div v-if="qr">
          <p class="mb-1 text-xs font-medium text-surface-600 dark:text-surface-400">
            {{ t('wallet.admin.customer_qr.web_link') }}
          </p>
          <div class="flex items-center gap-2">
            <code class="flex-1 truncate rounded bg-surface-100 px-2 py-1 text-xs dark:bg-surface-800">
              {{ qr.webUrl }}
            </code>
            <button
              type="button"
              class="rounded border border-surface-300 px-2 py-1 text-xs hover:bg-surface-50 dark:border-surface-600 dark:hover:bg-surface-800"
              @click="copy(qr.webUrl, 'web')"
            >
              {{ copiedWeb ? t('wallet.admin.actions.copied') : t('wallet.admin.actions.copy') }}
            </button>
          </div>
        </div>

        <!-- ダウンロード -->
        <div class="flex justify-center pt-2">
          <button
            type="button"
            class="rounded bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
            :disabled="!qrValue"
            @click="downloadSvg"
          >
            {{ t('wallet.admin.customer_qr.download') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
