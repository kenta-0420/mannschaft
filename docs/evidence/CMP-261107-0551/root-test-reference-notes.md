# 正本テスト参照の追加確認

殿が製品sourceを直接読んで確認。最新head CI成功は別証拠 ci-current-head.json に記録する。

- I01/I02: `frontend/scripts/verify-ranch-development-appearances.test.mjs` の `v4 validator rejects count, frame, phase, hash and traversal changes` / `inventory rejects missing and extra files`。N-1/N+1、3/5 frames、不正phase・duration・provenance SHA・traversal、missing/extra inventoryを拒否。`frontend/package.json` の `test:unit` が同試験とvalidator本体を実行する。
- I03/I04/I06: `frontend/app/utils/ranch-development-clips.spec.ts`。v4全384entryの完全tuple解決、production/隔離off/flagoff、UUID/identity不正・未知、v2保持、v3入力不変V1 alias。タイトルにv3を含むdescribe内でもcatalog4全entryを走査している。
- BE固定4件: `RanchApproved64AcceptanceTest` / `RanchApproved64BoundaryTest` / `RanchApproved64SelectionAcceptanceTest`。新機能の先行red→greenであり、既存欠陥修正の回帰redと混同しない。
- I11–I13: `useRanchClipPlayback.spec.ts` / `useRanchClipAtlas.spec.ts` / `DinosaurScene.clips.spec.ts` の既存28件。app所有の世代/取消/cache参照に関する限定証拠であり、全browser scheduler/物理GCの測定ではない。

本書はsourceテスト参照の記録。正式3探索の実施完了と専有runtime撤収は別証拠でroot採択済み。新head CIはci-current-head.jsonの12workflow成功で別確認済み。実装は通常merge済み。production artifact/build/scannerはpending。
