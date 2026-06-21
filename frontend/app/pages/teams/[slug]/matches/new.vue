<script setup lang="ts">
// F08.10 試合作成（クイックスタート・04_frontend_and_ux.md §G.1b）。
// 必須は kind＋相手名のみ。venue/duration/日時/homeAway は任意「後で設定可」。
import { z } from 'zod'
import type { CreateMatchRequest, MatchKind, HomeAway } from '~/types/match'
import { MATCH_KINDS } from '~/types/match'

definePageMeta({ layout: 'team', middleware: 'auth' })

const route = useRoute()
const router = useRouter()
const teamSlug = String(route.params.slug)
const { t } = useI18n()

const { createMatch } = useMatchApi()
const { buildOffsetDateTimeStr } = useDatetime()

// === 組織／チーム 数値 ID の解決（slug→数値 orgId/teamId・useMatchOrgContext に集約）===
const { resolveContext } = useMatchOrgContext()
const orgId = ref<number | null>(null)
const teamId = ref<number | null>(null)
async function loadOrganizationId(): Promise<void> {
  const ctx = await resolveContext(teamSlug)
  orgId.value = ctx?.orgId ?? null
  teamId.value = ctx?.teamId ?? null
}

// === フォーム状態 ===
const form = reactive<{
  kind: MatchKind | null
  opponentName: string
  homeAway: HomeAway | null
  kickoffDate: Date | null
  venue: string
  durationMinutes: number | null
}>({
  kind: null,
  opponentName: '',
  homeAway: null,
  kickoffDate: null,
  venue: '',
  durationMinutes: null,
})

const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const kindOptions = computed(() =>
  MATCH_KINDS.map((k) => ({ value: k, label: t(`match.kind.${k}`) })),
)
const homeAwayOptions = computed<{ value: HomeAway; label: string }[]>(() => [
  { value: 'HOME', label: t('match.create.home_away.HOME') },
  { value: 'AWAY', label: t('match.create.home_away.AWAY') },
  { value: 'NEUTRAL', label: t('match.create.home_away.NEUTRAL') },
])

// 必須は kind と opponentName のみ
const schema = z.object({
  kind: z.enum(['PRACTICE', 'FRIENDLY', 'TOURNAMENT', 'LEAGUE']),
  opponentName: z.string().trim().min(1),
})

function validate(): boolean {
  const result = schema.safeParse({ kind: form.kind, opponentName: form.opponentName })
  if (result.success) {
    errors.value = {}
    return true
  }
  const errs: Record<string, string> = {}
  for (const issue of result.error.issues) {
    const key = String(issue.path[0])
    if (key === 'kind') errs.kind = t('match.create.kind_required')
    if (key === 'opponentName') errs.opponentName = t('match.create.opponent_required')
  }
  errors.value = errs
  return false
}

function selectKind(kind: MatchKind): void {
  form.kind = kind
  if (errors.value.kind) delete errors.value.kind
}

async function submit(): Promise<void> {
  if (!validate()) return
  await loadOrganizationId()
  if (orgId.value === null || teamId.value === null || form.kind === null) return

  submitting.value = true
  const body: CreateMatchRequest = {
    kind: form.kind,
    opponentName: form.opponentName.trim(),
  }
  if (form.homeAway) body.homeAway = form.homeAway
  if (form.venue.trim()) body.venue = form.venue.trim()
  if (form.durationMinutes != null) body.durationMinutes = form.durationMinutes
  if (form.kickoffDate) {
    const iso = buildOffsetDateTimeStr(form.kickoffDate)
    if (iso) body.kickoffAt = iso
  }

  try {
    const created = await createMatch(orgId.value, teamId.value, body)
    // 作成成功後は live.vue へ遷移する（§G.1a-2 = 即記録開始）。3-B で live.vue を実装済み。
    if (created.id) {
      void router.push(`/teams/${teamSlug}/matches/${created.id}/live`)
    } else {
      void router.push(`/teams/${teamSlug}/matches`)
    }
  } catch {
    // エラーは composable 内で通知済み
  } finally {
    submitting.value = false
  }
}

function cancel(): void {
  void router.push(`/teams/${teamSlug}/matches`)
}

onMounted(() => loadOrganizationId())
</script>

<template>
  <div class="mx-auto max-w-xl">
    <div class="mb-1 flex items-center gap-3">
      <PageHeader :title="$t('match.create.title')" size="sm" :back-to="`/teams/${teamSlug}/matches`" />
    </div>
    <p class="mb-6 text-sm text-surface-500">{{ $t('match.create.subtitle') }}</p>

    <form @submit.prevent="submit">
      <!-- 種別（必須・タップ選択） -->
      <SectionCard :title="$t('match.create.kind_label')" class="mb-4">
        <p class="mb-2 text-xs text-surface-400">{{ $t('match.create.kind_hint') }}</p>
        <div class="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <button
            v-for="opt in kindOptions"
            :key="opt.value"
            type="button"
            class="min-h-[2.75rem] rounded-lg border px-3 py-2 text-sm font-medium transition"
            :class="
              form.kind === opt.value
                ? 'border-primary bg-primary-50 text-primary'
                : 'border-surface-300 hover:border-primary-300'
            "
            @click="selectKind(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
        <small v-if="errors.kind" class="mt-1 block text-red-500">{{ errors.kind }}</small>
      </SectionCard>

      <!-- 相手名（必須） -->
      <SectionCard :title="$t('match.create.opponent_label')" class="mb-4">
        <InputText
          v-model="form.opponentName"
          :placeholder="$t('match.create.opponent_placeholder')"
          class="w-full"
          :invalid="!!errors.opponentName"
        />
        <small v-if="errors.opponentName" class="mt-1 block text-red-500">
          {{ errors.opponentName }}
        </small>
      </SectionCard>

      <!-- 任意項目（後で設定可） -->
      <SectionCard :title="$t('match.create.optional_section')" class="mb-6">
        <p class="mb-3 text-xs text-surface-400">{{ $t('match.create.optional_hint') }}</p>
        <div class="flex flex-col gap-4">
          <div class="flex flex-col gap-1">
            <label class="text-sm text-surface-600">{{ $t('match.create.home_away_label') }}</label>
            <Select
              v-model="form.homeAway"
              :options="homeAwayOptions"
              option-label="label"
              option-value="value"
              show-clear
              :placeholder="$t('match.list.filter.all')"
              class="w-full"
            />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm text-surface-600">{{ $t('match.create.kickoff_label') }}</label>
            <DatePicker
              v-model="form.kickoffDate"
              show-time
              hour-format="24"
              date-format="yy/mm/dd"
              class="w-full"
            />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm text-surface-600">{{ $t('match.create.venue_label') }}</label>
            <InputText
              v-model="form.venue"
              :placeholder="$t('match.create.venue_placeholder')"
              class="w-full"
            />
          </div>
          <div class="flex flex-col gap-1">
            <label class="text-sm text-surface-600">{{ $t('match.create.duration_label') }}</label>
            <InputNumber
              v-model="form.durationMinutes"
              :min="1"
              :max="240"
              class="w-full"
            />
          </div>
        </div>
      </SectionCard>

      <div class="flex justify-end gap-2">
        <Button :label="$t('match.create.cancel')" text type="button" @click="cancel" />
        <Button
          :label="$t('match.create.submit')"
          icon="pi pi-play"
          type="submit"
          :loading="submitting"
        />
      </div>
    </form>
  </div>
</template>
