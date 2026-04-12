<script setup lang="ts">
definePageMeta({ middleware: 'auth' })

const scopeStore = useScopeStore()
const scopeType = computed(() => scopeStore.current.type as 'team' | 'organization')
const scopeId = computed(() => scopeStore.current.id ?? 0)
const { success, error: showError } = useNotification()
const { listFields, createField } = useMemberProfileApi()

interface ProfileField {
  id: number
  fieldName: string
  fieldType: string
  isRequired: boolean
  sortOrder: number
  isActive: boolean
  options: string[] | null
}

const fields = ref<ProfileField[]>([])
const loading = ref(true)
const showDialog = ref(false)
const form = ref({ fieldName: '', fieldType: 'TEXT', isRequired: false, options: '' })
const saving = ref(false)

const FIELD_TYPES = [
  { label: 'テキスト', value: 'TEXT' },
  { label: '数値', value: 'NUMBER' },
  { label: '日付', value: 'DATE' },
  { label: '選択肢', value: 'SELECT' },
]

function fieldTypeLabel(t: string) {
  return FIELD_TYPES.find((f) => f.value === t)?.label ?? t
}

async function load() {
  loading.value = true
  try {
    fields.value = await listFields(scopeType.value, scopeId.value) as ProfileField[]
  } catch {
    showError('項目の取得に失敗しました')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.value.fieldName) return
  saving.value = true
  try {
    const options = form.value.fieldType === 'SELECT'
      ? form.value.options.split('\n').map((s) => s.trim()).filter(Boolean)
      : undefined
    await createField(scopeType.value, scopeId.value, {
      fieldName: form.value.fieldName,
      fieldType: form.value.fieldType,
      isRequired: form.value.isRequired,
      options,
    })
    success('項目を追加しました')
    showDialog.value = false
    await load()
  } catch {
    showError('保存に失敗しました')
  } finally {
    saving.value = false
  }
}

watch(scopeId, (v) => { if (v) load() })
onMounted(() => { if (scopeId.value) load() })
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <div class="mb-6 flex items-center justify-between">
      <div>
        <PageHeader title="メンバー紹介管理"><p class="text-sm text-surface-500">メンバープロフィールのカスタム項目を管理します</p></PageHeader>
      </div>
      <Button label="項目を追加" icon="pi pi-plus" @click="showDialog = true" />
    </div>

    <PageLoading v-if="loading" />

    <DataTable v-else :value="fields" striped-rows data-key="id">
      <template #empty>
        <DashboardEmptyState icon="pi pi-id-card" message="カスタム項目がありません" />
      </template>
      <Column field="fieldName" header="項目名" />
      <Column header="種類" style="width: 120px">
        <template #body="{ data }">
          <Tag :value="fieldTypeLabel(data.fieldType)" severity="info" />
        </template>
      </Column>
      <Column header="必須" style="width: 80px">
        <template #body="{ data }">
          <i :class="data.isRequired ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-300'" />
        </template>
      </Column>
      <Column header="有効" style="width: 80px">
        <template #body="{ data }">
          <i :class="data.isActive ? 'pi pi-check text-green-500' : 'pi pi-minus text-surface-300'" />
        </template>
      </Column>
      <Column header="順序" style="width: 60px">
        <template #body="{ data }">
          <span class="text-sm text-surface-500">{{ data.sortOrder }}</span>
        </template>
      </Column>
    </DataTable>

    <Dialog v-model:visible="showDialog" header="項目を追加" :style="{ width: '440px' }" modal>
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium">項目名 <span class="text-red-500">*</span></label>
          <InputText v-model="form.fieldName" class="w-full" placeholder="例: 好きな食べ物" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">種類</label>
          <Select
            v-model="form.fieldType"
            :options="FIELD_TYPES"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>
        <div v-if="form.fieldType === 'SELECT'">
          <label class="mb-1 block text-sm font-medium">選択肢（1行に1つ）</label>
          <Textarea v-model="form.options" class="w-full" rows="4" placeholder="選択肢1&#10;選択肢2&#10;選択肢3" />
        </div>
        <div class="flex items-center gap-2">
          <Checkbox v-model="form.isRequired" :binary="true" input-id="isRequired" />
          <label for="isRequired" class="text-sm">必須</label>
        </div>
      </div>
      <template #footer>
        <Button label="キャンセル" severity="secondary" text @click="showDialog = false" />
        <Button label="追加" :loading="saving" :disabled="!form.fieldName" @click="save" />
      </template>
    </Dialog>
  </div>
</template>
