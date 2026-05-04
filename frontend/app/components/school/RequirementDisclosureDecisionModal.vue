<script setup lang="ts">
import { ref } from 'vue'
import type { DisclosureRequest, WithholdRequest, DisclosureMode, DisclosureRecipients } from '~/types/school'

const props = defineProps<{
  visible: boolean
  evaluationId?: number
  teamId: number
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  disclosed: [evaluationId: number, req: DisclosureRequest]
  withheld: [evaluationId: number, req: WithholdRequest]
}>()

const { t } = useI18n()

type ActiveTab = 'DISCLOSE' | 'WITHHOLD'

const activeTab = ref<ActiveTab>('DISCLOSE')
const selectedMode = ref<DisclosureMode>('WITH_NUMBERS')
const selectedRecipients = ref<DisclosureRecipients>('BOTH')
const message = ref('')
const withholdReason = ref('')
const submitting = ref(false)

const modeOptions = computed(() => [
  { label: t('school.disclosure.mode.WITH_NUMBERS'), value: 'WITH_NUMBERS' as DisclosureMode },
  { label: t('school.disclosure.mode.WITHOUT_NUMBERS'), value: 'WITHOUT_NUMBERS' as DisclosureMode },
  { label: t('school.disclosure.mode.MEETING_REQUEST_ONLY'), value: 'MEETING_REQUEST_ONLY' as DisclosureMode },
])

const recipientsOptions = computed(() => [
  { label: t('school.disclosure.recipients.STUDENT_ONLY'), value: 'STUDENT_ONLY' as DisclosureRecipients },
  { label: t('school.disclosure.recipients.GUARDIAN_ONLY'), value: 'GUARDIAN_ONLY' as DisclosureRecipients },
  { label: t('school.disclosure.recipients.BOTH'), value: 'BOTH' as DisclosureRecipients },
])

const tabOptions = computed(() => [
  { label: t('school.disclosure.tabDisclose'), value: 'DISCLOSE' as ActiveTab },
  { label: t('school.disclosure.tabWithhold'), value: 'WITHHOLD' as ActiveTab },
])

function resetForm(): void {
  activeTab.value = 'DISCLOSE'
  selectedMode.value = 'WITH_NUMBERS'
  selectedRecipients.value = 'BOTH'
  message.value = ''
  withholdReason.value = ''
  submitting.value = false
}

function onHide(): void {
  resetForm()
  emit('update:visible', false)
}

async function onSubmitDisclose(): Promise<void> {
  if (!props.evaluationId) return
  submitting.value = true
  try {
    const req: DisclosureRequest = {
      mode: selectedMode.value,
      recipients: selectedRecipients.value,
      message: message.value.trim() || undefined,
    }
    emit('disclosed', props.evaluationId, req)
    onHide()
  } finally {
    submitting.value = false
  }
}

async function onSubmitWithhold(): Promise<void> {
  if (!props.evaluationId || !withholdReason.value.trim()) return
  submitting.value = true
  try {
    const req: WithholdRequest = {
      withholdReason: withholdReason.value.trim(),
    }
    emit('withheld', props.evaluationId, req)
    onHide()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog
    data-testid="disclosure-decision-modal"
    :visible="visible"
    :header="$t('school.disclosure.title')"
    modal
    :draggable="false"
    style="width: 32rem"
    @update:visible="onHide"
  >
    <!-- タブ切り替え -->
    <div class="mb-4">
      <SelectButton
        v-model="activeTab"
        :options="tabOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        class="w-full"
        data-testid="disclosure-tab-selector"
      />
    </div>

    <!-- 開示タブ -->
    <template v-if="activeTab === 'DISCLOSE'">
      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-2 block font-medium">
          {{ $t('school.disclosure.mode.label') }}
        </label>
        <SelectButton
          v-model="selectedMode"
          :options="modeOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          class="flex-col w-full"
          data-testid="disclosure-mode-selector"
        />
      </div>

      <div class="mb-4">
        <label class="text-sm text-surface-500 mb-2 block font-medium">
          {{ $t('school.disclosure.recipients.label') }}
        </label>
        <SelectButton
          v-model="selectedRecipients"
          :options="recipientsOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          class="w-full"
          data-testid="disclosure-recipients-selector"
        />
      </div>

      <div class="mb-2">
        <label class="text-sm text-surface-500 mb-1 block font-medium">
          {{ $t('school.disclosure.message') }}
        </label>
        <Textarea
          v-model="message"
          class="w-full"
          rows="3"
          :placeholder="$t('school.disclosure.messagePlaceholder')"
          data-testid="disclosure-message-input"
        />
      </div>
    </template>

    <!-- 非開示タブ -->
    <template v-else>
      <div class="mb-2">
        <label class="text-sm text-surface-500 mb-1 block font-medium">
          {{ $t('school.disclosure.withholdReason') }} *
        </label>
        <Textarea
          v-model="withholdReason"
          class="w-full"
          rows="3"
          :placeholder="$t('school.disclosure.withholdReasonPlaceholder')"
          data-testid="withhold-reason-input"
        />
      </div>
    </template>

    <template #footer>
      <Button
        data-testid="disclosure-cancel-btn"
        :label="$t('common.cancel')"
        severity="secondary"
        @click="onHide"
      />
      <Button
        v-if="activeTab === 'DISCLOSE'"
        data-testid="disclosure-submit-btn"
        :label="submitting ? $t('school.disclosure.submitting') : $t('school.disclosure.submit')"
        severity="primary"
        :disabled="submitting"
        :loading="submitting"
        @click="onSubmitDisclose"
      />
      <Button
        v-else
        data-testid="withhold-submit-btn"
        :label="submitting ? $t('school.disclosure.submitting') : $t('school.disclosure.submit')"
        severity="warning"
        :disabled="submitting || !withholdReason.trim()"
        :loading="submitting"
        @click="onSubmitWithhold"
      />
    </template>
  </Dialog>
</template>
