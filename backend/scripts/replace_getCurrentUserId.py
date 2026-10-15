"""
全コントローラーの getCurrentUserId() を SecurityUtils.getCurrentUserId() に置換するスクリプト。

処理内容:
1. TODO コメント + getCurrentUserId() メソッド定義を削除
2. getCurrentUserId() の呼び出しを SecurityUtils.getCurrentUserId() に置換
3. import com.mannschaft.app.common.SecurityUtils を追加
"""

import re
import glob
import os

SRC_ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "java")
IMPORT_LINE = "import com.mannschaft.app.common.SecurityUtils;\n"

# getCurrentUserId メソッド定義のパターン（TODO コメント + メソッド本体）
METHOD_PATTERN = re.compile(
    r'\n'
    r'(    // TODO:.*(?:SecurityContextHolder|SecurityContext|認証情報|セキュリティコンテキスト|JWT).*\n)?'
    r'    private Long getCurrentUserId\(\) \{\n'
    r'        return 1L;\n'
    r'    \}\n',
    re.MULTILINE
)

# SecurityUtils.java 自身は除外
EXCLUDE_FILES = {"SecurityUtils.java"}

def process_file(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "getCurrentUserId" not in content:
        return False

    original = content

    # 1. メソッド定義を削除
    content = METHOD_PATTERN.sub("\n", content)

    # 2. getCurrentUserId() 呼び出しを置換
    content = content.replace("getCurrentUserId()", "SecurityUtils.getCurrentUserId()")

    # 3. import 追加（まだない場合）
    if "import com.mannschaft.app.common.SecurityUtils;" not in content:
        # 最後の import 行の後に追加
        last_import_idx = content.rfind("\nimport ")
        if last_import_idx != -1:
            # 行末を見つける
            line_end = content.index("\n", last_import_idx + 1)
            content = content[:line_end + 1] + IMPORT_LINE + content[line_end + 1:]

    if content != original:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True
    return False

def main():
    pattern = os.path.join(SRC_ROOT, "com", "mannschaft", "app", "**", "*.java")
    files = glob.glob(pattern, recursive=True)

    # worktree 内のファイルは除外
    files = [f for f in files if ".claude" not in f and os.path.basename(f) not in EXCLUDE_FILES]

    modified_count = 0
    modified_files = []

    for filepath in sorted(files):
        if process_file(filepath):
            modified_count += 1
            modified_files.append(filepath)

    print(f"Modified {modified_count} files:")
    for f in modified_files:
        # 相対パスで表示
        rel = os.path.relpath(f, os.path.join(os.path.dirname(__file__), ".."))
        print(f"  {rel}")

if __name__ == "__main__":
    main()
