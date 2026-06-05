<script setup lang="ts">
// F09.17 メッセージ型キャンペーン 詳細 + 状態遷移
import type {
  AdCampaignPreviewResponse,
  AdMessagingCampaign,
  AdMessagingCampaignStatus,
  ScopeType,
} from '~/types/adMessagingCampaign'

definePageMeta({ layout: 'organization', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const orgId = String(route.params.id)
const campaignId = String(route.params.campaignId)
// F09.17 Phase 11-d-3: 組織配下ページは scope='ORGANIZATION' 固定で composable を呼ぶ。
const scopeType: ScopeType = 'ORGANIZATION'
const scopeId = orgId
const { t } = useI18n()
const api = useAdMessagingCampaignApi()
const toast = useNotification()
const confirm = useConfirm()

const campaign = ref<AdMessagingCampaign | null>(null)
const loading = ref(true)
const activeTab = ref<'overview' | 'channels' | 'audience' | 'moderation'>('overview')
const preview = ref<AdCampaignPreviewResponse | null>(null)
const previewLoading = ref(false)
const transitioning = ref(false)

const statusSeverityMap: Record<AdMessagingCampaignStatus, 'success' | 'info' | 'warn' | 'danger' | 'secondary'> = {
  DRAFT: 'secondary',
  REVIEW: 'info',
  APPROVED: 'info',
  SCHEDULED: 'info',
  DELIVERING: 'success',
  PAUSED: 'warn',
  COMPLETED: 'secondary',
  BLOCKED: 'danger',
  CANCELLED: 'secondary',
}

function statusLabel(s: AdMessagingCampaignStatus): string {
  return t(`advertising.status.${s.toLowerCase()}`)
}

async function load() {
  loading.value = true
  try {
    const res = await api.getCampaign(scopeType, scopeId, campaignId)
    campaign.value = res.data
  }
  catch {
    toast.error(t('advertising.advertiser_crud.errors.load_failed'))
  }
  finally {
    loading.value = false
  }
}

async function runTransition(
  action: 'submit' | 'cancel' | 'launch' | 'pause' | 'resume',
  confirmKey: 'submit' | 'cancel' | 'launch' | 'pause' | 'resume',
) {
  confirm.require({
    message: t(`advertising.advertiser_crud.confirm.${confirmKey}`),
    accept: async () => {
      transitioning.value = true
      try {
        const fn = (() => {
          switch (action) {
            case 'submit': return api.submitCampaign
            case 'cancel': return api.cancelCampaign
            case 'launch': return api.launchCampaign
            case 'pause': return api.pauseCampaign
            case 'resume': return api.resumeCampaign
          }
        })()
        const res = await fn(scopeType, scopeId, campaignId)
        campaign.value = res.data
        toast.success(t(`advertising.actions.${action}`))
      }
      catch {
        toast.error(t('advertising.advertiser_crud.errors.transition_failed'))
      }
      finally {
        transitioning.value = false
      }
    },
  })
}

async function deleteCampaign() {
  confirm.require({
    message: t('advertising.advertiser_crud.confirm.delete'),
    accept: async () => {
      try {
        await api.deleteCampaign(scopeType, scopeId, campaignId)
        toast.success(t('advertising.actions.delete'))
        router.push(`/organizations/${orgId}/advertiser/messaging-campaigns`)
      }
      catch {
        toast.error(t('advertising.advertiser_crud.errors.save_failed'))
      }
    },
  })
}

async function fetchPreview() {
  previewLoading.value = true
  try {
    const res = await api.previewCampaign(scopeType, scopeId, campaignId)
    preview.value = res.data
  }
  catch {
    toast.error(t('advertising.advertiser_crud.errors.preview_failed'))
  }
  finally {
    previewLoading.value = false
  }
}

const isDraft = computed(() => campaign.value?.status === 'DRAFT')
const isReview = computed(() => campaign.value?.status === 'REVIEW')
const isApproved = computed(() => campaign.value?.status === 'APPROVED')
const isDelivering = computed(() => campaign.value?.status === 'DELIVERING')
const isPaused = computed(() => campaign.value?.status === 'PAUSED')

onMounted(load)
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between">
      <NuxtLink :to="`/organizations/${orgId}/advertiser/messaging-campaigns`" class="text-sm text-primary hover:underline">
        <i class="pi pi-arrow-left mr-1" />{{ t('advertising.pages.advertiser_campaign_list.title') }}
      </NuxtLink>
    </div>

    <div v-if="loading" class="flex justify-center py-20"><LoadingBounce /></div>

    <div v-else-if="campaign">
      <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <PageHeader :title="campaign.name" />
          <Tag
            class="mt-1"
            :value="statusLabel(campaign.status)"
            :severity="statusSeverityMap[campaign.status]"
          />
          <Tag
            class="ml-1 mt-1"
            :value="t(`advertising.moderation_status.${campaign.moderationStatus.toLowerCase()}`)"
            severity="secondary"
          />
        </div>

        <!-- 状態遷移アクション -->
        <div class="flex flex-wrap gap-2">
          <Button
            v-if="isDraft"
            icon="pi pi-send"
            :label="t('advertising.actions.submit')"
            :loading="transitioning"
            severity="primary"
            @click="runTransition('submit', 'submit')"
          />
          <Button
            v-if="isDraft || isReview"
            icon="pi pi-ban"
            :label="t('advertising.actions.cancel')"
            :loading="transitioning"
            severity="secondary"
            @click="runTransition('cancel', 'cancel')"
          />
          <Button
            v-if="isApproved"
            icon="pi pi-play"
            :label="t('advertising.actions.launch')"
            :loading="transitioning"
            severity="success"
            @click="runTransition('launch', 'launch')"
          />
          <Button
            v-if="isDelivering"
            icon="pi pi-pause"
            :label="t('advertising.actions.pause')"
            :loading="transitioning"
            severity="warn"
            @click="runTransition('pause', 'pause')"
          />
          <Button
            v-if="isPaused"
            icon="pi pi-play"
            :label="t('advertising.actions.resume')"
            :loading="transitioning"
            severity="success"
            @click="runTransition('resume', 'resume')"
          />
          <Button
            v-if="isDraft"
            icon="pi pi-trash"
            :label="t('advertising.actions.delete')"
            severity="danger"
            outlined
            @click="deleteCampaign"
          />
        </div>
      </div>

      <!-- 警告: BLOCKED 理由 -->
      <Message v-if="campaign.blockedReason" severity="error" :closable="false" class="mb-4">
        {{ campaign.blockedReason }}
      </Message>

      <!-- タブ -->
      <Tabs v-model:value="activeTab">
        <TabList>
          <Tab value="overview">{{ t('advertising.advertiser_crud.detail_tab.overview') }}</Tab>
          <Tab value="channels">{{ t('advertising.advertiser_crud.detail_tab.channels') }}</Tab>
          <Tab value="audience">{{ t('advertising.advertiser_crud.detail_tab.audience') }}</Tab>
          <Tab value="moderation">{{ t('advertising.advertiser_crud.detail_tab.moderation') }}</Tab>
        </TabList>
        <TabPanels>
          <!-- Overview -->
          <TabPanel value="overview">
            <SectionCard>
              <dl class="grid grid-cols-1 gap-3 text-sm md:grid-cols-2">
                <div>
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.total_budget') }}</dt>
                  <dd class="text-lg font-bold">¥{{ campaign.totalBudgetYen.toLocaleString() }}</dd>
                </div>
                <div>
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.list_table.consumed_budget') }}</dt>
                  <dd class="text-lg font-bold">¥{{ campaign.consumedBudgetYen.toLocaleString() }}</dd>
                </div>
                <div>
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.starts_at') }}</dt>
                  <dd>{{ campaign.startsAt }}</dd>
                </div>
                <div>
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.ends_at') }}</dt>
                  <dd>{{ campaign.endsAt }}</dd>
                </div>
                <div>
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.scheduled_timezone') }}</dt>
                  <dd>{{ campaign.scheduledTimezone }}</dd>
                </div>
                <div v-if="campaign.frequencyCapOverride != null">
                  <dt class="text-surface-500">{{ t('advertising.advertiser_crud.form.frequency_cap_override') }}</dt>
                  <dd>{{ campaign.frequencyCapOverride }}</dd>
                </div>
              </dl>
            </SectionCard>

            <!-- Preview -->
            <div class="mt-4">
              <div class="mb-2 text-right">
                <Button
                  icon="pi pi-sync"
                  :label="t('advertising.advertiser_crud.preview.fetch')"
                  size="small"
                  severity="secondary"
                  outlined
                  :loading="previewLoading"
                  @click="fetchPreview"
                />
              </div>
              <AdPreviewCard :preview="preview" :loading="previewLoading" />
            </div>
          </TabPanel>

          <!-- Channels -->
          <TabPanel value="channels">
            <div v-if="campaign.channels.length === 0" class="py-6 text-center text-surface-500">
              {{ t('advertising.pages.advertiser_campaign_detail.no_channels') }}
            </div>
            <div v-else class="space-y-3">
              <SectionCard
                v-for="ch in campaign.channels"
                :key="ch.id"
              >
                <template #header>
                  <div class="flex items-center justify-between">
                    <div>
                      <Tag :value="t(`advertising.channel.${ch.channelType.toLowerCase()}`)" />
                      <span class="ml-2 text-sm text-surface-500">{{ ch.locale }}</span>
                    </div>
                  </div>
                </template>
                <p v-if="ch.subject" class="mb-2 font-semibold">{{ ch.subject }}</p>
                <pre class="whitespace-pre-wrap rounded bg-surface-50 p-3 text-sm dark:bg-surface-700">{{ ch.bodyMarkdown }}</pre>
                <div v-if="ch.ctaLabel" class="mt-2 text-sm">
                  CTA: {{ ch.ctaLabel }}
                  <a v-if="ch.ctaUrl" :href="ch.ctaUrl" target="_blank" rel="noopener" class="ml-2 text-primary hover:underline">{{ ch.ctaUrl }}</a>
                </div>
              </SectionCard>
            </div>
          </TabPanel>

          <!-- Audience -->
          <TabPanel value="audience">
            <div v-if="campaign.audienceSegments.length === 0" class="py-6 text-center text-surface-500">
              {{ t('advertising.pages.advertiser_campaign_detail.no_segments') }}
            </div>
            <SectionCard v-else>
              <ul class="space-y-2">
                <li
                  v-for="seg in campaign.audienceSegments"
                  :key="seg.id"
                  class="flex items-center gap-2 text-sm"
                >
                  <Tag
                    :value="t(`advertising.inclusion_mode.${seg.inclusionMode.toLowerCase()}`)"
                    :severity="seg.inclusionMode === 'INCLUDE' ? 'success' : 'danger'"
                  />
                  <span class="font-medium">{{ t(`advertising.segment_type.${seg.segmentType.toLowerCase()}`) }}</span>
                  <code class="rounded bg-surface-100 px-2 py-0.5 text-xs dark:bg-surface-700">{{ JSON.stringify(seg.segmentValue) }}</code>
                </li>
              </ul>
            </SectionCard>
          </TabPanel>

          <!-- Moderation -->
          <TabPanel value="moderation">
            <div class="py-6 text-center text-surface-500">
              {{ t('advertising.advertiser_crud.detail_empty.moderation_logs') }}
            </div>
          </TabPanel>
        </TabPanels>
      </Tabs>
    </div>
  </div>
</template>
