<script setup lang="ts">
/**
 * 村寄合詳細 + 投票 Dialog — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/meetups.vue) から寄合詳細・権限フラグを受け取り、
 * 候補日リスト・投票ボタン・幹事用の確定 / キャンセルボタンを描画する。
 *
 * - ロジックは持たない（API 呼び出しは親が担う）
 * - 操作は emit で親に通知する
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'

import type {
  VillageMeetupCandidateDateResponse,
  VillageMeetupResponse,
  VillageMeetupStatus,
  VillageMeetupVoteType,
} from '~/types/village'

defineProps<{
  visible: boolean
  detailMeetup: VillageMeetupResponse | null
  isVillager: boolean
  isDetailOrganizer: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  castVote: [candidate: VillageMeetupCandidateDateResponse, voteType: VillageMeetupVoteType]
  confirmCandidate: [candidate: VillageMeetupCandidateDateResponse]
  cancelMeetup: []
}>()

const { t } = useI18n()

function severityForStatus(
  status: VillageMeetupStatus,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'DRAFT':
      return 'secondary'
    case 'OPEN':
      return 'success'
    case 'CONFIRMED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
    case 'CLOSED':
      return 'secondary'
  }
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="detailMeetup?.title ?? ''"
    :style="{ width: '42rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div v-if="detailMeetup" class="flex flex-col gap-3">
      <div class="flex items-center gap-2 flex-wrap">
        <Badge
          :value="t(`village.meetup.status.${detailMeetup.status}`)"
          :severity="severityForStatus(detailMeetup.status)"
        />
        <span class="text-xs text-surface-500">
          <i class="pi pi-users mr-1" />{{ detailMeetup.participantCount }} {{ t('village.meetup.participantCount') }}
        </span>
      </div>
      <p v-if="detailMeetup.description" class="whitespace-pre-wrap text-sm">
        {{ detailMeetup.description }}
      </p>
      <div v-if="detailMeetup.venue" class="text-sm">
        <strong>{{ t('village.meetup.venue') }}:</strong> {{ detailMeetup.venue }}
      </div>

      <!-- 候補日一覧 + 投票 -->
      <div class="mt-2">
        <h3 class="font-semibold mb-2">
          {{ t('village.meetup.candidateDates') }}
        </h3>
        <div class="flex flex-col gap-2">
          <div
            v-for="c in detailMeetup.candidateDates"
            :key="c.id"
            class="rounded border p-3 text-sm"
            :class="c.isConfirmed
              ? 'border-primary bg-primary-50 dark:bg-primary-950'
              : 'border-surface-200 dark:border-surface-700'"
          >
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <div class="flex items-center gap-2">
                <i class="pi pi-calendar" />
                <span>{{ c.candidateDate }}</span>
                <span v-if="c.candidateTimeStart">{{ c.candidateTimeStart }}</span>
                <span v-if="c.candidateTimeEnd"> - {{ c.candidateTimeEnd }}</span>
                <Badge
                  v-if="c.isConfirmed"
                  :value="t('village.meetup.confirmedDate')"
                  severity="info"
                />
              </div>
              <div class="flex items-center gap-2 text-xs text-surface-500">
                <span>
                  <i class="pi pi-check text-green-500" /> {{ c.voteCountYes }}
                </span>
                <span>
                  <i class="pi pi-question text-yellow-500" /> {{ c.voteCountMaybe }}
                </span>
                <span>
                  <i class="pi pi-times text-red-500" /> {{ c.voteCountNo }}
                </span>
              </div>
            </div>
            <div
              v-if="isVillager && detailMeetup.status === 'OPEN'"
              class="flex items-center gap-2 mt-2"
            >
              <Button
                :label="t('village.meetup.voteType.YES')"
                size="small"
                severity="success"
                outlined
                @click="emit('castVote', c, 'YES')"
              />
              <Button
                :label="t('village.meetup.voteType.MAYBE')"
                size="small"
                severity="warn"
                outlined
                @click="emit('castVote', c, 'MAYBE')"
              />
              <Button
                :label="t('village.meetup.voteType.NO')"
                size="small"
                severity="danger"
                outlined
                @click="emit('castVote', c, 'NO')"
              />
              <Button
                v-if="isDetailOrganizer"
                :label="t('village.meetup.confirm')"
                icon="pi pi-check"
                size="small"
                severity="primary"
                class="ml-auto"
                @click="emit('confirmCandidate', c)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <Button
        v-if="
          detailMeetup
            && detailMeetup.status !== 'CANCELLED'
            && detailMeetup.status !== 'CLOSED'
            && isDetailOrganizer
        "
        :label="t('village.meetup.cancel')"
        icon="pi pi-times"
        severity="danger"
        outlined
        @click="emit('cancelMeetup')"
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
