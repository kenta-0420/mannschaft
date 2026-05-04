<script setup lang="ts">
import type { MatchActivityType, MatchCategory, MatchLevel, MatchVisibility } from '~/types/matching'

definePageMeta({ middleware: 'auth' })
const route = useRoute()
const teamId = Number(route.params.id)
const { createRequest } = useMatchingApi()
const notification = useNotification()

const showCreateDialog = ref(false)
const saving = ref(false)
const listRef = ref<{ refresh: () => void } | null>(null)

const form = ref({
  title: '',
  activity_type: 'COMPETITION' as MatchActivityType,
  category: 'ANY' as MatchCategory,
  level: 'ANY' as MatchLevel,
  visibility: 'PLATFORM' as MatchVisibility,
  prefecture_code: '',
  description: '',
  preferred_date_from: '',
  preferred_date_to: '',
})

function openCreateDialog() {
  form.value = {
    title: '',
    activity_type: 'COMPETITION',
    category: 'ANY',
    level: 'ANY',
    visibility: 'PLATFORM',
    prefecture_code: '',
    description: '',
    preferred_date_from: '',
    preferred_date_to: '',
  }
  showCreateDialog.value = true
}

async function handleCreate() {
  if (!form.value.title.trim()) return
  saving.value = true
  try {
    await createRequest(teamId, {
      title: form.value.title,
      activity_type: form.value.activity_type,
      category: form.value.category,
      level: form.value.level,
      visibility: form.value.visibility,
      prefecture_code: form.value.prefecture_code || '00',
      description: form.value.description || undefined,
      preferred_date_from: form.value.preferred_date_from || undefined,
      preferred_date_to: form.value.preferred_date_to || undefined,
    })
    notification.success('募集を作成しました')
    showCreateDialog.value = false
    listRef.value?.refresh()
  } catch {
    notification.error('募集の作成に失敗しました')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="mb-4">
      <h1 class="text-2xl font-bold">マッチング</h1>
    </div>
    <MatchRequestList
      ref="listRef"
      :team-id="teamId"
      @create="openCreateDialog"
    />

    <Dialog v-model:visible="showCreateDialog" modal header="募集を作成" :style="{ width: '30rem' }">
      <div class="flex flex-col gap-4 py-2">
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">タイトル <span class="text-red-500">*</span></label>
          <InputText v-model="form.title" placeholder="例: 練習試合 相手募集" class="w-full" />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">カテゴリ <span class="text-red-500">*</span></label>
          <Select
            v-model="form.category"
            :options="[
              { label: '指定なし', value: 'ANY' },
              { label: '小学生', value: 'ELEMENTARY' },
              { label: '中学生', value: 'JUNIOR_HIGH' },
              { label: '高校生', value: 'HIGH_SCHOOL' },
              { label: '大学生', value: 'UNIVERSITY' },
              { label: '一般', value: 'ADULT' },
              { label: 'シニア', value: 'SENIOR' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">種別 <span class="text-red-500">*</span></label>
            <Select
              v-model="form.activity_type"
              :options="[
                { label: '試合', value: 'COMPETITION' },
                { label: '練習', value: 'PRACTICE' },
                { label: '交流', value: 'EXCHANGE' },
                { label: '補強募集', value: 'RECRUIT' },
                { label: 'その他', value: 'OTHER' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">レベル</label>
            <Select
              v-model="form.level"
              :options="[
                { label: '指定なし', value: 'ANY' },
                { label: '初級', value: 'BEGINNER' },
                { label: '中級', value: 'INTERMEDIATE' },
                { label: '上級', value: 'ADVANCED' },
              ]"
              option-label="label"
              option-value="value"
              class="w-full"
            />
          </div>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">希望日（開始）</label>
            <InputText v-model="form.preferred_date_from" type="date" class="w-full" />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm font-medium">希望日（終了）</label>
            <InputText v-model="form.preferred_date_to" type="date" class="w-full" />
          </div>
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">公開範囲</label>
          <Select
            v-model="form.visibility"
            :options="[
              { label: 'プラットフォーム全体', value: 'PLATFORM' },
              { label: '組織内のみ', value: 'ORGANIZATION' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div class="flex flex-col gap-1">
          <label class="text-sm font-medium">説明</label>
          <Textarea v-model="form.description" rows="3" class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" text :disabled="saving" @click="showCreateDialog = false" />
        <Button label="作成" icon="pi pi-check" :loading="saving" @click="handleCreate" />
      </template>
    </Dialog>
  </div>
</template>
