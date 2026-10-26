<script setup lang="ts">
interface Incident {
  pagePattern: string
  message: string
  // severity は INFO / WARNING / CRITICAL の3値
  severity: string
  since: string
}

interface ActiveIncidentResponse {
  incidents: Incident[]
}

const { t, locale } = useI18n()
const route = useRoute()
const api = useApi()

const incidents = ref<Incident[]>([])
const dismissedMessages = ref<Set<string>>(new Set())

function matchesRoute(pattern: string, path: string): boolean {
  if (pattern === '*') return true
  if (pattern.endsWith('/*')) return path.startsWith(pattern.slice(0, -2))
  return path === pattern
}

async function fetchIncidents() {
  try {
    // ?lang= で閲覧言語を付与し、BE側翻訳済みメッセージを受け取る
    const res = await api<ActiveIncidentResponse>(`/api/v1/active-incidents?lang=${locale.value}`)
    incidents.value = res.incidents.filter(i => matchesRoute(i.pagePattern, route.path))
    // dismissed 集合はリセットしない（セッション中に閉じた告知を保持する）
  } catch {
    // サイレント失敗 — インシデントバナーの失敗でアプリを壊さない
  }
}

// severity の3値: INFO → info / WARNING → warn / CRITICAL → error
function severityToPrimeVue(severity: string): string {
  if (severity === 'CRITICAL') return 'error'
  if (severity === 'WARNING') return 'warn'
  return 'info' // INFO およびその他
}

function dismiss(message: string) {
  dismissedMessages.value = new Set([...dismissedMessages.value, message])
}

const visibleIncidents = computed(() => {
  const seenMessages = new Set<string>()
  return incidents.value
    .map((incident, index) => ({ incident, index }))
    .filter(({ incident }) => {
      if (dismissedMessages.value.has(incident.message)) return false
      if (seenMessages.has(incident.message)) return false
      seenMessages.add(incident.message)
      return true
    })
})

let intervalId: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await fetchIncidents()
  // 設計値: 60秒ごとにポーリング
  intervalId = setInterval(fetchIncidents, 60000)
})

onUnmounted(() => {
  if (intervalId) clearInterval(intervalId)
})
</script>

<template>
  <div v-if="visibleIncidents.length > 0" class="active-incident-banner">
    <Message
      v-for="{ incident, index } in visibleIncidents"
      :key="index"
      :severity="severityToPrimeVue(incident.severity)"
      :closable="true"
      :close-button-props="{ 'aria-label': t('announcement.incident.dismiss') }"
      @close="dismiss(incident.message)"
    >
      {{ incident.message }}
    </Message>
  </div>
</template>
