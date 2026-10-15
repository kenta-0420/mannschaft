<script setup lang="ts">
const props = defineProps<{
  messageId: number
}>()

const emit = defineEmits<{
  migrated: [bulletinThreadUrl: string]
}>()

const { t } = useI18n()
const api = useApi()
const { migrateToBoard } = useChatApi()
const { showSuccess, showError } = useNotification()

const showDialog = ref(false)
const migrating = ref(false)
const threadTitle = ref('')
const selectedBoardId = ref<number | null>(null)
const copyHistory = ref(true)

interface BulletinBoard {
  id: number
  title: string
}
const boards = ref<BulletinBoard[]>([])
const boardsLoading = ref(false)

async function loadBoards() {
  boardsLoading.value = true
  try {
    const res = await api<{ data: BulletinBoard[] }>('/api/v1/bulletin-boards')
    boards.value = res.data
  } catch {
    showError(t('chat.boardMigration.error'))
  } finally {
    boardsLoading.value = false
  }
}

function openDialog() {
  showDialog.value = true
  loadBoards()
}

async function confirmMigration() {
  if (!selectedBoardId.value || !threadTitle.value.trim()) return
  migrating.value = true
  try {
    const res = await migrateToBoard(
      props.messageId,
      selectedBoardId.value,
      threadTitle.value.trim(),
      copyHistory.value,
    )
    showSuccess(t('chat.boardMigration.success'))
    showDialog.value = false
    emit('migrated', res.data.bulletinThreadUrl)
  } catch {
    showError(t('chat.boardMigration.error'))
  } finally {
    migrating.value = false
  }
}
</script>

<template>
  <div
    class="mb-1 flex items-center gap-2 rounded border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs dark:border-amber-800 dark:bg-amber-900/20"
    data-testid="board-migration-banner"
  >
    <i class="pi pi-info-circle text-amber-600 dark:text-amber-400" />
    <span class="flex-1 text-amber-700 dark:text-amber-300">{{ $t('chat.boardMigration.banner') }}</span>
    <Button
      :label="$t('chat.boardMigration.createThread')"
      size="small"
      severity="warning"
      outlined
      class="py-0.5 text-xs"
      data-testid="board-migration-open-dialog"
      @click="openDialog"
    />
  </div>

  <Dialog
    v-model:visible="showDialog"
    :header="$t('chat.boardMigration.dialogTitle')"
    modal
    :style="{ width: '420px' }"
    data-testid="board-migration-dialog"
    @hide="showDialog = false"
  >
    <div class="flex flex-col gap-4">
      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('chat.boardMigration.titleLabel') }}</label>
        <InputText
          v-model="threadTitle"
          class="w-full"
          :placeholder="$t('chat.boardMigration.titleLabel')"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label class="text-sm font-medium">{{ $t('chat.boardMigration.boardLabel') }}</label>
        <Select
          v-model="selectedBoardId"
          :options="boards"
          option-label="title"
          option-value="id"
          :loading="boardsLoading"
          :placeholder="$t('chat.boardMigration.boardLabel')"
          class="w-full"
        />
      </div>

      <div class="flex items-center gap-2">
        <Checkbox v-model="copyHistory" :binary="true" input-id="copy-history" />
        <label for="copy-history" class="cursor-pointer text-sm">{{ $t('chat.boardMigration.copyHistory') }}</label>
      </div>
    </div>

    <template #footer>
      <Button
        :label="$t('button.cancel')"
        severity="secondary"
        text
        @click="showDialog = false"
      />
      <Button
        :label="$t('chat.boardMigration.confirm')"
        :loading="migrating"
        :disabled="!selectedBoardId || !threadTitle.trim()"
        @click="confirmMigration"
      />
    </template>
  </Dialog>
</template>
