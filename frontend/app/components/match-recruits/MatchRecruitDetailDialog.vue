<script setup lang="ts">
/**
 * F17.1 村機能 — 練習試合・募集タブ 詳細 Dialog
 *
 * 募集の詳細情報と応募一覧（投稿者 / HEADMAN / ELDER のみ可視）を表示。
 * 応募・受諾・却下・取り下げ・募集締切のアクションは emit で親に通知し、
 * 親側 (villages/[id]/match-recruits.vue) で API を呼ぶ。
 */
import type {
  VillageMatchApplicationResponse,
  VillageMatchApplicationReviewRequest,
  VillageMatchRecruitResponse,
  VillageMatchRecruitStatus,
} from '~/types/village'

const props = defineProps<{
  visible: boolean
  recruit: VillageMatchRecruitResponse | null
  applications: VillageMatchApplicationResponse[]
  canSeeApplications: boolean
  isVillager: boolean
  isDetailOwner: boolean
  canManage: boolean
  currentUserId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'apply' | 'close-recruit'): void
  // 審査結果は BE の契約値（ACCEPTED / REJECTED）をそのまま流す。
  // FE 独自の accept / reject という語彙を持ち込まない。
  (
    e: 'review-app',
    app: VillageMatchApplicationResponse,
    status: VillageMatchApplicationReviewRequest['status'],
  ): void
  (e: 'withdraw-app', app: VillageMatchApplicationResponse): void
}>()

const { t } = useI18n()

const visibleModel = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value),
})

function severityForStatus(
  status: VillageMatchRecruitStatus,
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'OPEN':
      return 'success'
    case 'CLOSED':
      return 'secondary'
    case 'FULFILLED':
      return 'info'
    case 'CANCELLED':
      return 'danger'
  }
}

function severityForAppStatus(
  status: VillageMatchApplicationResponse['status'],
): 'success' | 'info' | 'warn' | 'danger' | 'secondary' {
  switch (status) {
    case 'PENDING':
      return 'warn'
    case 'ACCEPTED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'WITHDRAWN':
      return 'secondary'
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visibleModel"
    modal
    :draggable="false"
    :header="recruit?.title ?? ''"
    :style="{ width: '40rem' }"
    :breakpoints="{ '640px': '92vw' }"
  >
    <div v-if="recruit" class="flex flex-col gap-3">
      <div class="flex items-center gap-2 flex-wrap">
        <Badge
          :value="t(`village.matchRecruit.category.${recruit.category}`)"
          severity="secondary"
        />
        <Badge
          :value="t(`village.matchRecruit.status.${recruit.status}`)"
          :severity="severityForStatus(recruit.status)"
        />
      </div>
      <p v-if="recruit.description" class="whitespace-pre-wrap text-sm">
        {{ recruit.description }}
      </p>
      <div class="grid grid-cols-2 gap-2 text-sm">
        <div v-if="recruit.matchDate">
          <strong>{{ t('village.matchRecruit.matchDate') }}:</strong>
          {{ recruit.matchDate }}
          <span v-if="recruit.matchTimeStart">{{ recruit.matchTimeStart }}</span>
          <span v-if="recruit.matchTimeEnd"> - {{ recruit.matchTimeEnd }}</span>
        </div>
        <div v-if="recruit.venue">
          <strong>{{ t('village.matchRecruit.venue') }}:</strong>
          {{ recruit.venue }}
        </div>
        <div v-if="recruit.requiredCount">
          <strong>{{ t('village.matchRecruit.requiredCount') }}:</strong>
          {{ recruit.requiredCount }}
        </div>
        <div v-if="recruit.contactMethod">
          <strong>{{ t('village.matchRecruit.contactMethod') }}:</strong>
          {{ recruit.contactMethod }}
        </div>
        <div v-if="recruit.applicationDeadline">
          <strong>{{ t('village.matchRecruit.deadline') }}:</strong>
          {{ recruit.applicationDeadline }}
        </div>
      </div>

      <!-- 応募一覧（投稿者 / HEADMAN / ELDER のみ） -->
      <div v-if="canSeeApplications" class="mt-2">
        <h3 class="font-semibold mb-2">
          {{ t('village.matchRecruit.applications') }}
        </h3>
        <DashboardEmptyState
          v-if="applications.length === 0"
          icon="pi pi-inbox"
          :message="t('village.matchRecruit.noApplications')"
        />
        <div v-else class="flex flex-col gap-2">
          <div
            v-for="app in applications"
            :key="app.id"
            class="rounded border border-surface-200 dark:border-surface-700 p-3 text-sm"
          >
            <div class="flex items-center justify-between gap-2 flex-wrap">
              <span>
                {{ t('village.matchRecruit.applicantUser') }} #{{ app.applicantUserId }}
                <span v-if="app.applicantTeamId"> (team #{{ app.applicantTeamId }})</span>
              </span>
              <Badge
                :value="t(`village.matchApplication.status.${app.status}`)"
                :severity="severityForAppStatus(app.status)"
              />
            </div>
            <p v-if="app.message" class="text-xs text-surface-500 mt-1 whitespace-pre-wrap">
              {{ app.message }}
            </p>
            <div
              v-if="app.status === 'PENDING'"
              class="flex items-center gap-2 mt-2"
            >
              <Button
                :label="t('village.matchApplication.accept')"
                size="small"
                severity="success"
                @click="emit('review-app', app, 'ACCEPTED')"
              />
              <Button
                :label="t('village.matchApplication.reject')"
                size="small"
                severity="danger"
                outlined
                @click="emit('review-app', app, 'REJECTED')"
              />
              <Button
                v-if="currentUserId === app.applicantUserId"
                :label="t('village.matchApplication.withdraw')"
                size="small"
                severity="secondary"
                text
                @click="emit('withdraw-app', app)"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <Button
        v-if="
          recruit
            && recruit.status === 'OPEN'
            && isVillager
        "
        :label="t('village.matchRecruit.apply')"
        icon="pi pi-send"
        severity="primary"
        @click="emit('apply')"
      />
      <Button
        v-if="
          recruit
            && recruit.status === 'OPEN'
            && (isDetailOwner || canManage)
        "
        :label="t('village.matchRecruit.close')"
        icon="pi pi-times"
        severity="secondary"
        outlined
        @click="emit('close-recruit')"
      />
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="visibleModel = false"
      />
    </template>
  </Dialog>
</template>
