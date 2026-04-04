<script setup lang="ts">
const contactApi = useContactApi()
const { captureQuiet } = useErrorReport()

const count = ref(0)

async function fetchCount() {
  try {
    const result = await contactApi.listReceivedRequests()
    count.value = result.data.length
  } catch (e) {
    captureQuiet(e, { context: 'ContactRequestBadge: 件数取得' })
  }
}

onMounted(fetchCount)
</script>

<template>
  <Badge v-if="count > 0" :value="count" severity="danger" />
</template>
