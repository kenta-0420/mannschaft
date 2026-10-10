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


## 2026-10-03 正式 v3 試練結果

旧記録の「未実行」はその記録時点の状態であり、以下が現在の正式実測。
Node v22.23.2 / npm 10.9.8、共有 Windows turnstile、immutable bridge-v3/runner-v3 を使用。
各ジョブの source/head/UTC/exit/selector と取得時 WINPID/CREATED/TOKEN は Windows verification に保存し、全 tool session の終端を回収した。

|検証|source HEAD|終了 UTC|exit|実結果|
|---|---|---|---|---|
|frontend-install 2885|e1262e71|18:34:56.597Z|0|1413 packages、Nuxt prepare 完了、lock SHA 不変|
|命名 red 49845|e1262e71|18:40:39.941Z|1|799 件中 789 pass / 10 fail、compile failure なし|
|担当 4 spec 67214|468b00a2|18:56:05.851Z|0|4 files / 808 pass / 0 fail / 0 skip|
|差分 lint 初回 52481|468b00a2|18:57:17.503Z|1|const 1 error、img 1 warning|
|差分 lint 再検査 1112|5e93da57|18:59:41.080Z|0|担当 TS/Vue 32 files、指摘 0|
|全体型検査 45442|5e93da57|19:07:37.709Z|1|4096 MiB heap 上限で V8 OOM、型検査未通過|

red の native spy 1 件には constructor mock の警告もあり、その部分は振る舞い red と分別した。
BOM trim、未 paired surrogate、不可視 VS/CGJ、可視文字中 LRM/ALM、ZWNJ の判定差、および Intl 欠如時の fallback は具体的な旧実装差。
spy を function mock に修正し、命名実装は常に unicode-segmenter 0.17.3 /grapheme を使用する。
公式 raw 766 境界行・SHA・Gurmukhi regression と製品 trim/NFC/10・11 EGC/512 bytes/160 codepoints を別試験で確認した。
808 件の内訳は命名 799、command retry 3、命名 UI 3、Scene visibility/motion 3。
最終 lint 修正は Scene spec の const と HTML void img の書式のみで、振る舞い変更なし。

型検査前は C: 47441133568 bytes、Windows physical free 9865240576 bytes、WSL available 17494 MiB。
OOM 後も C: 44701429760 bytes を確認。8 GiB 増量・OOM 再実行・型 green の主張は行っていない。
CI 同等の型検査証拠または全体メモリ原因の解決は root へ引き継ぐ。型検査を恒久除外しない。
package-lock SHA-256: 307b6e39a940851a9623b230959a1a6f7429f80183258a78d1a4a48948ad67a6。
固定依存追加時の npm lock 生成が削除した既存 24 nested optional entry は、元の同版 metadata を保全して戻した。
新 unicode-segmenter entry/SRI は正式生成のまま。既存依存版変更 0、最終 lock は 7 行追加のみ。

統合残件と所有:

- root/統合隊: nuxt の六言語 ranch namespace 登録、SettingsRanchSection の root 組込、OpenAPI/generated/API error 連携。
- root/FE 統合: WidgetRanch 登録と Grid/Accordion/Carousel の active 伝播を実機で検証。担当差分と単体停止試験は保存済み。
- root/FE 統合: pending ranch mutation の画面遷移・再 mount を跨ぐ lifetime と auth logout reset。private birth body を共有・永続 store へ移していない。
- 素材隊/root: approved SceneManifest、反応・音声、96 logical PIXEL/PAINT_2D 同個体素材、64 mapping/assets の承認。
- root/診断隊: 六言語 questionnaire/description master の公開承認。UI 自体の五言語翻訳は完了、master の日本語 draft placeholder は別 gate。
- root: 実 API の本人認可・複数タブ/409・冪等再送・withdraw fail closed・プロフィール確認・実機/E2E。

担当 own source は命名の同版境界、assignment null、owner aggregate version、HatchResponse union、プロフィール ACK を維持。
生体 feed の PAUSED/care feature 停止は button/handler で防ぎ、未確定 snapshot 再取得は妨げない。
TOUCH は独立し、PAUSED/care 停止でも許される 0 affinity 反応を一律禁止しない。
unit fixture/fallback は実 API や本番素材の完成証拠ではない。公開 gate は閉じたまま。
