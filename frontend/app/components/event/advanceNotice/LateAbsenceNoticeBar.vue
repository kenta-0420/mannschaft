<script setup lang="ts">
import type { CareLinkResponse } from '~/types/careLink'
import type { RsvpResponse } from '~/types/event'
import LateNoticeDialog from './LateNoticeDialog.vue'
import AbsenceNoticeDialog from './AbsenceNoticeDialog.vue'

/**
 * F03.12 §15 事前遅刻・欠席連絡の起点バー。
 *
 * <p>RsvpWidget の直下に配置し、本人および見守り者（保護者）が
 * 「遅刻連絡」「欠席連絡」を起動できるエントリポイントを提供する。</p>
 *
 * <p>表示条件（{@link currentUserRsvpStatus} 別）:</p>
 * <ul>
 *   <li>{@code ATTENDING} - 「遅刻連絡」「欠席連絡」両方表示</li>
 *   <li>{@code MAYBE} / {@code UNDECIDED} / null - 「欠席連絡」のみ表示</li>
 *   <li>{@code NOT_ATTENDING} - バー全体を非表示</li>
 * </ul>
 *
 * <p>ケア対象者ドロップダウン: 自分 + {@code useCareLinkApi().getMyRecipients()}
 * （見守りしているケア対象者一覧）から選ぶ。ケア対象がいない場合は「自分」固定で
 * ドロップダウン自体を非表示にする。</p>
 */

const props = defineProps<{
  teamId: number
  eventId: number
  /** 自分の現在の RSVP ステータス（未回答時 null）。 */
  currentUserRsvpStatus?: RsvpResponse | null
}>()

const { t } = useI18n()
const authStore = useAuthStore()
const careLinkApi = useCareLinkApi()

const recipients = ref<CareLinkResponse[]>([])
const selectedUserId = ref<number | null>(authStore.user?.id ?? null)
const lateOpen = ref(false)
const absenceOpen = ref(false)

interface SubjectOption {
  label: string
  value: number
}

const subjectOptions = computed<SubjectOption[]>(() => {
  const list: SubjectOption[] = []
  if (authStore.user) {
    list.push({
      label: t('event.advanceNotice.subjectSelf'),
      value: authStore.user.id,
    })
  }
  for (const link of recipients.value) {
    if (link.status !== 'ACTIVE') continue
    list.push({
      label: link.careRecipientDisplayName,
      value: link.careRecipientUserId,
    })
  }
  return list
})

const showSubjectSelect = computed(() => subjectOptions.value.length > 1)

const showLateButton = computed(
  () => props.currentUserRsvpStatus === 'ATTENDING',
)
const showAbsenceButton = computed(() => props.currentUserRsvpStatus !== 'NOT_ATTENDING')
const isHidden = computed(() => props.currentUserRsvpStatus === 'NOT_ATTENDING')

async function loadRecipients() {
  try {
    const res = await careLinkApi.getMyRecipients()
    recipients.value = res.data ?? []
  } catch {
    recipients.value = []
  }
}

onMounted(() => {
  loadRecipients()
})

function openLate() {
  if (selectedUserId.value == null) return
  lateOpen.value = true
}

function openAbsence() {
  if (selectedUserId.value == null) return
  absenceOpen.value = true
}
</script>

<template>
  <div
    v-if="!isHidden"
    class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900"
    data-testid="late-absence-notice-bar"
  >
    <h3 class="mb-2 text-sm font-semibold">
      {{ t('event.advanceNotice.barTitle') }}
    </h3>

    <!-- ケア対象者選択（自分以外がいる場合のみ表示） -->
    <div v-if="showSubjectSelect" class="mb-3">
      <label
        for="advance-notice-subject"
        class="mb-1 block text-xs text-surface-500"
      >{{ t('event.advanceNotice.subjectLabel') }}</label>
      <Select
        v-model="selectedUserId"
        input-id="advance-notice-subject"
        :options="subjectOptions"
        option-label="label"
        option-value="value"
        class="w-full"
        data-testid="advance-notice-subject-select"
      />
    </div>

    <div class="flex flex-wrap gap-2">
      <Button
        v-if="showLateButton"
        :label="t('event.advanceNotice.lateNoticeButton')"
        icon="pi pi-clock"
        severity="warn"
        size="small"
        :disabled="selectedUserId == null"
        data-testid="late-notice-open-button"
        @click="openLate"
      />
      <Button
        v-if="showAbsenceButton"
        :label="t('event.advanceNotice.absenceNoticeButton')"
        icon="pi pi-times"
        severity="danger"
        size="small"
        :disabled="selectedUserId == null"
        data-testid="absence-notice-open-button"
        @click="openAbsence"
      />
    </div>

    <!-- ダイアログ -->
    <LateNoticeDialog
      v-if="selectedUserId != null"
      :team-id="teamId"
      :event-id="eventId"
      :user-id="selectedUserId"
      :open="lateOpen"
      @update:open="lateOpen = $event"
    />
    <AbsenceNoticeDialog
      v-if="selectedUserId != null"
      :team-id="teamId"
      :event-id="eventId"
      :user-id="selectedUserId"
      :open="absenceOpen"
      @update:open="absenceOpen = $event"
    />
  </div>
</template>
