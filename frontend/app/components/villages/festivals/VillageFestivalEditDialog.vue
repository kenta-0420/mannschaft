<script setup lang="ts">
/**
 * 村お祭り編集 Dialog — 表示専用の子コンポーネント。
 *
 * フォーム状態は親 (pages/villages/[id]/festivals.vue) が保持し、
 * 子は表示と入力イベントの転送に専念する。
 *
 * - ロジックは持たない（保存 / キャンセルは emit）
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'

interface FestivalFormState {
  title: string
  description: string
  startsAt: string
  endsAt: string
  bannerR2Key: string
  themeColorHex: string
}

defineProps<{
  visible: boolean
}>()

const form = defineModel<FestivalFormState>('form', { required: true })

const emit = defineEmits<{
  'update:visible': [value: boolean]
  submit: []
}>()

const { t } = useI18n()
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="t('village.festival.editTitle')"
    :style="{ width: '36rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-3">
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.festival.festivalTitle') }}
        </label>
        <InputText v-model="form.title" class="w-full" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.festival.description') }}
        </label>
        <Textarea v-model="form.description" class="w-full" rows="3" />
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.festival.starts') }}
          </label>
          <InputText
            v-model="form.startsAt"
            type="datetime-local"
            class="w-full"
          />
        </div>
        <div>
          <label class="block text-sm font-medium mb-1">
            {{ t('village.festival.ends') }}
          </label>
          <InputText
            v-model="form.endsAt"
            type="datetime-local"
            class="w-full"
          />
        </div>
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.festival.bannerImage') }}
        </label>
        <InputText v-model="form.bannerR2Key" class="w-full" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.festival.themeColor') }}
        </label>
        <InputText
          v-model="form.themeColorHex"
          type="color"
          class="w-full h-10"
        />
      </div>
    </div>
    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="emit('update:visible', false)"
      />
      <Button
        :label="t('village.action.save')"
        icon="pi pi-check"
        severity="primary"
        @click="emit('submit')"
      />
    </template>
  </Dialog>
</template>
