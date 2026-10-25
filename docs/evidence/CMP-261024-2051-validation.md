# CMP-261024-2051 フレンド申請 検証準備記録

2026-10-25時点の途中記録。scopeはapp。最終検分・実機・探索・CIの合格記録ではなく、台帳を完了へ変える根拠には使わない。実装ドラフトPRは#4106、runtime開始時の統合HEADは46deae93b5591fcb9bd4aae59bd68aa7157b0918。最終head SHAと成功CI run IDは未確定。

正本は[F01.5 §7.1](../features/F01.5_team_friend_relationships.md)のFR-01〜15。[完了証拠ゲート](../development/completion-evidence.md)に従い、実装PRのmerge済head SHA、同SHAの成功CI、review/real/exploratory各passedが揃ってから完了JSONを作る。この準備では完了JSONもtask-listの状態変更も追加しない。

## 保存した証拠

- [BE red/green要約](CMP-261024-2051/backend-red-green-summary.json): source SHA、実測件数、suite名、SQL本数/p95、comment境界、限界を保存。
- [FE red/green要約](CMP-261024-2051/frontend-red-green-summary.json): 回帰のred/green対応、型検査の未合格、初回CI失敗を保存。
- [schema衝突回帰のred/green](CMP-261024-2051/schema-regression-red-green-summary.json): 契約2 assertion失敗から41/41成功、既存契約比較と正規原本hashを保存。
- [実行receipt・JUnit件数](CMP-261024-2051/selected-run-receipts.json): 原本から許可キーを選別して直接取得。全runのcleanup=removed。本文ログ、認証入力、JWT、cookie、環境ファイル、OpenAPI全文は含めない。

原本は私有実行領域と各担当campaignにあり、ここには永続的な最小要約を保存した。BEのsuite件数はprivate要約とcampaignの記載、FEのsuite件数とsource SHA/exit/cleanupは原本receipt/JUnitを直接確認した。テストコードへの参照は実装PRの最終SHAへ対応付け直す必要がある。

## 実績と限界

BE red4（fe7316ab1808e258e7aae36e92a455b67d7216ca）は81件中80 assertion失敗、1成功、error/skip=0。候補・権限・incomingの未実装404、元失効の期待403に対する201を観測した。先行red1〜3の環境起因失敗は契約redとして数えない。

BE green1（daa8a1a7a586200b59ff0bb2d42ff2b0b41431e8）は212/212成功、ArchUnit37、freeze integrity2、standalone architecture347成功（各error/skip=0）。comment1（3688afb716e58c4d214b26bf647e0858b9d7a39c）は69/69成功で、slug/数値のcomment省略/null/空/300/301境界10追加ケースを含む。HTTP契約は実JWT/Security/MockMvc/Testcontainers MySQLであり、実ブラウザ検証とは区別する。

FR-15の100行×20回は候補SQL40本（2/page）、受信SQL60本（3/page、一括概要を含む）。HTTP/JWT込みp95は候補69.069666ms、受信78.619749ms。EXPLAINはddl-auto=create、Flyway無効のEntity生成schemaであり、本番migrationの索引証明ではない。Flyway適用専用DBのSHOW INDEX/EXPLAIN/HTTP性能は準備済み・未実行。

FE green1（91092f544bbc881f676f9ed4ad449221fe2ed925）は10files/131tests成功、unhandled=0、heap8192のtypecheck exit0。後続のbusy、旧件数/エラー/世代、日時、409二重更新、Paginator物理無効化を個別red→affected greenで確認した。pager-greenは15/15成功。詳細runとSHAは保存JSONにある。DOMやcomponentイベントの注入を実ブラウザ再現とは扱わない。

生成型合流後types-green（0c8444c0d534f3d1a31f429cae67c6d67f47cd0e）は22/22成功・対象ESLint成功だが、typecheckはEntityCreateDialogの生成schema欠落3件でexit2。このschema衝突は下記BE契約修正で是正済み。後続FE types-green2（e2545f9d8bf5100791d820043ccfeb84138b1ba1）は3files/22tests green、heap8192のtypecheck exit0、ESLint max-warnings=0 exit0、runner exit0/cleanup=removedを確認した。receipt/JUnitおよび必要なログ集計・hashは選別保存済み。typecheckの既存Duplicated imports警告は残るがTS error行は0、eslint.logは0bytes。個別コマンドexit0は殿の正式報告、全runner exit0はreceipt直接読取による根拠として区別する。PR #4106初回CI run40149069435/job121036557968も新E2E specのno-empty-pattern 7件で失敗した。E2E修正9eec97be14では2filesのlintがmax-warnings=0でexit0（private結果直接確認）。最終統合SHAの再CIは未確認であり、lint成功をCI全体成功とは扱わない。

OpenAPI schema是正は先行red834a71df8395f49897593dd0e035051a90cf7af4で契約2 assertion失敗・error/skip=0、ArchUnit37とfreeze2は成功。fix e1836e16b4後、正規生成元5880333e21fa0570f2c7b4b354de73c11f61b881のschema-green1は契約2+ArchUnit37+freeze2の41/41成功（error/skip=0）、両receipt cleanup=removed。一般エラーをCommonErrorDetailへ分離し、既存同名確認4schema、フレンド5schema/2pathsの不変をcomparisonで確認。正規OA commit5fe9c33d1af8c869c3b1154b3bfd60f3ef86d5b7、原本SHA256はb773146251944012149159a56011c4da966b83edc57329d69eb85368b256a20d（6367683bytes）。原本/WT/gitblob一致は殿確認報告として記録した。

## FRと現時点の根拠

BEの表記はbackend/src/test/java/com/mannschaft/app/social/配下、FEはfrontend/配下。以下は試験への対応であり、各FR全体を合格と宣言するものではない。

| FR | 自動試験の根拠 | 残る実観測・最終確認 |
|---|---|---|
| 01 | TeamFriendsControllerの数値path/排他契約、app/components/friends/FriendFollowDialog.spec.ts、nav-friend-toolbar.spec.ts | nav固定元の表示・確認方向と送信 |
| 02 | FriendRequestSourceTeamContractIT.FR02、FriendRequestEntry.spec.ts、useFriendRequestSources.spec.ts、Dialogの0/1/複数 | 両プロフィールURL入口と自己除外後候補 |
| 03 | SourceTeamContractIT.FR03の21件・安定順・総数・補正、Sourcesのページ跨ぎ/旧応答/空成功 | 実UI選択保持とbusy中Paginator |
| 04 | FriendRequestSourceTeamPermissionContractIT 21ケース、RoleService同値、既存nav権限 | 権限あり/なし/他テナント/匿名の直URL・HTTP |
| 05 | TeamFriendRequestSourceValidityContractIT 12ケース、両body・6失効・warm cache後403/write0、Dialog再選択 | 実UI選択後の剥奪・入力保持 |
| 06 | tests/unit/components/friends/FriendFollowDialog.spec.tsの1/2文字・299/300ms・同名slug・0/失敗、検索世代 | 実UI検索と失敗案内 |
| 07 | service/TeamFriendFollowSlugIT、既存404 SOCIAL_104写像 | 既知private slug成功、両プロフィール閲覧境界 |
| 08 | Dialogの確認前/取消POST0、Buttonの同tick再送、busy-red→greenとpager-red→green | 実ブラウザの入力/ページ物理抑止とキーボード |
| 09 | TeamFriendIncomingFollowContractIT.FR09、Incomingの方向/返し、Dialog固定方向 | A→B→B→A成立と送受信/成立一覧反映 |
| 10 | IncomingContractIT.FR10の非開示null/COUNT/認可/同Instant、Incoming伏せ字・datetime | 伏せ字リンクなし、時刻表示、他team拒否 |
| 11 | 既存Controller 202/409、Button最大3回/途中失敗/unmount、Dialog409の単経路 | 実API状態反映。202/通信失敗はモックなし実機と別のFE障害注入根拠 |
| 12 | TeamFriendPendingFollowContractIT.AC24の実DB HTTP interleaving、PendingList確認 | 取消/返しと一覧後成立の実状態。真の同時TX試験済とは記さない |
| 13 | comment1の10追加HTTP、XOR/slug400、friendFollowLocaleと6locale案内 | 配送保証なしのガイド/実表示 |
| 14 | Entry初期候補/認証復元0・クリック復元/安全復帰・自動送信0、locale番人 | SSR HTTP本人埋込0、360/390px、6言語、keyboard、再認可 |
| 15 | FriendRequestQueryPerformanceContractIT 2ケース、100行SQL定数2/3・p95、FE単一候補API | Flyway実索引/EXPLAIN/HTTP性能と最終実装N+1確認 |

runtimeは専用DBの初回migration1226本の適用で準備中（殿報告）。初期health待ち7分が足りず、殿承認の所有controllerのみSIGSTOP・Java継続・health UP後SIGCONTというインフラ対応中であり、本記録時点ではruntime readyを確認していない。実機8シナリオ・Flyway性能実測・3住民探索は未実行のまま。

## 完了前に埋める証拠

1. 最終統合PRのhead SHA、merge結果、同SHAの成功CI run ID、殿の検分結果。生成型typecheckとE2E lintの修正後ローカル結果は取得済みだが、統合CI全体の成功は未確認。
2. 固定clean SHAの実API/実DBで8シナリオの実機結果。権限横断/直URL、private既知slug、匿名復帰、SSR、mobileを実績に対応付ける。
3. 同じ専用Flyway適用DBで索引/EXPLAIN/HTTP性能。所有データのcleanup完了も記録する。
4. 実機の後に独立3住民（一般、中断、多言語/スマホ）の探索。各runId/persona/journey/source SHA/目的/証拠、殿裏取り、確定不具合のred/green。
5. 上記成立後だけdocs/evidence/CMP-261024-2051.jsonと台帳完了PRを作る。実装変更を含むappのreal/exploratoryにnot-applicableは使わない。

## B0気づき箱への登録経路

[B0_RUNNER](../prototypes/B0_RUNNER.md)の既存actual-resident-artifacts overlayを使う。3住民の原本を1つのdocs/prototypes/.b0-local/run-*.jsonへ束ねる。generatorはrun-*.json/blocked-*.jsonのmtime最新1件だけを読むため、住民ごとの別ファイルだけでは全員を取り込めない。

登録にはregistrationSource=actual-resident-artifacts、trusted=false、status=observed-unverified、runId、environment.sourceHeadが必要。personasは一意id/personaArchetype/runId/sourceHead/evidencePath、journeysは一意id/personaId/purpose/runId/sourceHead/evidencePathを持つ。insightsはid/featureKey/title/detailに加え対応personaId/journeyId/personaArchetype/originalRunId/evidencePath/status=observed-unverifiedを保持する。urgencyはurgent/normal/when-free。原本にIDがない場合は登録時付与と明記し、固定P01〜P20へ変換しない。気づき0件でも探索を行った宣言と原本証拠を保持する。

専用worktreeでB0_INCLUDE_LOCAL_OVERLAY=trueを指定してgenerate-beta-inventory-board-data.pyを実行し、validate-beta-inventory-board.mjs --self-test-overlayで整合を確認する。生成ボードを開くとb0RunOverlay.insightsが既存IndexedDBへ重複なしで入る。runner/dynamic runner実行やcoverage合格を意味しない。本陣の既存dirty beta-inventory-board-data.jsは上書きしない。現在は住民未実行のため観測登録も未実施。
