# M21 専用 host の有限検証

`node scripts/test-m21-host-cleanup.mjs` をリポジトリルートで実行する。既存 JDK 21 だけを使用し、javac/java はそれぞれ最大 heap 128MiB・30秒上限。新規自己 Temp と、試練自身が保持する短命 Java 子（heap 32MiB・10秒上限）だけを使用する。終了時に exact Temp を所有確認して撤去する。通常 CI では薄い `ModuleEventRealE2eCleanupAcceptanceTest` が同じ試練を呼ぶ。

この試練は helper / gate / formal child / worker / 生成ID cleanup / context close / 結果保存の各故障、primary の同一性と副失敗の順序、中断復元、固定3actorへの絞込み、自己 Process の世代不明・不一致でも回収を続ける契約を確認する。停止前提のどれかが未確認なら生成ID削除を行わず、`data:SKIPPED:回収前提が未確認` を結果へ記録し nonGreen とする。context close と結果保存は引き続き試行する。保存不能は元の失敗に抑制例外として追加し、失敗終了する。

実 host の専用 fixture JAR へ入れる project source は次の3件だけ。`javac -d <新規classes> -cp <固定productionCP> <この3source>` のように入力を明示し、その出力だけを専用 JAR にする。試練クラス・JUnit adapter・他 project testclasses は含めない。従来の2source JARを新HEADの検証へ転用しない。

- `ModuleEventRealE2eApplication.java`
- `ModuleEventRealE2eFixtures.java`
- `ModuleEventRealE2eCleanup.java`

起動 CP は従来どおり `<固定productionCP><pathSeparator><専用fixtureJAR>` の完全一致を要求する。正式 spec への11actor配布は維持し、探索 helper の private 環境だけを `system / admin-de / member` に絞る。資格情報を argv・stdout・永続ファイルへ追加しない。

host の stdout/stderr は既存の file / DISCARD redirect を維持するため、親が採用する読取り pipe の EOF 待機はない。保持 Process の有限 exit 確認と親側 stream close を行う。直接 process の exit は子孫が継承した出力 handle の close や孤児の不在を証明しない。事前捕捉し世代確認できた子孫だけを停止対象とし、host 異常終了時の孤児も含む全 owned 資源の回収証明は外側監督が担う。

終了済み・開始世代不明/不一致の親では `descendants()` を呼ばない。live な自己親の開始世代を列挙前後で確認した snapshot だけを使い、子 handle の正の開始世代を保持する。通常停止・強制停止の直前にも同じ開始世代を再照合し、不明/不一致になった子には停止を送らず nonGreen として外側監督へ残す。未知 PID から handle を取得し直さない。根拠は固定 JDK21.0.10+7 の [ProcessHandleImpl.descendants / Info.info](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.10%2B7/src/java.base/share/classes/java/lang/ProcessHandleImpl.java) と [Windows destroy0](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.10%2B7/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c)。

`host-result.json` の `cleanupStages` は保存処理開始時点の snapshot なので、保存自身の stage は `result:ATTEMPTED` である。保存前に `PASSED` と偽装しない。外側監督が当該 run の結果ファイルの存在・内容と、保持する host Process の実 exit/exit code を照合して保存成功を証明する。`ATTEMPTED` だけ、またはファイルだけでは成功としない。

この有限試練の green は Spring/実DB接続、実 host の依存 compile、実機、住民探索の合格を意味しない。実 host の compile と正式実行は root の別判定で行う。

## 正式 spec の既存 UI 権限境界

`frontend/tests/e2e/real/l1m-module-events.real.spec.ts` は、管理 API 契約と既存 batch / bell の UI を別の step で確認する。個別 publication 再開の管理画面は追加しない。HTTP step の API 呼び出しを UI 操作の証拠として扱わない。

| 視点 | 開始 URL / 入口・操作 | 最終画面・証跡 |
|---|---|---|
| `system` | `/` の表示中 `global-sidebar` → `/system-admin` → バッチ管理リンク → 詳細・同期実行 | バッチ表・詳細 dialog・実行結果トースト。`batch-ui.png` |
| `member / admin-ja / org-admin / outsider` | `/` の同じサイドバーを表示確認し管理入口の不在を確認。その後 `/system-admin` と `/system-admin/batches` を各々直打ち | 既存 middleware 契約の `/dashboard` 遷移、「アクセスできません」「管理者権限が必要です」、バッチ表・詳細・実行要素なし。`batch-entry-hidden-{code}.png` と `batch-denied-{code}-{index,batches}.png` |
| 6言語管理者 | `/` の bell → 通知一覧 | 永続配送文面・reload 後の表示。`bell-{locale}.png` |
| 他テナント `outsider` | `/` の bell → 通知一覧（ブラウザが発行した一覧 GET の 200 を観測） | 「通知はありません」、通知行0・検体の3段階の文面なし。`bell-outsider.png` |

11 fixture actor と actor ごとの単一 BrowserContext を維持し、既存 `pageFor` からセッションを再用する。追加ログイン・UI 対象操作の API 代替・モック・skip・retry・timeout 増加はない。画像は生成資格情報等を含み得る自己 run の private 証跡として扱う。

この UI 境界補強は静的な spec 追加であり、実機 red / green は未実行。初回直接遷移時の拒否トーストを含め、正式 M21 の同一 HEAD 実行で判定する。
