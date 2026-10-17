# モバイル操作領域のred証拠（最少抜粋）

原本SHA256: `2797d0592fc88b857994a8a53b16c63d901712bf4e4e353bdfc032801be3f625`

以下はPlaywright原本の試験名とassertion失敗。ページsnapshot・実行コードは公開対象外。

# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: budget-mobile-touch-targets.spec.ts >> 会計年度フォーム・日付選択・使い方の操作領域は360px幅でも44x44px以上
- Location: tests\e2e\real\budget-mobile-touch-targets.spec.ts:37:1

# Error details

```
Error: 閉じる の幅が44px以上

expect(received).toBeGreaterThanOrEqual(expected)

Expected: >= 44
Received:    40
```

