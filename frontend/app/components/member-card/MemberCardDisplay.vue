<script setup lang="ts">
import type { MemberCard, MemberCardQr } from '~/types/member-card'

const props = defineProps<{
  card: MemberCard
}>()

const emit = defineEmits<{
  suspend: [id: number]
  reactivate: [id: number]
}>()

const memberCardApi = useMemberCardApi()
const notification = useNotification()

const qrData = ref<MemberCardQr | null>(null)
const loadingQr = ref(false)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const statusSeverity = computed(() => {
  const map: Record<string, string> = { ACTIVE: 'success', SUSPENDED: 'warn', REVOKED: 'danger' }
  return map[props.card.status] ?? 'info'
})

const statusLabel = computed(() => {
  const map: Record<string, string> = { ACTIVE: '有効', SUSPENDED: '停止中', REVOKED: '無効' }
  return map[props.card.status] ?? props.card.status
})

const scopeLabel = computed(() => {
  const map: Record<string, string> = { PLATFORM: 'プラットフォーム', TEAM: 'チーム', ORGANIZATION: '組織' }
  return map[props.card.scopeType] ?? props.card.scopeType
})

async function loadQr() {
  if (props.card.status !== 'ACTIVE') return
  loadingQr.value = true
  try {
    qrData.value = await memberCardApi.getQr(props.card.id)
  } catch {
    notification.error('QRコードの取得に失敗しました')
  } finally {
    loadingQr.value = false
  }
}

async function handleRegenerate() {
  try {
    qrData.value = await memberCardApi.regenerate(props.card.id)
    notification.success('QRコードを再生成しました')
  } catch {
    notification.error('QRコードの再生成に失敗しました')
  }
}

onMounted(() => {
  loadQr()
  refreshTimer = setInterval(loadQr, 4 * 60 * 1000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<template>
  <Card class="w-full">
    <template #header>
      <div class="flex items-center justify-between px-4 pt-4">
        <div class="flex items-center gap-2">
          <i class="pi pi-id-card text-xl text-primary" />
          <span class="text-sm text-surface-500">{{ scopeLabel }}</span>
        </div>
        <Badge :value="statusLabel" :severity="statusSeverity" />
      </div>
    </template>
    <template #content>
      <div class="text-center">
        <p v-if="card.scopeName" class="mb-2 text-lg font-semibold">{{ card.scopeName }}</p>
        <p class="mb-4 font-mono text-sm text-surface-500">{{ card.cardNumber }}</p>

        <div v-if="card.status === 'ACTIVE'" class="mb-4">
          <div v-if="loadingQr" class="flex justify-center py-8">
            <ProgressSpinner style="width: 40px; height: 40px" />
          </div>
          <div v-else-if="qrData" class="inline-block rounded-lg border border-surface-200 bg-white p-4 dark:border-surface-600">
            <img
              :src="`https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(qrData.token)}`"
              alt="QRコード"
              class="h-48 w-48"
            />
            <p class="mt-2 text-xs text-surface-400">5分ごとに自動更新</p>
          </div>
        </div>

        <div v-if="card.checkinCount > 0" class="mb-4 text-sm text-surface-500">
          <i class="pi pi-check-circle mr-1" />
          チェックイン {{ card.checkinCount }} 回
        </div>
      </div>
    </template>
    <template #footer>
      <div class="flex justify-center gap-2">
        <Button
          v-if="card.status === 'ACTIVE'"
          label="QR再生成"
          icon="pi pi-refresh"
          severity="secondary"
          size="small"
          @click="handleRegenerate"
        />
        <Button
          v-if="card.status === 'ACTIVE'"
          label="一時停止"
          icon="pi pi-pause"
          severity="warn"
          size="small"
          outlined
          @click="emit('suspend', card.id)"
        />
        <Button
          v-if="card.status === 'SUSPENDED'"
          label="再開"
          icon="pi pi-play"
          severity="success"
          size="small"
          @click="emit('reactivate', card.id)"
        />
      </div>
    </template>
  </Card>
</template>
