# 境界返済戦役の複数大名運用

親Issueは [#4022](https://github.com/kenta-0420/mannschaft/issues/4022)、仕様は[境界負債返済計画](../architecture/modular_monolith_boundary_debt_repayment.md)を正本とする。これは担当調整基盤の手順である。下記の裁可済みL0-M依存免除以外のPhase入口・子CMP起票・品質ゲートを維持する。

## 同じ文面を各セッションへ貼る指令正本

以下のコードブロック全体を、別セッションの大名へ同じまま貼る。領地を先に決める必要はない。領地取得はレーンreadyを意味しない。

```text
CMP-261021-0017 の複数大名として自領を運用してください。
最初に本陣のHEADを最新と仮定せず、git fetch origin main → git show origin/main:docs/development/boundary-coordination.md で最新正本を読む。本陣のpull/resetやdirty資産への更新は禁止。CLAUDE.md / AGENTS.mdも最新正本を読み、mannschaft-daimyo / role-assignment に従う。CLI/manifestが本陣に無ければ、--no-checkoutで新しい管理用worktreeを作り、scripts/manifest/guide/計画/規約だけsparse配置してCLIを実行する。全ファイルのcheckoutを標準にしない。管理用worktreeと足軽の実装用worktreeを分ける。
1. statusのclosed=trueなら親戦役終結済みとして着手せず終了する。最新origin/main、親Issue #4022の全コメント、docs/task-list.md、移設先、調整CLIのstatusを確認する。本陣の既存資産は触らない。
2. statusで7領地の依頼・待ち・既存担当・入口を確認し、未所有で今進められる作業がある領地を選ぶ。working可能なレーンを優先し、無ければ読取準備で前提を整えられる領地を選ぶ。保持済みの領地しか無ければ担当を奪わず状況を報告する。
3. セッション固有UUIDv4を生成し、ownerを会話と引継書へ記録する。同じ本陣cwdでも他セッションとUUIDを共有しない。再開時のみ既存の自分のUUIDを使う。
4. CLI claim --territory <選んだ領地> --owner <UUID> がremoteに確定した後だけ、その領地の大名として足軽を運用する。競合・通信失敗・未確定なら足軽を起動しない。別領地を取るには現在の自領を明示解放する。
5. 次作業の選択前と待機からの再開前に必ずstatusを再取得し、他領地からの依頼・待ちを読む。自領内レーンでも既存holdを引き取らず、依存・手動gate・共有resourceを確認する。planningは読取準備のみ、workingは実装着手、preparation種別は証拠準備だけである。
6. 子CMPが未登録なら、専用worktreeのTask-Listのみの登録PRを先にmainへ通す。レーン/ロット・対象クラス・子CMP・branchを親Issueへ宣言する。波とGradle束を混同せず、後続波未確定を第1波の追加前提にしない。
7. 共有ファイル変更は指定ownerへ依頼し、依頼先/対象範囲/関連PR/必要な引渡し/状態をnoteへ追記する。相手APIの既存ファイルを無断編集しない。自領内の足軽もクラス/ファイルごとに分け、同じファイルを並行編集させない。
8. 試練red→実装green→検分→必要な実機・アリシゼーション→CI→通常mergeを、既存の大名フローで行う。未実行をgreen扱いしない。main追従・push前更新とPRの最新状態確認を省かない。
9. レーン完了はmain着地PRとgreen検証を証拠にupdate doneする。保持中断はpaused/blockedでresourceを保持し、解放/引継ぎ前には足軽と共有編集を止める。既存担当の明示解放を確認せず引き取らない。TTLによる自動引取は禁止。
10. 自領以外の作業を勝手に引き取らず、未解決依頼を残して引継書・親Issue・調整台帳へ記録する。PREや局所greenだけで親戦役を閉じない。Gradle撤退時だけ計画§14の判定・御裁可と代替出口証拠をgradle担当がgradle-withdrawn gateへ記録し、終結のL3-Bnn/I依存を読み替える。L5-Iは全入口・最新証拠・他レーン停止が必要。
```

## 古い本陣から開始する場合

本陣のHEADが古くdirtyな場合、本陣へpullやresetをせず、`git fetch origin main` の後に `git show origin/main:docs/development/boundary-coordination.md` で本手順を読む。CLIが本陣に無くても、本陣の作業木を更新する必要はない。

各大名はセッション固有の軽量な管理worktreeを標準とする。`git worktree add --detach --no-checkout <専用管理worktree> origin/main` で作成後、同worktreeで `git sparse-checkout set --no-cone` により `scripts/`、本manifest/guide、返済計画、`docs/task-list.md`、README/CLAUDE/AGENTS/TEST_CONVENTION、必要な `.claude/commands/` とスキルだけを配置する。`--no-checkout` を付けず全18,000超ファイルを先に展開すると、同じディスクを使う各セッションで空き容量を圧迫する。管理用worktreeは状態確認・調整操作だけに使い、実装足軽は規約どおり別の隔離worktreeを持つ。管理用worktreeの最新追従もdirty資産を保護し、作業再開時にmain上のCLI/manifestとの版一致を確認する。
## 配賦と所有

追跡する[manifest](boundary-coordination.json)が全141レーンの領地を一意に定める。

| 領地 | 担当 |
|---|---|
| foundation | L0-M/S/C、L1-G0/M/F/G |
| common-core | L1-K/A/R、L4の5領域とL4-I |
| repayment-a/b/c | L2の113発生元ロットを名前順round-robinで38/38/37に配賦 |
| gradle | L1-B、L3-0A/0K/0D/I/P/Bnn |
| closure | L-SHIME、L5-PRE-D/V/O、L5-I |

L2のA/B/Cは所有を分けるだけで、波やGradle束ではない。負債件数・D-3T件数を工数とみなさない。具体的L3-B01〜BnnはL3-0Aで固定し、個々の進捗は親Issueの既存チェックリストへ記録する。CLIのL3-Bnnは全束出口の予約枠で、planning→全束出口証拠後doneだけを受け付ける。束未確定の実装許可ではない。具体束を確定した後は、各Bの対象L2完了・依存DAG・パイロット継続判定を親Issueで確認して足軽へ渡し、個々Bの実装状態を同Issueへ記録する。aggregateのplanningは、個々Bを実装してよいという許可にも、入口充足後の個々Bを読取だけに制限する理由にも使わない。各Bのmodule内を実装し、共有buildへの反映時だけL3-Iをworkingへ進めて予約し、統合green後doneで解放する。全B出口が揃った時点でaggregateをdoneにする。

初期状態はL0-S/C・L1-K/G0だけを計画の完了記録としてseedする。L0-Mは9〜13計測予約・診断停止を含め担当保持、L1-Aは既存再開承認、L1-FはPR #4045の既存担当継続をlane単位のholdとする。common-core取得でもL1-Aを奪わず、L1-Rは付録Aの対象クラスと先行範囲を別途確認する。完了PRを観測したことと、担当が明示解放されたことを同一視しない。

## 裁可済みL0-M依存免除

[2026-10-27の御裁可](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6430453220)「L0-Mは計測に時間がかかりすぎるためスキップ」により、L1-B→L0-Mの依存edgeだけを免除する。L0-Mは基準計測未完了・性能未実証のままで、既存の計測試行・rawを保持し、正式な基準計測成功やdoneへ読み替えない。L3-Pの比較baselineも未実証であり、後続の性能greenや§14のGradle全体撤退の裁可ではない。計画§5.1.1を参照する。

CLIのstatus APIと実CLIの全体status / `status --territory gradle` は `dependencyWaivers` に対象edge・裁可URL・理由・基準計測未完了情報を表示する。manifest/stateのschema・keys・digestは変えず、L0-Mのstate/hold/held/history/evidenceとfoundation所有者を変更しない。L1-Bのbuild/weights予約、owner境界、他依存・gate・L5終結排他を維持する。

再開前に最新origin/mainを取得し、この変更を含む新版CLIを管理worktreeへ配置する。旧CLIはL1-Bを保守的に拒否するが、他操作は可能なので、全mutationが拒否されるとは扱わない。L0-Mのmeasurement予約とholdはこの裁可で解放されない。実際の計測中の同マシンheavy禁止と、L5終結前の停止・解放は別途既存ownerと調整する。

## 台帳の所在と初回だけの初期化

Node標準ライブラリとGitだけを使う。

```bash
node scripts/boundary-coordination.mjs status
```

実行状態はoriginの専用ブランチ `campaign/cmp-261021-0017-coordination` の `state.json`。mainとは親を持たないstate専用履歴であり、mainへマージしてはならない。mainへの通常実装PRとは別の調整台帳である。既存workflowのpush対象はmainのみなので、調整のたびに製品CIは起動しない。

本CLI・manifestをmainへ着地させた後、最初の管理担当だけが次を実行する。CLIはorigin/main上の両ファイルとの一致を確認する。既存refの再初期化・削除・force pushは禁止。

```bash
node scripts/boundary-coordination.mjs init
```

通常pushが通らない権限・保護設定なら、その理由を報告して停止する。資格情報を書き出したり別経路へ迂回したりしない。Gitの標準credential経路を使う。owner UUIDは共同作業者間の取り違え防止用であり、悪意ある同じGit書込権限者を隔離する認証秘密ではない。

CLIはGit objectだけを作成・取得し、作業木・index・HEAD・FETCH_HEADを変更しない。通常pushが更新する調整用remote-tracking refだけを許可し、それ以外のrefは変更しない。CAS競合では最新stateから操作を再評価する。push応答喪失時はcandidate commitがremote履歴に存在するか読戻して確定し、確認不能なら成功扱いしない。壊れたstate、manifest/schema不一致、通信失敗を空台帳へ丸めない。失敗後も同じownerでstatusを確認して再開する。manifestを将来改訂する場合、既存台帳と不一致なら停止するため、保持者全員の停止・明示移管を含む移行設計を別途行い、initで上書きしない。

## 日常操作

新規セッションでは次でUUIDを生成し、記録して使う。claimのowner省略時も新UUIDが発行され、失敗時も復旧できるようstderrへ先に表示する。

```bash
node -e "console.log(require('node:crypto').randomUUID())"
node scripts/boundary-coordination.mjs claim --territory common-core --owner <UUID>
node scripts/boundary-coordination.mjs update --lane L1-R --owner <UUID> --state planning --note '付録Aと既存先行担当を読取確認中'
node scripts/boundary-coordination.mjs update --gate return-targets --owner <UUID> --evidence '<親Issueの対象クラス宣言・既存担当との範囲調整>'
node scripts/boundary-coordination.mjs update --lane L1-R --owner <UUID> --state working
```

gateの条件・担当領地はmanifestに固定する。gate担当のownerだけが証拠つきで充足を記録できる。CLIは証拠の意味まで自動検証しないため、担当大名が入口条件に照合し、検分で裏取りする。gate設定だけで依存レーン完了やresourceを解放できない。

各L2ロットは `wave-<domain>` と `pattern-<domain>` gateを持つ。前者には登録済み波・子CMP・親Issueの証拠を、後者には採用パターンと追加入口（認可ならL1-A、C/FならL1-M）の充足証拠を記録する。後続波は先行波着手を妨げない。L3束割当/継続判定、L4入口/契約順、L5最新証拠もgateとして残し、未確定をready扱いしない。L3-IとL-SHIMEは常設のためdone後の再開を許し、各回の証拠をhistoryへ保持する。L3-0の基盤統合完了はIのdoneを証拠にbase-integrated gateへ固定し、後続P/Bの共有統合サイクルとは区別する。L4-Iの契約準備はplanningで行い、5領域の完了後にIのworking/全体統合へ進む。

noteは上書きせず追記する。依頼と応答はIDを揃え、未解決依頼を次の進捗で消さない。

```bash
node scripts/boundary-coordination.mjs update --territory repayment-a --owner <UUID> --note 'REQ-01 未解決: 宛先common-core / NameResolver API既存ファイル / PR #... / 必要な署名と移設先の引渡し待ち'
node scripts/boundary-coordination.mjs update --territory common-core --owner <UUID> --note 'REQ-01 応答: PR #...で提供 / 呼出側着手可 / 契約...'
```

statusの標準出力は7領地の概要・依頼note・hold・裁可済み依存免除を表示する。status --territory <自領> で自領レーン・gate条件/証拠・共有予約の詳細を確認する。claim/updateはowner・対象・新状態・commitの要点だけを表示する。各大名は作業選択・再開前に全領地のstatusを読む。自領内でもL1-R対象クラス・L2発生元・L3移設先をnoteへ記録し、他大名が確認できるようにする。通知daemonは持たない。親Issueの着手・中断・解放コメントも計画§7.3どおり続け、Git CAS台帳を排他確定の正本とする。食い違いがあれば着手せず、両方を確認して既存担当を保護する。

## 共有資産と待機・引継ぎ

| resource | 取得する段階・主なowner |
|---|---|
| build | L1-B/L1-M/L3-Iのworking開始。共有root build/settings/build-logic/CIは同時編集しない |
| architecture | L1-F/L1-G/L3-Iのworking開始。計画で指定された番人の担当範囲は維持する |
| freeze-constants | L1-F/L-SHIME。L2返済ロットは定数を変更しない |
| weights | L1-B/L3-I/L-SHIME。重み表の同時更新を防ぐ |
| verify-list | L1-G/L3-I/L4-I。L2は共有のverify必須化リストを編集するときだけ手動予約する |
| measurement | L0-Mの計測予約。計測窓と同マシンheavy停止は親Issueでも確認する |

計画§14で御裁可されたGradle撤退では、gradle担当のgradle-withdrawn gateに撤退判定・裁可・凍結空とModulith全verify等の代替出口証拠を記録する。この場合だけL5-IのL3-Bnn/I完了依存を読み替え、全L2/L4・最新最終証拠・他レーン停止は維持する。証拠なしの撤退や他領地のgate設定は認めない。

L5-Iは終結で使う共有resourceを取得し、その間の他レーンworkingを拒否する。L2のコード作業は別領地で並行でき、共有編集する段階だけ次を使う。既存番人の変更はそのownerへ依頼する。L2の同じ凍結ストアからの別行削除は計画§7.4の照合・再生成手順を守り、ストア全体を独占しない。

```bash
node scripts/boundary-coordination.mjs update --lane L2-actionmemo --owner <UUID> --resource verify-list --acquire
# 共有リストの変更と統合完了後に明示解放
node scripts/boundary-coordination.mjs update --lane L2-actionmemo --owner <UUID> --resource verify-list --free --confirmed-stopped --evidence '<共有変更PR・引渡し>'
```

resourceは `paused` / `blocked` にしても保持され、gate設定で解除されない。完了doneまたは明示releaseで解放する。territory handoffでは予約を次ownerへ引き継ぐ。重いGradle実行は引き続き既存turnstile経由とし、計測窓を避ける。resource予約をCPUロックや全面ファイルロックの代用にしない。

```bash
node scripts/boundary-coordination.mjs update --lane L1-R --owner <UUID> --state paused --note '担当保持。引継書...。未解決REQ-01...'
node scripts/boundary-coordination.mjs update --lane L1-R --owner <UUID> --state done --evidence '<main着地PR・green試験・出口照合>'
node scripts/boundary-coordination.mjs release --territory common-core --owner <UUID> --confirmed-stopped --evidence '<足軽停止・親Issue解放・引継書>'
node scripts/boundary-coordination.mjs handoff --territory common-core --owner <旧UUID> --to <新UUID> --confirmed-stopped --evidence '<親Issue合意・足軽停止・引継書>'
```

既存lane holdだけの移管は、当該既存担当の明示解放・足軽停止を親Issueで確認した後、領地取得済みownerが行う。期限経過やPRが完了らしいという理由だけでは実行しない。外部担当へ連絡不能なままの自動引取は行わない。

```bash
node scripts/boundary-coordination.mjs handoff --lane L1-A --owner <自UUID> --from existing:L1-A --to <自UUID> --confirmed-stopped --evidence '<既存担当の明示解放コメントURL・引継書>'
```

## 検証と受け入れ条件

`node --test scripts/boundary-coordination.test.mjs` がAC1〜9の根拠である。ローカルbareを使う実Git競合、異なる領地の再試行、応答喪失/送信失敗、担当保持、UUID/移管、入口/gate、共有resource、L2並行、L5終結排他、依頼追記、dirty作業木/index/調整用追跡ref以外のref不変を検証する。Harness CIでも同じ試験を実行する。

L0-M依存免除の追加試験は `計測免除AC1/2`（L1-B着手・完了、L0-Mと他lane不変、schema/keys/digest不変）、`計測免除AC3` の2本（build/weights・owner・L5排他、L1-G/L2入口・measurement停止条件）、`計測免除AC4`（status APIと実CLI全体/gradleの裁可表示）の4本。既存のL1-M未解消依存試験も維持し、CAS・通信失敗・dirty資産保全試験を削除しない。

実行結果・AC対応・5類型・実origin未実施の範囲は[検証記録](../evidence/CMP-261022-1026-coordination.md)を参照する。

本変更は製品UI/API/DBを変更しないため、画面E2Eと権限ロール横断・画面探索は対象外。代わりにmain着地後、実originで初期化→claim→status→releaseを管理担当が行い、remote台帳と保持3件を読戻して確認する。実origin未検証をローカル試験のgreenで代用しない。
