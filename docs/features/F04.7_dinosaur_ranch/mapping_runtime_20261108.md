# 正式診断64・出生81の対応と結果保存

CMP-261108-2026 / 2026-11-08。採択済みの対応・質問原稿を既存の正式registryへ接続する。実機/探索/CIの完了状態は戦役の証拠記録を参照し、この仕様書の存在を合格証拠としない。本番の運営行・公開設定は変更しない。

## 正本・版

- 診断64は `diagnosis64_species_mapping_v1.json` の明示64行を維持する。先頭4bitが種、末尾2bitがV1..V4で、全16種4外見と一対一。
- 出生81は `birth81_species_mapping_v1.json` の採択済み明示81行を使う。入力1..9×1..9が一意、全64外見を覆い、残り17枠は採択された重複。mod64/hashで代用しない。
- 正式種keyは承認済みsourceSpeciesKey、外見keyはV1..V4。catalogVersion=20261108、mappingVersion=`ranch-mapping-20261108-v1`。
- 質問版=`diagnosis-20261108-v1`、採点=`signed-centered-v1`。元draft-20261010-v2の24問と6同点質問の216翻訳値・ID・極性・順序は変更しない。旧draftは来歴として残す。正式登録がある新規開始はapproved版を優先し、保存済みsessionは当時の定義を保つ。
- 出生計算は既存本人プロフィール確認と `nfkc-hepburn-v1` / `digit-reduce-v1` のまま。ニックネームや推測読みを使わない。

## 不変結果と割当

`DiagnosisResultSummary` の既存fieldを保ち、nullable `speciesKey`,`variantKey`,`catalogVersion`（decimal string）,`mappingDescriptionSnapshot`（ja/en/zh/ko/es/de）を追加する。既存 `mappingVersion` とともに当時のidentityを結果のsummary_snapshot JSONへ固定する。新DDL・過去データ更新はない。対応説明には当時の種名・外見名と対応の意味を保存する。既存descriptionSnapshot/axisSelectionsは別の説明として維持する。

結果writerが呼ぶ `RanchResultMappingService` は起動時の不変mapだけを読み、他domain RepositoryやTXを呼ばない。診断writerのREQUIRES_NEWとauth guardは維持する。64/81解決は有限map参照で、ループ内DB取得を追加しない。計測していない性能は保証しない。

master未登録なら従来どおりnull mapping結果の作成・閲覧を維持する。master登録時は質問/採点/計算/結果schema版の互換性を要求し、互換な新規結果にidentityと六言語説明を必ず保存する。非互換版は新規保存を拒否する。公開boolは結果の計算/保存/本人閲覧に使わない。

旧JSONの追加field欠損はnullで読める。本人結果の一覧/詳細は保存JSONから返し、現masterで再計算・書戻しをしない。master解除/切替やプロフィール変更後も過去のidentity・説明を保つ。既存個体・種外見・名前・XP・points・愛着は更新しない。

新規正式割当は本人結果ID、対応版、species/variant/catalog、六言語対応説明と現登録masterの一致が必要。旧mappingVersion=null、identity欠損、別版/別種外見を現版へ読み替えない。出生は従来の本人確認revision・派生数・計算版も要求する。別本人結果/確認ID、古いversion、再送・二重送信の認可/冪等性/独立TX境界は既存契約を維持する。

## 登録と独立した公開可用性

server-owned `mannschaft.ranch.approved-visuals-available` は既定false。検証済みmaster/packと要求質問版がすべて登録された状態 AND 明示公開bool=trueだけで `GET /api/v1/me/ranch` の `approvedVisualsAvailable=true` を返す。care rule・報酬policy・残高・休止・widget表示・同梱512件だけから公開許可を作らない。

| 明示公開bool | master/pack/質問の登録整合 | approvedVisualsAvailable |
|---|---|---|
| false | 任意 | false |
| true | 未登録/質問版不承認 | false |
| true | 全整合 | true |

不正hash・構造・coverageはregistry起動時に拒否する。FEは成功したfresh GET stateだけから可用性を受け、未取得/取得失敗/field欠損/別catalogはfalse。ENROLL等の凍結ACKにあるboolは過去のsnapshotであり、公開許可へ使わない。既存FE actはACKをstateへ代入せずGETを再取得する境界を維持する。公開gate失効でも保存結果の説明閲覧は遮断しない。

## 正式v2素材

pack=`ranch-approved-20261108-v1`、manifest SHA256=`62d2d045e480bab986619410168bfef145989937a2b994c8cc4e3eec9346d26b`。512tupleは16種×4外見×4段階×2画風。孵化後384entry内にidle/walk/feed/happy各4frameを持ち、6144frameを別masterへ増やさない。EGG128は既存共通SVGの見た目を保つ技術PNG登録で、既存inlineSVG表示を維持する。

v1静的契約は維持する。v2 entryは既存12fieldsにsourceSpeciesKey/habitat/logicalCanvas/careSpriteRect/rendererProfile/clipsを追加する。孵化後cellはPIXEL96/PAINT384、logicalCanvas112、careSpriteRect=(8,8,96,96)、profile=APPROVED_APPEARANCE_V4。paint描画面448は原本cell384と別の表示surfaceで、画像をリサイズし直さない。EGGはprofile=COMMON_EGG_SVG_V1、logical96、rect=(0,0,96,96)、clips=null、frameCount1。

clipsは4action完全一致。各clipのexact15fieldsはfilePath/sourceSha256/frameWidth/frameHeight/frameCount/durationMs/phaseStarts/phaseMs/loop/returnTo/floorByFrame/sourceFloor384/sourceFloorByFrame384/tailMarks/shadowByFrame。idle anchorとmasterのhashを一致させ、manifest SHAが全action bytes/geometry/timingを束縛する。phaseは4個、0開始・厳密増加・1未満、duration1..60000ms、phaseMsとの誤差1e-6以内。idle/walkはloop、feed/happyはidleへ戻る。floorのnullを0に変えず、shadow/tailの位置と海happyのv4描画profileを保持する。

参照path越境・同path異hash・不足action・未知profile・PNG寸法不一致は拒否。原本1922PNGの実測合計280355204bytes、manifest込み282754797bytes。v1の256MiB制限を保ち、v2だけ320MiBの有限累積PNG byte上限へ拡張する。perfile20MiB・展開128MiB上限は維持する。BEはhash/PNG寸法とclip契約を検査し、FE CLIは同じ契約とファイル集合を検証する。原本の再圧縮・縮小・描き直しは行わない。

## 専用検証設定

以下を専用検証環境だけへ指定する。application.yml/application-prod.ymlや本番運営行には追記しない。

```properties
mannschaft.ranch.production-master.resource=ranch/approved/ranch-mapping-20261108-v1.json
mannschaft.ranch.production-master.version=ranch-mapping-20261108-v1
mannschaft.ranch.production-master.sha256=3037f7585d5bdb395bae86ac26276ac70e61b5c680629bacf02a2978f50eab1a
mannschaft.ranch.production-master.asset-pack-version=ranch-approved-20261108-v1
mannschaft.ranch.production-master.asset-pack-sha256=62d2d045e480bab986619410168bfef145989937a2b994c8cc4e3eec9346d26b
mannschaft.diagnosis.approved-catalog.resource=diagnosis/approved/diagnosis-20261108-v1.json
mannschaft.diagnosis.approved-catalog.version=diagnosis-20261108-v1
mannschaft.diagnosis.approved-catalog.sha256=10e2d71bfd2d84791c3fdb3b82c42cce9dc55ed98feb55a095e6016a5198f9d0
mannschaft.ranch.approved-visuals-available=true
```

新規割当/孵化等には別途既存の参加・care rule・本人確認条件を満たす。素材の公開boolだけで報酬や育成を有効にしない。