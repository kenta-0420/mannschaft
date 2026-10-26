<script setup lang="ts">
import type { VisibilityTemplateSummary } from '~/types/visibility-template'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const { templates, loading, error, fetchTemplates, deleteTemplate } = useVisibilityTemplate()

const USER_TEMPLATE_LIMIT = 10

const showDeleteDialog = ref(false)
const targetTemplate = ref<VisibilityTemplateSummary | null>(null)
const deleteLoading = ref(false)

onMounted(() => {
  fetchTemplates()
})

const isLimitReached = computed(
  () => (templates.value?.userTemplates.length ?? 0) >= USER_TEMPLATE_LIMIT,
)

function openDeleteDialog(tmpl: VisibilityTemplateSummary) {
  targetTemplate.value = tmpl
  showDeleteDialog.value = true
}

function closeDeleteDialog() {
  showDeleteDialog.value = false
  targetTemplate.value = null
}

async function confirmDelete() {
  if (!targetTemplate.value) return
  deleteLoading.value = true
  try {
    await deleteTemplate(targetTemplate.value.id)
    await fetchTemplates()
    closeDeleteDialog()
  } finally {
    deleteLoading.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <BackButton />
        <h1 class="text-xl font-bold">{{ t('visibilityTemplate.title') }}</h1>
      </div>
      <NuxtLink
        v-if="!isLimitReached"
        to="/settings/visibility-templates/create"
        class="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary/90"
      >
        <i class="pi pi-plus" />
        {{ t('visibilityTemplate.createNew') }}
      </NuxtLink>
    </div>

    <!-- 上限警告 -->
    <div
      v-if="isLimitReached"
      class="mb-4 rounded-lg border border-orange-200 bg-orange-50 px-4 py-3 text-sm text-orange-700 dark:border-orange-800 dark:bg-orange-900/20 dark:text-orange-300"
    >
      <i class="pi pi-exclamation-triangle mr-2" />
      {{ t('visibilityTemplate.limitReached') }}
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="flex items-center justify-center py-12">
      <i class="pi pi-spin pi-spinner text-3xl text-surface-400" />
    </div>

    <!-- エラー -->
    <div
      v-else-if="error"
      class="rounded-lg border border-red-200 bg-red-50 p-4 text-red-600 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400"
    >
      {{ error }}
    </div>

    <template v-else-if="templates">
      <!-- システムプリセット -->
      <section class="mb-8">
        <h2 class="mb-3 text-sm font-semibold uppercase tracking-wide text-surface-500">
          {{ t('visibilityTemplate.systemPresets') }}
        </h2>
        <div class="space-y-3">
          <div
            v-for="preset in templates.systemPresets"
            :key="preset.id"
            class="flex items-center gap-4 rounded-xl border-2 border-surface-300 bg-surface-0 px-5 py-4 dark:border-surface-600 dark:bg-surface-800"
          >
            <span class="text-2xl">{{ preset.iconEmoji ?? '🔒' }}</span>
            <div class="min-w-0 flex-1">
              <p class="font-medium">{{ preset.name }}</p>
              <p v-if="preset.description" class="mt-0.5 text-sm text-surface-500">
                {{ preset.description }}
              </p>
              <p class="mt-1 text-xs text-surface-400">
                {{ t('visibilityTemplate.ruleCount', { n: preset.ruleCount }) }}
              </p>
            </div>
            <span class="shrink-0 rounded-full bg-surface-100 px-2 py-1 text-xs text-surface-500 dark:bg-surface-700">
              <i class="pi pi-lock mr-1" />{{ t('label.status') }}
            </span>
          </div>
        </div>
      </section>

      <!-- ユーザー独自テンプレート -->
      <section>
        <h2 class="mb-3 text-sm font-semibold uppercase tracking-wide text-surface-500">
          {{ t('visibilityTemplate.userTemplates') }}
          <span class="ml-2 font-normal normal-case text-surface-400">
            {{ templates.userTemplates.length }} / {{ USER_TEMPLATE_LIMIT }}
          </span>
        </h2>

        <!-- テンプレートなし -->
        <div
          v-if="templates.userTemplates.length === 0"
          class="rounded-xl border-2 border-dashed border-surface-300 px-4 py-10 text-center dark:border-surface-600"
        >
          <i class="pi pi-file mb-3 text-3xl text-surface-300" />
          <p class="text-sm text-surface-400">{{ t('visibilityTemplate.noUserTemplates') }}</p>
          <NuxtLink
            to="/settings/visibility-templates/create"
            class="mt-3 inline-flex items-center gap-1 text-sm text-primary hover:underline"
          >
            <i class="pi pi-plus" />
            {{ t('visibilityTemplate.createNew') }}
          </NuxtLink>
        </div>

        <!-- テンプレート一覧 -->
        <div v-else class="space-y-3">
          <div
            v-for="tmpl in templates.userTemplates"
            :key="tmpl.id"
            class="flex items-center gap-4 rounded-xl border-2 border-surface-300 bg-surface-0 px-5 py-4 dark:border-surface-600 dark:bg-surface-800"
          >
            <span class="text-2xl">{{ tmpl.iconEmoji ?? '📋' }}</span>
            <div class="min-w-0 flex-1">
              <p class="font-medium">{{ tmpl.name }}</p>
              <p v-if="tmpl.description" class="mt-0.5 text-sm text-surface-500">
                {{ tmpl.description }}
              </p>
              <p class="mt-1 text-xs text-surface-400">
                {{ t('visibilityTemplate.ruleCount', { n: tmpl.ruleCount }) }}
              </p>
            </div>
            <button
              type="button"
              class="shrink-0 rounded-lg p-2 text-surface-400 transition-colors hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
              @click="openDeleteDialog(tmpl)"
            >
              <i class="pi pi-trash" />
            </button>
          </div>
        </div>
      </section>
    </template>

    <!-- 削除確認ダイアログ -->
    <Dialog
      v-model:visible="showDeleteDialog"
      modal
      :header="t('dialog.confirm_title')"
      class="w-full max-w-md"
      @hide="closeDeleteDialog"
    >
      <p class="text-sm text-surface-700 dark:text-surface-300">
        {{ t('visibilityTemplate.deleteConfirm') }}
      </p>
      <template #footer>
        <div class="flex justify-end gap-3">
          <Button
            :label="t('button.cancel')"
            severity="secondary"
            text
            @click="closeDeleteDialog"
          />
          <Button
            :label="t('button.delete')"
            severity="danger"
            :loading="deleteLoading"
            @click="confirmDelete"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
