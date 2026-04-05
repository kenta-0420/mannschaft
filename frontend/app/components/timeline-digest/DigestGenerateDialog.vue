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

const styleOptions = [
  { label: '要約 (SUMMARY)', value: 'SUMMARY' },
  { label: 'ナラティブ (NARRATIVE)', value: 'NARRATIVE' },
  { label: 'ハイライト (HIGHLIGHTS)', value: 'HIGHLIGHTS' },
  { label: 'テンプレート (TEMPLATE)', value: 'TEMPLATE' },
]

const isAiStyle = computed(() =>
  ['SUMMARY', 'NARRATIVE', 'HIGHLIGHTS'].includes(form.value.digestStyle),
)

function formatDateToIso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

function validate(): boolean {
  errors.value = {}
  if (!form.value.periodStart) {
    errors.value.periodStart = '期間開始を選択してください'
  }
  if (!form.value.periodEnd) {
    errors.value.periodEnd = '期間終了を選択してください'
  }
  if (
    form.value.periodStart &&
    form.value.periodEnd &&
    form.value.periodEnd < form.value.periodStart
  ) {
    errors.value.periodEnd = '期間終了は期間開始以降の日付を選択してください'
  }
  if (!form.value.digestStyle) {
    errors.value.digestStyle = 'スタイルを選択してください'
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
    notification.success('ダイジェストの生成を開始しました')
    emit('generated')
    emit('update:visible', false)
    resetForm()
  } catch {
    notification.error('ダイジェストの生成に失敗しました')
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
    header="ダイジェスト生成"
    :style="{ width: '480px' }"
    @update:visible="onHide"
  >
    <div class="space-y-4">
      <Message v-if="quota.enabled" severity="info" :closable="false">
        月次AI残り: {{ quota.remaining }}/{{ quota.limit }} 件
      </Message>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">期間開始 <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="form.periodStart"
          date-format="yy/mm/dd"
          placeholder="開始日を選択"
          class="w-full"
          show-icon
        />
        <small v-if="errors.periodStart" class="text-red-500">{{ errors.periodStart }}</small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">期間終了 <span class="text-red-500">*</span></label>
        <DatePicker
          v-model="form.periodEnd"
          date-format="yy/mm/dd"
          placeholder="終了日を選択"
          class="w-full"
          show-icon
        />
        <small v-if="errors.periodEnd" class="text-red-500">{{ errors.periodEnd }}</small>
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">ダイジェストスタイル <span class="text-red-500">*</span></label>
        <Select
          v-model="form.digestStyle"
          :options="styleOptions"
          option-label="label"
          option-value="value"
          placeholder="スタイルを選択"
          class="w-full"
        />
        <small v-if="errors.digestStyle" class="text-red-500">{{ errors.digestStyle }}</small>
      </div>

      <div v-if="isAiStyle" class="flex flex-col gap-1">
        <label class="text-sm font-medium">カスタムプロンプト補足（任意）</label>
        <Textarea
          v-model="form.customPromptSuffix"
          :rows="3"
          placeholder="AIへの追加指示を入力（任意）"
          class="w-full"
        />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" severity="secondary" text @click="onHide" />
      <Button
        label="生成"
        icon="pi pi-bolt"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
