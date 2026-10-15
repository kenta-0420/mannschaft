<script setup lang="ts">
/**
 * 祭詳細 — 参加表明（RSVP）セクション（F17.2 Wave2 ③お祭りの参加レイヤー §5.2/§5.6）。
 *
 * - SCHEDULED/ACTIVE のみ回答可（ENDED/CANCELLED は読み取りのみ・§5.6）
 * - GOING/MAYBE のみ（ABSENT は無い・§5.2・G3）。不参加は「無回答」と同じ扱い
 * - 自分の回答はハイライトし、再送で upsert・取り消しも可能（ENDED後は不可）
 * - 参加者一覧のみ表示する。未回答者一覧は絶対に出さない（G1）
 * - 表示は村ニックネーム（`displayName`）のみ。実名は出さない（G4）
 * - 一覧は size 上限付きページング（AC-14b・G3「全員を舐めて集計」の構造的抑止）。
 *   「もっと見る」で追加取得する（総数・hasNext は BE から返らないため、
 *   直前ページが size 丁度なら「まだあるかもしれない」と見なす簡易判定）
 *
 * ロジックは持たない（API 呼び出しは親が担う）。
 */
import Badge from 'primevue/badge'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import type {
  VillageFestivalRsvpResponse,
  VillageFestivalRsvpStatus,
} from '~/types/village'

const props = defineProps<{
  rsvps: VillageFestivalRsvpResponse[]
  myStatus: VillageFestivalRsvpStatus | null
  myRoleLabel: string | null
  /** 自分の参加表明ができるか（SCHEDULED/ACTIVE のみ） */
  canRespond: boolean
  loading: boolean
  hasMore: boolean
  loadingMore: boolean
}>()

const emit = defineEmits<{
  respond: [status: VillageFestivalRsvpStatus, roleLabel: string | null]
  cancelRsvp: []
  loadMore: []
}>()

const { t } = useI18n()

const roleLabelInput = ref(props.myRoleLabel ?? '')
watch(() => props.myRoleLabel, (v) => {
  roleLabelInput.value = v ?? ''
})

const statusOptions: VillageFestivalRsvpStatus[] = ['GOING', 'MAYBE']

function severityFor(status: VillageFestivalRsvpStatus): 'success' | 'warn' {
  return status === 'GOING' ? 'success' : 'warn'
}

function respond(status: VillageFestivalRsvpStatus) {
  emit('respond', status, roleLabelInput.value.trim() || null)
}
</script>

<template>
  <div class="flex flex-col gap-2">
    <h3 class="font-semibold">
      {{ t('village.festival.rsvp.title') }}
    </h3>

    <div v-if="canRespond" class="flex flex-col gap-2">
      <InputText
        v-model="roleLabelInput"
        :placeholder="t('village.festival.rsvp.roleLabelPlaceholder')"
        class="w-full"
        maxlength="60"
      />
      <div class="flex items-center gap-2 flex-wrap">
        <Button
          v-for="status in statusOptions"
          :key="status"
          :label="t(`village.festival.rsvp.${status.toLowerCase()}`)"
          size="small"
          :severity="severityFor(status)"
          :outlined="myStatus !== status"
          @click="respond(status)"
        />
        <Button
          v-if="myStatus"
          :label="t('village.festival.rsvp.cancel')"
          size="small"
          severity="secondary"
          text
          @click="emit('cancelRsvp')"
        />
      </div>
    </div>

    <div v-if="loading" class="text-center py-3 text-surface-500">
      <i class="pi pi-spin pi-spinner" />
    </div>
    <div v-else-if="rsvps.length === 0" class="text-xs text-surface-500">
      {{ t('village.festival.rsvp.empty') }}
    </div>
    <div v-else class="flex flex-wrap gap-2">
      <div
        v-for="r in rsvps"
        :key="r.id"
        class="inline-flex items-center gap-1 rounded-full border border-surface-200 px-2 py-1 text-xs dark:border-surface-700"
      >
        <span>{{ r.displayName ?? t('village.meetup.attendance.unknownMember') }}</span>
        <Badge
          :value="t(`village.festival.rsvp.${r.status.toLowerCase()}`)"
          :severity="severityFor(r.status)"
        />
        <span v-if="r.roleLabel" class="text-surface-400 dark:text-surface-300">
          ({{ r.roleLabel }})
        </span>
      </div>
    </div>

    <Button
      v-if="hasMore"
      :label="t('village.lobby.loadMore')"
      text
      size="small"
      :loading="loadingMore"
      @click="emit('loadMore')"
    />
  </div>
</template>
