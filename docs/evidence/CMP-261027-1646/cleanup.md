# L1-B 撤去・保持記録

製品L1-Bは最終e67のCI/検分とPR4169通常mergeで完了した。rawcleanupは別の後処理であり、**1root / 6,349bytesだけ撤去、残14root / 888,898,616bytesは未撤去・承認待ち保持**。安全な証拠はremote HEAD `54bc90eb6706d9e0d33ebbcf8d49e4cea8cfc742` のGit blob/packaged hash照合後に本PRへ採用した。今回の保持記録をraw撤去成功と扱わない。

[安全なreceipt要約](cleanup.json)に原本receiptのbytes/SHA・全14fullpath・所有根拠・用途・保持理由・再確認期限・再開条件を保存した。原本receipt SHA256は `a1be0ba503e1d569d30c0d52a04cf08a6ef3acaf542106f151575343fef940dc`。巨大なWindows全file一覧・生process/map詳細は転載していない。元raw本文の再読取は行っていない。

## 保持条件

- owner: root / gradle `9ec2bb94-5ba6-494b-9d4a-dd4ed47f29f5`。用途は完了証拠をGit採用済みの重複raw。
- 理由: 自動承認審査が、大きな証拠の削除について対象に対する具体的ユーザー承認不足として拒否した。初回一括拒否後、所有/停止/remote保存を追加確認して小root1件のみ承認・撤去されたが、次の205,401,645bytes rootで再拒否となり、全destructive作業を停止した。追加削除や別経路での迂回はしない。
- 再確認期限: `2026-10-29T10:00:00+09:00`。期限は状態再確認であり、自動削除の約束ではない。
- 再開条件: 残14rootへの明示的ユーザー承認、freshな所有/絶対path/停止/対象参照0再確認、root releaseがすべてそろった後のみ。未回答を承認として扱わない。

| 残root（採取時fullpath） | bytes |
|---|---:|
| `/home/kenta/verification-evidence/CMP-261027-1646-after-finalweights-1` | 205,401,645 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-freeze-openapi-1` | 7,305,798 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-independent-1` | 7,462 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-oldweights-1` | 205,420,499 |
| `/home/kenta/verification-evidence/CMP-261027-1646-after-remaining-1` | 5,475 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-1` | 1,058 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-2` | 555 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-3` | 310,200 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-4` | 212,672,762 |
| `/home/kenta/verification-evidence/CMP-261027-1646-before-finalweights-1` | 205,430,432 |
| `/home/kenta/verification-evidence/CMP261027-l1b-runtime-final` | 783 |
| `/home/kenta/verification-evidence/CMP261027-l1b-runtime-final2` | 2,778,596 |
| `C:\Claude\mannschaft\.claude\evidence\CMP-261027-1646-l1b-real-support` | 2,313,562 |
| `C:\Claude\mannschaft\.claude\evidence\CMP-261027-1646-l1b-support` | 47,249,789 |

14root各々のprovenanceと保持条件はJSONへ残した。rawの絶対pathはhash索引・所有確認の対象であり、stage証拠参照には本repo内の文書を使う。後日承認後のcleanupは独立した後処理としてreceiptを訂正でき、製品の完了実証や別件1554/親戦役の状態を読み替えない。

## 撤去済みと非対象

- 削除済み: `/home/kenta/verification-evidence/CMP-261027-1646-after-arch-continue-1` / 6,349bytes。残14件には含めない。
- root確認でbaseline/weights/implの3WTと実装local/remote branchは撤去済み。completion/manager WTは本手続きの間保持。
- 実機の専用runtime/DB/Valkey/volume等は既存の停止・撤去証明へ対応する。今回のraw保持と実行中runtimeを混同しない。
- verification-run --checkは自隊scratch0。他/未帰属2entriesは不干渉とし、全システム撤去完了を主張しない。
- 調整台帳はL1-B done/held[]、build/weights解放、親closed=false。L0-M measurementと他ownerの未解消依存には触れていない。
