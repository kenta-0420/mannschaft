<script setup lang="ts">
import type { Chart, CreateChartRequest, ChartSectionConfig } from '~/types/chart'

const props = defineProps<{
  chart?: Chart
}>()

const emit = defineEmits<{
  save: [data: CreateChartRequest]
  cancel: []
}>()

const form = ref<CreateChartRequest>({
  clientName: props.chart?.clientName ?? '',
  visitDate: props.chart?.visitDate ?? new Date().toISOString().slice(0, 10),
  chiefComplaint: props.chart?.chiefComplaint ?? '',
  notes: props.chart?.notes ?? '',
  nextVisitRecommendation: props.chart?.nextVisitRecommendation ?? '',
  sections: props.chart?.sections ?? {
    bodyChart: false,
    colorRecipe: false,
    beforeAfter: true,
    questionnaire: false,
    consent: false,
  },
})

const sectionLabels: Record<keyof ChartSectionConfig, string> = {
  bodyChart: '身体チャート（整骨院向け）',
  colorRecipe: 'カラー・薬剤レシピ（美容室向け）',
  beforeAfter: 'ビフォーアフター写真',
  questionnaire: '問診票',
  consent: '同意書（電子印鑑）',
}

const canSave = computed(() => form.value.clientName && form.value.visitDate)
</script>

<template>
  <div class="space-y-4">
    <div class="grid gap-4 md:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium">顧客名 *</label>
        <InputText v-model="form.clientName" class="w-full" />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">来店日 *</label>
        <InputText v-model="form.visitDate" type="date" class="w-full" />
      </div>
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">主訴・要望</label>
      <Textarea v-model="form.chiefComplaint" class="w-full" rows="3" />
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">施術メモ</label>
      <Textarea v-model="form.notes" class="w-full" rows="4" />
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium">次回来店推奨日</label>
      <InputText v-model="form.nextVisitRecommendation" class="w-full" placeholder="例: 2週間後" />
    </div>

    <div>
      <label class="mb-2 block text-sm font-medium">セクション設定</label>
      <div class="space-y-2">
        <div
          v-for="(label, key) in sectionLabels"
          :key="key"
          class="flex items-center gap-2"
        >
          <ToggleSwitch v-model="form.sections[key as keyof ChartSectionConfig]" />
          <label class="text-sm">{{ label }}</label>
        </div>
      </div>
    </div>

    <div class="flex justify-end gap-2">
      <Button label="キャンセル" severity="secondary" @click="emit('cancel')" />
      <Button :label="props.chart ? '更新' : '作成'" icon="pi pi-check" :disabled="!canSave" @click="emit('save', form)" />
    </div>
  </div>
</template>
