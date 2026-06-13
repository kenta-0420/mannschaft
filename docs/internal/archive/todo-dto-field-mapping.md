# TodoResponse / ProjectDetailResponse フィールドマッピング

Wave 1 第一陣: `@RequiredArgsConstructor` → `@Builder(toBuilder=true)` + ネストDTO 刷新

---

## TodoResponse

| 旧フィールドパス | 新フィールドパス | 型 |
|---|---|---|
| response.scopeType | response.scope.scopeType | String |
| response.scopeId | response.scope.scopeId | Long |
| response.projectId | response.scope.projectId | Long |
| response.milestoneId | response.scope.milestoneId | Long |
| response.title | response.content.title | String |
| response.description | response.content.description | String |
| response.startDate | response.content.startDate | LocalDate |
| response.progressRate | response.content.progressRate | BigDecimal |
| response.progressManual | response.content.progressManual | Boolean |
| response.sortOrder | response.content.sortOrder | int |
| response.dueDate | response.schedule.dueDate | LocalDate |
| response.dueTime | response.schedule.dueTime | LocalTime |
| response.daysRemaining | response.schedule.daysRemaining | Long |
| response.linkedScheduleId | response.schedule.linkedScheduleId | Long |
| response.status | response.status.status | String |
| response.priority | response.status.priority | String |
| response.completedAt | response.status.completedAt | LocalDateTime |
| response.statusLabel | response.status.statusLabel | TodoStatusLabelInfo |
| response.parentId | response.hierarchy.parentId | Long |
| response.depth | response.hierarchy.depth | Integer |
| response.children | response.hierarchy.children | List\<TodoResponse\> |
| response.childCount | response.hierarchy.childCount | int |
| response.descendantCompletedCount | response.hierarchy.descendantCompletedCount | int |
| response.descendantTotalCount | response.hierarchy.descendantTotalCount | int |
| response.createdAt | response.audit.createdAt | LocalDateTime |
| response.updatedAt | response.audit.updatedAt | LocalDateTime |
| response.createdBy | response.audit.createdBy | UserInfo |
| response.completedBy | response.audit.completedBy | UserInfo |
| response.assignees | response.assignees | List\<AssigneeResponse\> |
| response.id | response.id | Long |

### JSON レスポンスのパスマッピング（API レスポンス）

| 旧 JSON パス | 新 JSON パス |
|---|---|
| $.title | $.content.title |
| $.description | $.content.description |
| $.startDate | $.content.startDate |
| $.progressRate | $.content.progressRate |
| $.progressManual | $.content.progressManual |
| $.sortOrder | $.content.sortOrder |
| $.dueDate | $.schedule.dueDate |
| $.dueTime | $.schedule.dueTime |
| $.daysRemaining | $.schedule.daysRemaining |
| $.linkedScheduleId | $.schedule.linkedScheduleId |
| $.status | $.status.status |
| $.priority | $.status.priority |
| $.completedAt | $.status.completedAt |
| $.statusLabel | $.status.statusLabel |
| $.parentId | $.hierarchy.parentId |
| $.depth | $.hierarchy.depth |
| $.children | $.hierarchy.children |
| $.childCount | $.hierarchy.childCount |
| $.descendantCompletedCount | $.hierarchy.descendantCompletedCount |
| $.descendantTotalCount | $.hierarchy.descendantTotalCount |
| $.createdAt | $.audit.createdAt |
| $.updatedAt | $.audit.updatedAt |
| $.createdBy | $.audit.createdBy |
| $.completedBy | $.audit.completedBy |
| $.scopeType | $.scope.scopeType |
| $.scopeId | $.scope.scopeId |
| $.projectId | $.scope.projectId |
| $.milestoneId | $.scope.milestoneId |

---

## ProjectDetailResponse

| 旧フィールドパス | 新フィールドパス | 型 |
|---|---|---|
| response.title | response.content.title | String |
| response.description | response.content.description | String |
| response.emoji | response.content.emoji | String |
| response.color | response.content.color | String |
| response.status | response.meta.status | String |
| response.visibility | response.meta.visibility | String |
| response.dueDate | response.schedule.dueDate | LocalDate |
| response.daysRemaining | response.schedule.daysRemaining | Long |
| response.progressRate | response.progress.progressRate | BigDecimal |
| response.totalTodos | response.progress.totalTodos | int |
| response.completedTodos | response.progress.completedTodos | int |
| response.createdBy | response.audit.createdBy | UserInfo |
| response.milestones | response.milestones | List\<MilestoneDetail\> |
| response.unassignedTodos | response.unassignedTodos | UnassignedTodos |
| response.id | response.id | Long |

### JSON レスポンスのパスマッピング（API レスポンス）

| 旧 JSON パス | 新 JSON パス |
|---|---|
| $.title | $.content.title |
| $.description | $.content.description |
| $.emoji | $.content.emoji |
| $.color | $.content.color |
| $.status | $.meta.status |
| $.visibility | $.meta.visibility |
| $.dueDate | $.schedule.dueDate |
| $.daysRemaining | $.schedule.daysRemaining |
| $.progressRate | $.progress.progressRate |
| $.totalTodos | $.progress.totalTodos |
| $.completedTodos | $.progress.completedTodos |
| $.createdBy | $.audit.createdBy |

---

## FE 修正が必要なファイル（推定）

### composables
- `frontend/app/composables/useTodoApi.ts` — TodoResponse 型定義・フィールドアクセス箇所
- `frontend/app/composables/useTodoList.ts` — TodoResponse 型定義・フィールドアクセス箇所
- `frontend/app/composables/useTodoGantt.ts` — TodoResponse フィールドアクセス箇所

### types
- `frontend/app/types/todo.ts` — TodoResponse 型定義の更新

### components
- `frontend/app/components/todo/TodoListView.vue`
- `frontend/app/components/todo/TodoKanbanView.vue`
- `frontend/app/components/todo/TodoHandoffDialog.vue`
- `frontend/app/components/todo/TodoHandoffTimeline.vue`
- `frontend/app/components/todo/TodoGanttView.vue`
- `frontend/app/components/todos/TodoListTable.vue`
- `frontend/app/components/todos/TodoForm.vue`
- `frontend/app/components/todos/TodoComments.vue`
- `frontend/app/components/widgets/WidgetPersonalTodo.vue`
- `frontend/app/components/widgets/WidgetTodoCountdown.vue`

### pages
- `frontend/app/pages/todos/[id].vue`
- `frontend/app/pages/organizations/[id]/todos/[todoId].vue`
- `frontend/app/pages/teams/[id]/todos/[todoId].vue`
- `frontend/app/pages/my/projects/index.vue`
- `frontend/app/pages/my/projects/[projectId].vue`
- `frontend/app/pages/teams/[id]/projects/index.vue`
- `frontend/app/pages/teams/[id]/projects/[projectId].vue`

---

## 注記

- **openapi.json の再生成が必要**: `./gradlew bootRun` で Spring Boot を起動後、`cd frontend && npm run generate:types` を実行して `types/generated/index.ts` を更新すること
- **後方互換アクセサ**: BE 側は旧フラットアクセス (`getTitle()` 等) を `@Deprecated` アクセサとして維持済み。FE 対応完了後に削除予定
- **JSON 構造は変わる**: BE の `@JsonInclude(NON_NULL)` により null フィールドは省略される。FE 側は optional チェーン (`?.`) で対処すること
