<script setup lang="ts">
/**
 * 村お祭り詳細 Dialog — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/festivals.vue) からお祭り詳細・管理権限を受け取り、
 * バナー / バッジ / 説明文と編集・中止ボタンを描画する。
 *
 * - ロジックは持たない（操作は emit）
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'

import type {
  VillageFestivalLivePostResponse,
  VillageFestivalResponse,
  VillageFestivalRsvpResponse,
  VillageFestivalRsvpStatus,
  VillageFestivalStatus,
} from '~/types/village'

const props = defineProps<{
  visible: boolean
  festival: VillageFestivalResponse | null
  canManage: boolean
  isVillager: boolean
  // F17.2 Wave2 ③お祭りの参加レイヤー（RSVP・実況）
  rsvps: VillageFestivalRsvpResponse[]
  myRsvpStatus: VillageFestivalRsvpStatus | null
  myRsvpRoleLabel: string | null
  rsvpsLoading: boolean
  rsvpsHasMore: boolean
  rsvpsLoadingMore: boolean
  livePosts: VillageFestivalLivePostResponse[]
  livePostsLoading: boolean
  livePostPosting: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  edit: [f: VillageFestivalResponse]
  cancelFestival: [f: VillageFestivalResponse]
  respondRsvp: [status: VillageFestivalRsvpStatus, roleLabel: string | null]
  cancelRsvp: []
  loadMoreRsvps: []
  submitLivePost: [content: string]
}>()

const { t } = useI18n()

function severityForStatus(status: VillageFestivalStatus): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'SCHEDULED':
      return 'info'
    case 'ENDED':
      return 'secondary'
    case 'CANCELLED':
      return 'danger'
  }
}

// =====================================================================
// F17.2 Wave2 ③お祭りの参加レイヤー — 状態別ゲート（§5.6）
// =====================================================================

/** RSVP 回答セクションを表示するか（SCHEDULED/ACTIVE/ENDED は閲覧可・CANCELLED は非表示） */
const showRsvp = computed(() => !!props.festival && props.festival.status !== 'CANCELLED')

/** RSVP を書き込めるか（SCHEDULED/ACTIVE のみ・§5.6） */
const canRespondRsvp = computed(() =>
  props.isVillager
  && (props.festival?.status === 'SCHEDULED' || props.festival?.status === 'ACTIVE'),
)

/** 実況セクションを表示するか（ACTIVE 中のみ・§5.4） */
const showLive = computed(() => props.festival?.status === 'ACTIVE')
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="festival?.title ?? ''"
    :style="{ width: '38rem', maxHeight: '90vh' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="festival" class="flex flex-col gap-3 max-h-[70vh] overflow-y-auto pr-1">
      <div
        v-if="festival.bannerUrl"
        class="h-40 bg-surface-100 dark:bg-surface-800 overflow-hidden rounded"
      >
        <img
          :src="festival.bannerUrl"
          :alt="festival.title"
          class="w-full h-full object-cover"
        >
      </div>
      <div class="flex items-center gap-2">
        <Badge
          :value="t(`village.festival.status.${festival.status}`)"
          :severity="severityForStatus(festival.status)"
        />
        <span class="text-sm text-surface-500">
          {{ festival.startsAt }} 〜 {{ festival.endsAt }}
        </span>
      </div>
      <p v-if="festival.description" class="whitespace-pre-wrap text-sm">
        {{ festival.description }}
      </p>

      <!-- F17.2 Wave2 ③お祭りの参加レイヤー（RSVP・実況・§5） -->
      <template v-if="showRsvp">
        <hr class="border-surface-200 dark:border-surface-700">
        <VillageFestivalRsvpSection
          :rsvps="rsvps"
          :my-status="myRsvpStatus"
          :my-role-label="myRsvpRoleLabel"
          :can-respond="canRespondRsvp"
          :loading="rsvpsLoading"
          :has-more="rsvpsHasMore"
          :loading-more="rsvpsLoadingMore"
          @respond="(status, roleLabel) => emit('respondRsvp', status, roleLabel)"
          @cancel-rsvp="emit('cancelRsvp')"
          @load-more="emit('loadMoreRsvps')"
        />
      </template>

      <template v-if="showLive">
        <hr class="border-surface-200 dark:border-surface-700">
        <VillageFestivalLiveSection
          :live-posts="livePosts"
          :loading="livePostsLoading"
          :can-post="isVillager"
          :posting="livePostPosting"
          @submit="(content) => emit('submitLivePost', content)"
        />
      </template>
    </div>
    <template #footer>
      <Button
        v-if="canManage && festival"
        :label="t('village.festival.edit')"
        icon="pi pi-pencil"
        severity="secondary"
        outlined
        @click="emit('edit', festival)"
      />
      <Button
        v-if="canManage && festival && festival.status !== 'CANCELLED' && festival.status !== 'ENDED'"
        :label="t('village.festival.cancel')"
        icon="pi pi-times"
        severity="danger"
        outlined
        @click="emit('cancelFestival', festival)"
      />
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="emit('update:visible', false)"
      />
    </template>
  </Dialog>
</template>
