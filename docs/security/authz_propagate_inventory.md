# 認可 PROPAGATE 棚卸し台帳

認可の裏目付検出器（`AuthzGateEffectivenessAuditTest`、形②）が「保守的に合格」させている
PROPAGATE 箇所（2段抜けの疑いがある箇所）を独立に列挙し、戦役の規模を測るための台帳。

## PROPAGATE とは何か・なぜ静的に追えないのか

`AuthzGateEffectivenessAuditTest` の形②は、認可クラス（`*AccessGuard` / `*AccessService` /
`*AuthorizationService` / `AccessControlService`）が持つ DECISION 系メソッド（`can`/`is`/`has`/
`get`/`resolve` 等、`require`/`filter` 系を除く）の戻り値がローカル変数へ代入された場合、
その変数の同一ブロック内の全使用箇所を次のように分類する（同クラス javadoc L102-116）。

- `if (v)` / `while (v)` / `&&` / `||` / 三項条件、`.filter`/`anyMatch` 等、`throw` の一部 → **GATE**
- `return v` / `return f(v)` → **PROPAGATE**（判定は呼び元へ委ねられる）
- 小文字始まりメソッドの引数 → **PROPAGATE**（下流が enforce する可能性）
- `new XxxDto(..)` / `XxxResponse.from(..)` / `.builder()` 系のみ → **DTO_SINK**（違反候補）

GATE が 1 つでもあれば合格、PROPAGATE があれば保守的に合格（下流の enforce を否定できないため）、
DTO_SINK のみのときだけ違反候補とする（precision 優先の設計）。

検出器 javadoc の「既知の限界」（L124-128）はこの PROPAGATE を次のように明記している。

> **PROPAGATE は追わない**。下流メソッドが実際に enforce しているかは検証しない。
> よって「Controller が roleName を取り Service へ渡すが Service も素通し」という
> 2 段の抜けは**検出できない**（偽陰性）。下流の enforce は契約 IT で担保する。

つまり PROPAGATE は「委譲先メソッドの内部で実際に認可判定が使われているか」を機械的に
判定できないために保守的合格にしている箇所であり、**PROPAGATE = 認可漏れ確定ではない**。
下流で正しく enforce されているケースも、2段目で素通しになっているケースも両方含みうる。
本台帳はその**候補一覧**であり、個別の真偽判定（下流を実際に追う監査）は次工程（`AuthzPropagateInventoryTest`
の出力を種にした個別監査戦役）の役割とする。

## 測量方法

新設した棚卸しテスト
`backend/src/test/java/com/mannschaft/app/common/architecture/AuthzPropagateInventoryTest.java`
が、`AuthzGateEffectivenessAuditTest` の形②と**同じ判定基準**（GATE/PROPAGATE/DTO_SINK の
分類ロジック）で全 `src/main/java` を走査し、PROPAGATE に分類された箇所のみを収集する。

- 走査部品（マスク処理・ゲートクラス判定・メソッドパーサ・ゲート語彙収集・レシーバ識別子抽出）は
  `AuthzGateReturnValueGuardTest#mask` / `#isGateClassFile` / `AuthzGateEffectivenessAuditTest#parseMethods` /
  `#receiverIdentifiers` / `GateVocabulary` をそのまま流用（既存検出器は無編集）。
- GATE/PROPAGATE/DTO_SINK の分類判定式自体（`onlyFlowsIntoDto` 相当）は既存検出器側で `private`
  のため呼び出せず、同一の判定基準（javadoc L106-112 と同じ正規表現・除外条件）で
  `AuthzPropagateInventoryTest` 内に再実装した。
- package-private（アクセス修飾子省略）メソッドも認識できることを fixture で固定済み
  （既存検出器が過去に踏んだ偽陰性の再発防止）。

再生成コマンド:

```bash
cd backend && ./gradlew test --tests "*AuthzPropagateInventoryTest"
```

全件一覧はビルド生成物 `backend/build/reports/authz-propagate-inventory.txt` に出力される
（テスト実行のたびに再生成・コミット不要）。

## 実数

殿の実測（`./gradlew test --tests "*AuthzPropagateInventoryTest"`、本ロット着手前）:

- 総件数（ノイズ除去前）: **22**（return形 0 / 委譲形 22）
- 本ロットのノイズ除去（`RoleResolver#resolveViewerRole` の switch文対象式・log.warn引数の
  2件を誤検出として除外）後の実数: **委譲形 20**（return形 0 は変化なし）

再実行して確定値を更新する場合は上記コマンドを使うこと（`./gradlew` は本ロットでは実行禁止の
ため、以下の triage は実ソースの目視確認で行った。総件数の再確認は次ロットの宿題とする）。

## triage（仕事2・仕事3）

**制約と限界を先に明記する**: `./gradlew` 実行禁止のため、`backend/build/reports/authz-propagate-inventory.txt`
による機械的な全件列挙は得られていない。委譲形20件のうち、既知の分布から特定できた
**15件を実ソース読解で確認**した。残り**約5件は未確認**（`accessControlService.(get|is|has|can|resolve)`
呼び出しを持つファイルが44件あり、その全てを本ロットの時間内には確認しきれなかった）。
fail-closed の原則に従い、未確認分を推測でENFORCED扱いにはしていない。**次ロットで
`./gradlew test --tests "*AuthzPropagateInventoryTest"` を実行し、生成された正本テキストと
本表を突き合わせて残り約5件を確定させること。**

| Controller/Service#メソッド | パス:行 | 委譲先（クラス#メソッド・パス:行） | 下流の認可判定の実体 | 判定 |
|---|---|---|---|---|
| KbPageController#getPageTree | knowledgebase/controller/KbPageController.java:56 | KbPageService#getPageTree（knowledgebase/service/KbPageService.java:91-102） | `ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY ページを絞り込み。scope束縛は呼び出し元 `checkMembership` 済み | ENFORCED |
| KbPageController#getPage | 同:79 | KbPageService#getPage（同:109-124） | `checkAccessLevel(page, userRole)` が `ADMIN_ROLES.contains(userRole)` 未満なら KB_002 throw | ENFORCED |
| KbPageController#updatePage | 同:138 | KbPageService#updatePage（同:207-229） | userRoleはscope束縛済みentityに対する処理内で消費（controller側 `checkMembership` 併用） | ENFORCED |
| KbPageController#getRecentPages | 同:226 | KbPageService#getRecentPages（同:433-450） | `isAdmin = ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY を除外フィルタ | ENFORCED |
| KbRevisionController#getRevisions | knowledgebase/controller/KbRevisionController.java:50 | KbRevisionService#getRevisions（knowledgebase/service/KbRevisionService.java:40-49） | `checkRevisionAccess`: `isAdmin \|\| isCreator` でないと KB_002 throw。page自体はscope束縛済み | ENFORCED |
| KbRevisionController#getRevision | 同:76 | KbRevisionService#getRevision（同:58-68） | 同上 | ENFORCED |
| KbRevisionController#restoreRevision | 同:100 | KbRevisionService#restoreRevision（同:78-112） | 同上 | ENFORCED |
| KbSearchController#search | knowledgebase/controller/KbSearchController.java:55 | KbSearchService#search（knowledgebase/service/KbSearchService.java:35-58） | `isAdmin = ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY ページを除外フィルタ | ENFORCED |
| SkillController#getSkill | skill/controller/SkillController.java:98 | MemberSkillService#getSkill（skill/service/MemberSkillService.java:130-139） | `checkScopeOrThrow` でscope束縛＋`!skill.getUserId().equals(requestUserId) && !isAdmin(userRole)` で SKILL_003 throw | ENFORCED |
| SkillController#updateSkill | 同:114 | MemberSkillService#updateSkill（同:157-189） | 同様のscope束縛＋本人/ADMIN判定 | ENFORCED |
| DashboardService#getTeamDashboard | dashboard/service/DashboardService.java:384 | DashboardWidgetService#getWidgetSettings（dashboard/service/DashboardWidgetService.java:127-141） | `wk.isRoleRestricted() && !isAdmin` でロール制限ウィジェットを除外（139行目） | ENFORCED |
| DashboardService#getOrgDashboard | 同:598 | DashboardWidgetService#getWidgetSettings（同上） | 同上 | ENFORCED |
| MatchStatsController#getTeamStats | match/controller/MatchStatsController.java:169-172 | MatchStatsAggregationService#aggregateTeamStats（match/service/MatchStatsAggregationService.java:350-403） | `includeRankings ? buildPlayerRankings(...) : List.of()`（403行目）で SUPPORTER にはランキング非表示 | ENFORCED |
| KbPageFavoriteResolver#resolveAll | favorite/resolver/impl/KbPageFavoriteResolver.java:67 | 同クラス内 private `resolveCanEdit`（同:95-104） | `Set.of("ADMIN","DEPUTY_ADMIN").contains(roleName)` で ADMIN_ONLY/CUSTOM の編集可否判定。roleName==null は事前に unavailable 扱い（62行目） | ENFORCED |
| TeamFriendsController#isSupporterOnly | social/controller/TeamFriendsController.java:186 | （`"SUPPORTER".equals(roleName)` ＝ `java.lang.String#equals`） | 認可判定そのものではなく、ロール名の等値比較を `return` しているだけ。委譲先が `String` であり認可クラスではない | NOT_APPLICABLE（検出器の別ノイズ候補。本ロットでは検出器・製品コードとも未修正。次ロットの除外候補） |
| （残り約5件） | 未確認 | 未確認 | 未確認 | **未確認**（fail-closedのため断定しない。次ロットで `./gradlew` 実行の上、正本テキストと突き合わせて特定すること） |

### 内訳（確認できた15件中）

- ENFORCED: 14件
- UNCOVERED: 0件
- NOT_APPLICABLE: 1件（`TeamFriendsController#isSupporterOnly` — 委譲先が `String#equals` で認可の話ではない）

### UNCOVERED 一覧

該当なし（確認できた範囲内では0件）。ただし未確認の約5件にUNCOVEREDが潜んでいる可能性は
否定できない。次ロットで確定させること。

### 仕事3（契約ITの有無）

確認できた15件はすべてENFORCED（下流に実効的な認可判定あり）またはNOT_APPLICABLEと判定した
ため、UNCOVERED該当が0件であり、本ロットでは「無し」と記載すべき契約IT不足の対象が無い。
次ロットで残り約5件を確認しUNCOVEREDが見つかった場合、その都度 `backend/src/test/java`
配下の契約IT有無を調査すること。

## 自己検証 fixture

`AuthzPropagateInventoryTest` 内の `パーサ自己検証` ネストクラスに以下を同梱済み。

- `p`: `return v` 形が return形 PROPAGATE として検出されることを確認（陽性）
- `q`: 小文字始まりメソッドへの委譲が委譲形 PROPAGATE として検出されることを確認（陽性・委譲先名の記録も検証）
- `r`: DTO 構築のみに流れる形（DTO_SINK）が PROPAGATE として検出されないことを確認（陰性）
- `s`: `if` で打ち切る形（GATE）が PROPAGATE として検出されないことを確認（陰性）
- `t`: アクセス修飾子の無い（package-private）メソッド内の PROPAGATE も検出されることを確認
  （既存検出器が過去に踏んだ偽陰性パターンの固定）

### ノイズ除去（本ロットで追加）

実測（`AuthzPropagateInventoryTest` 実行結果 `backend/build/reports/authz-propagate-inventory.txt`、
殿が実測）で、委譲形 22 件のうち `RoleResolver#resolveViewerRole` の 2 件が誤検出と判明した。

- `switch (roleName.toUpperCase()) { case "ADMIN" -> ... }` の `roleName` が
  `switch` という**予約語への委譲**と誤検出されていた（`switch` はメソッド名になり得ない）
- `log.warn("... {}", roleName, ...)` の `roleName` が `warn` という**ロガー呼び出しへの委譲**と
  誤検出されていた（下流の認可判定ではなくログ出力）

`AuthzPropagateInventoryTest#classifyUsages` に以下の除外を追加し、対で「除外しすぎ防止」の
fixture を同梱した（番人は緩めるのではなく正確にする、の戒めに従う）。

- Java キーワード（`JAVA_KEYWORDS`、既存の `assignedVariable` 判定と同一セットを流用）を
  委譲先メソッド名として検出しないよう除外
  - `u`: `switch(roleName.toUpperCase())` は検出されない（陰性）
  - `u2`: `switchService.evaluate(id, userId, roleName)` のような実在メソッドへの委譲は
    除外されず検出される（除外しすぎ防止・陽性）
- レシーバが `log`/`logger`（大小無視）かつメソッド名が `trace/debug/info/warn/error` の
  ロガー呼び出しを除外（新設ヘルパ `enclosingCallReceiver`）
  - `v`: `log.warn("...", roleName)` は検出されない（陰性）
  - `v2`: `warningService.warn(id, userId, roleName)` のような `log`/`logger` 以外のレシーバへの
    `warn(..)` 委譲は除外されず検出される（除外しすぎ防止・陽性）

この結果、`RoleResolver#resolveViewerRole` はダッシュボード集計から脱落し、委譲形の実数は
**22 件 → 20 件** に減った（return形 0 件は変化なし）。
