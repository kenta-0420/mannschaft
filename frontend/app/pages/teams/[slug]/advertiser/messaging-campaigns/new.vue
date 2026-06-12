<script setup lang="ts">
// F09.17 Phase 11-d-4 チームスコープ メッセージ型キャンペーン 新規作成ウィザード (4 ステップ)
// 組織版 (pages/organizations/[id]/advertiser/messaging-campaigns/new.vue) を複製し
// scope='TEAM' 化したもの。
import { z } from 'zod'
import type {
  AdCampaignPreviewResponse,
  AdMessagingCampaignAudienceSegmentRequest,
  AdMessagingCampaignChannelRequest,
  CreateAdMessagingCampaignRequest,
  ScopeType,
} from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamSlug = String(route.params.slug)
// F09.17 Phase 11-d-4: チーム配下ページは scope='TEAM' 固定で composable を呼ぶ。
const scopeType: ScopeType = 'TEAM'
const scopeId = teamSlug
const { t } = useI18n()
const api = useAdMessagingCampaignApi()
const toast = useNotification()

const step = ref<0 | 1 | 2 | 3>(0)
const steps = [
  'advertising.advertiser_crud.create_step.basic',
  'advertising.advertiser_crud.create_step.channels',
  'advertising.advertiser_crud.create_step.audience',
  'advertising.advertiser_crud.create_step.preview',
]

// step1: 基本情報
const basic = ref<{
  name: string
  totalBudgetYen: number
  startsAt: string
  endsAt: string
  scheduledTimezone: string
  frequencyCapOverride: number | null
}>({
  name: '',
  totalBudgetYen: 10000,
  startsAt: '',
  endsAt: '',
  scheduledTimezone: 'Asia/Tokyo',
  frequencyCapOverride: null,
})

const basicErrors = ref<Record<string, string>>({})

const basicSchema = z
  .object({
    name: z.string().min(1).max(120),
    totalBudgetYen: z.number().int().min(1000).max(100_000_000),
    startsAt: z.string().min(1),
    endsAt: z.string().min(1),
    scheduledTimezone: z.string().min(1),
    frequencyCapOverride: z.number().int().min(1).max(30).nullable().optional(),
  })
  .refine((d) => d.startsAt < d.endsAt, {
    path: ['startsAt'],
    message: 'starts_before_ends',
  })

function validateBasic(): boolean {
  const result = basicSchema.safeParse(basic.value)
  if (result.success) {
    basicErrors.value = {}
    return true
  }
  const errs: Record<string, string> = {}
  for (const issue of result.error.issues) {
    const key = issue.path.join('.')
    errs[key] = (() => {
      switch (key) {
        case 'name':
          return t(basic.value.name.length === 0
            ? 'advertising.advertiser_crud.validation.name_required'
            : 'advertising.advertiser_crud.validation.name_max')
        case 'totalBudgetYen':
          return basic.value.totalBudgetYen < 1000
            ? t('advertising.advertiser_crud.validation.total_budget_min')
            : t('advertising.advertiser_crud.validation.total_budget_max')
        case 'startsAt':
          return issue.message === 'starts_before_ends'
            ? t('advertising.advertiser_crud.validation.starts_before_ends')
            : t('advertising.advertiser_crud.validation.starts_at_required')
        case 'endsAt':
          return t('advertising.advertiser_crud.validation.ends_at_required')
        case 'frequencyCapOverride':
          return t('advertising.advertiser_crud.validation.frequency_cap_range')
        default:
          return issue.message
      }
    })()
  }
  basicErrors.value = errs
  return false
}

// step2: チャネル
const channels = ref<AdMessagingCampaignChannelRequest[]>([])
const channelDraft = ref<AdMessagingCampaignChannelRequest>({
  channelType: 'ANNOUNCEMENT',
  locale: 'ja',
  subject: null,
  bodyMarkdown: '',
  imageUrl: null,
  ctaLabel: null,
  ctaUrl: null,
  bannerCreativeId: null,
})

function addChannel() {
  if (!channelDraft.value.bodyMarkdown) return
  if (channelDraft.value.channelType === 'BANNER' && !channelDraft.value.bannerCreativeId) return
  channels.value = [...channels.value, { ...channelDraft.value }]
  channelDraft.value = {
    channelType: 'ANNOUNCEMENT',
    locale: 'ja',
    subject: null,
    bodyMarkdown: '',
    imageUrl: null,
    ctaLabel: null,
    ctaUrl: null,
    bannerCreativeId: null,
  }
}

function removeChannel(idx: number) {
  channels.value = channels.value.filter((_, i) => i !== idx)
}

// step3: audience
const segments = ref<AdMessagingCampaignAudienceSegmentRequest[]>([])

// step4: preview
const preview = ref<AdCampaignPreviewResponse | null>(null)
const previewLoading = ref(false)
const submitting = ref(false)

function next() {
  if (step.value === 0) {
    if (!validateBasic()) return
  }
  if (step.value === 1) {
    if (channels.value.length === 0) {
      toast.warn(t('advertising.advertiser_crud.validation.at_least_one_channel'))
      return
    }
  }
  if (step.value === 2) {
    if (segments.value.length === 0) {
      toast.warn(t('advertising.advertiser_crud.validation.at_least_one_segment'))
      return
    }
  }
  if (step.value < 3) {
    step.value = (step.value + 1) as 0 | 1 | 2 | 3
  }
}

function back() {
  if (step.value > 0) {
    step.value = (step.value - 1) as 0 | 1 | 2 | 3
  }
}

async function submit() {
  submitting.value = true
  try {
    const payload: CreateAdMessagingCampaignRequest = {
      name: basic.value.name,
      totalBudgetYen: basic.value.totalBudgetYen,
      startsAt: basic.value.startsAt,
      endsAt: basic.value.endsAt,
      scheduledTimezone: basic.value.scheduledTimezone,
      frequencyCapOverride: basic.value.frequencyCapOverride ?? null,
    }
    const res = await api.createCampaign(scopeType, scopeId, payload)
    const campaignId = res.data.id

    // チャネル登録
    for (const ch of channels.value) {
      await api.createChannel(scopeType, scopeId, campaignId, ch)
    }
    // オーディエンス
    if (segments.value.length > 0) {
      await api.setAudience(scopeType, scopeId, campaignId, { segments: segments.value })
    }

    toast.success(t('advertising.pages.advertiser_campaign_create.saved'))
    router.push(`/teams/${teamSlug}/advertiser/messaging-campaigns/${campaignId}`)
  }
  catch {
    toast.error(t('advertising.advertiser_crud.errors.save_failed'))
  }
  finally {
    submitting.value = false
  }
}

async function fetchPreview() {
  // 既存 DRAFT が無いと preview は呼べないため、ウィザード段階では擬似値を表示
  previewLoading.value = true
  try {
    preview.value = {
      range: 'UNDER_100',
      label: '—',
      channelCounts: {},
    }
  }
  finally {
    previewLoading.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader :title="t('advertising.pages.advertiser_campaign_create.title')" />

    <!-- ステップインジケータ -->
    <div class="my-4 flex items-center gap-2">
      <div
        v-for="(s, idx) in steps"
        :key="idx"
        class="flex items-center gap-2"
      >
        <span
          class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium"
          :class="step >= idx ? 'bg-primary text-white' : 'bg-surface-200 text-surface-500 dark:bg-surface-700'"
        >
          {{ idx + 1 }}
        </span>
        <span class="hidden text-sm sm:inline" :class="step === idx ? 'font-semibold' : 'text-surface-500'">
          {{ t(s) }}
        </span>
        <i v-if="idx < steps.length - 1" class="pi pi-chevron-right text-xs text-surface-300" />
      </div>
    </div>

    <SectionCard>
      <!-- Step 1: 基本情報 -->
      <div v-if="step === 0">
        <AdMessagingCampaignFormFields
          v-model="basic"
          :errors="basicErrors"
        />
      </div>

      <!-- Step 2: チャネル -->
      <div v-else-if="step === 1" class="space-y-4">
        <div v-if="channels.length > 0" class="space-y-2">
          <div
            v-for="(ch, idx) in channels"
            :key="idx"
            class="flex items-center justify-between rounded-lg border border-surface-300 p-3 dark:border-surface-600"
          >
            <div>
              <Tag :value="t(`advertising.channel.${ch.channelType.toLowerCase()}`)" />
              <span class="ml-2 text-sm text-surface-500">{{ ch.locale }}</span>
              <p class="mt-1 max-w-md truncate text-sm">{{ ch.subject || ch.bodyMarkdown.slice(0, 80) }}</p>
            </div>
            <Button icon="pi pi-times" severity="danger" text rounded @click="removeChannel(idx)" />
          </div>
        </div>

        <div class="rounded-lg border-2 border-dashed border-surface-300 p-4 dark:border-surface-600">
          <p class="mb-3 font-semibold">{{ t('advertising.actions.add_channel') }}</p>
          <AdChannelEditor v-model="channelDraft" />
          <div class="mt-3 flex justify-end">
            <Button
              icon="pi pi-plus"
              :label="t('advertising.actions.add_channel')"
              size="small"
              :disabled="!channelDraft.bodyMarkdown"
              @click="addChannel"
            />
          </div>
        </div>
      </div>

      <!-- Step 3: audience -->
      <div v-else-if="step === 2">
        <AdAudienceComposer v-model="segments" />
      </div>

      <!-- Step 4: preview -->
      <div v-else-if="step === 3" class="space-y-4">
        <AdPreviewCard :preview="preview" :loading="previewLoading" />
        <div class="text-center">
          <Button
            icon="pi pi-sync"
            :label="t('advertising.advertiser_crud.preview.fetch')"
            severity="secondary"
            outlined
            @click="fetchPreview"
          />
        </div>

        <div class="rounded-lg border border-surface-300 p-4 dark:border-surface-600">
          <p class="mb-2 font-semibold">{{ t('advertising.advertiser_crud.detail_tab.overview') }}</p>
          <dl class="grid grid-cols-2 gap-2 text-sm">
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.name') }}</dt>
            <dd>{{ basic.name }}</dd>
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.total_budget') }}</dt>
            <dd>¥{{ basic.totalBudgetYen.toLocaleString() }}</dd>
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.starts_at') }}</dt>
            <dd>{{ basic.startsAt }}</dd>
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.ends_at') }}</dt>
            <dd>{{ basic.endsAt }}</dd>
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.detail_tab.channels') }}</dt>
            <dd>{{ channels.length }}</dd>
            <dt class="text-surface-500">{{ t('advertising.advertiser_crud.detail_tab.audience') }}</dt>
            <dd>{{ segments.length }}</dd>
          </dl>
        </div>
      </div>

      <!-- ナビゲーション -->
      <div class="mt-6 flex justify-between">
        <Button
          v-if="step > 0"
          :label="t('advertising.advertiser_crud.create_nav.back')"
          severity="secondary"
          icon="pi pi-arrow-left"
          @click="back"
        />
        <div v-else />
        <Button
          v-if="step < 3"
          :label="t('advertising.advertiser_crud.create_nav.next')"
          icon="pi pi-arrow-right"
          icon-pos="right"
          @click="next"
        />
        <Button
          v-else
          :label="t('advertising.advertiser_crud.create_nav.submit')"
          icon="pi pi-check"
          :loading="submitting"
          severity="success"
          @click="submit"
        />
      </div>
    </SectionCard>
  </div>
</template>
