<script setup lang="ts">
import type { EventRsvpSummary, RsvpResponse } from '~/types/event'

const props = defineProps<{
  eventId: number
  scopeType: 'team' | 'organization'
  scopeId: number
  summary: EventRsvpSummary | null
  myResponse: RsvpResponse | null
}>()

const emit = defineEmits<{
  responded: []
}>()

const { t } = useI18n()
const rsvpApi = useEventRsvpApi()
const notification = useNotification()

const selectedResponse = ref<RsvpResponse | null>(props.myResponse)
const comment = ref('')
const submitting = ref(false)

watch(
  () => props.myResponse,
  (val) => {
    selectedResponse.value = val
  },
)

const responseOptions: { label: string; value: RsvpResponse; severity: string; icon: string }[] = [
  {
    label: t('event.rsvp.attending'),
    value: 'ATTENDING',
    severity: 'success',
    icon: 'pi pi-check',
  },
  {
    label: t('event.rsvp.notAttending'),
    value: 'NOT_ATTENDING',
    severity: 'danger',
    icon: 'pi pi-times',
  },
  {
    label: t('event.rsvp.maybe'),
    value: 'MAYBE',
    severity: 'warn',
    icon: 'pi pi-question',
  },
]

async function submit() {
  if (!selectedResponse.value) return
  submitting.value = true
  try {
    if (props.myResponse) {
      await rsvpApi.updateRsvp(props.scopeType, props.scopeId, props.eventId, {
        response: selectedResponse.value,
        comment: comment.value || undefined,
      })
      notification.success(t('event.rsvp.update'))
    } else {
      await rsvpApi.submitRsvp(props.scopeType, props.scopeId, props.eventId, {
        response: selectedResponse.value,
        comment: comment.value || undefined,
      })
      notification.success(t('event.rsvp.submit'))
    }
    emit('responded')
  } catch {
    notification.error('回答の送信に失敗しました')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="rounded-xl border border-surface-200 bg-surface-0 p-4 dark:border-surface-700 dark:bg-surface-900">
    <h3 class="mb-3 text-base font-semibold">{{ $t('event.rsvp.title') }}</h3>

    <!-- サマリー -->
    <div v-if="summary" class="mb-4 grid grid-cols-4 gap-2">
      <div class="rounded-lg bg-green-50 p-2 text-center dark:bg-green-900/20">
        <p class="text-lg font-bold text-green-600">{{ summary.attending }}</p>
        <p class="text-xs text-surface-500">{{ $t('event.rsvp.summary.attending') }}</p>
      </div>
      <div class="rounded-lg bg-red-50 p-2 text-center dark:bg-red-900/20">
        <p class="text-lg font-bold text-red-600">{{ summary.notAttending }}</p>
        <p class="text-xs text-surface-500">{{ $t('event.rsvp.summary.notAttending') }}</p>
      </div>
      <div class="rounded-lg bg-yellow-50 p-2 text-center dark:bg-yellow-900/20">
        <p class="text-lg font-bold text-yellow-600">{{ summary.maybe }}</p>
        <p class="text-xs text-surface-500">{{ $t('event.rsvp.summary.maybe') }}</p>
      </div>
      <div class="rounded-lg bg-surface-100 p-2 text-center dark:bg-surface-800">
        <p class="text-lg font-bold">{{ summary.undecided }}</p>
        <p class="text-xs text-surface-500">{{ $t('event.rsvp.summary.undecided') }}</p>
      </div>
    </div>

    <!-- 回答フォーム -->
    <div class="mb-3">
      <p class="mb-2 text-sm font-medium">{{ $t('event.rsvp.myResponse') }}</p>
      <div class="flex flex-wrap gap-2">
        <Button
          v-for="opt in responseOptions"
          :key="opt.value"
          :label="opt.label"
          :icon="opt.icon"
          :severity="selectedResponse === opt.value ? opt.severity : 'secondary'"
          :outlined="selectedResponse !== opt.value"
          size="small"
          @click="selectedResponse = opt.value"
        />
      </div>
    </div>

    <div v-if="selectedResponse" class="mb-3">
      <label class="mb-1 block text-sm">{{ $t('event.rsvp.comment') }}</label>
      <Textarea v-model="comment" rows="2" class="w-full" />
    </div>

    <Button
      v-if="selectedResponse"
      :label="myResponse ? $t('event.rsvp.update') : $t('event.rsvp.submit')"
      icon="pi pi-send"
      size="small"
      :loading="submitting"
      @click="submit"
    />
  </div>
</template>
