<script setup lang="ts">
import type { ContentGateType, ContentPaymentGateResponse, PaymentItemResponse } from '~/types/payment'
import type { ContentPaymentGateRequest } from '~/composables/useContentPaymentGateApi'

const props = defineProps<{ scopeType: 'team' | 'organization'; scopeId: string }>()
const { t } = useI18n()
const { getPaymentItems } = usePaymentApi()
const { getContentPaymentGates, updateContentPaymentGates } = useContentPaymentGateApi()
const { showSuccess, showError } = useNotification()

const loading = ref(true)
const saving = ref(false)
const error = ref(false)
const gates = ref<ContentPaymentGateResponse[]>([])
const items = ref<PaymentItemResponse[]>([])
const contentType = ref<ContentGateType>('POST')
const contentId = ref<number | null>(null)
const selectedIds = ref<number[]>([])
const titleHidden = ref<Record<number, boolean>>({})

const eligibleItems = computed(() => items.value.filter((item) => item.meta.type !== 'DONATION' && item.audit.isActive))
const configured = computed(() => {
  const grouped = new Map<string, { type: ContentGateType; id: number; count: number }>()
  for (const gate of gates.value) {
    const type = gate.content.contentType as ContentGateType
    if (type !== 'POST' && type !== 'ANNOUNCEMENT') continue
    const key = `${type}:${gate.content.contentId}`
    const current = grouped.get(key)
    grouped.set(key, current ?? { type, id: gate.content.contentId, count: 0 })
    grouped.get(key)!.count++
  }
  return [...grouped.values()]
})

function selectConfigured(target: { type: ContentGateType; id: number }) {
  contentType.value = target.type
  contentId.value = target.id
  const current = gates.value.filter((gate) =>
    gate.content.contentType === target.type && gate.content.contentId === target.id,
  )
  selectedIds.value = current.map((gate) => gate.paymentItem.id)
  titleHidden.value = Object.fromEntries(current.map((gate) => [gate.paymentItem.id, gate.content.isTitleHidden]))
}

function toggleItem(itemId: number, checked: boolean) {
  selectedIds.value = checked
    ? [...new Set([...selectedIds.value, itemId])]
    : selectedIds.value.filter((id) => id !== itemId)
}

async function load() {
  loading.value = true
  error.value = false
  try {
    const [gateResponse, itemResponse] = await Promise.all([
      getContentPaymentGates(props.scopeType, props.scopeId),
      getPaymentItems(props.scopeType, props.scopeId),
    ])
    gates.value = gateResponse.data
    items.value = itemResponse.data
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || contentId.value == null || !Number.isInteger(contentId.value) || contentId.value <= 0) return
  saving.value = true
  try {
    const body: ContentPaymentGateRequest = {
      contentType: contentType.value,
      contentId: contentId.value,
      gates: selectedIds.value.map((paymentItemId) => ({
        paymentItemId,
        isTitleHidden: titleHidden.value[paymentItemId] === true,
      })),
    }
    await updateContentPaymentGates(props.scopeType, props.scopeId, body)
    await load()
    showSuccess(t('payment.admin.contentGate.saveSuccess'))
  } catch {
    showError(t('payment.admin.contentGate.saveError'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="rounded-xl border border-surface-200 bg-surface-0 p-4" data-testid="content-gate-manager">
    <h3 class="mb-3 text-sm font-semibold">{{ t('payment.admin.contentGate.title') }}</h3>
    <div v-if="loading" class="py-4 text-sm text-surface-500">{{ t('payment.admin.contentGate.loading') }}</div>
    <div v-else-if="error" class="py-4 text-sm text-red-600" role="alert">{{ t('payment.admin.contentGate.loadError') }}</div>
    <template v-else>
      <div class="grid gap-3 md:grid-cols-[10rem_1fr_auto]">
        <label class="text-sm">
          <span class="mb-1 block">{{ t('payment.admin.contentGate.contentType') }}</span>
          <select v-model="contentType" class="w-full rounded border border-surface-300 px-2 py-2" data-testid="content-gate-type">
            <option value="POST">{{ t('payment.admin.contentGate.post') }}</option>
            <option value="ANNOUNCEMENT">{{ t('payment.admin.contentGate.announcement') }}</option>
          </select>
        </label>
        <label class="text-sm">
          <span class="mb-1 block">{{ t('payment.admin.contentGate.contentId') }}</span>
          <input v-model.number="contentId" type="number" min="1" step="1" class="w-full rounded border border-surface-300 px-2 py-2" data-testid="content-gate-id">
        </label>
        <Button :label="t('payment.admin.contentGate.save')" :loading="saving" :disabled="saving || contentId == null || contentId <= 0" data-testid="content-gate-save" @click="save" />
      </div>

      <div v-if="configured.length" class="mt-4 flex flex-wrap gap-2" data-testid="content-gate-configured">
        <button v-for="target in configured" :key="`${target.type}:${target.id}`" type="button" class="rounded border border-surface-300 px-2 py-1 text-xs" @click="selectConfigured(target)">
          {{ target.type }} #{{ target.id }} ({{ target.count }})
        </button>
      </div>

      <div class="mt-4 grid gap-2 md:grid-cols-2">
        <label v-for="item in eligibleItems" :key="item.id" class="flex items-center gap-2 rounded border border-surface-200 p-2 text-sm">
          <input type="checkbox" :checked="selectedIds.includes(item.id)" :data-testid="`content-gate-item-${item.id}`" @change="toggleItem(item.id, ($event.target as HTMLInputElement).checked)">
          <span class="flex-1">{{ item.meta.name }}</span>
          <span v-if="selectedIds.includes(item.id)" class="flex items-center gap-1 text-xs">
            <input v-model="titleHidden[item.id]" type="checkbox" :data-testid="`content-gate-title-hidden-${item.id}`">
            {{ t('payment.admin.contentGate.titleHidden') }}
          </span>
        </label>
      </div>
      <p v-if="eligibleItems.length === 0" class="mt-3 text-sm text-surface-500">{{ t('payment.admin.contentGate.noItems') }}</p>
      <p class="mt-3 text-xs text-surface-500">{{ t('payment.admin.contentGate.clearHint') }}</p>
    </template>
  </section>
</template>
