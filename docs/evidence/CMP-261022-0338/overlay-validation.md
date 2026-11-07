# CMP-261022-0338 B0 overlay取込検証

- 入力: `docs/prototypes/.b0-local/run-CMP-261022-0338-residents-20261026.json`
- 生成: `B0_INCLUDE_LOCAL_OVERLAY=true python docs/prototypes/generate-beta-inventory-board-data.py`
- 検証: `node docs/prototypes/validate-beta-inventory-board.mjs --self-test-overlay`
- 結果: generator成功（features 44 / capabilities 95 / campaigns 595）。validatorはresident overlay・空insights・synthetic baselineを受理し、8種類の不正overlayを拒否。
- 初回UI取込準備後に生成物をベースラインへ戻した後、T #4125 mergeを反映した完了準備として再生成した。task-listはCMP-261022-0338のみ完了へ更新し、GitHub read-only同期は661参照で成功。現在の `beta-inventory-board-data.js` draftには完了状態と5件resident overlayが反映されている。 `node docs/prototypes/validate-beta-inventory-board.mjs --self-test-overlay` は成功（不正overlay 8種類を拒否）。HTMLは変更していない。B0 runnerの住民実行やcoverage達成は行っておらず、主張しない。
- 生成器の前提を満たすため `frontend/tests/e2e` だけ一時的にsparse展開し、検証後は元の `.claude docs scripts` に戻した。
