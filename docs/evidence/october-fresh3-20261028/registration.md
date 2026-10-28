# 第五陣 fresh3 観測登録

`mannschaft-daimyo` の正本 `.claude/commands/アリシゼーション.md` と `docs/prototypes/B0_RUNNER.md` の actual-resident-artifacts 手順に従う文書登録。製品sourceの変更なし。

- 初期登録基準: `54923edd410412cb371e46b49a505918f3171b37`。通常main追従先は `9eb2dbb58214a802d72440c61b301ce53d3e8358`、追従・board再生成後の測定HEADは `5d76dd31ad8bb7c7b15e971ce8ca7067eb8e008a`。
- FE `8f671cba847a1641222c95fa37d23830d8f4e4d5` / BE `1a10ca038ffb4a215d4b1b44242ff4ef68275607`。実走は新しい独立recovery-build5 production。以前のoutputとの全byte同一は主張しない。
- 独立探索はfresh3の住民3/4/5のみ。各1 context作成/閉鎖、予期しない業務書込0、pageErrors0、終了時runtime不変。3人とも探索実施完了と全目的達成を区別し、全目的達成・B0 runner実行・coverage合格とは扱わない。
- 公開証拠は原観測の選択項目とsession集計、原本path/SHA256のみ。テスト組織slug/委員会名はplaceholder化。DOM/HAR/trace、認証情報、HTTP本文、画像実体なし。視覚検査合格の主張なし。

累積overlayの旧22 persona / 22 journey / 49 insight、および既存runEnvironments・その他フィールドは全フィールドをdeep保持し、3/3/5だけ追記。登録後25/25/54。原personaId 1/2/3はoriginalPersonaIdへ保持し、一意累積IDだけ登録時付与。原runId/journeyIdを保持し、固定P01-P20へ変換しない。各insightは登録元、persona、journey、run、head、evidenceと`observed-unverified`を保持する。

住民3の入口未発見は導線欠落の証明ではない（住民5は到達）。初頁outboxの「一覧へ戻る」無効化はprevPage/currentPage===0の正当状態で、文言への戸惑いを保持。戻った時の折りたたみと名前完全一致locatorのTimeoutも未実証観測・自動操作制約を区別する。

NAME_ONLY詳細は別のroot制御再現で詳細200＋members403、再試行表示・見出しなしを確認。独立探索の成果へ転用せず、`root-name-only-proof.json`へ分離した。詳細のPromise.allでmembers403が全体loadFailedを起こす既存問題をCMP-261028-0513として台帳末尾へ7列・未着手登録。F04.10の可視詳細とmembers限定認可を維持し、認可を拡張しない。組織非所属API200のCMP-260930-0234、0660 shape、1311 list可視集合とは別原因。新CMPのred/green・修正後実機・独立探索は未実施。

検算結果（登録worktree）:

- 原本・ignored copy・生成b0RunOverlayの構造一致: PASS。
- 旧累積全フィールドdeep保持: PASS。
- validator既存関数`validateRunOverlay`と`--self-test-overlay`ブロックのみを無変更抽出して実行: PASS。実overlay、空insight、合成baseline、異なるhead/run受理、不正10mutation拒否。
- 台帳全602 CMP行: ID重複なし・7列: PASS。
- 3住民session集計と原本参照: PASS。
- 同じ環境で2回生成: generatedAtのみ除外して全生成データ一致: PASS。
- 通常validator / `--self-test-overlay`全体: FAIL。既存GitHubスナップショット参照集合不一致（validator 46行）でoverlay検査より前に停止。新CMP行由来のGitHub参照追加は0。初期登録状態a4cd6b91では基準54923eddと参照集合が不変だった。通常main追従後5d76では1818完了行の参照が3623→4182へ更新されており、基準との集合不変は主張しない。この失敗を合格扱いにせず、snapshotを改変・擬似同期しない。

再生成はB0_RUNNER記載の正本を `docs/prototypes/.b0-local/run-october-fresh3-20261028.json` にcopyし、同dirでこれが最新mtimeであることを確認して `B0_INCLUDE_LOCAL_OVERLAY=true python docs/prototypes/generate-beta-inventory-board-data.py`。sparse worktreeでは生成器が参照する `frontend/tests/e2e` も展開する。generatedAtは生成時刻、sources.taskListCommitは実行時HEADにおける台帳の最終commitであり、commit後の再実行ではこの来歴フィールドも変化しうる。

初期基準54923eddでの同command実行も同じ既存failureを再現した。[基準/登録後validator証拠](validator-baseline-proof.json)は初期基準/初期登録状態a4cd6b91の通常・自己検証の計4実行exit1と同メッセージを保存する（実行時HEAD fd6cc48fcfと記録追加a4cd6b91のvalidator入力5fileは同一Git blob）。元4run結果は変更しない。validatorとsnapshot/statusは基準blobとの内容一致（Windows checkoutのCRLF/LFを正規化）も確認した。

通常main追従・board再生成後HEAD5d76の通常validator/自己検証全体も、実測2実行とも同じ46行FAIL。別の`finalHeadValidation`へ測定HEAD・入力SHA256/Git blob・結果を追記した。この記録補足commitではvalidator入力5fileとboard/sourcefieldsを変更しない。既存snapshot失敗と、新CMP由来参照追加0、main由来1818参照更新を分離する。
