<script setup lang="ts">
import dayjs from 'dayjs'
import { isValidHourlyRate, useShiftHourlyRateApi } from '~/composables/shift/useShiftHourlyRateApi'
// composables/team・composables/shift は nuxt.config の imports.dirs に無く auto-import されないため明示 import する
import { useTeamMembers } from '~/composables/team/useTeamMembers'
import type { MemberResponse } from '~/types/member'
import type { ShiftHourlyRateResponse } from '~/types/shift'

/**
 * チーム設定 > 時給設定（CMP-260910-1555）。
 *
 * 時給を登録する入口が画面に 1 つも無かったため、シフトを公開しても予算の消化額が
 * 0 円のままで閾値警告も一度も出ない、という欠陥の入口側の根治。
 * 時給は「ユーザー × チーム × 適用開始日」の履歴管理型なので、登録は常に追加であり
 * 過去の設定は消えない（表示は「現在有効な時給」＋ダイアログ内の履歴一覧）。
 */
definePageMeta({ layout: 'team', middleware: 'auth' })

interface MemberRateRow {
  member: MemberResponse
  rate: ShiftHourlyRateResponse | null
}

const route = useRoute()
const teamSlug = String(route.params.slug)
const { t } = useI18n()
const notification = useNotification()
const teamApi = useTeamApi()
const { getAllMembers } = useTeamMembers()
const { getHourlyRate, setHourlyRate } = useShiftHourlyRateApi()
const { isAdmin, loadPermissions } = useRoleAccess('team', teamSlug)

const loading = ref(true)
const saving = ref(false)
const rows = ref<MemberRateRow[]>([])
const teamNumericId = ref<number | null>(null)

const showDialog = ref(false)
const targetRow = ref<MemberRateRow | null>(null)
const history = ref<ShiftHourlyRateResponse[]>([])
const historyLoading = ref(false)
const formRate = ref<number | null>(null)
const formEffectiveFrom = ref<Date>(new Date())

const missingCount = computed(() => rows.value.filter(r => r.rate === null).length)

/** 数値 teamId を解決する。時給 API は slug ではなく数値 ID を要求するため必須。 */
async function resolveTeamNumericId(): Promise<number> {
  const res = await teamApi.getTeam(teamSlug)
  const numericId = Number(res.data.numericId)
  if (!Number.isInteger(numericId) || numericId <= 0) {
    throw new Error('team_numeric_id_missing')
  }
  return numericId
}

/** 同時接続を膨らませないよう、時給取得を CHUNK_SIZE 件ずつに区切って実行する。 */
const RATE_FETCH_CHUNK_SIZE = 20

async function fetchRatesInChunks(
  members: MemberResponse[],
  teamId: string,
  date: string,
): Promise<MemberRateRow[]> {
  const result: MemberRateRow[] = []
  for (let i = 0; i < members.length; i += RATE_FETCH_CHUNK_SIZE) {
    const chunk = members.slice(i, i + RATE_FETCH_CHUNK_SIZE)
    const resolved = await Promise.all(
      chunk.map(async (member): Promise<MemberRateRow> => {
        const rates = await getHourlyRate(teamId, member.userId, date)
        return { member, rate: rates[0] ?? null }
      }),
    )
    result.push(...resolved)
  }
  return result
}

async function loadRows() {
  loading.value = true
  try {
    const teamId = teamNumericId.value ?? (await resolveTeamNumericId())
    teamNumericId.value = teamId
    const today = dayjs().format('YYYY-MM-DD')
    // メンバーは全ページ取得する。先頭ページだけを読むと、ページサイズを超える人数のチームで
    // 後半のメンバーが画面に現れず時給を登録できない。登録されなかったメンバーはシフト公開の
    // たびに予算消化がスキップされ続けるため、ここで取りこぼすと本ページの存在意義が消える。
    const members = await getAllMembers(teamSlug)
    // 時給は 1 人ずつ引く（一括取得の API が無いため）。全員を同時に投げると大規模チームで
    // ブラウザの同時接続数を食い潰すので、小さな束に区切って順に処理する。
    rows.value = await fetchRatesInChunks(members, String(teamId), today)
  }
  catch (error) {
    const status
      = (error as { statusCode?: number }).statusCode
        ?? (error as { status?: number }).status
        ?? (error as { response?: { status?: number } }).response?.status
    if (status === 403 || status === 404) {
      showError(createError({ statusCode: status, statusMessage: t('shift.hourlyRate.loadFailed') }))
      return
    }
    notification.error(t('shift.hourlyRate.loadFailed'))
  }
  finally {
    loading.value = false
  }
}

function openEdit(row: MemberRateRow) {
  targetRow.value = row
  formRate.value = row.rate ? Number(row.rate.hourlyRate) : null
  formEffectiveFrom.value = new Date()
  history.value = []
  showDialog.value = true
  void loadHistory(row)
}

/** 対象メンバーの時給履歴（適用開始日の降順）を読む。過去の設定が残っていることを画面で示す。 */
async function loadHistory(row: MemberRateRow) {
  if (teamNumericId.value === null) return
  historyLoading.value = true
  try {
    history.value = await getHourlyRate(String(teamNumericId.value), row.member.userId)
  }
  catch {
    notification.error(t('shift.hourlyRate.historyLoadFailed'))
  }
  finally {
    historyLoading.value = false
  }
}

async function submit() {
  const row = targetRow.value
  const teamId = teamNumericId.value
  if (!row || teamId === null) return
  // BE は @Positive なので 0 以下は必ず 400 になる。画面側で同じ条件を課して入口で弾く。
  if (!isValidHourlyRate(formRate.value)) {
    notification.error(t('shift.hourlyRate.validation.ratePositive'))
    return
  }
  saving.value = true
  try {
    await setHourlyRate(String(teamId), {
      userId: row.member.userId,
      hourlyRate: formRate.value,
      effectiveFrom: dayjs(formEffectiveFrom.value).format('YYYY-MM-DD'),
    })
    notification.success(t('shift.hourlyRate.saved'))
    showDialog.value = false
    await loadRows()
  }
  catch {
    notification.error(t('shift.hourlyRate.saveFailed'))
  }
  finally {
    saving.value = false
  }
}

onMounted(async () => {
  const result = await loadPermissions()
  if (!result.ok) {
    loading.value = false
    notification.error(t('shift.hourlyRate.permissionLoadFailed'))
    return
  }
  if (!isAdmin.value) {
    // 導線を隠すだけでは URL 直打ちを防げない。BE も 403 を返すが、画面側でも明示的に弾く。
    showError(createError({ statusCode: 403, statusMessage: t('shift.hourlyRate.forbidden') }))
    return
  }
  await loadRows()
})
</script>

<template>
  <div class="container mx-auto max-w-4xl p-4">
    <PageHeader :title="$t('shift.hourlyRate.title')" class="mb-4" />
    <p class="mb-4 text-sm text-surface-600 dark:text-surface-300">
      {{ $t('shift.hourlyRate.description') }}
    </p>

    <PageLoading v-if="loading" />

    <template v-else>
      <Message
        v-if="missingCount > 0"
        severity="warn"
        :closable="false"
        class="mb-4"
        data-testid="hourly-rate-missing-banner"
      >
        {{ $t('shift.hourlyRate.missingWarning', { count: missingCount }) }}
      </Message>

      <div class="overflow-x-auto">
        <DataTable :value="rows" striped-rows data-key="member.userId" data-testid="hourly-rate-table">
          <Column :header="$t('shift.hourlyRate.column.member')">
            <template #body="{ data }: { data: MemberRateRow }">
              <span class="font-medium">{{ data.member.displayName }}</span>
            </template>
          </Column>
          <Column :header="$t('shift.hourlyRate.column.rate')">
            <template #body="{ data }: { data: MemberRateRow }">
              <span v-if="data.rate" data-testid="hourly-rate-value">
                {{ $t('shift.hourlyRate.perHour', { amount: Number(data.rate.hourlyRate).toLocaleString('ja-JP') }) }}
              </span>
              <Tag v-else severity="warn" :value="$t('shift.hourlyRate.unset')" data-testid="hourly-rate-unset" />
            </template>
          </Column>
          <Column :header="$t('shift.hourlyRate.column.effectiveFrom')">
            <template #body="{ data }: { data: MemberRateRow }">
              <span v-if="data.rate">{{ data.rate.effectiveFrom }}</span>
              <span v-else class="text-surface-400">—</span>
            </template>
          </Column>
          <Column :header="$t('label.actions')">
            <template #body="{ data }: { data: MemberRateRow }">
              <Button
                :label="data.rate ? $t('button.edit') : $t('shift.hourlyRate.setButton')"
                :icon="data.rate ? 'pi pi-pencil' : 'pi pi-plus'"
                size="small"
                text
                class="min-h-11"
                :data-testid="`hourly-rate-edit-${data.member.userId}`"
                @click="openEdit(data)"
              />
            </template>
          </Column>
          <template #empty>
            <DashboardEmptyState :message="$t('shift.hourlyRate.noMembers')" />
          </template>
        </DataTable>
      </div>
    </template>

    <Dialog
      v-model:visible="showDialog"
      :header="$t('shift.hourlyRate.dialogTitle', { name: targetRow?.member.displayName ?? '' })"
      :style="{ width: '480px' }"
      modal
    >
      <form class="flex flex-col gap-4" @submit.prevent="submit">
        <div class="flex flex-col gap-2">
          <label for="hourly-rate-input">{{ $t('shift.hourlyRate.column.rate') }}</label>
          <InputNumber
            id="hourly-rate-input"
            v-model="formRate"
            :min="1"
            mode="currency"
            currency="JPY"
            locale="ja-JP"
            class="w-full"
            data-testid="hourly-rate-input"
          />
        </div>
        <div class="flex flex-col gap-2">
          <label for="hourly-rate-effective-from">{{ $t('shift.hourlyRate.column.effectiveFrom') }}</label>
          <DatePicker
            id="hourly-rate-effective-from"
            v-model="formEffectiveFrom"
            date-format="yy-mm-dd"
            class="w-full"
            data-testid="hourly-rate-effective-from"
          />
          <small class="text-surface-500">{{ $t('shift.hourlyRate.effectiveFromHint') }}</small>
        </div>

        <div class="flex flex-col gap-2">
          <span class="font-medium">{{ $t('shift.hourlyRate.historyTitle') }}</span>
          <PageLoading v-if="historyLoading" />
          <ul v-else-if="history.length > 0" class="text-sm" data-testid="hourly-rate-history">
            <li v-for="item in history" :key="item.id" class="py-1">
              {{ item.effectiveFrom }} —
              {{ $t('shift.hourlyRate.perHour', { amount: Number(item.hourlyRate).toLocaleString('ja-JP') }) }}
            </li>
          </ul>
          <span v-else class="text-sm text-surface-500">{{ $t('shift.hourlyRate.noHistory') }}</span>
        </div>

        <div class="flex justify-end gap-2">
          <Button
            :label="$t('button.cancel')"
            severity="secondary"
            text
            class="min-h-11"
            @click="showDialog = false"
          />
          <Button
            type="submit"
            :label="$t('button.save')"
            icon="pi pi-save"
            :loading="saving"
            class="min-h-11"
            data-testid="hourly-rate-save-btn"
          />
        </div>
      </form>
    </Dialog>
  </div>
</template>
