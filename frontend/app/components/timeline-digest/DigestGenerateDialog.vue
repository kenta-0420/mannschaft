<script setup lang="ts">
import type { AiQuotaResponse } from '~/types/timeline-digest'

const props = defineProps<{
  visible: boolean
  scopeType: 'ORGANIZATION' | 'TEAM'
  scopeId: number
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'generated'): void
}>()

const { generateDigest } = useTimelineDigestApi()
const notification = useNotification()
const { t } = useI18n()

const submitting = ref(false)
const errors = ref<Record<string, string>>({})

const form = ref({
  periodStart: null as Date | null,
  periodEnd: null as Date | null,
  digestStyle: 'SUMMARY',
  customPromptSuffix: '',
})

const quota = ref<AiQuotaResponse>({
  enabled: true,
  used: 0,
  limit: 30,
  remaining: 30,
})

const styleOptions = computed(() => [
  { label: t('timeline_digest.style_summary'), value: 'SUMMARY' },
  { label: t('timeline_digest.style_narrative'), value: 'NARRATIVE' },
  { label: t('timeline_digest.style_highlights'), value: 'HIGHLIGHTS' },
  { label: t('timeline_digest.style_template'), value: 'TEMPLATE' },
])

const isAiStyle = computed(() =>
  ['SUMMARY', 'NARRATIVE', 'HIGHLIGHTS'].includes(form.value.digestStyle),
)

function formatDateToIso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function validate(): boolean {
  errors.value = {}
  if (!form.value.periodStart) {
    errors.value.periodStart = t('timeline_digest.error_period_start_required')
  }
  if (!form.value.periodEnd) {
    errors.value.periodEnd = t('timeline_digest.error_period_end_required')
  }
  if (
    form.value.periodStart &&
    form.value.periodEnd &&
    form.value.periodEnd < form.value.periodStart
  ) {
    errors.value.periodEnd = t('timeline_digest.error_period_end_invalid')
  }
  if (!form.value.digestStyle) {
    errors.value.digestStyle = t('timeline_digest.error_style_required')
  }
  return Object.keys(errors.value).length === 0
}

async function submit() {
  if (!validate()) return
  submitting.value = true
  try {
    const res = await generateDigest({
      scopeId: props.scopeId,
      scopeType: props.scopeType,
      periodStart: formatDateToIso(form.value.periodStart!),
      periodEnd: formatDateToIso(form.value.periodEnd!),
      digestStyle: form.value.digestStyle,
      customPromptSuffix: isAiStyle.value ? form.value.customPromptSuffix || undefined : undefined,
    })
    if (res.data.aiQuota) {
      quota.value = res.data.aiQuota
    }
    notification.success(t('timeline_digest.success_generate'))
    emit('generated')
    emit('update:visible', false)
    resetForm()
  } catch {
    notification.error(t('timeline_digest.error_generate'))
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    periodStart: null,
    periodEnd: null,
    digestStyle: 'SUMMARY',
    customPromptSuffix: '',
  }
  errors.value = {}
}

function onHide() {
  emit('update:visible', false)
  resetForm()
}
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :header="$t('timeline_digest.dialog_generate_title')"
    :style="{ width: '480px' }"
    @update:visible="onHide"
  >
    <div class="space-y-4">
      <Message v-if="quota.enabled" severity="info" :closable="false">
        {{ $t('timeline_digest.label_quota', { remaining: quota.remaining, limit: quota.limit }) }}
      </Message>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_period_start') }} <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="form.periodStart"
          date-format="yy/mm/dd"
          :placeholder="$t('timeline_digest.placeholder_period_start')"
          class="w-full"
          show-icon
        />
        <small v-if="errors.periodStart" class="text-red-500">{{ errors.periodStart }}</small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_period_end') }} <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="form.periodEnd"
          date-format="yy/mm/dd"
          :placeholder="$t('timeline_digest.placeholder_period_end')"
          class="w-full"
          show-icon
        />
        <small v-if="errors.periodEnd" class="text-red-500">{{ errors.periodEnd }}</small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_digest_style') }} <span class="text-red-500">*</span></label>
        <Select
          v-model="form.digestStyle"
          :options="styleOptions"
          option-label="label"
          option-value="value"
          :placeholder="$t('timeline_digest.placeholder_select_style')"
          class="w-full"
        />
        <small v-if="errors.digestStyle" class="text-red-500">{{ errors.digestStyle }}</small>
      </div>

      <div v-if="isAiStyle" class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('timeline_digest.label_custom_prompt') }}</label>
        <Textarea
          v-model="form.customPromptSuffix"
          :rows="3"
          :placeholder="$t('timeline_digest.placeholder_custom_prompt')"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <Button :label="$t('button.cancel')" severity="secondary" text @click="onHide" />
      <Button
        :label="$t('timeline_digest.button_generate')"
        icon="pi pi-bolt"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
