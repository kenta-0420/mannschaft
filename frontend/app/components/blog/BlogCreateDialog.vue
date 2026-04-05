<script setup lang="ts">
const visible = defineModel<boolean>('visible', { default: false })

const { createMyPost } = useBlogApi()
const { captureQuiet } = useErrorReport()
const teamStore = useTeamStore()
const orgStore = useOrganizationStore()
const notification = useNotification()

const creating = ref(false)
const form = ref({ title: '', scopeType: 'PERSONAL', scopeId: null as number | null })

const scopeOptions = computed(() => {
  const opts: Array<{ label: string; scopeType: string; scopeId: number | null }> = [
    { label: '個人', scopeType: 'PERSONAL', scopeId: null },
  ]
  for (const t of teamStore.myTeams)
    opts.push({ label: `チーム: ${t.nickname1 || t.name}`, scopeType: 'TEAM', scopeId: t.id })
  for (const o of orgStore.myOrganizations)
    opts.push({ label: `組織: ${o.nickname1 || o.name}`, scopeType: 'ORGANIZATION', scopeId: o.id })
  return opts
})

const selectedScope = computed({
  get: () =>
    scopeOptions.value.find(
      (o) => o.scopeType === form.value.scopeType && o.scopeId === form.value.scopeId,
    ) ?? scopeOptions.value[0],
  set: (v) => {
    form.value.scopeType = v?.scopeType ?? 'PERSONAL'
    form.value.scopeId = v?.scopeId ?? null
  },
})

watch(visible, (v) => {
  if (v) form.value = { title: '', scopeType: 'PERSONAL', scopeId: null }
})

async function submit() {
  if (!form.value.title.trim()) return
  creating.value = true
  try {
    const res = await createMyPost({
      title: form.value.title.trim(),
      body: '.',
      status: 'DRAFT',
      scopeType: form.value.scopeType,
      scopeId: form.value.scopeId,
    })
    visible.value = false
    const q = new URLSearchParams({ title: res.data.title })
    if (res.data.scopeType) q.set('scopeType', res.data.scopeType)
    if (res.data.scopeId != null) q.set('scopeId', String(res.data.scopeId))
    navigateTo(`/blog/posts/${res.data.id}/edit?${q.toString()}`)
  } catch (error) {
    captureQuiet(error, { context: 'BlogCreateDialog: ブログ記事作成' })
    const msg = (
      error as { data?: { error?: { fieldErrors?: { field: string; message: string }[] } } }
    )?.data?.error?.fieldErrors
      ?.map((f) => `${f.field}: ${f.message}`)
      .join(', ')
    notification.error('記事の作成に失敗しました', msg || '時間をおいて再試行してください')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <Dialog v-model:visible="visible" header="ブログ記事を作成" modal :style="{ width: '420px' }">
    <div class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.title"
          class="w-full"
          placeholder="記事のタイトル"
          autofocus
          @keydown.enter="submit"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">投稿先</label>
        <Select
          v-model="selectedScope"
          :options="scopeOptions"
          option-label="label"
          class="w-full"
        />
      </div>
      <p class="text-xs text-surface-500">
        ※ 下書きとして保存されます。公開はブログ編集ページから行えます。
      </p>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="visible = false" />
      <Button
        label="作成して編集へ"
        icon="pi pi-arrow-right"
        :loading="creating"
        :disabled="!form.title.trim()"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
