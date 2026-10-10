# ハーネス改善の再現性計測

計測対象ごとの受け入れ条件達成率と完全成功率、欠陥数、介入回数、所要時間、費用を記録する。複数runからモデルやハーネスの差を判断するときに使う。実モデル呼び出しや外部接続は行わず、実験者が観測値をJSONへ記録してCLIで集計する。

## 計測手順

1. 代表課題を3つ選ぶ。例は、既存の番人違反修正、API契約不具合、UI導線の欠落。難度と作業量が極端に偏らない課題を選び、計測する課題自体は混ぜず別caseにする。
2. 各課題について開始SHA、モデル、effort、実行環境（OS、Node等のversion、関連fixtureや設定を識別できる文字列）、固定した受け入れ条件を記録する。条件や環境が変わるなら別caseとする。
3. 各runを独立worktreeで最低3回実施する。同じcaseのrunはcaseに記録した開始SHA・モデル・effort・環境を使い、runごとに上書きしない。受け入れ条件は開始前に固定する。
4. run終了後に達成したAC、欠陥数、介入回数、所要秒数、費用を記録する。費用を測れないときは `null` とする。未完了runは `completed:false` として残す。完了したが一つ以上のACを満たせなかったrunも削除せず、失敗として母数に含める。
5. `node scripts/harness-benchmark.mjs <記録.json>` を実行し、caseごとの集計を保存・比較する。測定していない項目や回数は推測で埋めず、測定未実施と報告する。

成功率の母数は全run。途中未完了runも失敗として分母へ含め、成功数には「completedかつ全AC達成」のrunだけ数える。AC達成率も全run中の達成AC数を全run数×AC数で割り、途中時点の達成済みACを残す。runが0件なら両率は `null`。欠陥数・介入回数・時間・費用も途中未完了runを含む全試行分を合計する。費用はcaseごとに固定した単位で記録し、case間の費用を合算しない。既知分の小計と未測定件数を示し、未測定が一つでもあれば総費用を `null` にする。runが0件なら測定済み費用の総額は0、未測定件数も0。0は測定済みのゼロ費用であり、未測定とは区別する。

## 記録形式

次の例は**すべて架空の記入例**で、実測結果ではない。実験結果として引用しないこと。

```json
{
  "schemaVersion": 1,
  "cases": [
    {
      "id": "example-guard-fix",
      "startSha": "0123456789abcdef0123456789abcdef01234567",
      "model": "example-model",
      "effort": "medium",
      "environment": "example only; Node v24; Windows; fixture-v1",
      "costUnit": "USD",
      "acceptanceCriteria": [
        { "id": "AC-1", "description": "番人違反を再現できる" },
        { "id": "AC-2", "description": "修正後に番人テストが通る" }
      ],
      "runs": [
        {
          "id": "run-1",
          "completed": true,
          "passedCriteria": ["AC-1", "AC-2"],
          "defects": 0,
          "interventions": 1,
          "durationSeconds": 900,
          "cost": null
        },
        {
          "id": "run-2",
          "completed": false,
          "passedCriteria": ["AC-1"],
          "defects": 0,
          "interventions": 0,
          "durationSeconds": 240,
          "cost": 0
        }
      ]
    }
  ]
}
```

`completed:false` のrunも全試行の記録に残し、成功数以外の率・欠陥・介入・時間・費用の集計にも含める。run IDはcase内で一意、case IDとAC IDも各スコープ内で一意にする。`startSha` は40桁のGit SHA。`costUnit` はcase内で全run共通の単位（例: `USD`）を明記する。未知のAC、空のcase/AC、負数、NaN/Infinity、caseごとに固定したmodel/environmentのrun上書きなどはCLIが拒否する。異なる課題・条件・費用単位のcaseは合算しない。

実測はまだ実施していない。上記のJSONは形式説明用の架空例である。
