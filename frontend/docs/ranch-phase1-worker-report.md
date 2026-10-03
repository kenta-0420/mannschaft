# ⚔️足軽FE 復旧checkpoint

Windows保全generatorから新隔離worktreeへ復元。base38全文と既存6ファイルのanchorを照合して適用。READMEはbaseに存在しない新規ファイル。
旧/tmp最終source/ログは現存未確認のため、byte完全一致を保証しない。旧Prettier整形差と一部fixの空白anchorをassert付き等価補完した。

命名512byte/160codepoint、owner aggregate version、HatchResponse union、出生情報ACK/opaque confirmationを維持。一般profile DTO変更なし。
diagnosis/private birth profile/birth resultのpending操作は元key・body・versionを保持し再送。診断中断前の保存、PIXEL卵の96座標、未参加assignment=nullガードを補修。
方式UIは現在のstate.assignment.availableMethodsを参照し、保存結果のmappingVersionだけでは永久準備中としない。個々の結果の適合はserver検証と409処理が正本。

UI ranch.jsonは日本語・英語・中国語・韓国語・スペイン語・ドイツ語を整備。全119 leaf keyと補間変数は一致、非日本語の日本語placeholderは0。診断質問・説明masterの承認は独立した公開gate。

復旧版のfresh UT/lint/typecheckはまだ開始していない。正式bridgeのnpm ciは共有枠待機中。旧17greenは当時の報告であって復旧版の証拠ではない。
命名のIntl.SegmenterはNode22でUnicode17を実測したが、Java21との同版保証を満たさない。固定FE/Javaライブラリと共有境界fixtureの比較案をWindows保全し、依存変更・pair parity試験は未実施。

残件: npm ci後の差分lint/owned 4 selector UT再実行/4GB上限の型確認、命名server exact fixtureと固定segmentation契約、64素材manifest gate、root実機とアリシゼーション。
nuxt.configの六言語ranch.json登録、SettingsRanchSectionのroot組込、OpenAPI生成は統合隊所有。素材は文字fallback、公開可能ではない。


## Unicode 17 固定契約の再検証（未完了）

FE は `unicode-segmenter=0.17.3` の `/grapheme` を常に使う方針へ変更する。
native `Intl.Segmenter` の Unicode 版一致や、コードポイント数による代替を green と扱わない。
正規化・入力分類はクライアント補助であり、ICU4J 78.3 によるサーバー検証が最終正本となる。

共有公式 fixture は CORE の独立 commit `1fe513f556c32bdd47f051baca9f4a2fb06c765b` から取り込んだ。
raw SHA-256 は `e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec`、126570 bytes。
公式 766 行を NFC/trim せず境界配列で照合する試験と、製品名の正規化・拒否条件を別に準備した。
製品条件は Unicode White_Space trim、NFC、1〜10 EGC、UTF-8 512 bytes 以下、160 codepoints 以下。
Cc/Bidi_Control/Zl/Zp/未 paired surrogate は trim 前に拒否し、不可視のみを拒否する。
可視 emoji 内の ZWJ/VS/tag flag は保持する。上限超過は切り詰めず拒否する。

正式実行の状態:

- 旧 frontend-install 12146 はディスク guard exit 4、npm 未実行。
- 旧 frontend-install 58813 は prepare 出力後に bridge exit 127。成功した ci と扱わない。
- frontend-lock 64120 は 2026-10-03T16:31:06.578Z に exit 0 を回収した。
  生成結果が既存 nested native optional 依存を削除したため、その lock は採用しない。
- 元 lock を戻し、隔離 worktree 内で generated node_modules を可逆退避して、
  SHA 固定 v3 runner の frontend-lock 47400 を 1 件投入した。追記時点では共有枠待機中。
- fresh UT、差分 ESLint、4 GiB 上限の型検査はまだ実行していない。
  過去 17 UT の報告は今回の実体・ログによる green 証拠に使わない。

private birth-profile の raw 値を共有 state や永続ブラウザ保存へ移す変更は行っていない。
一般 profile/generated DTO への DOB・内部 revision 追加も行っていない。
生体 feed の休止/featureStatus ガード候補は TOUCH と分け、配送・報酬停止を care 停止に混ぜない。
未確定 ranch command の画面遷移/再 mount を跨ぐ lifetime と auth logout reset は統合確認を要する。
素材 manifest/反応/音声接続、64 mapping/assets 承認、診断 master の公開承認、実機 E2E は未完了。
fallback 表示や unit fixture を本番素材完成・実 API・公開可能の証拠と扱わない。
