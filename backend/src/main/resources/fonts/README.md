# PDF 用日本語フォント

`PdfFontConfig`（`backend/src/main/java/com/mannschaft/app/config/PdfFontConfig.java`）が
クラスパスから読み込む日本語フォントの実体を格納する。Flying Saucer 経由の PDF 生成
（`PdfGeneratorService`）で日本語を表示するために必須。

## 入手元

Google Fonts の公式配布物（static 版・無加工）から取得した。

- `NotoSansJP-Regular.ttf`
  - 取得元: `https://fonts.google.com/download/list?family=Noto+Sans+JP` が返す
    `static/NotoSansJP-Regular.ttf` のダウンロード URL（`fonts.gstatic.com` 配信）
  - バージョン: v56
- `NotoSerifJP-Regular.ttf`
  - 取得元: `https://fonts.google.com/download/list?family=Noto+Serif+JP` が返す
    `static/NotoSerifJP-Regular.ttf` のダウンロード URL（`fonts.gstatic.com` 配信）
  - バージョン: v33

いずれも可変フォント（Variable Font）ではなく、Google Fonts が配布している
**static 版 Regular ウェイトをそのまま**取得したものであり、サブセット化・
インスタンス化などの加工は一切行っていない。

## ライセンス

SIL Open Font License, Version 1.1（OFL）。再配布可能。

- `OFL-NotoSansJP.txt` — Noto Sans JP（Source Han Sans 系, Copyright Adobe）のライセンス全文
- `OFL-NotoSerifJP.txt` — Noto Serif JP（Copyright Google）のライセンス全文

## ファイルサイズ

| ファイル | サイズ |
|---|---|
| `NotoSansJP-Regular.ttf` | 約 5.5MB |
| `NotoSerifJP-Regular.ttf` | 約 7.7MB |

いずれも 10MB 未満。

## ファイル名について

`PdfFontConfig.init()` が参照するパス（`fonts/NotoSansJP-Regular.ttf` /
`fonts/NotoSerifJP-Regular.ttf`）と厳密に一致させている。変更する場合は
`PdfFontConfig` 側も同時に更新すること。
