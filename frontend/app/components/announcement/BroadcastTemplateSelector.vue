<script setup lang="ts">
import type { AnnouncementScopeType } from '~/types/announcement'
import type { AnnouncementTemplate } from '~/types/announcement_broadcast'

const props = defineProps<{
  scopeType: AnnouncementScopeType
  scopeId: string
  modelValue: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
  apply: [template: AnnouncementTemplate]
}>()

const { t } = useI18n()
const { templates, loading, fetchTemplates } = useAnnouncementTemplates(props.scopeType, props.scopeId)

onMounted(() => {
  fetchTemplates()
})

const selectedTemplate = computed({
  get() {
    return props.modelValue
  },
  set(value: number | null) {
    emit('update:modelValue', value)
    if (value !== null) {
      const tpl = templates.value.find(t => t.id === value)
      if (tpl) emit('apply', tpl)
    }
  },
})

const templateOptions = computed(() =>
  templates.value.map(tpl => ({
    label: tpl.isDefault ? `${tpl.name}（デフォルト）` : tpl.name,
    value: tpl.id,
  })),
)

// 親（Step1）がテンプレート保存後に一覧を再取得できるよう公開する
defineExpose({ refresh: fetchTemplates })
</script>

<template>
  <div class="flex items-center gap-2">
    <Select
      v-model="selectedTemplate"
      :options="templateOptions"
      option-label="label"
      option-value="value"
      :placeholder="t('announcement.template_select_placeholder')"
      :loading="loading"
      :show-clear="true"
      class="w-full"
    />
  </div>
</template>
