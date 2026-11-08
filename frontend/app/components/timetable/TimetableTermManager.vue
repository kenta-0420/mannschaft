<script setup lang="ts">
/**
 * 時間割 — 学期の管理（一覧・追加・削除）
 *
 * チーム画面と組織画面で共用する（CMP-261105-1507）。学期の作成・削除 API の呼び出しと
 * エラーの解釈（400 の fieldErrors / 409 TIMETABLE_021・TIMETABLE_022）はここに集約し、
 * 成功したら `changed` を emit して親に一覧の再取得を任せる。
 * 追加・削除の操作は canManage が true のときだけ出す（ADMIN と MANAGE_TIMETABLE を持つ DEPUTY_ADMIN）。
 *
 * 一覧の取得は親が行い、その状態を loadState で受け取る。取得失敗（error）は「学期がありません」の
 * 空状態へ落とさず、再試行できるエラー表示にする。再試行は `retry` を emit し、親が初回と同じ取得処理を呼ぶ。
 */
import dayjs from 'dayjs'
import type { TimetableTerm } from '~/types/timetable'
import {
  TERM_DATE_OVERLAP_CODE,
  TERM_HAS_TIMETABLES_CODE,
  parseTermApiError,
} from '~/utils/timetableTermError'

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeSlug: string
  terms: TimetableTerm[]
  canManage: boolean
  hint: string
  /** 親が行う一覧取得の状態。省略時は取得済みとして扱う。 */
  loadState?: 'loading' | 'error' | 'loaded'
}>()

const emit = defineEmits<{
  (e: 'changed' | 'retry'): void
}>()

const { t } = useI18n()
const timetableApi = useTimetableApi()
const notification = useNotification()
const { confirmAction } = useConfirmDialog()
const { formatDate, userTimezone } = useDatetime()

const showDialog = ref(false)
const submitting = ref(false)
const deletingTermId = ref<number | null>(null)
const fieldErrors = ref<Record<string, string>>({})
const formError = ref('')

function emptyForm() {
  return {
    name: '',
    startDate: '',
    endDate: '',
    academicYear: String(dayjs().tz(userTimezone.value).year()),
    sortOrder: 0,
  }
}
const form = ref(emptyForm())

function openDialog() {
  form.value = emptyForm()
  fieldErrors.value = {}
  formError.value = ''
  showDialog.value = true
}

const ACADEMIC_YEAR_MIN = 2000
const ACADEMIC_YEAR_MAX = 2100

/** 全角数字を半角へ正規化し、前後の空白を除く（「２０２７」→「2027」）。 */
function normalizeYearText(text: string): string {
  return text.normalize('NFKC').trim()
}

/** 年度の入力を整数に解釈する。範囲外を丸めず、そのままエラーとして示すため null を返す。 */
function parseAcademicYear(text: string): number | null {
  const normalized = normalizeYearText(text)
  if (!/^\d+$/.test(normalized)) return null
  const year = Number(normalized)
  return year >= ACADEMIC_YEAR_MIN && year <= ACADEMIC_YEAR_MAX ? year : null
}

function onYearBlur() {
  form.value.academicYear = normalizeYearText(form.value.academicYear)
}

/** 送信前の検証。DB の CHECK（start_date < end_date）と同じく同日も不可。 */
function validate(): boolean {
  const errors: Record<string, string> = {}
  form.value.academicYear = normalizeYearText(form.value.academicYear)
  if (form.value.academicYear === '') {
    errors.academicYear = t('timetable.term_academic_year_required')
  } else if (parseAcademicYear(form.value.academicYear) == null) {
    errors.academicYear = t('timetable.term_academic_year_range', {
      min: ACADEMIC_YEAR_MIN,
      max: ACADEMIC_YEAR_MAX,
    })
  }
  if (form.value.startDate && form.value.endDate && form.value.startDate >= form.value.endDate) {
    errors.endDate = t('timetable.term_end_before_start')
  }
  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

async function submit() {
  formError.value = ''
  if (!form.value.name || !form.value.startDate || !form.value.endDate) return
  if (!validate()) return
  submitting.value = true
  const body = {
    name: form.value.name,
    startDate: form.value.startDate,
    endDate: form.value.endDate,
    academicYear: parseAcademicYear(form.value.academicYear) ?? undefined,
    sortOrder: form.value.sortOrder,
  }
  try {
    if (props.scopeType === 'team') {
      await timetableApi.createTeamTerm(props.scopeSlug, body)
    } else {
      await timetableApi.createOrgTerm(props.scopeSlug, body)
    }
    notification.success(t('timetable.term_success'))
    showDialog.value = false
    emit('changed')
  } catch (error) {
    const parsed = parseTermApiError(error)
    if (Object.keys(parsed.fieldErrors).length > 0) {
      fieldErrors.value = parsed.fieldErrors
    }
    if (parsed.code === TERM_DATE_OVERLAP_CODE) {
      formError.value = t('timetable.term_date_overlap')
      notification.error(formError.value)
    } else {
      notification.error(t('timetable.term_error'))
    }
  } finally {
    submitting.value = false
  }
}

function requestDelete(term: TimetableTerm) {
  confirmAction({
    header: t('dialog.confirm_title'),
    message: t('timetable.term_delete_confirm', { name: term.name }),
    onAccept: () => deleteTerm(term.id),
  })
}

async function deleteTerm(termId: number) {
  deletingTermId.value = termId
  try {
    await timetableApi.deleteTerm(termId)
    notification.success(t('timetable.term_delete_success'))
    emit('changed')
  } catch (error) {
    const parsed = parseTermApiError(error)
    if (parsed.code === TERM_HAS_TIMETABLES_CODE) {
      notification.error(t('timetable.term_has_timetables'))
    } else {
      notification.error(t('dialog.error'))
    }
  } finally {
    deletingTermId.value = null
  }
}
</script>

<template>
  <div>
    <div class="mb-4 flex items-center justify-between gap-2">
      <p class="text-sm text-surface-500">{{ hint }}</p>
      <Button
        v-if="canManage && loadState !== 'error'"
        :label="$t('timetable.add_term')"
        icon="pi pi-plus"
        size="small"
        data-testid="term-add-button"
        @click="openDialog"
      />
    </div>
    <DashboardErrorState
      v-if="loadState === 'error'"
      :message="$t('timetable.term_load_error')"
      testid="term-load-error-state"
      @retry="emit('retry')"
    />
    <DataTable v-else :value="terms" data-key="id" striped-rows :loading="loadState === 'loading'">
      <template #empty>
        <div class="py-8 text-center text-surface-500">
          {{ $t('timetable.no_term') }}
        </div>
      </template>
      <Column field="academicYear" :header="$t('timetable.term_academic_year')" style="width: 80px" />
      <Column field="name" :header="$t('timetable.term_name')" />
      <Column :header="$t('timetable.term_start')">
        <template #body="{ data }">
          {{ formatDate(data.startDate) }}
        </template>
      </Column>
      <Column :header="$t('timetable.term_end')">
        <template #body="{ data }">
          {{ formatDate(data.endDate) }}
        </template>
      </Column>
      <Column v-if="canManage" :header="$t('label.actions')" style="width: 80px">
        <template #body="{ data }">
          <Button
            v-tooltip="$t('button.delete')"
            icon="pi pi-trash"
            size="small"
            text
            severity="danger"
            :aria-label="$t('button.delete')"
            :loading="deletingTermId === data.id"
            :data-testid="`term-delete-${data.id}`"
            @click="requestDelete(data)"
          />
        </template>
      </Column>
    </DataTable>

    <Dialog
      v-model:visible="showDialog"
      :header="$t('timetable.add_term')"
      :modal="true"
      class="w-full max-w-md"
    >
      <div class="space-y-4">
        <small
          v-if="formError"
          class="block text-red-500"
          role="alert"
          data-testid="term-form-error"
        >{{ formError }}</small>
        <div>
          <label class="mb-1 block text-sm font-medium" for="term-academic-year">
            {{ $t('timetable.term_academic_year') }}
            <span class="text-red-500" aria-hidden="true">*</span>
          </label>
          <InputText
            id="term-academic-year"
            v-model="form.academicYear"
            class="w-full"
            inputmode="numeric"
            autocomplete="off"
            :invalid="!!fieldErrors.academicYear"
            :aria-describedby="fieldErrors.academicYear ? 'term-academic-year-error' : undefined"
            aria-required="true"
            data-testid="term-year-input"
            @blur="onYearBlur"
          />
          <small
            v-if="fieldErrors.academicYear"
            id="term-academic-year-error"
            class="mt-1 block text-red-500"
            role="alert"
            data-testid="term-academic-year-error"
          >{{ fieldErrors.academicYear }}</small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="term-name">{{ $t('timetable.term_name') }}</label>
          <InputText
            id="term-name"
            v-model="form.name"
            class="w-full"
            :placeholder="$t('timetable.term_name_placeholder')"
            :invalid="!!fieldErrors.name"
            data-testid="term-name-input"
          />
          <small
            v-if="fieldErrors.name"
            class="mt-1 block text-red-500"
            role="alert"
            data-testid="term-name-error"
          >{{ fieldErrors.name }}</small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="term-start">{{ $t('timetable.term_start') }}</label>
          <InputText
            id="term-start"
            v-model="form.startDate"
            type="date"
            class="w-full"
            :invalid="!!fieldErrors.startDate"
            data-testid="term-start-input"
          />
          <small
            v-if="fieldErrors.startDate"
            class="mt-1 block text-red-500"
            role="alert"
            data-testid="term-start-date-error"
          >{{ fieldErrors.startDate }}</small>
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium" for="term-end">{{ $t('timetable.term_end') }}</label>
          <InputText
            id="term-end"
            v-model="form.endDate"
            type="date"
            class="w-full"
            :invalid="!!fieldErrors.endDate"
            :aria-describedby="fieldErrors.endDate ? 'term-end-date-error' : undefined"
            data-testid="term-end-input"
          />
          <small
            v-if="fieldErrors.endDate"
            id="term-end-date-error"
            class="mt-1 block text-red-500"
            role="alert"
            data-testid="term-end-date-error"
          >{{ fieldErrors.endDate }}</small>
        </div>
      </div>
      <template #footer>
        <Button
          :label="$t('button.cancel')"
          severity="secondary"
          @click="showDialog = false"
        />
        <Button
          :label="$t('timetable.add_term')"
          icon="pi pi-check"
          :loading="submitting"
          :disabled="!form.name || !form.startDate || !form.endDate"
          data-testid="term-dialog-submit"
          @click="submit"
        />
      </template>
    </Dialog>
  </div>
</template>
