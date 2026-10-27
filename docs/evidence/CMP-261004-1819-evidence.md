# CMP-261004-1819 試練・実装証拠

対象scopeは`app`。実装PRは[#4193](https://github.com/kenta-0420/mannschaft/pull/4193)。状態は実装中であり、green・実機E2E・独立探索は未実施。

## 原red（変更せず保存）

source head `1acfd1f2dc5cd43ff61111eda370c9f9e6f0825a`、[CI run 40589948384](https://github.com/kenta-0420/mannschaft/actions/runs/40589948384)、attempt 1、shard4 job `122491463835` の対象2XMLを限定採取した。artifact ID `12778100142`、ZIP SHA256 `ef3c682655e6fc843a7455ccb79603f5bd5f2e2790e2c224cff16b51226629a8`。全量ログ・認証情報・実機fixtureの値は公開しない。

| 対象 | tests | failures | pass | errors | skips | XML SHA256 |
|---|---:|---:|---:|---:|---:|---|
| `ShiftScheduleCreateTeamExistenceContractIT` | 20 | 6 | 14 | 0 | 0 | `5d3235435409d0489c733c6bf1c0aae11176217932290664068ebdca9385e370` |
| `ShiftScheduleCreateTeamExistenceFacadeTest` | 6 | 5 | 1 | 0 | 0 | `f3e905f934a7edf154b6871c380de3fbf805729caff6ceba91807a5a70aa3a39` |

26ケースの11 failは、製品原因10件とテスト契約の誤期待1件に分かれる。ITの未発行/0/-1/Long.MAX_VALUE/論理削除の5件では、期待403・増分0に対して実際に201・DB増分1を観測した。UTの5件では親照会の欠落、書込呼出し、実行順序の実アサーションが失敗した。起動失敗やcompile失敗をredに数えていない。

残る1件は文字列`null`に対する期待400 COMMON_001と、既存応答404 COMMON_005の相違だった。`GlobalExceptionHandler#handleTypeMismatch`/`isUnresolvedScopeSlug`の既存契約に合わせ、AC-07とテスト期待だけを訂正する。欠落/空queryは400、文字列nullは404として別表示し、合計26ケースを保つ。handlerは変更しない。

Facade XMLのsuite名はJUnitのDisplayName（原文の文字化けを含む）でFQCNと異なる。修復せず原本を保持したうえで、固定XML basename、全testcase.classnameのFQCN完全一致、件数、producer/source/digestからrootが独立に同定した。

## ローカル試行の制約

同じ原headを正規canonical runner・turnstile・fresh cacheで実行したローカル試行は、compileJava/compileTestJavaと既存archUnit関連taskを経て`test FAILED`、exit1に到達した。ただし対象suite metadata照合で採取を停止し、ローカル26件の実数とactual redを独立認証できていない。対象XMLはscratchの清掃前に保全できず、ローカルをred/greenの証拠へ算入しない。上記CI原本が実redの根拠である。

rootの最終確認ではcanonical cleanup=removed、元source/headは不変・clean、own group/scratch参照/新規containerの残存0。ローカル失敗を成功扱いせず、実装許可後にのみ製品編集を開始した。

## 実装とAC対応

| AC | 実装・試験への対応 | 現在の判定 |
|---|---|---|
| 01/02 | Facadeで既存認可後に親存在照会。Optional.emptyならCOMMON_002。実フィルタMySQL ITの不在5ケースでDB増分も検証 | 原red実証済み、green待ち |
| 03/04 | SYSTEM_ADMIN所属/非所属・通常ADMIN/DEPUTY_ADMIN/user_rolesのみADMINの201と返却/保存teamIdを同ITで確認。既存FacadeTest正常2件は新依存stubを各ケースへ追加 | 回帰のgreen待ち |
| 05/06 | MEMBER/SUPPORTER/他teamADMIN/非所属、実在/不在、匿名401とDB0 | 原CIはpass、修正後green待ち |
| 07 | 欠落/空の400と文字列null404を分離。既存handler不変 | 誤期待1件訂正、green待ち |
| 08/09 | 専用Facade UTはempty/例外のwrite0、拒否lookup0、許可lookup1→write1の順序を検証 | 製品red5件実証済み、green待ち |
| 10 | 不正日付SHIFT_010、feature gate優先の実フィルタIT。既存Service検証は変更なし | 原CI対象ケースpass、修正後green待ち |
| 11 | Facade非TX、公開TeamService窓口のみ。既存Authz/TX番人の変更なし | 修正後CIで確認予定 |
| 12/13 | 実認証の実機ロール横断/匿名直API、不在teamId POST、後続の独立3persona目的探索とtriage/正常閉鎖 | 未実施 |

未削除親の存在確認だけを追加し、ACTIVE限定、新エラー、migration、別ドメインRepository参照は追加しない。同時親削除のTX保証は別課題1821へ分離する。原台帳ACと実装中状態を保持する。修正後の重いローカルbuild/testは資源割当のため未実施で、通常CIの確認はrootが担当する。
