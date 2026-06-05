<script setup lang="ts">
import type { GenerateHandoverPackRequest, HandoverPack, MemberTerm, PiiLevel } from '~/types/repairPlanHandover'

const props = defineProps<{
  scopeType: string
  scopeId: string
  teamId: string
}>()

const emit = defineEmits<{
  /** パック生成開始時に発火（一覧リフレッシュ用）。 */
  generated: [pack: HandoverPack]
}>()

const { t } = useI18n()
const notification = useNotification()
const { generatePack, getDownloadUrl } = useHandoverPackApi(props.scopeType, props.scopeId)
const { listTerms } = useTermApi(props.teamId)

const terms = ref<MemberTerm[]>([])
const selectedTermId = ref<number | null>(null)
const piiLevel = ref<PiiLevel>('STANDARD')
const memo = ref('')
const generating = ref(false)
const lastPack = ref<HandoverPack | null>(null)
const downloading = ref(false)

onMounted(async () => {
  try {
    terms.value = await listTerms()
    if (terms.value.length > 0 && terms.value[0]) {
      selectedTermId.value = terms.value[0].id
    }
  } catch {
    notification.error(t('common.fetch_failed'))
  }
})

const termOptions = computed(() =>
  terms.value.map((term) => ({
    label: `${term.userDisplayName}${term.roleName ? ` (${term.roleName})` : ''} ${term.termStart} 〜 ${term.termEnd}`,
    value: term.id,
  })),
)

async function handleGenerate() {
  if (selectedTermId.value === null) return

  const req: GenerateHandoverPackRequest = {
    termId: selectedTermId.value,
    piiLevel: piiLevel.value,
    memo: memo.value || undefined,
  }

  generating.value = true
  lastPack.value = null
  try {
    const pack = await generatePack(req)
    lastPack.value = pack
    emit('generated', pack)
  } catch {
    notification.error(t('common.save_failed'))
  } finally {
    generating.value = false
  }
}

async function handleDownload() {
  if (!lastPack.value) return

  downloading.value = true
  try {
    const res = await getDownloadUrl(lastPack.value.id)
    window.open(res.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch {
    notification.error(t('common.fetch_failed'))
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <SectionCard :title="$t('repair_plan.handover.builder.title')">
    <!-- 任期選択（必須） -->
    <div class="mb-4">
      <label class="mb-1 block text-sm font-medium">
        {{ $t('repair_plan.handover.builder.select_term') }}
        <span class="ml-1 text-red-500">*</span>
      </label>
      <Select
        v-model="selectedTermId"
        :options="termOptions"
        option-label="label"
        option-value="value"
        class="w-full"
        :placeholder="$t('repair_plan.handover.builder.select_term')"
      />
    </div>

    <!-- PII レベル（ラジオ） -->
    <div class="mb-4">
      <label class="mb-2 block text-sm font-medium">
        {{ $t('repair_plan.handover.builder.pii_level') }}
      </label>
      <div class="flex flex-col gap-2 sm:flex-row sm:gap-4">
        <label class="flex cursor-pointer items-center gap-2 text-sm">
          <RadioButton v-model="piiLevel" input-id="pii-standard" value="STANDARD" />
          <span>{{ $t('repair_plan.handover.builder.pii_standard') }}</span>
        </label>
        <label class="flex cursor-pointer items-center gap-2 text-sm">
          <RadioButton v-model="piiLevel" input-id="pii-anonymized" value="ANONYMIZED" />
          <span>{{ $t('repair_plan.handover.builder.pii_anonymized') }}</span>
        </label>
      </div>
    </div>

    <!-- メモ（任意） -->
    <div class="mb-5">
      <label class="mb-1 block text-sm font-medium">
        {{ $t('repair_plan.handover.builder.memo') }}
      </label>
      <Textarea
        v-model="memo"
        class="w-full"
        rows="3"
        :auto-resize="true"
      />
    </div>

    <!-- アクションボタン -->
    <div class="flex flex-wrap items-center gap-3">
      <Button
        :label="generating ? $t('repair_plan.handover.builder.generating') : $t('repair_plan.handover.builder.generate_button')"
        icon="pi pi-file-pdf"
        :loading="generating"
        :disabled="selectedTermId === null || generating"
        @click="handleGenerate"
      />

      <!-- 生成完了後のダウンロードボタン -->
      <Button
        v-if="lastPack && lastPack.status === 'READY'"
        :label="$t('repair_plan.handover.builder.download_button')"
        icon="pi pi-download"
        severity="secondary"
        :loading="downloading"
        @click="handleDownload"
      />
    </div>

    <!-- 透かし注意書き -->
    <p
      v-if="lastPack && lastPack.status === 'READY'"
      class="mt-3 text-xs text-surface-400 dark:text-surface-500"
    >
      <i class="pi pi-info-circle mr-1" />
      {{ $t('repair_plan.handover.watermark_notice') }}
    </p>

    <!-- 生成中メッセージ -->
    <Message
      v-if="lastPack && lastPack.status === 'GENERATING'"
      severity="info"
      :closable="false"
      class="mt-3"
    >
      {{ $t('repair_plan.handover.status.generating') }}
    </Message>

    <!-- 生成失敗メッセージ -->
    <Message
      v-if="lastPack && lastPack.status === 'FAILED'"
      severity="error"
      :closable="false"
      class="mt-3"
    >
      {{ $t('repair_plan.handover.status.failed') }}
    </Message>
  </SectionCard>
</template>
