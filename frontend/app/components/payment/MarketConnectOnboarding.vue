<script setup lang="ts">
/**
 * F22.1 市の謝礼決済: 受取口座（Stripe Connect）登録導線。
 *
 * 受取側（応じ手=USER / チーム=TEAM / 組織=ORG）が Connect 口座を登録し、状態
 * （未登録/審査中/完了/要件不足）を表示する。未完了なら「受取口座の登録が必要」案内を出す。
 *
 * BE 配線（recon 済み・実在 EP のみ）:
 *   - GET  /api/v1/payment/connect/status           （状態照会）
 *   - POST /api/v1/payment/connect/onboarding-link   （リンク発行 → Stripe hosted onboarding へ遷移）
 *
 * セキュリティ:
 *   - onboardingUrl は Stripe hosted ページへの遷移にのみ使用。clientSecret/acct_ は扱わない。
 *   - 状態は本人 / scope ADMIN にのみ BE が返す（IDOR は BE 側で担保）。
 */
import type { ConnectStatusResponse, ScopeKind } from '~/types/marketPayment'

interface Props {
  /** 受領主体種別（USER/TEAM/ORG）。 */
  scopeKind: ScopeKind
  /** 受領主体 ID（TEAM/ORG 時必須・USER 時は省略可）。 */
  scopeId?: number | null
}

const props = defineProps<Props>()

const { t } = useI18n()
const marketPaymentApi = useMarketPaymentApi()

const status = ref<ConnectStatusResponse | null>(null)
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)

/** onboarding 完了（払出可能）か。 */
const isReady = computed(() => status.value?.onboardingStatus === 'READY')
/** 要件不足（追加情報が必要）か。 */
const isRestricted = computed(() => status.value?.onboardingStatus === 'RESTRICTED')
/** 未登録（リンク未発行）か。 */
const isPending = computed(
  () => !status.value || status.value.onboardingStatus === 'PENDING',
)
/** 審査中（hosted onboarding 中）か。 */
const isOnboarding = computed(() => status.value?.onboardingStatus === 'ONBOARDING')

async function loadStatus() {
  loading.value = true
  errorMessage.value = null
  try {
    const res = await marketPaymentApi.getConnectStatus(props.scopeKind, props.scopeId)
    status.value = res.data
  } catch {
    // 未登録（404 等）はエラー扱いにせず「未登録」状態として描画する。
    status.value = null
  } finally {
    loading.value = false
  }
}

async function startOnboarding() {
  if (submitting.value) {
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    const returnUrl = window.location.href
    const refreshUrl = window.location.href
    const res = await marketPaymentApi.createOnboardingLink({
      scopeKind: props.scopeKind,
      scopeId: props.scopeId ?? null,
      returnUrl,
      refreshUrl,
    })
    // Stripe hosted onboarding へ遷移する。
    window.location.assign(res.data.onboardingUrl)
  } catch (e: unknown) {
    errorMessage.value
      = e instanceof Error ? e.message : t('market.payment.connect.linkFailed')
    submitting.value = false
  }
}

onMounted(loadStatus)
</script>

<template>
  <section class="market-connect">
    <h3 class="market-connect__title">
      {{ t('market.payment.connect.title') }}
    </h3>

    <p v-if="loading" class="market-connect__loading">
      {{ t('market.payment.connect.loading') }}
    </p>

    <template v-else>
      <!-- 状態バッジ -->
      <p
        class="market-connect__status"
        :class="{
          'market-connect__status--ready': isReady,
          'market-connect__status--warn': isRestricted,
        }"
      >
        <span class="market-connect__status-label">
          {{ t('market.payment.connect.statusLabel') }}:
        </span>
        <span v-if="isReady">{{ t('market.payment.connect.status.ready') }}</span>
        <span v-else-if="isOnboarding">{{ t('market.payment.connect.status.onboarding') }}</span>
        <span v-else-if="isRestricted">{{ t('market.payment.connect.status.restricted') }}</span>
        <span v-else>{{ t('market.payment.connect.status.notRegistered') }}</span>
      </p>

      <!-- 未完了案内 -->
      <p v-if="!isReady" class="market-connect__notice">
        {{ t('market.payment.connect.registrationRequired') }}
      </p>

      <!-- 要件不足の詳細 -->
      <ul
        v-if="isRestricted && status?.requirementsDue?.length"
        class="market-connect__requirements"
      >
        <li v-for="req in status.requirementsDue" :key="req">
          {{ req }}
        </li>
      </ul>

      <p v-if="errorMessage" class="market-connect__error" role="alert">
        {{ errorMessage }}
      </p>

      <button
        v-if="!isReady"
        type="button"
        class="market-connect__button"
        :disabled="submitting"
        @click="startOnboarding"
      >
        {{ submitting
          ? t('market.payment.connect.redirecting')
          : (isPending
            ? t('market.payment.connect.register')
            : t('market.payment.connect.continue')) }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.market-connect {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 1rem;
  border: 1px solid var(--p-surface-200, #e5e7eb);
  border-radius: 0.5rem;
}

.market-connect__title {
  font-weight: 600;
  margin: 0;
}

.market-connect__status--ready {
  color: var(--p-green-600, #16a34a);
}

.market-connect__status--warn {
  color: var(--p-orange-600, #ea580c);
}

.market-connect__status-label {
  font-weight: 500;
}

.market-connect__notice {
  font-size: 0.875rem;
  color: var(--p-surface-600, #4b5563);
}

.market-connect__requirements {
  margin: 0;
  padding-left: 1.25rem;
  font-size: 0.8125rem;
  color: var(--p-orange-700, #c2410c);
}

.market-connect__error {
  color: var(--p-red-600, #dc2626);
  font-size: 0.875rem;
}

.market-connect__button {
  align-self: flex-start;
  padding: 0.5rem 1rem;
  border-radius: 0.5rem;
  background-color: var(--p-primary-color, #3b82f6);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}

.market-connect__button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
