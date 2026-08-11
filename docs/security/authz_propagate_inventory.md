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

**殿による再実測（verify クローン、`./gradlew test --tests "*AuthzPropagateInventoryTest*"`、
BUILD SUCCESSFUL 7m57s）で上記が裏取り済み: 総件数 20（return形 0 / 委譲形 20）。
ノイズ除去が正しく効いていることを実測で確認した。**

再実行して確定値を更新する場合は上記コマンドを使うこと（本ロットの triage は `./gradlew`
実行禁止のため実ソースの目視確認で行った）。

## triage（仕事2・仕事3）— 確定版

`./gradlew` の再実測（BUILD SUCCESSFUL、`backend/build/reports/authz-propagate-inventory.txt` 実出力）
により正本の総件数20件（委譲形20/return形0）が確定した。全20件の triage が完了している
（前段15件は本ロット、残り6件は殿が実コードで追加確認。うち `KbPageFavoriteResolver#resolveAll`
は両者で重複して確認していたため、正本の並びで1エントリのみ計上する）。

以下は正本テキストの並び順（1〜20）に揃えた**20行**の一覧である。

| # | Controller/Service#メソッド | パス:行 | 委譲先（クラス#メソッド・パス:行） | 下流の認可判定の実体 | 判定 |
|---|---|---|---|---|---|
| 1 | MatchStatsController#getTeamStats | match/controller/MatchStatsController.java:169-172 | MatchStatsAggregationService#aggregateTeamStats（match/service/MatchStatsAggregationService.java:350-403） | `includeRankings ? buildPlayerRankings(...) : List.of()`（403行目）で SUPPORTER にはランキング非表示 | ENFORCED |
| 2 | DashboardService#getTeamDashboard | dashboard/service/DashboardService.java:384 | DashboardWidgetService#getWidgetSettings（dashboard/service/DashboardWidgetService.java:127-141） | `wk.isRoleRestricted() && !isAdmin` でロール制限ウィジェットを除外（139行目） | ENFORCED |
| 3 | DashboardService#getOrgDashboard | dashboard/service/DashboardService.java:598 | DashboardWidgetService#getWidgetSettings（同上） | 同上 | ENFORCED |
| 4 | KbSearchController#search | knowledgebase/controller/KbSearchController.java:55 | KbSearchService#search（knowledgebase/service/KbSearchService.java:35-58） | `isAdmin = ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY ページを除外フィルタ | ENFORCED |
| 5 | KbRevisionController#getRevisions | knowledgebase/controller/KbRevisionController.java:50 | KbRevisionService#getRevisions（knowledgebase/service/KbRevisionService.java:40-49） | `checkRevisionAccess`: `isAdmin \|\| isCreator` でないと KB_002 throw。page自体はscope束縛済み | ENFORCED |
| 6 | KbRevisionController#getRevision | 同:76 | KbRevisionService#getRevision（同:58-68） | 同上 | ENFORCED |
| 7 | KbRevisionController#restoreRevision | 同:100 | KbRevisionService#restoreRevision（同:78-112） | 同上 | ENFORCED |
| 8 | KbPageController#getPageTree | knowledgebase/controller/KbPageController.java:56 | KbPageService#getPageTree（knowledgebase/service/KbPageService.java:91-102） | `ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY ページを絞り込み。scope束縛は呼び出し元 `checkMembership` 済み | ENFORCED |
| 9 | KbPageController#getPage | 同:79 | KbPageService#getPage（同:109-124） | `checkAccessLevel(page, userRole)` が `ADMIN_ROLES.contains(userRole)` 未満なら KB_002 throw | ENFORCED |
| 10 | KbPageController#updatePage | 同:138 | KbPageService#updatePage（同:207-229） | userRoleはscope束縛済みentityに対する処理内で消費（controller側 `checkMembership` 併用） | ENFORCED |
| 11 | KbPageController#getRecentPages | 同:226 | KbPageService#getRecentPages（同:433-450） | `isAdmin = ADMIN_ROLES.contains(userRole)` で ADMIN_ONLY を除外フィルタ | ENFORCED |
| 12 | SkillController#getSkill | skill/controller/SkillController.java:98 | MemberSkillService#getSkill（skill/service/MemberSkillService.java:130-139） | `checkScopeOrThrow` でscope束縛＋`!skill.getUserId().equals(requestUserId) && !isAdmin(userRole)` で SKILL_003 throw | ENFORCED |
| 13 | SkillController#updateSkill | 同:114 | MemberSkillService#updateSkill（同:157-189） | 同様のscope束縛＋本人/ADMIN判定 | ENFORCED |
| 14 | SkillController#deleteSkill | skill/controller/SkillController.java:133 | MemberSkillService#deleteSkill（skill/service/MemberSkillService.java:201-207） | `findSkillOrThrow(id)` でentity取得後 `checkScopeOrThrow(skill, scopeType, scopeId)` が **skill自身のscopeType/scopeId**（entity由来）とリクエスト由来の引数を突合。不一致はSKILL_003で秘匿。加えて本人orADMIN判定 | ENFORCED（MemberSkillService.java:203, 260-263） |
| 15 | SkillController#getCertificateUrl | skill/controller/SkillController.java:178 | MemberSkillService#getSkill（skill/service/MemberSkillService.java:130-133） | 同上 `checkScopeOrThrow` | ENFORCED（:133, 260-263） |
| 16 | GoogleCalendarService#isSchedulePushableToUser | schedule/service/GoogleCalendarService.java:559 | 同ファイル、`satisfies` 直前の `accessControlService.resolveEffectiveRoleName(userId, scopeId, scopeType)` | **scopeIdは `schedule.getTeamId()/getOrganizationId()`（entity由来、:551-552）**。`resolveEffectiveRoleName` はuserId×scopeIdでmembershipを都度再クエリし、非所属ならnullを返す。`MinViewRoleThreshold.satisfies` はnullを最弱扱いで通さない。さらに先行してF00 `contentVisibilityChecker.canView`(:554)がゲート | ENFORCED（:551-559, AccessControlService.java:213-249, MinViewRoleThreshold.java:60-63） |
| 17 | GoogleCalendarService#filterBackfillSchedules | schedule/service/GoogleCalendarService.java:634 | 同 `resolveEffectiveRoleName`（:631） | 候補集合自体が `scheduleRepository.findUnsyncedByUserAndScope(userId, scopeType, scopeId)`（:688-689）で同じuserId×scopeIdに限定され、role再解決も同じ組で行う。先行して `contentVisibilityChecker.filterAccessible`（:629-630） | ENFORCED（:623-636, 685-691） |
| 18 | KbPageFavoriteResolver#resolveAll | favorite/resolver/impl/KbPageFavoriteResolver.java:67 | 同クラス内 private `resolveCanEdit`（同:95-104） | `Set.of("ADMIN","DEPUTY_ADMIN").contains(roleName)` で ADMIN_ONLY/CUSTOM の編集可否判定。roleName==null は事前に unavailable 扱い（62行目） | ENFORCED |
| 19 | TeamFriendsController#isSupporterOnly | social/controller/TeamFriendsController.java:186 | （`"SUPPORTER".equals(roleName)` ＝ `java.lang.String#equals`） | 認可判定そのものではなく、ロール名の等値比較を `return` しているだけ。委譲先が `String` であり認可クラスではない | NOT_APPLICABLE（検出器の別ノイズ候補。本ロットでは検出器・製品コードとも未修正。次ロットの除外候補） |
| 20 | SharedFolderQueryService#findVisibleFiles | filesharing/service/SharedFolderQueryService.java:140 | SharedFolderAccessGuard#resolveVisibleFileLevels（filesharing/service/SharedFolderAccessGuard.java:168-184） | `resolveRoleScope(folder)` で**folder自身のteamId/organizationId**（entity由来）を得て `hasRoleOrAbove(userId, scope.scopeId, scope.scopeType, level)` を各レベル評価し許可集合を構築。到達前に :81 `authorizeView(folder,userId)` も通過 | ENFORCED（SharedFolderAccessGuard.java:168-184, SharedFolderQueryService.java:79-86, 132-140） |

### 数の突き合わせ（解消済み）

本ロット前段の triage（15件）と殿が追加確認した6件（合計21エントリ）を正本20行と突き合わせた結果、
**`KbPageFavoriteResolver#resolveAll`（正本 #18）が両方で重複して確認されていた**ことが判明した
（前段の「match 1 / dashboard 2 / knowledgebase 8 / skill 2 / favorite 1 / NOT_APPLICABLE 1」＝15件の
「favorite 1」と、殿の追加6件のうち1件が同一エントリ）。15 + 6 − 1（重複） = 20 で正本と一致する。
重複は解消済みであり、以降の未確認・未特定の残件は存在しない。

### 結論

検出器（`AuthzGateEffectivenessAuditTest` 形②）が「下流が実際にenforceしているか検証しない
（javadoc L124-128の既知の限界）」として**原理的に追えない**としていたPROPAGATEの盲点は、
**実査の結果、全件が下流でenforceされており空だった**。正本20件すべてを確認し、UNCOVERED
は0件である。

### なぜ安全だったかの共通構造（本 triage の最重要知見）

確認できた委譲先はいずれも、**呼び出し元から渡された scopeId / roleName をそのまま信じて
使う**のではなく、次の形を取っていた:

1. 対象の **entity 自身が持つ scopeId**（`page.getScopeId()` / `schedule.getTeamId()` /
   `folder.getOrganizationId()` / `skill` 自身の scopeType・scopeId 等）を取得する
2. その entity 由来の scopeId と `userId` を**委譲先の内部で改めてペアにし、membership・role を
   都度再解決**する（`accessControlService.getRoleName` / `resolveEffectiveRoleName` /
   `hasRoleOrAbove` 等を委譲先が呼び直す）
3. 再解決した role/canEdit が null・不足なら、フィルタ除外・throw・UNAVAILABLE 化のいずれかで
   応答を止める

この「entity 由来の scope で都度再解決」という形であれば、呼び出し元が渡した引数が
仮に細工されていても委譲先が entity から真の scope を取り直すため、2段抜け（BOLA）は
成立しない。**逆に言えば、今後 PROPAGATE を新規に追加する際は、この構造（entity由来scope
での再解決）を踏襲しているかを確認基準とすべき**である。

### 内訳（正本20件・確定値）

- **ENFORCED: 19件**
- **UNCOVERED: 0件**
- **NOT_APPLICABLE: 1件**（`TeamFriendsController#isSupporterOnly` — 委譲先が `String#equals` で認可の話ではない）

19 + 0 + 1 = 20 で正本の総件数と一致する。

### UNCOVERED 一覧

該当なし（0件）。正本20件すべての triage で UNCOVERED は発見されなかった。

### 仕事3（契約ITの有無）

UNCOVERED が0件であったため、契約IT不足として次ロットへ引き継ぐ対象は無し。

### 限界の明記

- 本 triage は各メソッドの直接の呼び出し経路（委譲先の実装）を読んだものであり、
  `accessControlService` / `AuthzGateEffectivenessAuditTest` 自体の実装が正しく動作する
  という前提に立っている。その前提自体の妥当性は別途の監査対象である。
- 実行時のE2E確認（実際にリクエストを送って認可漏れが無いことを踏む）は本 triage の
  範囲外である。静的な読解のみに基づく判定であり、リフレクション・動的ディスパッチ等で
  実行時に異なる実装が呼ばれるケースは考慮していない。
- この判定（triage結果・共通構造の評価）は棚卸しの一覧が増減した際（新規PROPAGATE検出や
  検出器のロジック変更時）に**やり直す必要がある**。再生成コマンド:
  ```bash
  cd backend && ./gradlew test --tests "*AuthzPropagateInventoryTest"
  ```
  再生成後、`backend/build/reports/authz-propagate-inventory.txt` の行数・内容を本表と
  突き合わせ、新規行があれば追加で triage すること。

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
