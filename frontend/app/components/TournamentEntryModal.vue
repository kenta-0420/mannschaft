<script setup lang="ts">
import type {
  TournamentEntryMember,
  TeamMemberCandidate,
  EntryTemplate,
} from '~/types/tournament'

/**
 * F08.7 Phase 9 / 9-B — 大会エントリー管理モーダル。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>エントリーメンバー一覧の表示</li>
 *   <li>テンプレートからの一括適用（isAdmin のみ）</li>
 *   <li>チームメンバー候補からの手動追加・編集（isAdmin のみ）</li>
 *   <li>エントリー表の PDF ダウンロード</li>
 * </ul>
 */
interface Props {
  isOpen: boolean
  orgId: string
  tournamentId: number
  divisionId: number
  participantId: number
  teamId: string
  isAdmin: boolean
}

interface Emits {
  (e: 'close' | 'saved'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const { t } = useI18n()
const toast = useNuxtApp().$toast as { add: (opts: Record<string, unknown>) => void } | undefined

const {
  getEntryMembers,
  loadEntryMembersFromTeam,
  upsertEntryMembers,
  downloadEntryPdf,
  getEntryTemplates,
  applyEntryTemplate,
} = useTournamentApi()

// ===== 状態 =====

const loading = ref(false)
const saving = ref(false)
const applyingTemplate = ref(false)
const downloadingPdf = ref(false)

/** 現在のエントリーメンバー一覧 */
const entryMembers = ref<TournamentEntryMember[]>([])
/** チームメンバー候補（isAdmin 時のみ取得） */
const teamCandidates = ref<TeamMemberCandidate[]>([])
/** 人数制約 */
const minCount = ref<number | null>(null)
const maxCount = ref<number | null>(null)
const entryCount = ref(0)

/** テンプレート一覧（isAdmin 時のみ取得） */
const templates = ref<EntryTemplate[]>([])
const selectedTemplateId = ref<string | null>(null)
const overwriteExisting = ref(false)

/** 手動編集フォーム（候補ごとの背番号・ポジション） */
interface MemberForm {
  userId: number
  displayName: string
  memberNumber: string | null
  isAlreadyEntered: boolean
  jerseyNumber: number | null
  position: string | null
  notes: string | null
  selected: boolean
}

const memberForms = ref<MemberForm[]>([])

// ===== 算出プロパティ =====

const countRangeLabel = computed(() => {
  if (minCount.value === null && maxCount.value === null) {
    return t('tournament.entry.noLimit')
  }
  if (minCount.value !== null && maxCount.value !== null) {
    return t('tournament.entry.countRange', { min: minCount.value, max: maxCount.value })
  }
  if (minCount.value !== null) {
    return t('tournament.entry.countRange', { min: minCount.value, max: '∞' })
  }
  return t('tournament.entry.countRange', { min: 0, max: maxCount.value })
})

const selectedTemplate = computed(() =>
  templates.value.find(t => t.id === selectedTemplateId.value) ?? null,
)

// ===== データ取得 =====

async function fetchEntryMembers() {
  loading.value = true
  try {
    const res = await getEntryMembers(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.participantId,
      props.isAdmin,
    )
    entryMembers.value = res.entryMembers
    entryCount.value = res.entryCount
    minCount.value = res.minEntryCount
    maxCount.value = res.maxEntryCount

    if (props.isAdmin && res.teamMemberCandidates) {
      teamCandidates.value = res.teamMemberCandidates
      buildMemberForms(res.teamMemberCandidates)
    }
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
  finally {
    loading.value = false
  }
}

async function fetchTemplates() {
  if (!props.isAdmin) return
  try {
    const res = await getEntryTemplates(props.orgId, props.teamId)
    templates.value = Array.isArray(res) ? res : []
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
}

function buildMemberForms(candidates: TeamMemberCandidate[]) {
  // 既存エントリーの情報をマップ化
  const existingMap = new Map(entryMembers.value.map(m => [m.userId, m]))

  memberForms.value = candidates.map(c => {
    const existing = existingMap.get(c.userId)
    return {
      userId: c.userId,
      displayName: c.displayName,
      memberNumber: c.memberNumber,
      isAlreadyEntered: c.isAlreadyEntered,
      jerseyNumber: existing?.jerseyNumber ?? null,
      position: existing?.position ?? c.position ?? null,
      notes: existing?.notes ?? null,
      selected: c.isAlreadyEntered,
    }
  })
}

// ===== アクション =====

async function onApplyTemplate() {
  if (!selectedTemplateId.value) return
  applyingTemplate.value = true
  try {
    const res = await applyEntryTemplate(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.participantId,
      { templateId: selectedTemplateId.value, overwriteExisting: overwriteExisting.value },
    )
    entryMembers.value = res.entryMembers
    entryCount.value = res.total

    let detail = t('tournament.entry.template.applySuccess', {
      applied: res.applied,
      skipped: res.skipped,
    })
    if (res.skippedInactive > 0) {
      detail += '\n' + t('tournament.entry.template.skippedInactive', { count: res.skippedInactive })
    }
    toast?.add({ severity: 'success', summary: detail, life: 4000 })
    emit('saved')

    // フォームを再構築
    buildMemberForms(teamCandidates.value)
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
  finally {
    applyingTemplate.value = false
  }
}

async function onSaveMembers() {
  saving.value = true
  try {
    const members = memberForms.value
      .filter(f => f.selected)
      .map((f, i) => ({
        userId: f.userId,
        jerseyNumber: f.jerseyNumber,
        position: f.position,
        notes: f.notes,
        sortOrder: i + 1,
      }))

    const res = await upsertEntryMembers(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.participantId,
      { members },
    )
    entryMembers.value = res.entryMembers
    entryCount.value = res.entryCount

    toast?.add({
      severity: 'success',
      summary: t('tournament.entry.saveSuccess'),
      life: 3000,
    })
    emit('saved')
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
  finally {
    saving.value = false
  }
}

async function onLoadFromTeam() {
  saving.value = true
  try {
    const res = await loadEntryMembersFromTeam(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.participantId,
      { overwriteExisting: overwriteExisting.value },
    )
    entryMembers.value = res.entryMembers
    entryCount.value = res.total

    toast?.add({
      severity: 'success',
      summary: t('tournament.entry.loadSuccess', { added: res.added, skipped: res.skipped }),
      life: 4000,
    })
    emit('saved')
    buildMemberForms(teamCandidates.value)
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
  finally {
    saving.value = false
  }
}

async function onDownloadPdf() {
  downloadingPdf.value = true
  try {
    const blob = await downloadEntryPdf(
      props.orgId,
      props.tournamentId,
      props.divisionId,
      props.participantId,
    )
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `entry-${props.participantId}.pdf`
    a.click()
    URL.revokeObjectURL(url)
  }
  catch {
    // エラーは useApi の onResponseError で処理される
  }
  finally {
    downloadingPdf.value = false
  }
}

// ===== ウォッチ =====

watch(
  () => props.isOpen,
  async (isOpen) => {
    if (isOpen) {
      await Promise.all([fetchEntryMembers(), fetchTemplates()])
    }
    else {
      // モーダルを閉じたらリセット
      entryMembers.value = []
      teamCandidates.value = []
      memberForms.value = []
      templates.value = []
      selectedTemplateId.value = null
    }
  },
)
</script>

<template>
  <Dialog
    :visible="isOpen"
    :header="t('tournament.entry.title')"
    :style="{ width: '700px' }"
    :breakpoints="{ '768px': '95vw' }"
    modal
    @update:visible="emit('close')"
  >
    <!-- ローディング -->
    <div v-if="loading" class="flex justify-center py-8">
      <LoadingBounce />
    </div>

    <div v-else class="flex flex-col gap-6">
      <!-- ① エントリー一覧セクション -->
      <section>
        <div class="mb-3 flex items-center justify-between">
          <h3 class="text-lg font-semibold">
            {{ t('tournament.entry.title') }}
          </h3>
          <span class="text-sm text-surface-500">
            {{ t('tournament.entry.memberCount', { count: entryCount }) }}
            <span v-if="minCount !== null || maxCount !== null" class="ml-1">
              / {{ countRangeLabel }}
            </span>
          </span>
        </div>

        <div v-if="entryMembers.length === 0" class="rounded border border-surface-200 py-4 text-center text-surface-400 dark:border-surface-700">
          —
        </div>

        <DataTable
          v-else
          :value="entryMembers"
          size="small"
          class="text-sm"
          striped-rows
        >
          <Column :header="t('tournament.entry.jersey')" field="jerseyNumber" style="width: 80px">
            <template #body="{ data }">
              {{ data.jerseyNumber ?? '—' }}
            </template>
          </Column>
          <Column :header="t('tournament.entry.position')" field="position" style="width: 120px">
            <template #body="{ data }">
              {{ data.position ?? '—' }}
            </template>
          </Column>
          <Column header="名前" field="displayName" />
          <Column :header="t('tournament.entry.notes')" field="notes">
            <template #body="{ data }">
              {{ data.notes ?? '—' }}
            </template>
          </Column>
        </DataTable>
      </section>

      <!-- ② テンプレート適用セクション（isAdmin のみ） -->
      <section v-if="isAdmin">
        <h3 class="mb-3 text-lg font-semibold">
          {{ t('tournament.entry.template.title') }}
        </h3>

        <div v-if="templates.length === 0" class="text-sm text-surface-400">
          {{ t('tournament.entry.template.noTemplates') }}
        </div>

        <div v-else class="flex flex-col gap-3">
          <div class="flex gap-3">
            <Select
              v-model="selectedTemplateId"
              :options="templates"
              option-label="name"
              option-value="id"
              :placeholder="t('tournament.entry.template.select')"
              class="flex-1"
            />
            <Button
              :label="t('tournament.entry.template.apply')"
              :loading="applyingTemplate"
              :disabled="!selectedTemplateId"
              @click="onApplyTemplate"
            />
          </div>

          <div v-if="selectedTemplate" class="text-xs text-surface-500">
            {{ t('tournament.entry.memberCount', { count: selectedTemplate.memberCount }) }}
          </div>

          <div class="flex items-center gap-2">
            <Checkbox v-model="overwriteExisting" binary input-id="overwrite" />
            <label for="overwrite" class="text-sm cursor-pointer">
              既存のエントリーを上書きする
            </label>
          </div>
        </div>
      </section>

      <!-- ③ 手動追加・編集セクション（isAdmin のみ） -->
      <section v-if="isAdmin">
        <div class="mb-3 flex items-center justify-between">
          <h3 class="text-lg font-semibold">
            {{ t('tournament.entry.saveMembers') }}
          </h3>
          <Button
            :label="t('tournament.entry.loadFromTeam')"
            severity="secondary"
            size="small"
            :loading="saving"
            @click="onLoadFromTeam"
          />
        </div>

        <div v-if="memberForms.length === 0" class="text-sm text-surface-400">
          —
        </div>

        <div v-else class="flex flex-col gap-2">
          <div
            v-for="form in memberForms"
            :key="form.userId"
            class="flex items-center gap-3 rounded border border-surface-200 p-2 dark:border-surface-700"
            :class="{ 'bg-surface-50 dark:bg-surface-800': form.selected }"
          >
            <Checkbox v-model="form.selected" binary :input-id="`member-${form.userId}`" />
            <label :for="`member-${form.userId}`" class="w-32 cursor-pointer truncate text-sm font-medium">
              {{ form.displayName }}
              <span v-if="form.memberNumber" class="ml-1 text-xs text-surface-400">#{{ form.memberNumber }}</span>
            </label>

            <InputNumber
              v-model="form.jerseyNumber"
              :placeholder="t('tournament.entry.jersey')"
              :disabled="!form.selected"
              :use-grouping="false"
              :min="1"
              :max="999"
              class="w-24"
              size="small"
            />

            <InputText
              v-model="form.position"
              :placeholder="t('tournament.entry.position')"
              :disabled="!form.selected"
              class="w-28"
              size="small"
              maxlength="30"
            />

            <InputText
              v-model="form.notes"
              :placeholder="t('tournament.entry.notes')"
              :disabled="!form.selected"
              class="flex-1"
              size="small"
              maxlength="100"
            />

            <Tag v-if="form.isAlreadyEntered" :value="t('tournament.entry.alreadyEntered')" severity="info" />
          </div>

          <div class="flex justify-end pt-2">
            <Button
              :label="t('tournament.entry.saveMembers')"
              :loading="saving"
              @click="onSaveMembers"
            />
          </div>
        </div>
      </section>
    </div>

    <!-- ④ PDF出力ボタン（フッター） -->
    <template #footer>
      <div class="flex justify-between">
        <Button
          :label="t('tournament.entry.downloadPdf')"
          icon="pi pi-file-pdf"
          severity="secondary"
          :loading="downloadingPdf"
          @click="onDownloadPdf"
        />
        <Button
          :label="$t('common.close')"
          text
          @click="emit('close')"
        />
      </div>
    </template>
  </Dialog>
</template>
