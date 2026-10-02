# 認可Controller番人 Wave5 — D=2委譲探索への賢化と凍結負債の返済

対象: `backend/src/test/java/com/mannschaft/app/common/architecture/AuthzControllerGuardArchTest.java`
関連: 認可漏れ(IDOR)全域監査戦役（台帳 `.claude/campaigns/2026-07-10-authz-idor-audit.md`）

## 1. 目的

公開Controllerエンドポイント（`@RestController`/`@Controller` の public な `@*Mapping` メソッド）が
認可シグナルを一切持たない事故を、CI の ArchUnit 静的解析で機械的に検知する番人。
FreezingArchRule で既存の非該当EPを凍結し、**新規に認可シグナルなしEPが追加された場合のみ** fail させる。

## 2. Wave5 で何を変えたか（賢化）

### 課題（返済対象の負債）
従来のシグナル(B)判定は「起点 Mapping メソッドが **直接** 認可クラスを呼ぶ」場合のみ合格としていた。
そのため **認可を Service へ委譲した薄い Controller** が正しく認可していても全て凍結ストアに落ち、
番人が「認可済みEP」と「無認可EP」を区別できず、凍結ストアが肥大化していた（2720件）。

### 変更（案① D=2 BFS）
シグナル(B)を **呼び出しグラフの深さ2までの幅優先探索** に置き換えた。
`Controller → 注入Service → private helper → 認可クラス` のような2ホップまでの委譲を認可シグナルとして認識する。

必須ガード:
- 各訪問メソッドで「直接呼び先が白名簿クラスか」を判定（**到達しない限り合格させない**）
- `visited`（FQN集合）で同一メソッド再訪を防止（サイクルガード）
- 深さ上限 **D=2**（起点=深さ0、そこから2ホップまで）
- 展開対象を `com.mannschaft.app` 配下に限定（外部ライブラリへ潜らない＝指数爆発防止）

白名簿クラス（＝認可クラス）は従来どおり命名規約ベース:
`AccessControlService`（FQN一致）/ `ContentVisibilityChecker`（FQN一致）/ `*AccessGuard` / `*AccessService`。
シグナル(A) `@PreAuthorize` は変更なし。凍結の照合キー `.as("...(Wave4)")` は不変（別ルール化を避けるため）。

### 偽陰性ゼロの担保
`AuthzControllerGuardConditionTest`（メタテスト）＋ test 配下 fixture パッケージ
`com.mannschaft.app.common.architecture.fixtures` で、合格判定の単一正準
`AuthzControllerGuardArchTest.hasAuthorizationSignal(JavaMethod)` を fixture 限定で評価する:

| fixture | 期待 | 賢化前 | 賢化後 |
|---|---|---|---|
| authorized-direct（直接 `*AccessGuard` 呼び）| シグナルあり | ✅ true | ✅ true |
| unauthorized（認可呼び皆無）| シグナルなし（違反検出）| ✅ false | ✅ false |
| helper-depth2（深さ2委譲で認可到達）| シグナルあり | ❌ false（検出漏れ=red）| ✅ true |

helper-depth2 の **red→green 遷移** を実際に確認済み（賢化により深さ2委譲を救済）。
unauthorized が依然 false であること＝**緩めすぎていない**ことを担保。

## 3. 効果（実測）

`./gradlew test --tests "*AuthzControllerGuardArchTest" --tests "*AuthzControllerGuardConditionTest"` を実走:

- 凍結ストア `9ed4737d-c74f-4374-923e-4663d3c9e256`: **2720件 → 1319件**（1401件を自動 chip-away）
- 番人 `AuthzControllerGuardArchTest`: green（新規違反なし）
- メタ `AuthzControllerGuardConditionTest`: 3件 green

chip-away は `refreeze=false` + `allowStoreCreation=true` の下、テスト実走の副作用として発生
（ストアの手編集は禁止＝対処療法）。

## 4. 残N（1319件）の内訳 — 監査バックログ

これは「認可皆無で危険」なEP集合ではなく、**番人が命名規約で認可シグナルを機械認識できなかったEPの作業待ち行列**である。
大半は正当な別作法で認可している。以降の Wave で作法ごとに棚卸し・命名寄せ・番人拡張を進める。

### ドメイン別（上位）
todo 90 / village 84 / parking 68 / auth 65 / schedule 55 / admin 52 / moderation 46 / shift 45 /
notification 32 / payment 31 / tournament 27 / queue 26 / contact 25 / school 23 / actionmemo 23 /
ticket 22 / reflection 22 / circulation 22 / quickmemo 21 / publicview 21 / analytics 21 / dashboard 20 /
chat 20 / team 19 / timeline 18 / safetycheck 18 / organization 18 / family 18 …（以下小口多数、全1319件）

### 作法別（残る理由の分類）
1. **命名外のスコープ表明ヘルパ**（最多）: `todoService.assertTodoScope(id, scope, scopeId)` のように
   `*AccessGuard`/`*AccessService` を名乗らない自前の IDOR 表明メソッドで認可。番人は保守的に凍結維持。
   → 将来は「命名を `*AccessGuard` 系へ寄せる」か「番人の白名簿に `assert*Scope` 系メソッド名パターンを追加」で救済可能。
2. **意図的公開（流派ロ）**: publicview(21) / landing(1) / cspreport(1) / signage(5) 等。
   認証不要が仕様。SecurityConfig の permitAll や公開共有トークンで保護。
3. **署名/トークン認証の受け口**: `*WebhookController`（payment/schedule/advertising/line）・`StripeConnectController` 等。
   Spring Security フィルタ外での署名検証・Webhookトークン照合で保護（Controller本体に認可呼びが出ない）。
4. **深さ3以上の委譲**: 認可が D=2 より深いサービス階層にあるEP。D=3化は指数コストとの兼ね合いで将来検討。

## 5. 早馬候補（真の穴C・**要検証／本Waveでは未修正**）

番人の残バックログを標本監査した結果、**認可が完全に欠落している疑いの強いEP**を1件検出。防御目的で記録する。

- **`todo.controller.TeamTodoController#listComments` / `#addComment`**（`OrgTodoController` の同等EPも要確認）
  - 兄弟EP `addAssignee`/`removeAssignee` は `todoService.assertTodoScope(id, TEAM, teamId)` で
    パス上の teamId と todo の実スコープの一致を表明している。
  - 一方 comment 系EPは `TodoCommentService.listComments`/`addComment` が
    `verifyTodoExists(todoId)`（**存在確認のみ**）しか行わず、**スコープ表明が無い**。
    パスの teamId は解決されるが todo の実スコープと突合されない。
  - 結果、todo の内部 id を知る任意の認証ユーザーが、所属外チームの todo コメントを
    閲覧・投稿できる BOLA/IDOR の疑い（`updateComment`/`deleteComment` は本人照合ありで別枠）。
  - **対処方針（早馬向け・本Wave対象外）**: comment 系EPに兄弟と同じ `assertTodoScope` を追加し、
    番人賢化で当該EPが凍結ストアから外れる（＝認可済みと機械認識される）ことを確認する。

## 6. 既知の限界

- **interface 経由の委譲**: `MethodCallTarget#resolveMember()` が具象実装体でなく interface メソッドに
  解決する（または解決不能）場合、その枝は辿らず保守的に不合格（凍結維持）とする。
  偽陰性（無認可EPを見逃す）は作らない側に倒しているが、正当な interface 委譲Controllerが
  凍結に残る偽陽性は残る。本リポは具象Service主体のため影響は小さい。
- 残1319件は「安全が証明された集合」ではない。監査バックログとして Wave で消化する。
