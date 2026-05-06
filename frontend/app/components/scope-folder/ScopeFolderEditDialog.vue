<script setup lang="ts">
import type { ScopeFolder, CreateFolderRequest, UpdateFolderRequest } from '~/types/scopeFolder'

interface Props {
  visible: boolean
  scopeType: 'TEAM' | 'ORGANIZATION'
  editTarget?: ScopeFolder
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'saved', folder: ScopeFolder): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const { t } = useI18n()
const toast = useToast()
const folderApi = useScopeFolderApi()

const PRESET_COLORS = [
  '#EF4444',
  '#F97316',
  '#EAB308',
  '#22C55E',
  '#3B82F6',
  '#8B5CF6',
]

const name = ref('')
const selectedColor = ref<string | null>(null)
const saving = ref(false)

const isEdit = computed(() => props.editTarget !== undefined)
const dialogTitle = computed(() =>
  isEdit.value ? t('scopeFolder.editFolder') : t('scopeFolder.addFolder'),
)

watch(
  () => props.visible,
  (val) => {
    if (val) {
      if (props.editTarget) {
        name.value = props.editTarget.name
        selectedColor.value = props.editTarget.color
      }
      else {
        name.value = ''
        selectedColor.value = null
      }
    }
  },
)

function close() {
  emit('update:visible', false)
}

async function save() {
  if (!name.value.trim()) return
  saving.value = true
  try {
    let folder: ScopeFolder
    if (isEdit.value && props.editTarget) {
      const req: UpdateFolderRequest = {
        name: name.value.trim(),
        ...(selectedColor.value ? { color: selectedColor.value } : {}),
      }
      folder = await folderApi.updateFolder(props.editTarget.id, req)
    }
    else {
      const req: CreateFolderRequest = {
        name: name.value.trim(),
        ...(selectedColor.value ? { color: selectedColor.value } : {}),
      }
      folder = await folderApi.createFolder(props.scopeType, req)
    }
    emit('saved', folder)
    close()
  }
  catch {
    toast.add({
      severity: 'error',
      summary: t('dialog.error'),
      detail: t('error.unknown'),
      life: 3000,
    })
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="props.visible"
    :header="dialogTitle"
    :modal="true"
    :closable="true"
    :style="{ width: '400px' }"
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- フォルダ名 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">
          {{ $t('scopeFolder.folderName') }}
          <span class="ml-1 text-red-500">*</span>
        </label>
        <InputText
          v-model="name"
          :maxlength="100"
          :placeholder="$t('scopeFolder.folderName')"
          class="w-full"
          @keyup.enter="save"
        />
      </div>

      <!-- カラー選択 -->
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium">{{ $t('scopeFolder.folderColor') }}</label>
        <div class="flex flex-wrap gap-2">
          <!-- 色なし -->
          <button
            type="button"
            class="h-8 w-8 rounded-full border-2 border-surface-300 bg-surface-0 transition-all hover:scale-110"
            :class="selectedColor === null ? 'ring-2 ring-primary ring-offset-2' : ''"
            :aria-label="$t('button.cancel')"
            @click="selectedColor = null"
          >
            <span class="text-xs text-surface-400">−</span>
          </button>
          <!-- プリセットカラー -->
          <button
            v-for="color in PRESET_COLORS"
            :key="color"
            type="button"
            class="h-8 w-8 rounded-full border-2 border-transparent transition-all hover:scale-110"
            :class="selectedColor === color ? 'ring-2 ring-primary ring-offset-2' : ''"
            :style="{ backgroundColor: color }"
            :aria-label="color"
            @click="selectedColor = color"
          />
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          outlined
          @click="close"
        />
        <Button
          :label="$t('button.save')"
          :loading="saving"
          :disabled="!name.trim()"
          @click="save"
        />
      </div>
    </template>
  </Dialog>
</template>
