<script setup lang="ts">
import type {
  VisibilityTemplateRuleType,
  CreateVisibilityTemplateRequest,
} from '~/types/visibility-template'

definePageMeta({
  middleware: 'auth',
})

const { t } = useI18n()
const router = useRouter()
const { createTemplate, loading } = useVisibilityTemplate()

const RULE_TYPES: VisibilityTemplateRuleType[] = [
  'TEAM_FRIEND_OF',
  'ORGANIZATION_MEMBER_OF',
  'TEAM_MEMBER_OF',
  'REGION_MATCH',
  'EXPLICIT_TEAM',
  'EXPLICIT_USER',
  'EXPLICIT_SOCIAL_PROFILE',
]

const MAX_RULES = 20
const NAME_MAX_LEN = 60
const DESC_MAX_LEN = 240
const EMOJI_MAX_LEN = 16

interface RuleFormItem {
  ruleType: VisibilityTemplateRuleType
  ruleTargetId: string
}

const formName = ref('')
const formDescription = ref('')
const formIconEmoji = ref('')
const formRules = ref<RuleFormItem[]>([])

const submitError = ref<string | null>(null)

function addRule() {
  if (formRules.value.length >= MAX_RULES) return
  formRules.value.push({ ruleType: 'TEAM_MEMBER_OF', ruleTargetId: '' })
}

function removeRule(index: number) {
  formRules.value.splice(index, 1)
}

async function submit() {
  submitError.value = null
  const request: CreateVisibilityTemplateRequest = {
    name: formName.value.trim(),
    description: formDescription.value.trim() || undefined,
    iconEmoji: formIconEmoji.value.trim() || undefined,
    rules: formRules.value.map((r) => ({
      ruleType: r.ruleType,
      ruleTargetId: r.ruleTargetId ? Number(r.ruleTargetId) : undefined,
    })),
  }

  try {
    await createTemplate(request)
    await router.push('/settings/visibility-templates')
  } catch (e) {
    submitError.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <div class="mb-6 flex items-center gap-3">
      <BackButton />
      <h1 class="text-xl font-bold">{{ t('visibilityTemplate.createNew') }}</h1>
    </div>

    <form class="space-y-6" @submit.prevent="submit">
      <!-- テンプレート名 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium">
          {{ t('visibilityTemplate.form.name') }}
          <span class="ml-1 text-xs text-red-500">{{ t('label.required') }}</span>
        </label>
        <InputText
          v-model="formName"
          class="w-full"
          :maxlength="NAME_MAX_LEN"
          required
          :placeholder="t('visibilityTemplate.form.name')"
        />
        <p class="mt-1 text-right text-xs text-surface-400">
          {{ formName.length }} / {{ NAME_MAX_LEN }}
        </p>
      </div>

      <!-- 説明 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium">
          {{ t('visibilityTemplate.form.description') }}
          <span class="ml-1 text-xs text-surface-400">{{ t('label.optional') }}</span>
        </label>
        <Textarea
          v-model="formDescription"
          class="w-full"
          :maxlength="DESC_MAX_LEN"
          rows="3"
          :placeholder="t('visibilityTemplate.form.description')"
        />
        <p class="mt-1 text-right text-xs text-surface-400">
          {{ formDescription.length }} / {{ DESC_MAX_LEN }}
        </p>
      </div>

      <!-- アイコン絵文字 -->
      <div>
        <label class="mb-1.5 block text-sm font-medium">
          {{ t('visibilityTemplate.form.iconEmoji') }}
          <span class="ml-1 text-xs text-surface-400">{{ t('label.optional') }}</span>
        </label>
        <InputText
          v-model="formIconEmoji"
          class="w-32"
          :maxlength="EMOJI_MAX_LEN"
          :placeholder="'📋'"
        />
      </div>

      <!-- ルール一覧 -->
      <div>
        <div class="mb-2 flex items-center justify-between">
          <label class="text-sm font-medium">
            {{ t('visibilityTemplate.form.rules') }}
            <span class="ml-1 text-xs text-surface-400">
              {{ formRules.length }} / {{ MAX_RULES }}
            </span>
          </label>
          <button
            v-if="formRules.length < MAX_RULES"
            type="button"
            class="flex items-center gap-1 rounded-lg px-3 py-1.5 text-sm text-primary transition-colors hover:bg-primary/10"
            @click="addRule"
          >
            <i class="pi pi-plus text-xs" />
            {{ t('visibilityTemplate.form.addRule') }}
          </button>
        </div>

        <div v-if="formRules.length === 0" class="rounded-xl border-2 border-dashed border-surface-300 px-4 py-6 text-center text-sm text-surface-400 dark:border-surface-600">
          <button
            type="button"
            class="text-primary hover:underline"
            @click="addRule"
          >
            + {{ t('visibilityTemplate.form.addRule') }}
          </button>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="(rule, index) in formRules"
            :key="index"
            class="rounded-xl border-2 border-surface-300 bg-surface-0 p-4 dark:border-surface-600 dark:bg-surface-800"
          >
            <div class="flex items-start gap-3">
              <div class="flex-1 space-y-3">
                <!-- ルール種別 -->
                <div>
                  <label class="mb-1 block text-xs font-medium text-surface-500">
                    {{ t('visibilityTemplate.form.ruleType') }}
                  </label>
                  <Select
                    v-model="rule.ruleType"
                    :options="RULE_TYPES"
                    class="w-full"
                    :option-label="(rt: VisibilityTemplateRuleType) => t(`visibilityTemplate.ruleTypes.${rt}`)"
                  />
                </div>
                <!-- 対象ID -->
                <div>
                  <label class="mb-1 block text-xs font-medium text-surface-500">
                    {{ t('visibilityTemplate.form.ruleTargetId') }}
                    <span class="ml-1 text-surface-400">{{ t('label.optional') }}</span>
                  </label>
                  <InputText
                    v-model="rule.ruleTargetId"
                    type="number"
                    class="w-full"
                    :placeholder="t('visibilityTemplate.form.ruleTargetId')"
                  />
                </div>
              </div>
              <!-- 削除ボタン -->
              <button
                type="button"
                class="mt-1 shrink-0 rounded-lg p-2 text-surface-400 transition-colors hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
                @click="removeRule(index)"
              >
                <i class="pi pi-times" />
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- エラー表示 -->
      <div
        v-if="submitError"
        class="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-600 dark:border-red-800 dark:bg-red-900/20 dark:text-red-400"
      >
        {{ submitError }}
      </div>

      <!-- 送信ボタン -->
      <div class="flex justify-end gap-3 pt-2">
        <NuxtLink
          to="/settings/visibility-templates"
          class="rounded-lg px-5 py-2 text-sm font-medium text-surface-600 transition-colors hover:bg-surface-100 dark:text-surface-400 dark:hover:bg-surface-700"
        >
          {{ t('button.cancel') }}
        </NuxtLink>
        <Button
          type="submit"
          :label="t('button.create')"
          :loading="loading"
          :disabled="!formName.trim()"
        />
      </div>
    </form>
  </div>
</template>
