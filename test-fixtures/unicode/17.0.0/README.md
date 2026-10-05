# Unicode 17.0.0 書記素互換試験

- 正式取得元: https://www.unicode.org/Public/17.0.0/ucd/auxiliary/GraphemeBreakTest.txt
- ライセンス: Unicode License V3（SPDX: Unicode-3.0）、https://www.unicode.org/license.txt 。原文 header の terms_of_use 参照も保持。
- 原文 SHA-256: `e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec`
- 原文サイズ: 126570 bytes
- 取得日: 2026-10-03。raw bytesを保存し、NFC・trim・改行変換を行わない。

ICU4J 78.3 / ROOT character break iterator の分割を、公式の÷/×から得るcode point segment arrayと照合する。UTF-16offset数値の比較や命名用NFCを混ぜない。FEはこの同じファイルとSHAを入力に使い、別途downloadして異版にしない。

このfixtureはUnicode 17 / UAX29 rev47互換検証だけに使用する。商品名のtrim・NFC・制御/不可視のみ拒否・1〜10書記素・160CP/512bytes上限はDinosaurNameValidatorTestの別契約で検証する。全appのロケール処理は変更しない。

公式原文header19/20行の末尾空白も保持する。初回git diff --checkはこの2行でexit2となったため、原文は上記SHAで同一性を厳格確認し、provenanceと実装の差分形式を別に検査した。全体whitespace設定は変更していない。
