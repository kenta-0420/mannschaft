<script setup lang="ts">
import type { ConfirmableRecipientGroup, ConfirmableTarget } from '~/types/confirmable'

const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string }>()
const emit = defineEmits<{ changed: [] }>()
const { listRecipientGroups, createRecipientGroup, updateRecipientGroup, deleteRecipientGroup } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const groups = ref<ConfirmableRecipientGroup[]>([])
const loading = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const name = ref('')
const targets = ref<ConfirmableTarget[]>([])

async function load() {
  if (!props.scopeId) return
  loading.value = true
  try { groups.value = (await listRecipientGroups(props.scopeType, props.scopeId)).data }
  catch (error) { handleApiError(error, 'confirmable recipient groups') }
  finally { loading.value = false }
}
function edit(group: ConfirmableRecipientGroup) {
  editingId.value = group.id
  name.value = group.name
  targets.value = [...group.targets]
}
function reset() { editingId.value = null; name.value = ''; targets.value = [] }
async function save() {
  if (!name.value.trim() || !targets.value.length) return
  saving.value = true
  try {
    const request = { name: name.value.trim(), targets: targets.value }
    if (editingId.value) await updateRecipientGroup(props.scopeType, props.scopeId, editingId.value, request)
    else await createRecipientGroup(props.scopeType, props.scopeId, request)
    reset(); await load(); emit('changed')
  } catch (error) { handleApiError(error, 'confirmable recipient group save') }
  finally { saving.value = false }
}
async function remove(groupId: string) {
  try { await deleteRecipientGroup(props.scopeType, props.scopeId, groupId); if (editingId.value === groupId) reset(); await load(); emit('changed') }
  catch (error) { handleApiError(error, 'confirmable recipient group delete') }
}
function targetLabel(target: ConfirmableTarget) { return `${t(`confirmable.target_type.${target.type}`)} #${target.id}` }
watch(() => props.scopeId, load, { immediate: true })
</script>
<template>
  <SectionCard :title="$t('confirmable.recipient_groups')">
    <div class="flex flex-col gap-3">
      <p class="text-sm text-surface-500">{{ $t('confirmable.recipient_groups_help') }}</p>
      <InputText v-model="name" :placeholder="$t('confirmable.group_name')" />
      <ConfirmableTargetPicker v-model="targets" :scope-type="scopeType" :scope-id="scopeId" />
      <div class="flex gap-2">
        <Button :label="editingId ? $t('button.save') : $t('button.create')" :loading="saving" :disabled="!name.trim() || !targets.length" @click="save" />
        <Button v-if="editingId" :label="$t('button.cancel')" text @click="reset" />
      </div>
      <PageLoading v-if="loading" size="28px" />
      <DataTable v-else :value="groups">
        <Column field="name" :header="$t('confirmable.group_name')" />
        <Column :header="$t('confirmable.targets')"><template #body="{ data }: { data: ConfirmableRecipientGroup }">{{ data.targets.map(targetLabel).join(', ') }}</template></Column>
        <Column><template #body="{ data }: { data: ConfirmableRecipientGroup }"><Button :label="$t('button.edit')" text @click="edit(data)" /><Button :label="$t('button.delete')" text severity="danger" @click="remove(data.id)" /></template></Column>
      </DataTable>
    </div>
  </SectionCard>
</template>
