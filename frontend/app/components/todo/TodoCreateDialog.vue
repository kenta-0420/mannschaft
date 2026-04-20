<script setup lang="ts">
const visible = defineModel<boolean>('visible', { required: true })

const todoApi = useTodoApi()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()

const createForm = ref({
  title: '',
  description: '',
  priority: 'MEDIUM' as string,
  dueDate: null as Date | null,
  scopeType: 'PERSONAL' as string,
  scopeId: null as number | null,
})
const creating = ref(false)

const scopeOptions = computed(() => {
  const opts: Array<{ label: string; scopeType: string; scopeId: number | null }> = [
    { label: '個人', scopeType: 'PERSONAL', scopeId: null },
  ]
  teamStore.myTeams.forEach((t) =>
    opts.push({ label: t.nickname1 || t.name, scopeType: 'TEAM', scopeId: t.id }),
  )
  orgStore.myOrganizations.forEach((o) =>
    opts.push({ label: o.nickname1 || o.name, scopeType: 'ORGANIZATION', scopeId: o.id }),
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

function resetForm() {
  createForm.value = {
    title: '',
    description: '',
    priority: 'MEDIUM',
    dueDate: null,
    scopeType: 'PERSONAL',
    scopeId: null,
  }
}

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
    notification.error('作成に失敗しました')
  } finally {
    creating.value = false
  }

  if (success) {
    visible.value = false
    notification.success('TODOを作成しました')
    await nextTick()
    emit('created')
  }
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    header="TODOを作成"
    modal
    class="w-full max-w-lg"
    @hide="resetForm"
  >
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="createForm.title"
          class="w-full"
          placeholder="TODOのタイトル"
          autofocus
        />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">説明（任意）</label>
        <Textarea
          v-model="createForm.description"
          class="w-full"
          rows="2"
          placeholder="詳細や補足"
          auto-resize
        />
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">共有先</label>
          <Select
            v-model="selectedScopeOption"
            :options="scopeOptions"
            option-label="label"
            class="w-full"
          />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">優先度</label>
          <Select
            v-model="createForm.priority"
            :options="[
              { label: '高', value: 'HIGH' },
              { label: '中', value: 'MEDIUM' },
              { label: '低', value: 'LOW' },
            ]"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium">期限（任意）</label>
        <DatePicker
          v-model="createForm.dueDate"
          class="w-full"
          date-format="yy/mm/dd"
          show-icon
        />
      </div>
    </div>

    <template #footer>
      <Button label="キャンセル" text severity="secondary" @click="visible = false" />
      <Button
        label="作成"
        icon="pi pi-check"
        :loading="creating"
        :disabled="!createForm.title.trim()"
        @click="submitCreate"
      />
    </template>
  </Dialog>
</template>
