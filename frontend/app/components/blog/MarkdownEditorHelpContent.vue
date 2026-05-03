<script setup lang="ts">
</script>

<template>
  <div class="flex-1 overflow-y-auto px-4 pb-6 text-xs leading-relaxed text-surface-700">
    <section class="help-section">
      <h4>☰ 目次ファーストで書く</h4>
      <ol class="help-ol">
        <li><strong>☰ ボタン</strong>を押して目次ブロックを挿入</li>
        <li>
          ブロック内のリストに見出しを書く<br >
          <code>- 大見出し（H1）</code><br >
          <code> - 中見出し（H2）</code><br >
          <code> - 小見出し（H3）</code>
        </li>
        <li><strong>⚡ 構成を生成</strong>ボタンを押すと見出しが自動挿入される</li>
        <li>各見出しの下に本文を書く</li>
        <li>目次ブロックを編集して再度押すと差分が反映される</li>
      </ol>
      <div class="help-example">
        <div class="help-example-label">入力例</div>
        <pre>
- はじめに
  - 背景
  - 目的
- 本題
- まとめ</pre
        >
        <div class="help-example-label mt-2">↓ 生成される構成</div>
        <pre>
# はじめに
## 背景
## 目的
# 本題
# まとめ</pre
        >
      </div>
    </section>

    <section class="help-section">
      <h4>⚡ 構成を生成 — 押すたびに差分反映</h4>
      <p class="mb-2 text-surface-500">
        目次ブロックの内容を「正」として、ドキュメント側を自動更新します。
      </p>
      <table class="help-table">
        <thead>
          <tr>
            <td style="font-weight: 700; color: #15803d">目次への操作</td>
            <td style="font-weight: 700; color: #15803d">ドキュメントの変化</td>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>項目を追加</td>
            <td>新しい見出しセクションが追加される</td>
          </tr>
          <tr>
            <td>項目を削除</td>
            <td>「削除(退避)」区切りの下に移動（本文は失われない）</td>
          </tr>
          <tr>
            <td>順番を変更</td>
            <td>見出し順が目次の順に組み替わる</td>
          </tr>
          <tr>
            <td>変更なし</td>
            <td>何も変わらない（何度押しても安全）</td>
          </tr>
        </tbody>
      </table>
      <p class="mt-2 text-surface-400">
        ※ 退避されたセクションは
        <code>── 削除(退避) ──</code>
        の区切り線の下にまとめて表示されます。内容を確認して不要なら削除してください。
      </p>
    </section>

    <section class="help-section">
      <h4>✍️ マークダウン早見表</h4>
      <table class="help-table">
        <tr>
          <td><code>**テキスト**</code></td>
          <td><strong>太字</strong></td>
        </tr>
        <tr>
          <td><code>*テキスト*</code></td>
          <td><em>斜体</em></td>
        </tr>
        <tr>
          <td><code>~~テキスト~~</code></td>
          <td><del>打ち消し</del></td>
        </tr>
        <tr>
          <td><code># 見出し</code></td>
          <td>大見出し (H1)</td>
        </tr>
        <tr>
          <td><code>## 見出し</code></td>
          <td>中見出し (H2)</td>
        </tr>
        <tr>
          <td><code>### 見出し</code></td>
          <td>小見出し (H3)</td>
        </tr>
        <tr>
          <td><code>- 項目</code></td>
          <td>箇条書き</td>
        </tr>
        <tr>
          <td><code>1. 項目</code></td>
          <td>番号付き</td>
        </tr>
        <tr>
          <td><code>- [ ] タスク</code></td>
          <td>チェックボックス</td>
        </tr>
        <tr>
          <td><code>`コード`</code></td>
          <td>インラインコード</td>
        </tr>
        <tr>
          <td><code>> 引用</code></td>
          <td>引用ブロック</td>
        </tr>
        <tr>
          <td><code>[文字](URL)</code></td>
          <td>リンク</td>
        </tr>
        <tr>
          <td><code>![説明](URL)</code></td>
          <td>画像</td>
        </tr>
        <tr>
          <td><code>---</code></td>
          <td>区切り線</td>
        </tr>
      </table>
    </section>

    <section class="help-section">
      <h4>🛠 ツールバー</h4>
      <div class="space-y-1">
        <div class="help-row">
          <span class="help-badge">B I S</span>太字・斜体・打ち消し線
        </div>
        <div class="help-row"><span class="help-badge">H1 H2 H3</span>見出しを挿入</div>
        <div class="help-row"><span class="help-badge">リスト / 1. / ☑</span>各種リスト</div>
        <div class="help-row">
          <span class="help-badge">` / ```</span>コード（インライン / ブロック）
        </div>
        <div class="help-row"><span class="help-badge">❝</span>引用</div>
        <div class="help-row"><span class="help-badge">🔗 🖼</span>リンク・画像を挿入</div>
        <div class="help-row"><span class="help-badge">⊞</span>表を挿入</div>
        <div class="help-row">
          <span class="help-badge">☰</span>目次ブロックを挿入（現在の見出しから自動生成）
        </div>
        <div class="help-row">
          <span class="help-badge">⚡</span
          >目次ブロックから見出しを生成・更新（何度でも押せる）
        </div>
      </div>
    </section>

    <section class="help-section">
      <h4>⌨️ キーボードショートカット</h4>
      <div class="space-y-1">
        <div class="help-row"><span class="help-badge">Ctrl+B</span>太字</div>
        <div class="help-row"><span class="help-badge">Ctrl+I</span>斜体</div>
        <div class="help-row">
          <span class="help-badge">Ctrl+S</span>保存（エディタ外で有効）
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.help-section {
  margin-top: 1.25rem;
}
.help-section h4 {
  font-size: 0.75rem;
  font-weight: 700;
  color: #15803d;
  margin-bottom: 0.5rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px dashed #bbf7d0;
}
.help-ol {
  padding-left: 1.1rem;
  margin: 0;
  color: #374151;
  line-height: 1.9;
}
.help-ol li {
  margin-bottom: 0.25rem;
}
.help-ol code {
  background: #dcfce7;
  color: #15803d;
  padding: 0.05em 0.3em;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-family: monospace;
}
.help-example {
  margin-top: 0.5rem;
  border: 1px solid #bbf7d0;
  border-radius: 0.5rem;
  overflow: hidden;
}
.help-example-label {
  background: #dcfce7;
  color: #166534;
  font-size: 0.65rem;
  font-weight: 600;
  padding: 0.2rem 0.5rem;
}
.help-example pre {
  margin: 0;
  padding: 0.4rem 0.6rem;
  background: #f7fef9;
  color: #166534;
  font-size: 0.72rem;
  font-family: monospace;
  line-height: 1.7;
  white-space: pre;
}
.help-table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 0.25rem;
}
.help-table tr:nth-child(even) td {
  background: #f0fdf4;
}
.help-table td {
  padding: 0.25rem 0.4rem;
  font-size: 0.72rem;
  color: #374151;
}
.help-table td:first-child {
  font-family: monospace;
  color: #15803d;
  white-space: nowrap;
  width: 55%;
}
.help-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.2rem 0;
  font-size: 0.72rem;
  color: #374151;
}
.help-badge {
  background: #dcfce7;
  color: #15803d;
  border: 1px solid #86efac;
  border-radius: 0.25rem;
  padding: 0.1rem 0.4rem;
  font-size: 0.65rem;
  font-family: monospace;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}
</style>
