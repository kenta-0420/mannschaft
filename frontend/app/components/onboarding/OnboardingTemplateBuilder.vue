<script setup lang="ts">
import type { OnboardingTemplate, OnboardingPreset, CreateStepRequest, OnboardingStepType } from '~/types/onboarding'

const props = defineProps<{
  template?: OnboardingTemplate
  presets?: OnboardingPreset[]
}>()

const emit = defineEmits<{
  save: [data: {
    name: string
    description: string
    deadlineDays: number | null
    reminderDaysBefore: number | null
    isOrderEnforced: boolean
    isAdminNotifiedOnComplete: boolean
    isTimelinePostedOnComplete: boolean
    presetId?: number
    steps: CreateStepRequest[]
  }]
  cancel: []
}>()

const form = ref({
  name: props.template?.name ?? '',
  description: props.template?.description ?? '',
  deadlineDays: props.template?.deadlineDays ?? null as number | null,
  reminderDaysBefore: props.template?.reminderDaysBefore ?? null as number | null,
  isOrderEnforced: props.template?.isOrderEnforced ?? false,
  isAdminNotifiedOnComplete: props.template?.isAdminNotifiedOnComplete ?? true,
  isTimelinePostedOnComplete: props.template?.isTimelinePostedOnComplete ?? false,
})

const steps = ref<CreateStepRequest[]>(
  props.template?.steps.map(s => ({
    title: s.title,
    description: s.description ?? undefined,
    stepType: s.stepType,
    referenceUrl: s.referenceUrl ?? undefined,
    deadlineOffsetDays: s.deadlineOffsetDays ?? undefined,
  })) ?? [],
)

const stepTypeOptions: { label: string; value: OnboardingStepType }[] = [
  { label: '手動チェック', value: 'MANUAL' },
  { label: 'URL確認', value: 'URL' },
  { label: 'フォーム提出', value: 'FORM' },
  { label: 'ナレッジベース', value: 'KNOWLEDGE_BASE' },
  { label: 'プロフィール完了', value: 'PROFILE_COMPLETION' },
]

function addStep() {
  steps.value.push({ title: '', stepType: 'MANUAL' })
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
}

function moveStep(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= steps.value.length) return
  const temp = steps.value[index]
  steps.value[index] = steps.value[target]
  steps.value[target] = temp
}

function loadPreset(preset: OnboardingPreset) {
  form.value.name = preset.name
  form.value.description = preset.description ?? ''
  steps.value = preset.stepsJson.map(s => ({
    title: s.title,
    description: s.description ?? undefined,
    stepType: s.stepType,
    referenceUrl: s.referenceUrl ?? undefined,
  }))
}

function handleSave() {
  emit('save', {
    ...form.value,
    steps: steps.value,
  })
}

const canSave = computed(() => form.value.name && steps.value.length > 0 && steps.value.every(s => s.title))
</script>

<template>
  <div class="space-y-6">
    <!-- プリセット選択 -->
    <div v-if="presets && presets.length > 0 && !template" class="rounded-lg border border-surface-200 bg-surface-50 p-4 dark:border-surface-700 dark:bg-surface-800">
      <p class="mb-2 text-sm font-medium">プリセットから作成</p>
      <div class="flex flex-wrap gap-2">
        <Button
          v-for="preset in presets"
          :key="preset.id"
          :label="preset.name"
          severity="secondary"
          size="small"
          outlined
          @click="loadPreset(preset)"
        />
      </div>
    </div>

    <!-- 基本情報 -->
    <div class="space-y-3">
      <div>
        <label class="mb-1 block text-sm font-medium">テンプレート名 *</label>
        <InputText v-model="form.name" class="w-full" placeholder="新メンバー向けオンボーディング" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" class="w-full" rows="2" />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">完了期限（日数）</label>
          <InputNumber v-model="form.deadlineDays" class="w-full" :min="1" placeholder="未設定" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">リマインダー（期限N日前）</label>
          <InputNumber v-model="form.reminderDaysBefore" class="w-full" :min="1" placeholder="未設定" />
        </div>
      </div>
      <div class="space-y-2">
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isOrderEnforced" />
          <label class="text-sm">ステップの順序を強制する</label>
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isAdminNotifiedOnComplete" />
          <label class="text-sm">全ステップ完了時にADMINに通知</label>
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.isTimelinePostedOnComplete" />
          <label class="text-sm">完了時にタイムラインに自動投稿</label>
        </div>
      </div>
    </div>

    <!-- ステップ管理 -->
    <div>
      <div class="mb-2 flex items-center justify-between">
        <label class="text-sm font-medium">ステップ（{{ steps.length }}）</label>
        <Button label="ステップ追加" icon="pi pi-plus" size="small" severity="secondary" @click="addStep" />
      </div>
      <div class="space-y-3">
        <div
          v-for="(step, index) in steps"
          :key="index"
          class="rounded-lg border border-surface-200 p-3 dark:border-surface-700"
        >
          <div class="mb-2 flex items-center gap-2">
            <span class="text-xs text-surface-400">{{ index + 1 }}</span>
            <InputText v-model="step.title" class="flex-1" placeholder="ステップのタイトル" />
            <Dropdown v-model="step.stepType" :options="stepTypeOptions" option-label="label" option-value="value" class="w-40" />
            <Button icon="pi pi-arrow-up" size="small" text severity="secondary" :disabled="index === 0" @click="moveStep(index, -1)" />
            <Button icon="pi pi-arrow-down" size="small" text severity="secondary" :disabled="index === steps.length - 1" @click="moveStep(index, 1)" />
            <Button icon="pi pi-trash" size="small" text severity="danger" @click="removeStep(index)" />
          </div>
          <InputText
            v-if="step.stepType === 'URL'"
            v-model="step.referenceUrl"
            class="w-full"
            placeholder="https://..."
          />
        </div>
      </div>
      <p v-if="steps.length === 0" class="py-4 text-center text-sm text-surface-500">
        ステップを追加してください
      </p>
    </div>

    <!-- ボタン -->
    <div class="flex justify-end gap-2">
      <Button label="キャンセル" severity="secondary" @click="emit('cancel')" />
      <Button :label="template ? '更新' : '作成'" icon="pi pi-check" :disabled="!canSave" @click="handleSave" />
    </div>
  </div>
</template>
