<script setup lang="ts">
/**
 * F22.1 / F03.11: 札主の応募者管理（成立＝CONFIRM）パネル。
 *
 * 札主（募集主）が応募者一覧を見て成立（APPLIED / WAITLISTED → CONFIRMED）させる。
 * 謝礼の支払者は応募者本人であり、clientSecret も応募者本人にだけ返るため、
 * カード与信の確認導線は RecruitmentApplicationButton 側に置く。
 *
 * BE 配線（recon 済み・実在 EP）:
 *   - GET   /api/v1/recruitment-listings/{listingId}/participants
 *   - POST  /api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm
 */
import Button from 'primevue/button'
import Tag from 'primevue/tag'
import type { RecruitmentParticipantResponse } from '~/types/recruitment'

interface Props {
  listingId: number
}

const props = defineProps<Props>()

const { t } = useI18n()
const recruitmentApi = useRecruitmentApi()
const { success, error } = useNotification()

const participants = ref<RecruitmentParticipantResponse[]>([])
const loading = ref(false)
const confirmingId = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await recruitmentApi.listListingParticipants(props.listingId)
    participants.value = res.data
  } catch (e) {
    error(String(e))
  } finally {
    loading.value = false
  }
}

function statusLabel(s: string): string {
  return t(`recruitment.participantStatus.${s.toLowerCase()}`)
}

function onConfirmClick(p: RecruitmentParticipantResponse) {
  void doConfirm(p.id)
}

async function doConfirm(participantId: number) {
  if (confirmingId.value != null) {
    return
  }
  confirmingId.value = participantId
  try {
    await recruitmentApi.confirmApplication(props.listingId, participantId)
    success(t('recruitment.action.confirmApplication'))
    await load()
  } catch (e) {
    error(String(e))
  } finally {
    confirmingId.value = null
  }
}

onMounted(load)
</script>

<template>
  <section class="flex flex-col gap-3">
    <h2 class="text-lg font-semibold">
      {{ t('recruitment.label.participants') }}
    </h2>

    <div v-if="loading" class="flex justify-center p-6">
      <LoadingBounce />
    </div>

    <div
      v-else-if="participants.length === 0"
      class="rounded border border-dashed p-6 text-center text-gray-500"
    >
      {{ t('recruitment.label.noParticipants') }}
    </div>

    <div v-else class="flex flex-col gap-2">
      <div
        v-for="p in participants"
        :key="p.id"
        class="flex items-center justify-between rounded border border-gray-200 p-3"
      >
        <div class="flex flex-col">
          <div class="text-sm text-gray-500">
            #{{ p.id }}
          </div>
          <div class="mt-1">
            <Tag :value="statusLabel(p.status)" />
          </div>
        </div>
        <div class="flex gap-2">
          <Button
            v-if="p.status === 'APPLIED' || p.status === 'WAITLISTED'"
            :label="t('recruitment.action.confirmApplication')"
            icon="pi pi-check"
            size="small"
            :loading="confirmingId === p.id"
            @click="onConfirmClick(p)"
          />
        </div>
      </div>
    </div>
  </section>
</template>
