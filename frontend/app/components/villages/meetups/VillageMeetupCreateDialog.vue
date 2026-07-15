<script setup lang="ts">
/**
 * 村寄合作成 Dialog — 表示専用の子コンポーネント。
 *
 * フォーム状態は親 (pages/villages/[id]/meetups.vue) が保持し、
 * 子は表示と入力イベントの転送に専念する。
 *
 * - ロジックは持たない（保存 / キャンセル / 候補日操作は emit）
 */
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'

/**
 * 候補日フォーム行。
 *
 * BE (`MeetupCreateRequest.candidateDates`) は `List<LocalDate>` ＝ 日付のみを受け取り、
 * 開始 / 終了時刻を保持する項目を持たない。そのため時刻入力は提供しない。
 */
interface CandidateDateForm {
  candidateDate: string
}

interface MeetupFormState {
  title: string
  description: string
  location: string
  candidateDates: CandidateDateForm[]
}

defineProps<{
  visible: boolean
}>()

const form = defineModel<MeetupFormState>('form', { required: true })

const emit = defineEmits<{
  'update:visible': [value: boolean]
  addCandidateDateRow: []
  removeCandidateDateRow: [index: number]
  submit: []
}>()

const { t } = useI18n()
</script>

<template>
  <Dialog
    :visible="visible"
    modal
    :draggable="false"
    :header="t('village.meetup.createTitle')"
    :style="{ width: '36rem' }"
    :breakpoints="{ '640px': '92vw' }"
    @update:visible="(v: boolean) => emit('update:visible', v)"
  >
    <div class="flex flex-col gap-3">
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.meetup.meetupTitle') }}
        </label>
        <InputText v-model="form.title" class="w-full" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.meetup.description') }}
        </label>
        <Textarea v-model="form.description" class="w-full" rows="3" />
      </div>
      <div>
        <label class="block text-sm font-medium mb-1">
          {{ t('village.meetup.location') }}
        </label>
        <InputText v-model="form.location" class="w-full" />
      </div>
      <div>
        <div class="flex items-center justify-between mb-2">
          <label class="block text-sm font-medium">
            {{ t('village.meetup.candidateDates') }}
          </label>
          <Button
            :label="t('village.meetup.addCandidateDate')"
            icon="pi pi-plus"
            size="small"
            text
            @click="emit('addCandidateDateRow')"
          />
        </div>
        <div class="flex flex-col gap-2">
          <div
            v-for="(c, idx) in form.candidateDates"
            :key="idx"
            class="grid grid-cols-[1fr_auto] gap-2 items-end"
          >
            <InputText
              v-model="c.candidateDate"
              type="date"
              class="w-full"
            />
            <Button
              icon="pi pi-trash"
              severity="danger"
              text
              size="small"
              :disabled="form.candidateDates.length <= 1"
              :aria-label="t('village.meetup.removeCandidateDate')"
              @click="emit('removeCandidateDateRow', idx)"
            />
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <Button
        :label="t('village.action.cancel')"
        severity="secondary"
        text
        @click="emit('update:visible', false)"
      />
      <Button
        :label="t('village.action.save')"
        icon="pi pi-check"
        severity="primary"
        @click="emit('submit')"
      />
    </template>
  </Dialog>
</template>
