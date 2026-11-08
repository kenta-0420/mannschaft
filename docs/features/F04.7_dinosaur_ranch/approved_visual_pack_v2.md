# 正式素材pack v2

catalog `20261108`、pack `ranch-approved-20261108-v1` は、承認済み16種×4外見×4段階×2画風の512tupleを保持する。孵化後384tupleの各々に待機・歩行・食事・喜びの4clipを持たせ、6144frameを承認原本のSHA256とRGBAで照合する。画像の描き直し・色変換・リサイズは行わない。speciesKeyは承認sourceSpeciesKey、variantKeyはV1〜V4。

## geometryと動作

PIXELの元cellは96、PAINT_2Dは384。各action atlasは横4frame。素材をlogical112×112のrect(8,8,96,96)へ描き、表示canvasは112/448。元cellと描画面を分ける。`APPROVED_APPEARANCE_V4` profileで従来v4の海happy回転・影・接地・tail表示を保持し、正式catalog番号で動作を分岐させない。

正式clipが選ばれたSceneではlegacy atlasへasset=null/enabled=falseを渡し、loaderとcanvasの描画主体を1つにする。動き抑制中のtouchでcanvasが再生成されても、legacyの遅延paintが影やpaddingを上書きしない。clipを持たないv1/DEV2は従来のlegacy描画を使う。

EGG128tupleは`COMMON_EGG_SVG_V1` profile、clips=null、frameCount=1で画風別共通PNG2件を参照する。PNGは既存SVGのpath/fill/strokeから技術生成する。実画面の卵は従来SVG・crack反応を保持する。

manifestルートは`schemaVersion,catalogVersion,packVersion,approvalStatus,environment,entries`。v2 entryは従来の12fieldsに`sourceSpeciesKey,habitat,logicalCanvas,careSpriteRect,rendererProfile,clips`を追加する。clipsは`idle,walk,feed,happy`完全一致。各clipは次の15fieldsを持つ。

```text
filePath,sourceSha256,frameWidth,frameHeight,frameCount,durationMs,
phaseStarts,phaseMs,loop,returnTo,floorByFrame,sourceFloor384,
sourceFloorByFrame384,tailMarks,shadowByFrame
```

idleのpath/hash/geometryはentryの従来anchorと一致し、master.assetsのsha256/staticFallbackSha256と照合する。manifest全体SHAで4action全体を束縛する。v1のinteger literalとPIXEL96検査は維持。v2の位相・接地・影・tailは原本の有限小数を丸めず保持し、構造値はフィールド別整数検査を行う。4phaseは0始まり・厳密増加・1未満、正のphaseMsとdurationMsとの整合誤差は1e-6以内。idle/walkはloop、feed/happyはidleへ復帰する。未知field、不足動作、重複tuple、path脱出、PNG/hash/寸法不一致、未参照file、coverage不足を拒否する。

## 登録と配布検査

`generate-ranch-production-appearances.mjs`に原本root・採択証拠・masterassets出力・RGBA証拠出力を明示する。既存承認atlas/fallback bytesを正式FE namespaceへ再配置し、PNG1922件（1536atlas+384fallback+2egg）とmanifestを生成する。原本と公開previewは編集しない。

`verify-ranch-production-assets.mjs`はpack/masterの明示SHAと実bytesで512tupleを検証し、有限TS registryと配布検査用のregistration/masterfixtureを同一入力から生成する。`--frontend-only`はFEだけを配置し、BE resourceは統合担当が同一artifactから配置する。既存packに同bytesがある場合は巨大stageへ重複copyしない。未検証objectをinstallerやartifact例外へ通さない。

`verify-ranch-production-artifact.mjs`は登録metadataとmaster/manifestの実hashを再検証する。元DEVと同じPNGbytesでも、検証済み正式namespaceの厳密path+hashでのみ配布を許可する。未登録・改竄・不足正式fileとDEV namespace/import/pathの混入を拒否する。

別名リンクはrealpathによる重複排除より前に論理pathを検査する。正式file/正式directory/その祖先への未登録aliasへ正式namespaceの例外を引き継がせず、走査順による拒否漏れを防ぐ。内部の通常node_modulesリンクは従来どおり許容し、外部リンクと循環は拒否する。

1922PNGの実測合計は280355204bytes、manifest込み282754797bytesで、v1の256MiB上限を超える。原本bytesを再圧縮せず保持するため、v1の256MiBは維持し、512tuple/4clip契約のv2だけ累積PNG320MiBを上限とする。同pathはhash一致を検査して一度だけ計上する。perfile20MiB・PNG展開128MiB・次元/件数/path検査は維持し、境界直前/ちょうど/超過は巨大ファイルを生成しないbytecounter試験で確認する。

## 可用性と保存結果

FEは同梱manifestの512tuple/16種4variant/全段階2画風/APPROVED/PRODUCTION/単一catalogを検査する。BE state取得成功かつ`approvedVisualsAvailable === true`の場合だけ正式描画と新規割当を有効にする。取得中/失敗/field欠落/owner切替では無効。care featureStatusや報酬・残高から公開可否を推定しない。BEの独立property`mannschaft.ranch.approved-visuals-available`は既定false。本番全体設定は変更せず、専用検証環境だけ明示的に有効化する。

本人の結果閲覧・新結果作成は公開boolの外に保つ。結果詳細は保存時の`mappingDescriptionSnapshot`をlocaleで表示する。mappingVersion/speciesKey/variantKey/catalogVersion/対応説明を持たない旧結果は閲覧できるが新規割当に使えず、新結果作成へ案内する。保存identityと既存個体を現masterへ自動移行しない。

## 検証範囲

CLI試験はv1回帰・v2小数timing/512tuple/4action/卵・path/hash/PNG/geometry拒否と配布scannerの正式例外/負例を対象とする。FE試験は可用性真理値表・正式identity/版・従来clip playback/atlas/fallback・v4海happy数式・保存説明/旧結果案内を対象とする。production buildと専用実BE/FEでの4action・fallback・動き抑制の実画面確認は別の必須ゲートであり、単体試験で代替しない。
