<script setup lang="ts">
import type { MemberSkillResponse, SkillCategoryResponse } from '~/types/skill'

const visible = defineModel<boolean>('visible', { default: false })

const props = defineProps<{
  teamId: number
  skill?: MemberSkillResponse | null
  categories: SkillCategoryResponse[]
}>()

const emit = defineEmits<{
  saved: []
}>()

const { registerSkill, updateSkill, getSkillUploadUrl } = useSkillApi()
const notification = useNotification()

const saving = ref(false)
const uploading = ref(false)
const certificateFile = ref<File | null>(null)
const certificateS3Key = ref<string | null>(null)

const form = ref({
  skillCategoryId: null as number | null,
  name: '',
  issuer: '',
  credentialNumber: '',
  acquiredOn: '',
  expiresAt: '',
})

const isEdit = computed(() => !!props.skill)
const dialogHeader = computed(() => (isEdit.value ? 'スキル編集' : 'スキル登録'))

const activeCategoryOptions = computed(() =>
  props.categories.filter((c) => c.isActive).map((c) => ({ label: c.name, value: c.id })),
)

watch(visible, (v) => {
  if (!v) return
  if (props.skill) {
    form.value = {
      skillCategoryId: props.skill.skillCategoryId,
      name: props.skill.name,
      issuer: props.skill.issuer || '',
      credentialNumber: props.skill.credentialNumber || '',
      acquiredOn: props.skill.acquiredOn || '',
      expiresAt: props.skill.expiresAt || '',
    }
    certificateS3Key.value = null
  } else {
    form.value = {
      skillCategoryId: null,
      name: '',
      issuer: '',
      credentialNumber: '',
      acquiredOn: '',
      expiresAt: '',
    }
    certificateS3Key.value = null
  }
  certificateFile.value = null
})

async function uploadCertificate(): Promise<string | undefined> {
  if (!certificateFile.value) return undefined
  uploading.value = true
  try {
    const res = await getSkillUploadUrl(props.teamId)
    const { uploadUrl, s3Key } = res.data
    await $fetch(uploadUrl, {
      method: 'PUT',
      body: certificateFile.value,
      headers: { 'Content-Type': certificateFile.value.type },
    })
    return s3Key
  } catch {
    notification.error('証明書のアップロードに失敗しました')
    return undefined
  } finally {
    uploading.value = false
  }
}

function onFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    certificateFile.value = target.files[0] ?? null
  }
}

async function submit() {
  if (!form.value.skillCategoryId || !form.value.name.trim()) return
  saving.value = true
  try {
    let s3Key = certificateS3Key.value
    if (certificateFile.value) {
      const uploaded = await uploadCertificate()
      if (certificateFile.value && !uploaded) {
        saving.value = false
        return
      }
      s3Key = uploaded ?? null
    }

    if (isEdit.value && props.skill) {
      await updateSkill(props.teamId, props.skill.id, {
        name: form.value.name.trim(),
        issuer: form.value.issuer.trim() || undefined,
        credentialNumber: form.value.credentialNumber.trim() || undefined,
        acquiredOn: form.value.acquiredOn || undefined,
        expiresAt: form.value.expiresAt || undefined,
        certificateS3Key: s3Key || undefined,
        version: props.skill.version,
      })
      notification.success('スキルを更新しました')
    } else {
      await registerSkill(props.teamId, {
        skillCategoryId: form.value.skillCategoryId,
        name: form.value.name.trim(),
        issuer: form.value.issuer.trim() || undefined,
        credentialNumber: form.value.credentialNumber.trim() || undefined,
        acquiredOn: form.value.acquiredOn || undefined,
        expiresAt: form.value.expiresAt || undefined,
        certificateS3Key: s3Key || undefined,
      })
      notification.success('スキルを登録しました')
    }
    visible.value = false
    emit('saved')
  } catch {
    notification.error('スキルの保存に失敗しました')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="dialogHeader"
    modal
    :style="{ width: '520px' }"
    class="w-full max-w-lg"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium">
          カテゴリ <span class="text-red-500">*</span>
        </label>
        <Select
          v-model="form.skillCategoryId"
          :options="activeCategoryOptions"
          option-label="label"
          option-value="value"
          placeholder="カテゴリを選択"
          class="w-full"
          :disabled="isEdit"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">
          スキル名 <span class="text-red-500">*</span>
        </label>
        <InputText
          v-model="form.name"
          class="w-full"
          placeholder="例: 普通自動車免許"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">発行元</label>
        <InputText
          v-model="form.issuer"
          class="w-full"
          placeholder="例: 公安委員会"
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">資格番号</label>
        <InputText
          v-model="form.credentialNumber"
          class="w-full"
          placeholder="例: ABC-12345"
        />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">取得日</label>
          <InputText
            v-model="form.acquiredOn"
            type="date"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">有効期限</label>
          <InputText
            v-model="form.expiresAt"
            type="date"
            class="w-full"
          />
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">証明書</label>
        <input
          type="file"
          accept="image/*,application/pdf"
          class="block w-full text-sm text-surface-500 file:mr-3 file:rounded file:border-0 file:bg-primary/10 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary hover:file:bg-primary/20"
          @change="onFileSelect"
        >
        <p v-if="certificateFile" class="mt-1 text-xs text-surface-400">
          {{ certificateFile.name }}
        </p>
        <p v-else-if="isEdit && skill?.hasCertificate" class="mt-1 text-xs text-green-600 dark:text-green-400">
          <i class="pi pi-check-circle mr-1" />証明書アップロード済み
        </p>
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        :label="isEdit ? '更新' : '登録'"
        icon="pi pi-check"
        :loading="saving || uploading"
        :disabled="!form.skillCategoryId || !form.name.trim()"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
