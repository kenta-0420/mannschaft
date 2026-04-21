<script setup lang="ts">
import type { VisibilityTemplateSummary } from '~/types/visibility-template'

interface Props {
  modelValue: number | null
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

const { t } = useI18n()
const { templates, loading, error, fetchTemplates } = useVisibilityTemplate()

onMounted(() => {
  fetchTemplates()
})

function selectTemplate(template: VisibilityTemplateSummary) {
  if (props.disabled) return
  if (props.modelValue === template.id) {
    emit('update:modelValue', null)
  } else {
    emit('update:modelValue', template.id)
  }
}

function isSelected(id: number): boolean {
  return props.modelValue === id
}
</script>

<template>
  <div class="space-y-4">
    <!-- ローディング -->
    <div v-if="loading" class="flex items-center justify-center py-6">
      <i class="pi pi-spin pi-spinner text-2xl text-surface-400" />
    </div>

    <!-- エラー -->
    <div v-else-if="error" class="rounded-lg border border-red-200 bg-red-50 p-4 text-red-600 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400">
      {{ error }}
    </div>

    <template v-else-if="templates">
      <!-- システムプリセット -->
      <div>
        <p class="mb-2 text-sm font-semibold text-surface-500">
          {{ t('visibilityTemplate.systemPresets') }}
        </p>
        <div class="space-y-2">
          <button
            v-for="preset in templates.systemPresets"
            :key="preset.id"
            type="button"
            class="flex w-full items-center gap-3 rounded-xl border-2 px-4 py-3 text-left transition-colors"
            :class="[
              isSelected(preset.id)
                ? 'border-primary bg-primary/5 dark:bg-primary/10'
                : 'border-surface-300 bg-surface-0 hover:border-primary/50 dark:border-surface-600 dark:bg-surface-800',
              disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer',
            ]"
            :disabled="disabled"
            @click="selectTemplate(preset)"
          >
            <span class="text-xl">{{ preset.iconEmoji ?? '🔒' }}</span>
            <div class="min-w-0 flex-1">
              <p class="font-medium">{{ preset.name }}</p>
              <p v-if="preset.description" class="truncate text-sm text-surface-500">
                {{ preset.description }}
              </p>
            </div>
            <span class="shrink-0 text-xs text-surface-400">
              {{ t('visibilityTemplate.ruleCount', { n: preset.ruleCount }) }}
            </span>
            <i
              v-if="isSelected(preset.id)"
              class="pi pi-check shrink-0 text-primary"
            />
          </button>
        </div>
      </div>

      <!-- ユーザー独自テンプレート -->
      <div>
        <p class="mb-2 text-sm font-semibold text-surface-500">
          {{ t('visibilityTemplate.userTemplates') }}
        </p>
        <p
          v-if="templates.userTemplates.length === 0"
          class="rounded-xl border-2 border-dashed border-surface-300 px-4 py-6 text-center text-sm text-surface-400 dark:border-surface-600"
        >
          {{ t('visibilityTemplate.noUserTemplates') }}
        </p>
        <div v-else class="space-y-2">
          <button
            v-for="tmpl in templates.userTemplates"
            :key="tmpl.id"
            type="button"
            class="flex w-full items-center gap-3 rounded-xl border-2 px-4 py-3 text-left transition-colors"
            :class="[
              isSelected(tmpl.id)
                ? 'border-primary bg-primary/5 dark:bg-primary/10'
                : 'border-surface-300 bg-surface-0 hover:border-primary/50 dark:border-surface-600 dark:bg-surface-800',
              disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer',
            ]"
            :disabled="disabled"
            @click="selectTemplate(tmpl)"
          >
            <span class="text-xl">{{ tmpl.iconEmoji ?? '📋' }}</span>
            <div class="min-w-0 flex-1">
              <p class="font-medium">{{ tmpl.name }}</p>
              <p v-if="tmpl.description" class="truncate text-sm text-surface-500">
                {{ tmpl.description }}
              </p>
            </div>
            <span class="shrink-0 text-xs text-surface-400">
              {{ t('visibilityTemplate.ruleCount', { n: tmpl.ruleCount }) }}
            </span>
            <i
              v-if="isSelected(tmpl.id)"
              class="pi pi-check shrink-0 text-primary"
            />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
