# CMP-261021-0717 住民探索記録

公開選択記録 `resident-observations-20261105.json` SHA-256 `2661c3cf82cf2a402f003a6f27505358ddf38ea30f53d3a6634a49efe5f97805`。3件すべて `trusted=false` / `observed-unverified`、source `dc977b6724958111c149140ab83f0094b433bb78`、run `CMP-261021-0717-ui9-20261105`。観測は事実として保持し、これだけで不具合を確定しない。

| 住民 | 観測事実 | 制限・root triage |
|---|---|---|
| A | 公開/取消操作を行ったが、消化額返還の理解に至らなかった。 | 一般会計0と消化額の関係はroot triageで別概念と確認。本人の理解として記録しない。 |
| B | 自分のシフト画面へ戻ると月表示が初期化し、未提出の交代理由が消えた。archive後にも本人枠と交代導線が残り、取消の意味は理解されなかった。 | 原因未確定。公開前の空表示はA公開より前なので正常。ARCHIVED継続表示は既存FULL可視性契約。 |
| C | 別組織schedule/slotsの404、budget管理拒否を観測。推測allocation URLの404は認可成功の根拠にしない。 | 原 `technical-error-closed` を保持。書込API認可は未検証。 |

rootは原記録、選択画像、poststateを独立確認した。A/B/Cの原sessionは65042/58768/48420、各exit0。選択PNGは [A](CMP-261021-0717/resident-a-budget.png)、[B](CMP-261021-0717/b-my-shift.png)、[C](CMP-261021-0717/c-denied.png)。root poststate `2026-11-05 03:32:06Z`: shift ARCHIVED/deleted=null、消化3/4 CANCELLED 9,600/12,000、allocation2 consumed0/deleted=null。

Aの返還理解、Bの取消理解は未達。Cの権限範囲は限定観測。selector/technical errorは原記録どおり保持しアプリ障害に読み替えない。blocker昇格0、新規確定不備0。探索の実施は目的達成や全認可経路coverageを意味しない。
