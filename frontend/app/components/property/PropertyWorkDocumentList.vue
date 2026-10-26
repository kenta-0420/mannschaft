<script setup lang="ts">
/**
 * 添付文書一覧（DocumentKind 別グループ表示）（F09.13 Phase 1-ε）。
 *
 * - props.documents: パッケージ詳細レスポンスから受け取る PropertyWorkDocumentResponse[]
 * - props.canEdit: ADMIN / DEPUTY_ADMIN のときのみ削除ボタン表示
 *
 * F05.5 SharedFile 本体（fileName / size 等）の取得は本フェーズで未実装の場合があり、
 * バックエンドから fileName が来ていない場合は sharedFileId をリンク表示する。
 */
import type { DocumentKind, PropertyWorkDocumentResponse } from '~/types/property'

const props = defineProps<{
  documents: PropertyWorkDocumentResponse[] | null | undefined
  canEdit: boolean
}>()

const emit = defineEmits<{
  detach: [documentId: number]
}>()

const { t } = useI18n()

// 表示順固定: MINUTES → QUOTE → CONTRACT → REPORT → PHOTO → DRAWING → INVOICE → RECEIPT → OTHER
const KIND_ORDER: DocumentKind[] = [
  'MINUTES',
  'QUOTE',
  'CONTRACT',
  'REPORT',
  'PHOTO',
  'DRAWING',
  'INVOICE',
  'RECEIPT',
  'OTHER',
]

interface Group {
  kind: DocumentKind
  items: PropertyWorkDocumentResponse[]
}

const grouped = computed<Group[]>(() => {
  const docs = props.documents ?? []
  const map = new Map<DocumentKind, PropertyWorkDocumentResponse[]>()
  for (const doc of docs) {
    const list = map.get(doc.documentKind) ?? []
    list.push(doc)
    map.set(doc.documentKind, list)
  }
  return KIND_ORDER.filter((kind) => map.has(kind)).map((kind) => {
    const items = (map.get(kind) ?? []).slice().sort((a, b) => {
      const ord = (a.displayOrder ?? 0) - (b.displayOrder ?? 0)
      return ord !== 0 ? ord : a.id - b.id
    })
    return { kind, items }
  })
})

const isEmpty = computed(() => grouped.value.length === 0)

function kindLabel(kind: DocumentKind): string {
  return t(`property.documentKind.${kind}`)
}

function onDetach(documentId: number) {
  if (!confirm(t('property.documents.detachConfirm'))) return
  emit('detach', documentId)
}
</script>

<template>
  <div class="space-y-4">
    <p
      v-if="isEmpty"
      class="rounded-md border border-dashed border-surface-300 p-4 text-center text-sm text-surface-500 dark:border-surface-700 dark:text-surface-400"
    >
      {{ t('property.documents.empty') }}
    </p>

    <div
      v-for="group in grouped"
      :key="group.kind"
      class="rounded-md border border-surface-200 dark:border-surface-700"
    >
      <h4
        class="border-b border-surface-200 bg-surface-50 px-3 py-2 text-sm font-medium text-surface-700 dark:border-surface-700 dark:bg-surface-900 dark:text-surface-200"
      >
        {{ kindLabel(group.kind) }}
        <span class="ml-2 text-xs text-surface-500 dark:text-surface-400">
          ({{ group.items.length }})
        </span>
      </h4>

      <ul class="divide-y divide-surface-100 dark:divide-surface-800">
        <li
          v-for="doc in group.items"
          :key="doc.id"
          class="flex items-center justify-between gap-2 px-3 py-2 text-sm"
        >
          <div class="min-w-0 flex-1">
            <span class="truncate text-surface-800 dark:text-surface-100">
              {{ doc.fileName ?? `#${doc.sharedFileId}` }}
            </span>
            <span
              v-if="doc.note"
              class="ml-2 text-xs text-surface-500 dark:text-surface-400"
            >
              {{ doc.note }}
            </span>
          </div>
          <Button
            v-if="canEdit"
            icon="pi pi-times"
            severity="danger"
            text
            rounded
            size="small"
            :aria-label="t('property.documents.detach')"
            :data-testid="`property-detach-doc-${doc.id}`"
            @click="onDetach(doc.id)"
          />
        </li>
      </ul>
    </div>
  </div>
</template>
