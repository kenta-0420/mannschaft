<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })

const todoApi = useTodoApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()
const auth = useAuthStore()
const { t } = useI18n()

// ドラフトキー: ユーザーIDが取れる場合はユーザー固有、取れない場合は汎用キー
const draftKey = computed<string>(() => {
  const uid = auth.user?.id
  return uid != null ? `todo-create-draft-${uid}` : 'todo-create-draft-anon'
})

type CreateForm = {
  title: string
  description: string
  priority: string
  startDate: Date | null
  dueDate: Date | null
  scopeType: string
  scopeId: string | null
}

const createForm = ref<CreateForm>({
  title: '',
  description: '',
  priority: 'MEDIUM',
  startDate: null,
  dueDate: null,
  scopeType: 'PERSONAL',
  scopeId: null,
})
const creating = ref(false)
// 下書き復元フラッシュの表示フラグ
const draftRestoredFlash = ref(false)

// useFormDraft: createForm を source として監視し自動保存
const { clear, restore, savedFlash } = useFormDraft<CreateForm>(draftKey.value, {
  source: createForm as unknown as import('vue').WatchSource<CreateForm>,
  debounceMs: 1000,
  flashMs: 1500,
  autoRestore: false, // ダイアログ open 時に手動で restore する
})

const scopeOptions = computed(() => {
  const opts: Array<{ label: string; scopeType: string; scopeId: string | null }> = [
    { label: t('common.personal') ?? '個人', scopeType: 'PERSONAL', scopeId: null },
  ]
  teamStore.myTeams.forEach((t) =>
    opts.push({ label: t.nickname1 || t.name, scopeType: 'TEAM', scopeId: String(t.id) }),
  )
  orgStore.myOrganizations.forEach((o) =>
    opts.push({ label: o.nickname1 || o.name, scopeType: 'ORGANIZATION', scopeId: String(o.id) }),
  )
  return opts
})

const selectedScopeOption = computed({
  get: () =>
    scopeOptions.value.find(
      (o) => o.scopeType === createForm.value.scopeType && o.scopeId === createForm.value.scopeId,
    ) ?? scopeOptions.value[0]!,
  set: (val) => {
    createForm.value.scopeType = val.scopeType
    createForm.value.scopeId = val.scopeId
  },
})

const priorityOptions = [
  { label: t('todo.priorityValue.HIGH'), value: 'HIGH' },
  { label: t('todo.priorityValue.MEDIUM'), value: 'MEDIUM' },
  { label: t('todo.priorityValue.LOW'), value: 'LOW' },
]

function resetForm() {
  createForm.value = {
    title: '',
    description: '',
    priority: 'MEDIUM',
    startDate: null,
    dueDate: null,
    scopeType: 'PERSONAL',
    scopeId: null,
  }
  draftRestoredFlash.value = false
}

// ダイアログが開いたとき: 下書き復元を試みる
watch(visible, (isOpen) => {
  if (!isOpen) return
  const saved = restore()
  if (saved !== null) {
    createForm.value = {
      title: saved.title ?? '',
      description: saved.description ?? '',
      priority: saved.priority ?? 'MEDIUM',
      startDate: saved.startDate ? new Date(saved.startDate) : null,
      dueDate: saved.dueDate ? new Date(saved.dueDate) : null,
      scopeType: saved.scopeType ?? 'PERSONAL',
      scopeId: saved.scopeId ?? null,
    }
    // 復元フラッシュを表示
    draftRestoredFlash.value = true
    setTimeout(() => {
      draftRestoredFlash.value = false
    }, 2000)
  }
})

const emit = defineEmits<{ created: [] }>()

async function submitCreate() {
  if (!createForm.value.title.trim()) return
  creating.value = true
  let success = false
  try {
    const body = {
      title: createForm.value.title.trim(),
      description: createForm.value.description.trim() || undefined,
      priority: createForm.value.priority,
      startDate: createForm.value.startDate
        ? createForm.value.startDate.toISOString().slice(0, 10)
        : undefined,
      dueDate: createForm.value.dueDate
        ? createForm.value.dueDate.toISOString().slice(0, 10)
        : undefined,
    }
    if (createForm.value.scopeType === 'PERSONAL') {
      await todoApi.createPersonalTodo(body)
    } else {
      const type =
        createForm.value.scopeType === 'TEAM' ? ('team' as const) : ('organization' as const)
      await todoApi.createTodo(type, createForm.value.scopeId!, body)
    }
    success = true
  } catch {
    notification.error(t('todo.create.create_failed'))
  } finally {
    creating.value = false
  }

  if (success) {
    clear()
    visible.value = false
    notification.success(t('todo.create.create_success'))
    await nextTick()
    emit('created')
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    :header="t('todo.create.dialog_title')"
    modal
    class="w-full max-w-lg"
    @hide="resetForm"
  >
    <div class="space-y-4">
      <!-- 下書き復元フラッシュ -->
      <p v-if="draftRestoredFlash" class="text-xs text-emerald-500">
        {{ t('todo.create.draft_restored') }}
      </p>

      <!-- タイトル（必須・常時表示） -->
      <div>
        <label class="mb-1 block text-sm font-medium">
          {{ t('todo.create.title_label') }} <span class="text-red-500">*</span>
        </label>
        <InputText
          v-model="createForm.title"
          class="w-full"
          :placeholder="t('todo.create.title_placeholder')"
          autofocus
          @keydown.enter="submitCreate"
        />
      </div>

      <!-- 下書き自動保存フラッシュ -->
      <p v-if="savedFlash" class="text-xs text-emerald-500">
        {{ t('action_memo.input.draft_saved') }}
      </p>

      <!-- 詳細セクション（折りたたみ） -->
      <details class="group">
        <summary
          class="flex cursor-pointer select-none list-none items-center gap-1 text-sm text-surface-500 hover:text-surface-700 dark:hover:text-surface-300"
        >
          <!-- 展開/折りたたみアイコン -->
          <i class="pi pi-chevron-right text-xs transition-transform group-open:rotate-90" />
          {{ t('todo.create.details_toggle') }}
        </summary>

        <div class="mt-3 space-y-4">
          <!-- 説明 -->
          <div>
            <label class="mb-1 block text-sm font-medium">
              {{ t('todo.create.description_label') }}
            </label>
            <Textarea
              v-model="createForm.description"
              class="w-full"
              rows="2"
              :placeholder="t('todo.create.description_placeholder')"
              auto-resize
            />
          </div>

          <!-- 共有先・優先度 -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('todo.create.scope_label') }}
              </label>
              <Select
                v-model="selectedScopeOption"
                :options="scopeOptions"
                option-label="label"
                class="w-full"
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('todo.create.priority_label') }}
              </label>
              <Select
                v-model="createForm.priority"
                :options="priorityOptions"
                option-label="label"
                option-value="value"
                class="w-full"
              />
            </div>
          </div>

          <!-- 開始日・期限 -->
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('todo.create.start_date_label') }}
              </label>
              <DatePicker
                v-model="createForm.startDate"
                class="w-full"
                date-format="yy/mm/dd"
                show-icon
              />
            </div>
            <div>
              <label class="mb-1 block text-sm font-medium">
                {{ t('todo.create.due_date_label') }}
              </label>
              <DatePicker
                v-model="createForm.dueDate"
                class="w-full"
                date-format="yy/mm/dd"
                show-icon
              />
            </div>
          </div>
        </div>
      </details>
    </div>

    <template #footer>
      <Button
        :label="t('todo.create.cancel_button')"
        text
        severity="secondary"
        @click="visible = false"
      />
      <Button
        :label="t('todo.create.submit_button')"
        icon="pi pi-check"
        :loading="creating"
        :disabled="!createForm.title.trim()"
        @click="submitCreate"
      />
    </template>
  </Dialog>
</template>
