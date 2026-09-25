<script setup lang="ts">
import type { ConfirmableRecipientGroup, ConfirmableTarget, ConfirmableTargetType } from '~/types/confirmable'
const props = defineProps<{ scopeType: 'TEAM' | 'ORGANIZATION'; scopeId: string }>()
const { listRecipientGroups, createRecipientGroup, deleteRecipientGroup } = useConfirmableNotificationApi()
const { handleApiError } = useErrorHandler()
const { t } = useI18n()
const groups = ref<ConfirmableRecipientGroup[]>([])
const loading = ref(false)
const saving = ref(false)
const name = ref('')
const targetType = ref<ConfirmableTargetType>('TEAM')
const targetId = ref<number | null>(null)
const targetTypeOptions = computed(() => ['ORGANIZATION', 'TEAM'].map(value => ({ label: t(`confirmable.target_type.${value}`), value })))
async function load() { loading.value = true; try { groups.value = (await listRecipientGroups(props.scopeType, props.scopeId)).data } catch (error) { handleApiError(error, 'confirmable recipient groups') } finally { loading.value = false } }
async function create() {
  if (!name.value.trim() || targetId.value === null) return
  saving.value = true
  try { await createRecipientGroup(props.scopeType, props.scopeId, { name: name.value.trim(), targets: [{ type: targetType.value, id: targetId.value }] }); name.value = ''; targetId.value = null; await load() } catch (error) { handleApiError(error, 'confirmable recipient group create') } finally { saving.value = false }
}
async function remove(groupId: string) { try { await deleteRecipientGroup(props.scopeType, props.scopeId, groupId); await load() } catch (error) { handleApiError(error, 'confirmable recipient group delete') } }
function targetLabel(target: ConfirmableTarget) { return `${t(`confirmable.target_type.${target.type}`)} #${target.id}` }
onMounted(load)
</script>
<template>
  <SectionCard :title="$t('confirmable.recipient_groups')">
    <div class="flex flex-col gap-3">
      <p class="text-sm text-surface-500">{{ $t('confirmable.recipient_groups_help') }}</p>
      <div class="grid gap-2 md:grid-cols-3"><InputText v-model="name" :placeholder="$t('confirmable.group_name')" /><Select v-model="targetType" :options="targetTypeOptions" option-label="label" option-value="value" /><InputNumber v-model="targetId" :placeholder="$t('confirmable.target_id')" :use-grouping="false" /></div>
      <div><Button :label="$t('button.create')" :loading="saving" @click="create" /></div>
      <PageLoading v-if="loading" size="28px" />
      <DataTable v-else :value="groups"><Column field="name" :header="$t('confirmable.group_name')" /><Column :header="$t('confirmable.targets')"><template #body="{ data }: { data: ConfirmableRecipientGroup }"><span>{{ data.targets.map(targetLabel).join(', ') }}</span></template></Column><Column><template #body="{ data }: { data: ConfirmableRecipientGroup }"><Button :label="$t('button.delete')" text severity="danger" @click="remove(data.id)" /></template></Column></DataTable>
    </div>
  </SectionCard>
</template>
