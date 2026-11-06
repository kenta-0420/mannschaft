# CMP-261024-2100 WSL孤立コードの完了証拠

実装PR [#4098](https://github.com/kenta-0420/mannschaft/pull/4098)、実装head `f647865277a011386e1c0e899b966f32ed4e7fa5`。squash mergeは `3c06286cfb8b90ea8c8124b804e2973477aae625`。対象はtooling・運用文書・台帳で、製品UI/APIの変更はない。

## 正本照合と採否

最新mainと正本ref/既存PRを基準に、home旧Git11コピー・Ranch保留2コピー・pending overlay19ファイルを照合した。非祖先を未反映と即断せず、squash採用・改行正規化・後続改善・ignoredなlogs内specまで確認した。archive77本の293 source root・nested tar27本は、手書き273hashの8群を最終的に意味判定した。必要なonly-copy/only-archiveコードは0件、新規回収branch/PRも0件。

必要ソースはmainまたは既存Draft #3616・#3629・#3724・#3726・#4009に存在する。5正本の確認head・旧未完理由・次の具体手順を元CMPへ対応付け、元の状態とACを維持した。CMP-261003-1020は元ID・元AC・実装中の行を復元した。旧PR #3609は台帳の原形保全方針を維持した。日付欄の旧固定環境specは退役し、CMP-260910-1557は未着手のまま環境に依存しない次の検証を証拠欄へ引き継いだ。

## 実WSLの撤去と保護

| 対象 | 撤去・不存在確認 | 撤去した割当容量 |
|---|---:|---:|
| Ranch | 3コピー | 1,327,251,456 bytes |
| home | 11コピー | 3,919,519,744 bytes |
| 旧archive | 77ファイル | 11,447,758,848 bytes |
| 追加expanded source | 20ディレクトリ | 2,449,125,376 bytes |
| 旧backend build | 22ディレクトリ | 1,156,612,096 bytes |
| 合計 | 133 path | 20,300,267,520 bytes（約18.91 GiB） |

各担当が直前のidentity/fingerprint・所有/共有Git・全proc/mount・停止中を含むDocker bindを確認してからexact対象だけを撤去した。親担当が133path全不存在・残存0件を独立再確認し、同じ既存7コンテナの稼働継続、正式L0M/Q9・本体・既存実行環境・共有C Gitの実在と保護も確認した。

私用BC10診断2ファイル（203,101 bytes）だけをprivate領域へ保存し、WSL/Windows双方からSHAを照合した。本文・秘密設定・cookieは公開せず、追加source保全0件・不要config/cookieの新規保全0件。旧XMLはhash/size/suite件数/失敗名・種別/source HEADのscalarへ縮約し、raw全保全0件・例外0件。全量archiveは追加していない。

fstrimは終了0。最終WSL df実使用は83,074,048,000 bytes（約77.37 GiB）。WSL停止・オフライン物理圧縮は未実施で、Windows VHDXの155,813,150,720 bytesは不変。C空き容量は外部活動の影響があるため回収効果に算入しない。C側一時索引2ファイルは当初共有資産として自動承認reviewで拒否されたが、当session自作・借用完了・正本Gitから全bytes同一再生成可能・改変なし・既存資産0という追加証拠を提示した同一削除操作の再審査で承認され、14,842,057 bytesを撤去した。親runtime・最終reportsは保護した。このC側撤去量はWSLの約18.91 GiBとは別集計であり、全tmp削除済みとは扱わない。

## 検分・CIと検証限界

親担当が実装PRの全3文書・台帳diff・採否根拠を全文検分した。初回の検証候補数の誤記をblogui1＋monsho1＋l13 3＋orggroup5＝10件へ修正後、finding0で合格。実機stageは実WSL撤去・親最終再確認・fstrimの実測を根拠にpassedとした。製品UI・利用者導線の変更がないtoolingのためexploratoryは対象外。

実装headの[Task-List CI](https://github.com/kenta-0420/mannschaft/actions/runs/40097747185)と[Backend docs CI](https://github.com/kenta-0420/mannschaft/actions/runs/40097747130)はsuccess。親担当がマージ時の17check完了（success11・skip6）、commit status0、期待head一致を確認してマージした。本完了記録のJSON/台帳構造は自CMPだけを選択検証し、GitHub上の実装PR/CIメタデータは完了記録PRのCIで照合する。

今回、製品の重いbuild/testは追加実行していない。旧局所149green・選択27green・旧途中失敗proofを最新source全体のgreenや製品受入れ成功に読み替えない。元featureの未完了・再開時の最新main差分確認と不足検証は各正本に残している。

採否8群・既存PR/CMP・監査JSON名・運用追記の詳細は[調査記録](../development/wsl-orphan-code-resolution-20261024.md)と[既存コピーの孤立コード照合手順](../development/worktree_operations.md#既存コピーの孤立コード照合)を参照する。私用証跡・runtime全量は公開しない。
