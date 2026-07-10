<script setup lang="ts">
/**
 * F09.19.4b 運用型キャンペーン（バナー広告）作成/編集フォーム（org/team 共通）。
 *
 * <p>設計書 §6.5 / §8.5。作成（新規）と編集（[campaignId] 指定）を同一フォームで扱う。
 * §6.5 の状態別編集可否に従い、PAUSED では pricingModel / rateCardId / startDate を
 * disabled にする。</p>
 */
import { z } from 'zod'
import type { ScopeType } from '~/types/adMessagingCampaign'
import type {
  CreateOperationalCampaignRequest,
  OperationalCampaignStatus,
  PublicRateCard,
} from '~/composables/useOperationalCampaignApi'

const props = defineProps<{
  scopeType: ScopeType
  scopeId: string
  basePrefix: string
  /** 編集モード時のキャンペーン ID（未指定なら新規作成）。 */
  campaignId?: string
}>()

const { t } = useI18n()
const api = useOperationalCampaignApi()
const toast = useNotification()
const router = useRouter()

const isEdit = computed(() => !!props.campaignId)

const loading = ref(true)
const submitting = ref(false)
const rateCards = ref<PublicRateCard[]>([])
/** 編集対象の現在ステータス（disabled 判定に使用）。 */
const currentStatus = ref<OperationalCampaignStatus | null>(null)

const form = ref<{
  name: string
  pricingModel: 'CPM' | 'CPC'
  rateCardId: number | null
  dailyBudget: number
  startDate: string
  endDate: string
}>({
  name: '',
  pricingModel: 'CPM',
  rateCardId: null,
  dailyBudget: 0,
  startDate: '',
  endDate: '',
})

const errors = ref<Record<string, string>>({})

// PAUSED では pricingModel / rateCardId / startDate を編集不可（§6.5）
const isPaused = computed(() => currentStatus.value === 'PAUSED')
const disablePricingModel = computed(() => isPaused.value)
const disableRateCard = computed(() => isPaused.value)
const disableStartDate = computed(() => isPaused.value)

/** 選択中 pricingModel に一致する料金カードのみ選択肢化する。 */
const rateCardOptions = computed(() =>
  rateCards.value.filter((c) => c.pricingModel === form.value.pricingModel),
)

/** 現在選択されている料金カード。 */
const selectedRateCard = computed(() =>
  rateCards.value.find((c) => c.id === form.value.rateCardId) ?? null,
)

/** 選択カードの最低日予算（未選択なら null）。 */
const minDailyBudget = computed(() => selectedRateCard.value?.minDailyBudget ?? null)

function rateCardLabel(card: PublicRateCard): string {
  const unit = card.pricingModel === 'CPM'
    ? t('advertising.operational_campaigns.form.unit_cpm')
    : t('advertising.operational_campaigns.form.unit_cpc')
  return `${card.label ?? ''} · ${unit} ¥${Math.round(card.unitPrice ?? 0).toLocaleString()} · ${t('advertising.operational_campaigns.form.min_daily_budget')} ¥${Math.round(card.minDailyBudget ?? 0).toLocaleString()}`
}

/** pricingModel 変更時、現在の rateCard が不一致なら選択解除する。 */
watch(() => form.value.pricingModel, () => {
  if (disableRateCard.value) return
  if (selectedRateCard.value && selectedRateCard.value.pricingModel !== form.value.pricingModel) {
    form.value.rateCardId = null
  }
})

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function buildSchema() {
  const min = minDailyBudget.value
  return z
    .object({
      name: z.string().min(1).max(200),
      pricingModel: z.enum(['CPM', 'CPC']),
      rateCardId: z.number().int().positive(),
      dailyBudget: z.number().positive(),
      startDate: z.string().min(1),
      endDate: z.string(),
    })
    .refine((d) => min == null || d.dailyBudget >= min, {
      path: ['dailyBudget'],
      message: 'daily_budget_below_min',
    })
    .refine((d) => d.endDate === '' || d.startDate === '' || d.startDate <= d.endDate, {
      path: ['endDate'],
      message: 'end_before_start',
    })
    // 新規作成時のみ startDate は本日以降（PAUSED 編集は startDate 変更不可のため対象外）
    .refine((d) => isEdit.value || d.startDate === '' || d.startDate >= todayIso(), {
      path: ['startDate'],
      message: 'start_in_past',
    })
}

function validate(): boolean {
  const result = buildSchema().safeParse(form.value)
  if (result.success) {
    errors.value = {}
    return true
  }
  const errs: Record<string, string> = {}
  for (const issue of result.error.issues) {
    const key = issue.path.join('.')
    errs[key] = (() => {
      switch (key) {
        case 'name':
          return t(form.value.name.length === 0
            ? 'advertising.operational_campaigns.errors.name_required'
            : 'advertising.operational_campaigns.errors.name_max')
        case 'rateCardId':
          return t('advertising.operational_campaigns.errors.rate_card_required')
        case 'dailyBudget':
          return issue.message === 'daily_budget_below_min'
            ? t('advertising.operational_campaigns.errors.daily_budget_min', {
              min: `¥${Math.round(minDailyBudget.value ?? 0).toLocaleString()}`,
            })
            : t('advertising.operational_campaigns.errors.daily_budget_required')
        case 'startDate':
          return issue.message === 'start_in_past'
            ? t('advertising.operational_campaigns.errors.start_in_past')
            : t('advertising.operational_campaigns.errors.start_date_required')
        case 'endDate':
          return t('advertising.operational_campaigns.errors.end_before_start')
        default:
          return issue.message
      }
    })()
  }
  errors.value = errs
  return false
}

async function loadRateCards() {
  try {
    const res = await api.listRateCards()
    rateCards.value = res.data ?? []
  }
  catch {
    rateCards.value = []
    toast.error(t('advertising.operational_campaigns.errors.rate_cards_load_failed'))
  }
}

async function loadCampaign() {
  if (!props.campaignId) return
  try {
    const res = await api.getCampaign(props.scopeType, props.scopeId, props.campaignId)
    const c = res.data
    currentStatus.value = (c.status as OperationalCampaignStatus) ?? null
    form.value = {
      name: c.name ?? '',
      pricingModel: (c.pricingModel as 'CPM' | 'CPC') ?? 'CPM',
      rateCardId: c.rateCardId ?? null,
      dailyBudget: c.dailyBudget ?? 0,
      startDate: c.startDate ?? '',
      endDate: c.endDate ?? '',
    }
  }
  catch {
    toast.error(t('advertising.operational_campaigns.errors.load_failed'))
  }
}

async function init() {
  loading.value = true
  try {
    await Promise.all([loadRateCards(), loadCampaign()])
  }
  finally {
    loading.value = false
  }
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    const payload: CreateOperationalCampaignRequest = {
      name: form.value.name,
      pricingModel: form.value.pricingModel,
      rateCardId: form.value.rateCardId as number,
      dailyBudget: form.value.dailyBudget,
      startDate: form.value.startDate,
      endDate: form.value.endDate === '' ? undefined : form.value.endDate,
    }
    if (isEdit.value && props.campaignId) {
      await api.updateCampaign(props.scopeType, props.scopeId, props.campaignId, payload)
      toast.success(t('advertising.operational_campaigns.toast.updated'))
    }
    else {
      await api.createCampaign(props.scopeType, props.scopeId, payload)
      toast.success(t('advertising.operational_campaigns.toast.created'))
    }
    router.push(`${props.basePrefix}/operational-campaigns`)
  }
  catch {
    toast.error(t('advertising.operational_campaigns.errors.save_failed'))
  }
  finally {
    submitting.value = false
  }
}

onMounted(init)
</script>

<template>
  <div>
    <PageHeader
      :title="isEdit
        ? t('advertising.operational_campaigns.form.edit_title')
        : t('advertising.operational_campaigns.form.create_title')"
      :back-to="`${basePrefix}/operational-campaigns`"
    />

    <div v-if="loading" class="flex justify-center py-20">
      <LoadingBounce />
    </div>

    <SectionCard v-else>
      <div class="max-w-2xl space-y-4">
        <!-- name -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.name') }}
            <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.name"
            class="w-full"
            maxlength="200"
            data-testid="field-name"
          />
          <p v-if="errors.name" class="mt-1 text-xs text-red-500">{{ errors.name }}</p>
        </div>

        <!-- pricingModel -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.pricing_model') }}
            <span class="text-red-500">*</span>
          </label>
          <Select
            v-model="form.pricingModel"
            :options="['CPM', 'CPC']"
            :disabled="disablePricingModel"
            class="w-full"
            data-testid="field-pricing-model"
          />
          <p v-if="disablePricingModel" class="mt-1 text-xs text-surface-400">
            {{ t('advertising.operational_campaigns.form.locked_when_paused') }}
          </p>
        </div>

        <!-- rateCardId -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.rate_card') }}
            <span class="text-red-500">*</span>
          </label>
          <Select
            v-model="form.rateCardId"
            :options="rateCardOptions"
            :option-label="rateCardLabel"
            option-value="id"
            :disabled="disableRateCard"
            :placeholder="t('advertising.operational_campaigns.form.rate_card_placeholder')"
            class="w-full"
            data-testid="field-rate-card"
          />
          <p v-if="rateCardOptions.length === 0" class="mt-1 text-xs text-amber-600">
            {{ t('advertising.operational_campaigns.form.no_rate_cards') }}
          </p>
          <p v-else-if="disableRateCard" class="mt-1 text-xs text-surface-400">
            {{ t('advertising.operational_campaigns.form.locked_when_paused') }}
          </p>
          <p v-if="errors.rateCardId" class="mt-1 text-xs text-red-500">{{ errors.rateCardId }}</p>
        </div>

        <!-- dailyBudget -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.daily_budget') }}
            <span class="text-red-500">*</span>
          </label>
          <InputNumber
            v-model="form.dailyBudget"
            :min="0"
            :step="100"
            mode="currency"
            currency="JPY"
            locale="ja-JP"
            class="w-full"
            data-testid="field-daily-budget"
          />
          <p v-if="minDailyBudget != null" class="mt-1 text-xs text-surface-400">
            {{ t('advertising.operational_campaigns.form.min_daily_budget') }}:
            ¥{{ Math.round(minDailyBudget).toLocaleString() }}
          </p>
          <p v-if="errors.dailyBudget" class="mt-1 text-xs text-red-500">{{ errors.dailyBudget }}</p>
        </div>

        <!-- startDate -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.start_date') }}
            <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="form.startDate"
            type="date"
            :disabled="disableStartDate"
            class="w-full"
            data-testid="field-start-date"
          />
          <p v-if="disableStartDate" class="mt-1 text-xs text-surface-400">
            {{ t('advertising.operational_campaigns.form.locked_when_paused') }}
          </p>
          <p v-if="errors.startDate" class="mt-1 text-xs text-red-500">{{ errors.startDate }}</p>
        </div>

        <!-- endDate -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('advertising.operational_campaigns.form.end_date') }}
          </label>
          <InputText
            v-model="form.endDate"
            type="date"
            class="w-full"
            data-testid="field-end-date"
          />
          <p class="mt-1 text-xs text-surface-400">
            {{ t('advertising.operational_campaigns.form.end_date_hint') }}
          </p>
          <p v-if="errors.endDate" class="mt-1 text-xs text-red-500">{{ errors.endDate }}</p>
        </div>

        <!-- 日予算は目安の上限である旨の注記（§7.3） -->
        <Message severity="info" :closable="false">
          {{ t('advertising.operational_campaigns.form.daily_budget_notice') }}
        </Message>

        <div class="flex justify-end gap-2 pt-2">
          <NuxtLink :to="`${basePrefix}/operational-campaigns`">
            <Button
              :label="t('advertising.operational_campaigns.form.cancel')"
              severity="secondary"
              text
            />
          </NuxtLink>
          <Button
            :label="t('advertising.operational_campaigns.form.save')"
            icon="pi pi-check"
            :loading="submitting"
            data-testid="operational-campaign-save"
            @click="submit"
          />
        </div>
      </div>
    </SectionCard>
  </div>
</template>
