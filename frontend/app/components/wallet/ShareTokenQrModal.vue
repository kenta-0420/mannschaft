<script setup lang="ts">
/**
 * F18 Phase 3 「店舗で提示」QR コードモーダル。
 *
 * <p>顧客がレジで自分のカードを提示する際に 5 分 TTL のワンタイムトークンを
 * 発行し、ディープリンク URL を QR コードに描画して画面表示する。
 * 店主側端末がスキャンすると {@code resolve-by-token} 経由でカード ID を特定し、
 * 残高加減算 / スタンプ押印を行う。</p>
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §16
 *
 * <ul>
 *   <li>{@code show} が true になると即座に {@code generateShareToken} を呼ぶ</li>
 *   <li>5 分カウントダウン（200ms tick）を画面に表示し、0 になると「期限切れ」表示</li>
 *   <li>「新しい QR を発行」ボタンで再発行可能</li>
 *   <li>モーダルを閉じるとタイマー停止（onBeforeUnmount でも保険）</li>
 * </ul>
 */
const props = defineProps<{ cardId: string, show: boolean }>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const api = useWalletApi()

const token = ref<string | null>(null)
const expiresAt = ref<Date | null>(null)
const qrValue = ref<string | null>(null)
const remainingMs = ref<number>(0)
const issuing = ref<boolean>(false)
const error = ref<string | null>(null)
let timer: ReturnType<typeof setInterval> | undefined

async function issue() {
  if (issuing.value) return
  issuing.value = true
  error.value = null
  try {
    const res = await api.generateShareToken(props.cardId)
    token.value = res.token
    expiresAt.value = new Date(res.expiresAt)
    qrValue.value = res.deepLinkUrl
    startCountdown()
  }
  catch (e) {
    console.error('[wallet/ShareTokenQrModal] generateShareToken failed', e)
    error.value = t('wallet.share.generate_failed')
  }
  finally {
    issuing.value = false
  }
}

function startCountdown() {
  if (timer) clearInterval(timer)
  const tick = () => {
    if (!expiresAt.value) return
    remainingMs.value = Math.max(0, expiresAt.value.getTime() - Date.now())
    if (remainingMs.value === 0 && timer) {
      clearInterval(timer)
      timer = undefined
    }
  }
  tick()
  timer = setInterval(tick, 200)
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = undefined
  }
}

const remainingText = computed(() => {
  const sec = Math.floor(remainingMs.value / 1000)
  return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`
})
const expired = computed(() => remainingMs.value === 0 && !!expiresAt.value)

watch(() => props.show, (v) => {
  if (v) {
    // モーダルを開いたとき必ず再発行（古い token が残っていても新しい 5 分を取得）
    token.value = null
    expiresAt.value = null
    qrValue.value = null
    remainingMs.value = 0
    issue()
  }
  else {
    stopCountdown()
  }
})

onBeforeUnmount(() => {
  stopCountdown()
})

function handleClose() {
  emit('close')
}
</script>

<template>
  <div
    v-if="show"
    class="share-modal__overlay"
    role="dialog"
    aria-modal="true"
    :aria-label="t('wallet.share.title')"
    @click.self="handleClose"
  >
    <div class="share-modal">
      <h2 class="share-modal__title">
        {{ t('wallet.share.title') }}
      </h2>
      <p class="share-modal__instruction">
        {{ t('wallet.share.instruction') }}
      </p>

      <div v-if="issuing && !qrValue" class="share-modal__loading">
        …
      </div>

      <div v-else-if="error" class="share-modal__error" role="alert">
        {{ error }}
      </div>

      <div
        v-else-if="qrValue"
        class="share-modal__qr"
        :class="{ 'share-modal__qr--expired': expired }"
      >
        <QrCodeImage :value="qrValue" :size="320" />
      </div>

      <p v-if="qrValue && !expired" class="share-modal__countdown" aria-live="polite">
        {{ t('wallet.share.remaining', { time: remainingText }) }}
      </p>
      <p v-else-if="expired" class="share-modal__expired" role="alert">
        {{ t('wallet.share.expired') }}
      </p>

      <div class="share-modal__actions">
        <Button
          v-if="expired || error"
          :label="t('wallet.share.regenerate')"
          :disabled="issuing"
          :loading="issuing"
          class="flex-1"
          @click="issue"
        />
        <Button
          :label="t('wallet.actions.close')"
          severity="secondary"
          class="flex-1"
          @click="handleClose"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.share-modal__overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 1rem;
}
.share-modal {
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  padding: 1.5rem 1.25rem;
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
}
.share-modal__title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
  text-align: center;
}
.share-modal__instruction {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-text-muted-color, #6b7280);
  text-align: center;
}
.share-modal__loading,
.share-modal__error {
  min-height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.9375rem;
  color: var(--p-text-muted-color, #6b7280);
}
.share-modal__error {
  color: #dc2626;
}
.share-modal__qr {
  background: #fff;
  padding: 0.5rem;
  border-radius: 0.5rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  display: flex;
  align-items: center;
  justify-content: center;
}
.share-modal__qr :deep(svg) {
  display: block;
  width: 100%;
  max-width: 320px;
  height: auto;
}
.share-modal__qr--expired {
  opacity: 0.3;
}
.share-modal__countdown {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--p-text-color, #111827);
  font-variant-numeric: tabular-nums;
}
.share-modal__expired {
  margin: 0;
  font-size: 0.9375rem;
  font-weight: 600;
  color: #dc2626;
}
.share-modal__actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.5rem;
  width: 100%;
}
</style>
