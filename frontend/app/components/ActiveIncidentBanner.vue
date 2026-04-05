<script setup lang="ts">
interface Incident {
  pagePattern: string
  message: string
  severity: string
  since: string
}

interface ActiveIncidentResponse {
  incidents: Incident[]
}

const route = useRoute()
const api = useApi()

const incidents = ref<Incident[]>([])
const dismissed = ref<Set<number>>(new Set())

function matchesRoute(pattern: string, path: string): boolean {
  if (pattern === '*') return true
  if (pattern.endsWith('/*')) return path.startsWith(pattern.slice(0, -2))
  return path === pattern
}

async function fetchIncidents() {
  try {
    const res = await api<ActiveIncidentResponse>('/api/v1/active-incidents')
    incidents.value = res.incidents.filter(i => matchesRoute(i.pagePattern, route.path))
    dismissed.value = new Set()
  } catch {
    // サイレント失敗 — インシデントバナーの失敗でアプリを壊さない
  }
}

function severityToPrimeVue(severity: string): string {
  if (severity === 'CRITICAL') return 'error'
  if (severity === 'WARNING') return 'warn'
  return 'info'
}

function dismiss(index: number) {
  dismissed.value = new Set([...dismissed.value, index])
}

const visibleIncidents = computed(() =>
  incidents.value.map((incident, index) => ({ incident, index })).filter(({ index }) => !dismissed.value.has(index))
)

let intervalId: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await fetchIncidents()
  intervalId = setInterval(fetchIncidents, 300000)
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
      @close="dismiss(index)"
    >
      {{ incident.message }}
    </Message>
  </div>
</template>
