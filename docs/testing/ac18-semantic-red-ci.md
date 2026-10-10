# AC-18 別thread SQL測定の限定hosted RED

状態: 製造済み・実行待ち（pending）。semantic REDはまだ取得していない。自然CI run38049654507 のAUTHZ_BASELINE失敗原因は未立証。

## 固定sourceと実行境界

専用branch `feature/61741-ac18-semantic-red-ci` のpushだけで `.github/workflows/backend-ac18-semantic-red.yml` が起動する。PRは開かず、既存フルCI・main条件は変更しない。独立concurrencyで進行中runをcancelしない。

runnerのcheckout.refはTEST_ONLY commit `d7fdfa88f82d90faa68ddd0e916e0e7bf6db4991` に固定する（tree `3ff005f0df808c28698e0027e853e014d3a98b03`）。これはHEAD39にfrozen試練3filesだけを追加したsourceであり、workflowと本運用記録を含む次commitとは別である。event SHAとcheckout SHAを証跡に併記する。製品sourceはHEAD39のまま、実装patchは含めない。

単独selectorは `com.mannschaft.app.shift.ShiftScheduleSlotFacadeRaceAndQueryIT.別スレッドSQLを認可クエリ数に含めない`。実MySQLの別thread SELECT 1を認可計測窓へ挿入し、既存HTTP201・認可baseline同数・scope読取・FOR UPDATE期待値を保持する。test・assertion・mockは変更しない。限定診断時だけarchUnitTest/archUnitFreezeStoreIntegrityTestを除外し、通常CIでは除外しない。

## 結果の扱い

Gradle終了直後の実exitを `ac18-gradle-exit.txt` へ記録し、同じ値でstepを終了する。失敗jobを成功へ変換しない。artifactは指定JUnit XMLとこの実exitだけ、保存3日。raw logはuploadしない。

回収後は既存 `classify-junit.py` へ実exitを渡す。tests=1・skipped=0と、HTTP201通過後の認可baseline assertion failureをXMLで確認する。起動/fixture/Docker/Future失敗やtimeoutはsemantic REDにしない。XMLまたは実exitがない場合、特にtimeoutで実exit未記録の場合はRED不成立。

この結果は計測器の別thread混入契約の診断であり、自然CIの原因立証・製品全回帰・Security filter認可・E2E完了の証拠ではない。実行・回収・分類・後続回帰はpending。
