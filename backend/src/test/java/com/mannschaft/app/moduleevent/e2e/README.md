# M21 専用 host の有限検証

`node scripts/test-m21-host-cleanup.mjs` をリポジトリルートで実行する。既存 JDK 21 だけを使用し、javac/java はそれぞれ最大 heap 128MiB・30秒上限。新規自己 Temp と、試練自身が保持する短命 Java 子（heap 32MiB・10秒上限）だけを使用する。終了時に exact Temp を所有確認して撤去する。通常 CI では薄い `ModuleEventRealE2eCleanupAcceptanceTest` が同じ試練を呼ぶ。

この試練は helper / gate / formal child / worker / 生成ID cleanup / context close / 結果保存の各故障、primary の同一性と副失敗の順序、中断復元、固定3actorへの絞込み、自己 Process の世代不明・不一致でも回収を続ける契約を確認する。停止前提のどれかが未確認なら生成ID削除を行わず、`data:SKIPPED:回収前提が未確認` を結果へ記録し nonGreen とする。context close と結果保存は引き続き試行する。保存不能は元の失敗に抑制例外として追加し、失敗終了する。

実 host の専用 fixture JAR へ入れる project source は次の3件だけ。`javac -d <新規classes> -cp <固定productionCP> <この3source>` のように入力を明示し、その出力だけを専用 JAR にする。試練クラス・JUnit adapter・他 project testclasses は含めない。従来の2source JARを新HEADの検証へ転用しない。

- `ModuleEventRealE2eApplication.java`
- `ModuleEventRealE2eFixtures.java`
- `ModuleEventRealE2eCleanup.java`

起動 CP は従来どおり `<固定productionCP><pathSeparator><専用fixtureJAR>` の完全一致を要求する。正式 spec への11actor配布は維持し、探索 helper の private 環境だけを `system / admin-de / member` に絞る。資格情報を argv・stdout・永続ファイルへ追加しない。

host の stdout/stderr は既存の file / DISCARD redirect を維持するため、親が採用する読取り pipe の EOF 待機はない。保持 Process の有限 exit 確認と親側 stream close を行う。直接 process の exit は子孫が継承した出力 handle の close や孤児の不在を証明しない。事前捕捉できた子孫は全件停止を試し、host 異常終了時の孤児も含む全 owned 資源の回収証明は外側監督が担う。

この有限試練の green は Spring/実DB接続、実 host の依存 compile、実機、住民探索の合格を意味しない。実 host の compile と正式実行は root の別判定で行う。
