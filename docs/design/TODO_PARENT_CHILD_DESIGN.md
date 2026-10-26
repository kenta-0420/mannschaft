# TODO親子階層設計書

## 1. 概要

TODOに親子関係（最大3階層）を導入し、親課題・子課題・孫課題の構造を持たせる。

```
Lv1: 親課題（例: リリース準備）
 └ Lv2: 子課題（例: テスト実施）
    └ Lv3: 孫課題（例: 単体テスト作成）
```

### 1.1 用語定義

| 用語 | 定義 |
|------|------|
| 親TODO | `parent_id IS NULL` のルートTODO（Lv1） |
| 子TODO | `parent_id` が Lv1 を指すTODO（Lv2） |
| 孫TODO | `parent_id` が Lv2 を指すTODO（Lv3） |
| 深度（depth） | ルートからの階層数。Lv1=0, Lv2=1, Lv3=2 |
| 子孫TODO | あるTODOから見た全ての下位TODO |

### 1.2 スコープ

- **対象**: PERSONAL / TEAM / ORGANIZATION の全スコープ
- **階層制限**: 最大3階層（depth=0, 1, 2）
- **子TODO上限**: 1親あたり最大50件

---

## 2. データベース設計

### 2.1 マイグレーション

```sql
-- ※ファイル名は既存マイグレーション番号体系に合わせて採番すること（M5対策）

ALTER TABLE todos
  ADD COLUMN parent_id BIGINT UNSIGNED NULL AFTER milestone_id,
  ADD COLUMN depth     TINYINT UNSIGNED NOT NULL DEFAULT 0 AFTER parent_id,
  ADD CONSTRAINT fk_todo_parent
    FOREIGN KEY (parent_id) REFERENCES todos(id)
    ON DELETE SET NULL;

CREATE INDEX idx_todo_parent ON todos(parent_id, deleted_at, sort_order);
CREATE INDEX idx_todo_depth  ON todos(parent_id, depth);
```

### 2.2 カラム定義

| カラム | 型 | NULL | デフォルト | 説明 |
|--------|-----|------|------------|------|
| `parent_id` | BIGINT UNSIGNED | YES | NULL | 親TODO ID。NULLならルート |
| `depth` | TINYINT UNSIGNED | NO | 0 | 階層深度。0=ルート, 1=子, 2=孫 |

### 2.3 FK制約: ON DELETE SET NULL の理由

`CASCADE` ではなく `SET NULL` を採用する。

- **理由**: 親を削除（論理削除）しても子は独立したTODOとして残る
  - 例: 「リリース準備」を消しても「テスト実施」は残したい
- **論理削除との整合**: 親を softDelete しても子は自動的には消えない（意図的な設計）
- 親の物理削除は運用上発生しないが、万一発生しても子のデータが消失しない

### 2.4 depth カラムの整合性について（S4/M3対策）

`depth` は `parent_id` チェーンから導出可能な派生データだが、検索性能のためDBに保存する。

- **不整合リスク**: 物理削除（`ON DELETE SET NULL`）で `parent_id=NULL, depth=1` の状態が発生しうる
- **対策**: 
  - バリデーションは `depth` カラムだけでなく `parent_id` チェーンでも行う
  - 物理削除は禁止（運用ルール）。論理削除のみを使用
  - 定期バッチ等での整合性チェックは将来検討

---

## 3. エンティティ変更

### 3.1 TodoEntity

```java
// 追加フィールド
private Long parentId;

@Column(nullable = false)
@Builder.Default
private Integer depth = 0;
```

### 3.2 TodoResponse（DTO）

```java
// 既存フィールドに追加
private final Long parentId;
private final Integer depth;
private final List<TodoResponse> children;  // 子TODO一覧（1階層のみ）
private final int childCount;               // 直接の子TODO件数
private final int descendantCompletedCount; // 子孫の完了数
private final int descendantTotalCount;     // 子孫の合計数
```

### 3.3 CreateTodoRequest

```java
// 追加フィールド
private final Long parentId;  // nullable。指定時は子TODOとして作成
```

---

## 4. バリデーションルール

### 4.1 作成時バリデーション

| # | ルール | エラーコード | 説明 |
|---|--------|-------------|------|
| V1 | 親TODOが存在すること | TODO_010 | `findTodoOrThrow(parentId)` |
| V2 | 親が論理削除されていないこと | TODO_010 | `deletedAt IS NULL` |
| V3 | `depth < 2` であること | TODO_020 (新規) | Lv3にはもう子を作れない |
| V4 | 親と同一スコープであること | TODO_021 (新規) | `scopeType` と `scopeId` が一致 |
| V5 | 親と同一プロジェクトであること | TODO_011 | `projectId` が一致（両方NULLも可） |
| V6 | 子TODO数が50未満であること | TODO_022 (新規) | 1親あたりの上限チェック |

### 4.2 更新時バリデーション

| # | ルール | 説明 |
|---|--------|------|
| U1 | `parentId` の変更は不可 | 親子関係の付け替えは初期実装では対応しない。混乱防止 |
| U2 | 子TODOがあるTODOのプロジェクト変更は拒否 | 子の連動更新は担当者への通知なしに変更が走りリスクが高いため |

### 4.3 削除時バリデーション

| # | ルール | 説明 |
|---|--------|------|
| D1 | 親の論理削除は子に波及しない | 子は独立TODOとして残る |
| D2 | 親の物理削除は `SET NULL` | FK制約による保護 |

---

## 5. サービスロジック変更

### 5.1 TodoService.createTodo

```java
// parentId 処理の追加（既存ロジックの後に挿入）
Integer depth = 0;
Long parentId = request.getParentId();
if (parentId != null) {
    // S2対策: スコープフィルタ付きで検索し、存在有無でIDの帰属を推測させない
    TodoEntity parent = todoRepository.findByIdAndDeletedAtIsNull(parentId)
        .filter(p -> p.getScopeType() == scopeType && p.getScopeId().equals(scopeId))
        .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));

    // V3: 深度チェック
    if (parent.getDepth() >= 2) {
        throw new BusinessException(TodoErrorCode.MAX_DEPTH_EXCEEDED);
    }

    // V4: スコープ一致チェック（S2対策により上記filterで実施済み）

    // V5: プロジェクト一致チェック
    if (!Objects.equals(parent.getProjectId(), request.getProjectId())) {
        throw new BusinessException(TodoErrorCode.SCOPE_MISMATCH);
    }

    // V6: 子TODO上限チェック
    long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(parentId);
    if (childCount >= MAX_CHILD_SIZE) {
        throw new BusinessException(TodoErrorCode.CHILD_LIMIT_EXCEEDED);
    }

    depth = parent.getDepth() + 1;
}

// TodoEntity.builder() に追加
.parentId(parentId)
.depth(depth)
```

### 5.2 TodoService.deleteTodo

```java
// 変更なし。親の softDelete は子に波及しない。
// 子TODOは parent_id を保持し続けるが、親が論理削除されていれば
// UI側で「(親課題は削除済み)」等の表示にする。
```

### 5.3 TodoService.changeStatus

```java
// ステータス変更は単独TODOに対して行う（子への連鎖なし）。
// 理由: 「親を完了にしたら子も全部完了」は誤操作リスクが高い。
//       フロントで「未完了の子課題があります」と警告し、ユーザー判断に委ねる。
```

### 5.4 TodoService.toTodoResponse（変更）

```java
// 一覧用: childCount / descendant統計は含めない（N+1防止: M1対策）
// 詳細用: childCount / descendant統計を含めるオーバーロードを用意

// 一覧用（従来互換）
private TodoResponse toTodoResponse(TodoEntity entity) {
    // ... 既存ロジック ...
    // parentId, depth のみ追加。children=空, childCount=0, descendant*=0
}

// 詳細用（子TODO統計付き）
private TodoResponse toTodoResponseWithStats(TodoEntity entity) {
    long childCount = todoRepository.countByParentIdAndDeletedAtIsNull(entity.getId());
    long descendantTotal = todoRepository.countDescendants(entity.getId());
    long descendantCompleted = todoRepository.countCompletedDescendants(entity.getId());
    List<TodoEntity> childEntities = todoRepository
        .findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(entity.getId());
    List<TodoResponse> children = childEntities.stream()
        .map(this::toTodoResponse)  // 子の子は統計なし（再帰しない）
        .toList();

    return new TodoResponse(
        /* ... 既存フィールド ... */
        entity.getParentId(), entity.getDepth(),
        children, (int) childCount,
        (int) descendantCompleted, (int) descendantTotal
    );
}
```

### 5.5 新規メソッド: getChildTodos

```java
/**
 * 指定TODOの直接の子TODO一覧を取得する。
 * スコープ認可チェック付き。
 */
public ApiResponse<List<TodoResponse>> getChildTodos(
        TodoScopeType scopeType, Long scopeId, Long todoId) {
    TodoEntity parent = findTodoOrThrow(todoId);
    // スコープ認可: 呼び出し元のスコープと一致するか検証（S1対策）
    if (parent.getScopeType() != scopeType || !parent.getScopeId().equals(scopeId)) {
        throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND); // IDORを防ぐため存在を隠す
    }
    List<TodoEntity> children = todoRepository
        .findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(todoId);
    return ApiResponse.of(children.stream().map(this::toTodoResponse).toList());
}
```

---

## 6. リポジトリ変更

### 6.1 TodoRepository 追加メソッド

```java
// 子TODO取得（ソート順）
List<TodoEntity> findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(Long parentId);

// 子TODO件数
long countByParentIdAndDeletedAtIsNull(Long parentId);

// 子孫の統計（完了数・合計数）
@Query("""
    SELECT COUNT(t) FROM TodoEntity t
    WHERE t.deletedAt IS NULL
      AND (t.parentId = :parentId
           OR t.parentId IN (SELECT c.id FROM TodoEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL))
    """)
long countDescendants(@Param("parentId") Long parentId);

@Query("""
    SELECT COUNT(t) FROM TodoEntity t
    WHERE t.deletedAt IS NULL
      AND t.status = 'COMPLETED'
      AND (t.parentId = :parentId
           OR t.parentId IN (SELECT c.id FROM TodoEntity c WHERE c.parentId = :parentId AND c.deletedAt IS NULL))
    """)
long countCompletedDescendants(@Param("parentId") Long parentId);

// ルートTODOのみ取得（一覧表示用）
Page<TodoEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullAndParentIdIsNull(
    TodoScopeType scopeType, Long scopeId, Pageable pageable);

// ルートTODOのみ取得（ステータスフィルタ付き）
Page<TodoEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNullAndParentIdIsNull(
    TodoScopeType scopeType, Long scopeId, TodoStatus status, Pageable pageable);
```

---

## 7. API変更

### 7.1 既存API変更

| API | 変更内容 |
|-----|---------|
| `POST /api/v1/todos` | `parentId` パラメータ追加 |
| `POST /api/v1/teams/{teamId}/todos` | `parentId` パラメータ追加 |
| `GET /api/v1/teams/{teamId}/todos` | `parentId=null` 明示指定時のみルート限定。未指定時は従来通り全件返す（破壊的変更回避: M4対策） |
| `GET /api/v1/todos/my` | 全階層のTODOをフラットに返す（従来互換） |

### 7.2 新規API

| HTTP | Path | 説明 |
|------|------|------|
| `GET` | `/api/v1/teams/{teamId}/todos/{id}/children` | 子TODO一覧 |
| `GET` | `/api/v1/todos/{id}/children` | 個人TODOの子一覧 |

### 7.3 レスポンス例

```json
{
  "data": {
    "id": 101,
    "title": "リリース準備",
    "parentId": null,
    "depth": 0,
    "childCount": 3,
    "descendantCompletedCount": 2,
    "descendantTotalCount": 5,
    "children": [
      {
        "id": 201,
        "title": "テスト実施",
        "parentId": 101,
        "depth": 1,
        "childCount": 2,
        "descendantCompletedCount": 1,
        "descendantTotalCount": 2,
        "children": []
      }
    ]
  }
}
```

---

## 8. エラーコード追加

```java
/** 子TODO階層の上限（3階層）を超過 */
MAX_DEPTH_EXCEEDED("TODO_020", "これ以上子課題を追加できません（最大3階層）", Severity.WARN),

/** 親TODOとスコープが不一致 */
PARENT_SCOPE_MISMATCH("TODO_021", "親課題と同じスコープ内でのみ子課題を作成できます", Severity.WARN),

/** 子TODO数が上限（50件）に達している */
CHILD_LIMIT_EXCEEDED("TODO_022", "子課題の上限（50件）に達しています", Severity.WARN),
```

---

## 9. ダッシュボード影響

### 9.1 DashboardService.toTodoMap

```java
// 追加フィールド
map.put("parent_id", entity.getParentId());
map.put("depth", entity.getDepth());
```

### 9.2 getPersonalTodos

- 変更なし。`findMyTodos` は全階層のTODOを返す
- ダッシュボードでは階層を意識せずフラット表示（シンプルさ優先）

---

## 10. 既存機能への影響と互換性

| 機能 | 影響 | 対応 |
|------|------|------|
| TODO一覧（ページネーション） | ルートのみ返すよう変更 | Repositoryメソッド追加 |
| TODO詳細取得 | `parentId`, `depth`, `children` 追加 | レスポンスDTO拡張 |
| TODO作成 | `parentId` オプション追加 | バリデーション追加 |
| TODO更新 | `parentId` 変更不可 | バリデーション追加 |
| TODO削除 | 変更なし | 子は独立して残る |
| ステータス変更 | 変更なし | 子への連鎖なし |
| 一括ステータス変更 | 変更なし | 従来通り指定IDのみ |
| 担当者管理 | 変更なし | TODO単位で独立 |
| コメント | 変更なし | TODO単位で独立 |
| プロジェクト進捗 | 変更なし | 各TODO独立で集計 |
| findMyTodos | 変更なし | 全階層フラット返却 |
| ダッシュボード | 軽微な追加 | `parent_id`, `depth` フィールド追加 |

---

## 11. 変更対象ファイル一覧

| ファイル | 変更種別 |
|----------|---------|
| `V4.001__add_todo_parent_id.sql` | **新規** |
| `TodoEntity.java` | フィールド追加 |
| `TodoResponse.java` | フィールド追加 |
| `CreateTodoRequest.java` | フィールド追加 |
| `TodoErrorCode.java` | 3コード追加 |
| `TodoService.java` | createTodo/toTodoResponse 変更、getChildTodos 追加 |
| `TodoRepository.java` | クエリメソッド追加 |
| `PersonalTodoController.java` | GET children エンドポイント追加 |
| `TeamTodoController.java` | GET children エンドポイント追加、listTodos 変更 |
| `DashboardService.java` | toTodoMap に parent_id/depth 追加 |

---

## 12. テスト計画

| テスト | 内容 |
|--------|------|
| 正常系: 子TODO作成 | Lv1にLv2を追加できること |
| 正常系: 孫TODO作成 | Lv2にLv3を追加できること |
| 異常系: 4階層目の拒否 | Lv3に子を追加すると `TODO_020` |
| 異常系: スコープ不一致 | 別チームの親に子を作ると `TODO_021` |
| 異常系: プロジェクト不一致 | 別プロジェクトの親に子を作ると `TODO_011` |
| 異常系: 子50件超過 | 51件目で `TODO_022` |
| 異常系: 削除済み親への追加 | 論理削除された親に子を追加すると `TODO_010` |
| 親削除時の子 | 親を softDelete しても子は残ること |
| 子TODO一覧 | `GET /children` で直接の子のみ返ること |
| 一覧表示 | ルートTODOのみページネーション対象 |
| findMyTodos | 全階層がフラットに返ること |
| プロジェクト進捗 | 親子それぞれ独立カウントされること |
| descendant統計 | completedCount / totalCount が正確なこと |
| 異常系: parentId変更拒否 | 更新時に parentId を変更しようとすると拒否されること（U1検証） |
| 異常系: 子持ちPJ変更拒否 | 子TODOがある親のプロジェクト変更が拒否されること（U2検証） |
| セキュリティ: スコープ認可 | 他スコープのTODOの children を取得すると TODO_NOT_FOUND（S1検証） |
| セキュリティ: IDOR | 他スコープの parentId を指定して子を作成すると TODO_NOT_FOUND（S2検証） |

---

## 13. 精査記録

### 精査1回目: セキュリティ観点

| ID | 重要度 | 指摘 | 対策 | 反映箇所 |
|----|--------|------|------|----------|
| S1 | 高 | `getChildTodos` にスコープ認可チェックがなく、他人のTODOの子が閲覧可能 | スコープ帰属検証を追加。不一致時は `TODO_NOT_FOUND` を返しIDの存在を隠す | 5.5 |
| S2 | 高 | `parentId` 指定時の存在確認エラーで他スコープのID存在が推測可能（IDOR） | スコープフィルタ付きで親を検索。不一致時も `TODO_NOT_FOUND` で統一 | 5.1 |
| S3 | 中 | `countDescendants` サブクエリの負荷 | 子50件上限 x 3階層 = 最大2500件。インデックスで十分 | 許容（監視対象） |
| S4 | 中 | `ON DELETE SET NULL` で `depth` 不整合 | 物理削除禁止の運用ルール明記。`depth` 単独に依存しない設計 | 2.4 |
| S5 | 低 | `CreateTodoRequest` コンストラクタ引数順変更 | JSONデシリアライズは名前ベースのため影響なし | 影響なし |

### 精査2回目: 保守性・整合性観点

| ID | 重要度 | 指摘 | 対策 | 反映箇所 |
|----|--------|------|------|----------|
| M1 | 高 | `toTodoResponse` で毎回子TODO統計クエリ発行（N+1問題） | 一覧用/詳細用のオーバーロード分離。一覧では統計を含めない | 5.4 |
| M2 | 高 | U2のプロジェクト変更時の挙動が曖昧（2案併記） | 「子がいる場合は拒否」に統一 | 4.2 |
| M3 | 中 | `depth` は派生データで不整合リスクあり | 検索性能トレードオフとして許容。注意事項を明記 | 2.4 |
| M4 | 中 | 一覧APIの「ルートのみ」変更がフロント破壊的変更 | 未指定時は従来通り全件返す。`parentId=null` 明示時のみルート限定 | 7.1 |
| M5 | 低 | マイグレーション番号体系の乖離 | 既存番号体系に合わせて採番する注記を追加 | 2.1 |
| M6 | 低 | テスト計画にU1/U2の検証が不足 | テストケース4件追加 | 12 |
