<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type {
  RecruitmentListingResponse,
  RecruitmentParticipantResponse,
} from '~/types/recruitment'

const route = useRoute()
const { t } = useI18n()
const api = useRecruitmentApi()
const { success, error } = useNotification()
const authStore = useAuthStore()

const listingId = computed(() => Number(route.params.id))
const listing = ref<RecruitmentListingResponse | null>(null)
const loading = ref(false)
const myParticipation = ref<RecruitmentParticipantResponse | null>(null)
const cancelling = ref(false)

/**
 * 自分が札主（募集主）か。USER scope の作成者本人を判定する。
 * TEAM/ORG scope の ADMIN 委任は BE 認可で担保され、応募者管理 EP が 403/404 を返す。
 */
const isOwner = computed(
  () => !!listing.value
    && authStore.currentUser?.id != null
    && listing.value.createdBy === authStore.currentUser.id,
)

async function load() {
  loading.value = true
  try {
    const result = await api.getListing(listingId.value)
    listing.value = result.data
    // 自分の参加状態を確認
    try {
      const myList = await api.listMyActiveParticipations()
      const mine = myList.data.find((p) => p.listingId === listingId.value)
      myParticipation.value = mine ?? null
    }
    catch {
      myParticipation.value = null
    }
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

async function onPublish() {
  if (!listing.value) return
  loading.value = true
  try {
    const result = await api.publishListing(listingId.value)
    listing.value = result.data
    success(t('recruitment.action.publish'))
  }
  catch (e) {
    error(String(e))
  }
  finally {
    loading.value = false
  }
}

async function onCancel() {
  if (!listing.value || !window.confirm(t('recruitment.confirmModal.cancel.message'))) return
  cancelling.value = true
  try {
    const result = listing.value.scopeType === 'PERSONAL'
      ? await api.cancelMyMarketListing(listingId.value, { reason: t('recruitment.action.cancelListing') })
      : await api.cancelListing(listingId.value, { reason: t('recruitment.action.cancelListing') })
    listing.value = result.data
    success(t('recruitment.action.cancelledListing'))
  }
  catch (e) {
    error(String(e))
  }
  finally {
    cancelling.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div v-if="loading && !listing" class="flex justify-center p-8">
    <LoadingBounce />
  </div>

  <div v-else-if="listing" class="container mx-auto max-w-3xl p-4">
    <div class="mb-4">
      <h1 class="text-2xl font-bold">
        {{ listing.title }}
      </h1>
      <div class="mt-1 flex gap-2">
        <Tag :value="t(`recruitment.status.${listing.status.toLowerCase()}`)" />
        <Tag :value="t(`recruitment.participationType.${listing.participationType.toLowerCase()}`)" severity="info" />
      </div>
    </div>

    <div v-if="listing.description" class="mb-4 whitespace-pre-wrap text-gray-700">
      {{ listing.description }}
    </div>

    <div class="mb-4 grid grid-cols-2 gap-3 rounded border border-gray-200 p-4">
      <div>
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.startAt') }}
        </div>
        <div>{{ listing.startAt }}</div>
      </div>
      <div>
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.endAt') }}
        </div>
        <div>{{ listing.endAt }}</div>
      </div>
      <div>
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.applicationDeadline') }}
        </div>
        <div>{{ listing.applicationDeadline }}</div>
      </div>
      <div>
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.capacity') }}
        </div>
        <div>{{ listing.confirmedCount }} / {{ listing.capacity }}</div>
      </div>
      <div v-if="listing.location" class="col-span-2">
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.location') }}
        </div>
        <div>{{ listing.location }}</div>
      </div>
      <div v-if="listing.paymentEnabled && listing.price != null" class="col-span-2">
        <div class="text-xs text-gray-500">
          {{ t('recruitment.field.price') }}
        </div>
        <div class="text-lg font-semibold">
          ¥{{ listing.price.toLocaleString() }}
        </div>
      </div>
    </div>

    <div class="flex gap-2">
      <Button
        v-if="listing.status === 'DRAFT'"
        :label="t('recruitment.action.publish')"
        icon="pi pi-send"
        @click="onPublish"
      />
      <Button
        v-if="isOwner && !['CANCELLED', 'AUTO_CANCELLED', 'COMPLETED'].includes(listing.status)"
        :label="t('recruitment.action.cancelListing')"
        icon="pi pi-times"
        severity="danger"
        outlined
        :loading="cancelling"
        data-testid="recruitment-listing-cancel"
        @click="onCancel"
      />
      <RecruitmentApplicationButton
        :listing="listing"
        :my-participant-id="myParticipation?.id"
        :my-participant-status="myParticipation?.status"
        @applied="load"
        @cancelled="load"
      />
    </div>

    <!-- 札主の応募者管理。謝礼の決済確認は支払者本人の申込導線に置く。 -->
    <RecruitmentParticipantManager
      v-if="isOwner"
      class="mt-6 border-t border-gray-200 pt-6"
      :listing-id="listingId"
    />
  </div>
</template>
