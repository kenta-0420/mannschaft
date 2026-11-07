# 複数大名の領地調整基盤 検証記録

- 対象: CMP-261022-1026。親Issue [#4022](https://github.com/kenta-0420/mannschaft/issues/4022)。製品UI/API/DBを変更しないtooling。
- 比較元: `94d251a8a970037c1733cacefbc2b27f7745deb7`（登録PR [#4052](https://github.com/kenta-0420/mannschaft/pull/4052) main着地後）。
- 実行日: 2026-10-22 JST。Windows / Node v24.15.0 / 実Gitのローカルbare origin。
- 実装: [CLI](../../scripts/boundary-coordination.mjs)、[manifest](../development/boundary-coordination.json)、[同一貼付指令・操作手順](../development/boundary-coordination.md)。試験: [受入試験](../../scripts/boundary-coordination.test.mjs)。

## 試験の順序と結果

登録CI待ちの間にTEMPで設計prototypeと試験案を先行作成し、殿の読み取り検分を反映した。純粋なtest-first開発としては報告しない。登録main反映後、未実装transition骨格へ受入試験を当て、依存未充足でworkingを拒否すべきassertionが `Missing expected exception`（期待 `/依存:L0-M/`）で失敗する検出能力を確認した。import・環境起動の失敗をredと数えていない。

| 実行 | 結果 | 意味 |
|---|---|---|
| 骨格にAC7の依存拒否試験 | 0/1、exit 1 | 依存を確認しないtransitionを実assertで検出 |
| 初回本体受入全体 | 29/29、exit 0、147.98秒 | 実Git競合、失敗、所有境界、CLI、保全を含む |
| 追加の未知lane/空証拠/main不一致init拒否 | 2/2、exit 0 | 未初期化のまま安全停止する境界を追加 |
| 解放証拠の永続化変更後の対象再検証 | 4/4、exit 0、27.07秒 | release/handoff/resource freeを追記保存。実CLIのrelease後remote再読でも固有証拠と旧依頼を保持 |
| 141レーン/4回CAS打切りの境界 | 2/2、exit 0、44.54秒 | 実Gitで競合を4回発生させて安全停止。remoteに未確定claimを残さない |

全受入試験はHarness CIの既存Node `--test` 一覧へ1行追加した。上の部分実行で対象の追加・変更を検証し、最終全体は同じファイルのCI実行で確認する。

ローカルログは専用worktreeの `.claude/evidence/cmp-261022-1026/` に保存した（揮発資料）。`skeleton-red.log`、`acceptance-first.log`、`acceptance-boundaries.log`、`release-evidence.log`、`cas-boundary.log`。試験本体は追跡済みなので再実行可能である。

## 受け入れ条件と根拠

試験名のAC番号で対応を固定する。

| AC | 条件 | green試験・実装の対応 |
|---|---|---|
| AC1 | 全未完了・完了seedを含む141レーン、正本113L2を重複欠落なく7領地へ配賦 | `正本113ロットを38/38/37へ一度ずつ配賦`。manifestと計画の全L2集合を実比較 |
| AC2 | 同文の別UUIDから排他取得し、別領地は競合再試行で取得 | `同じ領地の同時claim`は両candidateの親SHA一致をバリアで保証。`別領地の同時claim`は双方の状態をremote再読 |
| AC3 | L0-M/L1-A/L1-Fの既存lane担当を領地取得・解放で奪わない | `既存担当3件は領地claim・解放でも保持`。初期holdと予約を保存 |
| AC4 | 他owner・空UUID・複数領地・無断移管を拒否し、引継ぎ後の旧ownerを拒否 | 非owner/TTL引取、territory handoff、既存lane移管の各正負試験。停止確認と証拠を必須化 |
| AC5 | 途中失敗・push不明・競合時は成功を捏造せず最新stateを再評価 | 応答喪失readback、送信不能、致命Git祖先照合エラー、4回CAS打切り。通常FF pushのみ |
| AC6 | 空・未知lane・壊れたschema/manifest/state・無担当working・必須予約欠損を拒否 | 壊れたstate、未知lane/空証拠、required-resource、resource0でもowner必須の各試験。空台帳へ置換しない |
| AC7 | 入口/gate/resource/終結を維持し準備と実装を区別 | 依存拒否、ロット固有波gate、gate担当、共有build保持、L2並行とverifyだけ予約、締め重み表、常設反復と履歴、aggregate、L5双方向/終結後排他、裁可済みGradle撤退の正負試験 |
| AC8 | 他領地への依頼と未解決待ちを次の進捗で消さない | `未解決依頼のnote`追記保持。解放/handoff/resource freeの操作・旧新owner・証拠もnoteへ追記。指令は作業選択/再開前に全領地statusを読む |
| AC9 | 同じCLI実入口で運用でき、本陣資産を変更しない | 実CLIの自動UUID/claim/update/status/release/引数拒否、main不一致init拒否、dirty作業木/index/HEAD/調整用tracking以外のref不変。調整pushによるtracking更新だけは許容 |

## 攻め口5類型

- 空・0件・null: 空UUID/空証拠/欠落state/未初期化/未知laneを拒否する。0件負債のL2も113集合から外さない（AC1/4/6/9）。
- 境界値: 141レーン・113ロットの完全集合一致、7領地一意性、同ownerは1領地のみ、競合再試行は4回で停止（AC1/2/4/5）。
- 途中失敗: 送信前失敗、push成功後の応答喪失、祖先照合の致命失敗、CAS競合を実Gitで検証。確認不能を成功にしない（AC5）。
- 認可/所有: Git書込権限を共有する協調大名の取り違えをUUIDで防ぎ、他owner/gate担当/既存hold/旧ownerを拒否する。UUIDは悪意ある同じGit権限者を隔離する認証秘密ではない。製品ユーザー/テナント認可は変更しない（AC3/4/6/7）。
- 性能: 状態はcampaign専用の固定141レーンを一括読み、再試行4回、常駐監視や無限取得を持たない。重いアプリ負荷やGradle性能改善を対象にしない。CLI概要は7領地と依頼/hold、自領指定時だけ詳細を返す（AC1/5/9）。

## 未完了・適用外・環境制約

- 実originの調整ref初期化→claim→status→releaseは、本コードがmainへ着地した後に管理担当が行う。現時点では未実施。ローカルbareのgreenを実origin完了の代わりにしない。
- 製品UI/API/DBを変更しないため、画面実機E2E・画面アリシゼーション・製品ロール/テナント横断は本tooling変更では適用外。代わりに実Gitと実CLIの往復を検証した。後続の各返済レーンでは計画の実機条件を引き継ぐ。
- 既存Harness4本のローカル実行は34件中32件成功/2件失敗。失敗①sparse checkoutで既存 `docs/evidence/` が無く既存全証拠列挙がENOENT、②既存3000files分類が125.82msで100ms閾値超過。新CLIの試験失敗ではないが、ローカルHarness全緑とは報告しない。既存試験・閾値は変更せず、full checkoutのHarness CIで最終確認する。
- 本作業中のC:空容量0は、殿がこの専用worktreeだけをsparse化して復旧した。本陣・他worktreeの資産を変更していない。以後の大名の管理worktreeはno-checkoutから必要資産だけ配置する手順を正本へ記載した。
