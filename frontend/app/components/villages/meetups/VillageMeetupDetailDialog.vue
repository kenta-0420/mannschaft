<script setup lang="ts">
/**
 * 村寄合詳細 + 投票 Dialog — 表示専用の子コンポーネント。
 *
 * 親 (pages/villages/[id]/meetups.vue) から寄合詳細・投票集計・権限フラグを受け取り、
 * 候補日リスト・投票ボタン・幹事用の確定 / キャンセルボタンを描画する。
 *
 * - ロジックは持たない（API 呼び出しは親が担う）
 * - 操作は emit で親に通知する
 *
 * BE 契約に関する注意（MeetupCandidateDateResponse は `{id, meetupId, candidateDate, sortOrder}` のみ）:
 * - 票数は候補日 DTO に含まれない。投票集計 API (`GET /meetups/{id}/votes`) の結果を
 *   `voteSummary` prop で受け取り、candidateDateId で突き合わせて表示する。
 * - 候補日に `isConfirmed` フラグは存在しない。`MeetupResponse.confirmedDate`（LocalDate）と
 *   候補日の `candidateDate` を突き合わせて導出する。
 * - 候補日に時刻は存在しない（日付のみ）。
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'

import type {
  VillageMeetupCandidateDateResponse,
  VillageMeetupResponse,
  VillageMeetupStatus,
  VillageMeetupVoteSummary,
  VillageMeetupVoteSummaryCandidate,
  VillageMeetupVoteType,
} from '~/types/village'

const props = defineProps<{
  visible: boolean
  detailMeetup: VillageMeetupResponse | null
  voteSummary: VillageMeetupVoteSummary | null
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
): 'success' | 'info' | 'danger' {
  switch (status) {
    case 'PLANNING':
      return 'success'
    case 'CONFIRMED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
  }
}

/** candidateDateId → 集計値。投票集計 API の結果を候補日に突き合わせるための索引。 */
const summaryByCandidateId = computed<Map<string, VillageMeetupVoteSummaryCandidate>>(() => {
  const map = new Map<string, VillageMeetupVoteSummaryCandidate>()
  for (const c of props.voteSummary?.candidates ?? []) {
    map.set(c.candidateDateId, c)
  }
  return map
})

function summaryFor(candidateDateId: string): VillageMeetupVoteSummaryCandidate | null {
  return summaryByCandidateId.value.get(candidateDateId) ?? null
}

/**
 * 確定済み候補日か。BE に候補日単位の `isConfirmed` は無いため、
 * 寄合の `confirmedDate` と候補日の日付が一致するかで導出する。
 */
function isConfirmedCandidate(candidate: VillageMeetupCandidateDateResponse): boolean {
  const confirmed = props.detailMeetup?.confirmedDate
  return !!confirmed && confirmed === candidate.candidateDate
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
      </div>
      <p v-if="detailMeetup.description" class="whitespace-pre-wrap text-sm">
        {{ detailMeetup.description }}
      </p>
      <div v-if="detailMeetup.location" class="text-sm">
        <strong>{{ t('village.meetup.location') }}:</strong> {{ detailMeetup.location }}
      </div>

      <!-- 候補日一覧 + 投票 -->
      <div class="mt-2">
        <h3 class="font-semibold mb-2">
          {{ t('village.meetup.candidateDates') }}
        </h3>
        <div class="flex flex-col gap-2">
          <div
            v-for="c in detailMeetup.candidateDates ?? []"
            :key="c.id"
            class="rounded border p-3 text-sm"
            :class="isConfirmedCandidate(c)
              ? 'border-primary bg-primary-50 dark:bg-primary-950'
              : 'border-surface-200 dark:border-surface-700'"
          >
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <div class="flex items-center gap-2">
                <i class="pi pi-calendar" />
                <span>{{ c.candidateDate }}</span>
                <Badge
                  v-if="isConfirmedCandidate(c)"
                  :value="t('village.meetup.confirmedDate')"
                  severity="info"
                />
              </div>
              <div class="flex items-center gap-2 text-xs text-surface-500">
                <span>
                  <i class="pi pi-check text-green-500" />
                  {{ summaryFor(c.id)?.availableCount ?? 0 }}
                </span>
                <span>
                  <i class="pi pi-question text-yellow-500" />
                  {{ summaryFor(c.id)?.maybeCount ?? 0 }}
                </span>
                <span>
                  <i class="pi pi-times text-red-500" />
                  {{ summaryFor(c.id)?.unavailableCount ?? 0 }}
                </span>
              </div>
            </div>
            <div
              v-if="isVillager && detailMeetup.status === 'PLANNING'"
              class="flex items-center gap-2 mt-2"
            >
              <Button
                :label="t('village.meetup.voteType.AVAILABLE')"
                size="small"
                severity="success"
                outlined
                @click="emit('castVote', c, 'AVAILABLE')"
              />
              <Button
                :label="t('village.meetup.voteType.MAYBE')"
                size="small"
                severity="warn"
                outlined
                @click="emit('castVote', c, 'MAYBE')"
              />
              <Button
                :label="t('village.meetup.voteType.UNAVAILABLE')"
                size="small"
                severity="danger"
                outlined
                @click="emit('castVote', c, 'UNAVAILABLE')"
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
