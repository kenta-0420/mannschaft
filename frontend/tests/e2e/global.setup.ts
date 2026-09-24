/**
 * E2E テスト globalSetup — storageState ファイルの存在保証。
 *
 * storageState（tests/e2e/.auth/user.json 等）は gitignore されており、
 * 新規 clone / worktree ではファイルが存在しない。Playwright は storageState
 * ファイル不在時に ENOENT でプロジェクト初期化に失敗し、モックテスト含め全滅する。
 *
 * 本 globalSetup は:
 *   1. storageState ファイルが存在しなければ空の有効な JSON を書き込む
 *   2. 依存 setup プロジェクトが後続で本物の認証情報に置き換える
 *
 * これにより、認証情報が未設定の環境でも最低限モックテストは実行可能になる。
 */
import fs from 'node:fs'
import path from 'node:path'

// playwright.config.ts の projects[].use.storageState と同期させること
const STORAGE_STATE_FILES = [
  'tests/e2e/.auth/user.json',
  'tests/e2e/.auth/admin.json',
  'tests/e2e/.auth/real-user.json',
  'tests/e2e/.auth/real-admin.json',
]

const PLACEHOLDER = JSON.stringify({ cookies: [], origins: [] })

async function globalSetup() {
  const projectRoot = process.cwd()

  for (const relativePath of STORAGE_STATE_FILES) {
    const fullPath = path.join(projectRoot, relativePath)
    // ディレクトリがなければ作成（.auth/ は .gitkeep で管理されているが念のため）
    const dir = path.dirname(fullPath)
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true })
    }
    // ファイルが存在しない または 空の場合のみプレースホルダーを書き込む
    if (!fs.existsSync(fullPath) || fs.statSync(fullPath).size === 0) {
      fs.writeFileSync(fullPath, PLACEHOLDER, 'utf-8')
      console.log(`[globalSetup] プレースホルダー作成: ${relativePath}`)
    }
  }
}

export default globalSetup
