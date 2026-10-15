<script setup lang="ts">
import type {
  BulletinArchiveFolder,
  BulletinScopeType,
  CreateArchiveFolderRequest,
  UpdateArchiveFolderRequest,
} from '~/types/bulletin'

interface Props {
  visible: boolean
  scopeType: BulletinScopeType
  /** TEAM/ORGANIZATION は数値ID、VILLAGE は UUID 文字列 */
  scopeId: string | number
  /** 編集対象。未指定なら新規作成。 */
  editTarget?: BulletinArchiveFolder | null
  /** 新規作成時の親フォルダ UUID（ルート直下なら null）。 */
  parentFolderId?: string | null
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'saved', folder: BulletinArchiveFolder): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const { t } = useI18n()
const { showError } = useNotification()
const { createArchiveFolder, updateArchiveFolder } = useBulletinApi()

/** 色／アイコン選択 UI は F15.3 ScopeFolder のパターンを流用。 */
const PRESET_COLORS = ['#EF4444', '#F97316', '#EAB308', '#22C55E', '#3B82F6', '#8B5CF6']
const PRESET_ICONS = [
  'pi-folder',
  'pi-calendar',
  'pi-book',
  'pi-file',
  'pi-flag',
  'pi-bookmark',
  'pi-star',
  'pi-inbox',
  'pi-briefcase',
  'pi-megaphone',
]

const name = ref('')
const selectedColor = ref<string | null>(null)
const selectedIcon = ref<string | null>(null)
const saving = ref(false)

const isEdit = computed(() => props.editTarget != null)
const dialogTitle = computed(() =>
  isEdit.value ? t('bulletin.archive.editFolder') : t('bulletin.archive.createFolder'),
)

watch(
  () => props.visible,
  (val) => {
    if (!val) return
    if (props.editTarget) {
      name.value = props.editTarget.name
      selectedColor.value = props.editTarget.color
      selectedIcon.value = props.editTarget.icon
    }
    else {
      name.value = ''
      selectedColor.value = null
      selectedIcon.value = null
    }
  },
)

function close() {
  emit('update:visible', false)
}

async function save() {
  const trimmed = name.value.trim()
  if (!trimmed) return
  saving.value = true
  try {
    let folder: BulletinArchiveFolder
    if (isEdit.value && props.editTarget) {
      const req: UpdateArchiveFolderRequest = {
        name: trimmed,
        color: selectedColor.value,
        icon: selectedIcon.value,
      }
      const res = await updateArchiveFolder(
        props.scopeType,
        props.scopeId,
        props.editTarget.id,
        req,
      )
      folder = res.data
    }
    else {
      const req: CreateArchiveFolderRequest = {
        name: trimmed,
        parentFolderId: props.parentFolderId ?? null,
        ...(selectedColor.value ? { color: selectedColor.value } : {}),
        ...(selectedIcon.value ? { icon: selectedIcon.value } : {}),
      }
      const res = await createArchiveFolder(props.scopeType, props.scopeId, req)
      folder = res.data
    }
    emit('saved', folder)
    close()
  }
  catch {
    showError(t('bulletin.archive.saveFailed'))
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
    :style="{ width: '420px' }"
    @update:visible="close"
  >
    <div class="flex flex-col gap-4">
      <!-- フォルダ名 -->
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">
          {{ $t('bulletin.archive.folderName') }}
          <span class="ml-1 text-red-500">*</span>
        </label>
        <InputText
          v-model="name"
          :maxlength="100"
          :placeholder="$t('bulletin.archive.folderNamePlaceholder')"
          class="w-full"
          @keyup.enter="save"
        />
      </div>

      <!-- カラー選択 -->
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium">{{ $t('bulletin.archive.folderColor') }}</label>
        <div class="flex flex-wrap gap-2">
          <button
            type="button"
            class="h-8 w-8 rounded-full border-2 border-surface-300 bg-surface-0 transition-all hover:scale-110"
            :class="selectedColor === null ? 'ring-2 ring-primary ring-offset-2' : ''"
            :aria-label="$t('bulletin.archive.noColor')"
            @click="selectedColor = null"
          >
            <span class="text-xs text-surface-400">−</span>
          </button>
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

      <!-- アイコン選択 -->
      <div class="flex flex-col gap-2">
        <label class="text-sm font-medium">{{ $t('bulletin.archive.folderIcon') }}</label>
        <div class="flex flex-wrap gap-2">
          <button
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-md border-2 border-surface-300 bg-surface-0 transition-all hover:scale-110"
            :class="selectedIcon === null ? 'ring-2 ring-primary ring-offset-2' : ''"
            :aria-label="$t('bulletin.archive.noIcon')"
            @click="selectedIcon = null"
          >
            <span class="text-xs text-surface-400">−</span>
          </button>
          <button
            v-for="icon in PRESET_ICONS"
            :key="icon"
            type="button"
            class="flex h-8 w-8 items-center justify-center rounded-md border-2 border-transparent bg-surface-100 text-surface-600 transition-all hover:scale-110 dark:bg-surface-800 dark:text-surface-300"
            :class="selectedIcon === icon ? 'ring-2 ring-primary ring-offset-2' : ''"
            :aria-label="icon"
            @click="selectedIcon = icon"
          >
            <i :class="`pi ${icon}`" />
          </button>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="flex justify-end gap-2">
        <Button
          :label="$t('bulletin.archive.cancel')"
          severity="secondary"
          outlined
          @click="close"
        />
        <Button
          :label="$t('bulletin.archive.save')"
          :loading="saving"
          :disabled="!name.trim()"
          @click="save"
        />
      </div>
    </template>
  </Dialog>
</template>
