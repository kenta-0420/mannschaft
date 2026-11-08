import ts from 'typescript'
import { tokenizeJava } from './beAbsenceReasons'

/** コメントや文字列中の見かけの宣言を拾わず、FRIEND定数の第1引数だけを読む。 */
export function parseFriendErrorCodes(source: string): string[] {
  const tokens = tokenizeJava(source)
  const codes: string[] = []
  for (let i = 0; i < tokens.length - 2; i++) {
    const token = tokens[i]!
    if (token.kind === 'id' && token.text.startsWith('FRIEND_') && tokens[i + 1]?.text === '(' && tokens[i + 2]?.kind === 'str') {
      codes.push(JSON.parse(tokens[i + 2]!.text) as string)
    }
  }
  return codes.sort()
}

/** FEの明示集合をASTで読む。コード全体の文字列grepで集合外の言及を誤算入しない。 */
export function parseFriendI18nCodes(source: string): string[] {
  const file = ts.createSourceFile('useErrorHandler.ts', source, ts.ScriptTarget.Latest, true)
  let codes: string[] = []
  function visit(node: ts.Node): void {
    if (ts.isVariableDeclaration(node) && node.name.getText(file) === 'FRIEND_I18N_CODES' && node.initializer) {
      const init = node.initializer
      const array = ts.isNewExpression(init) ? init.arguments?.[0] : init
      if (array && ts.isArrayLiteralExpression(array)) {
        if (!array.elements.every(ts.isStringLiteral)) throw new Error('FRIEND_I18N_CODES は文字列の明示集合にする')
        codes = array.elements.map(item => (item as ts.StringLiteral).text)
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(file)
  return codes.sort()
}

export function flattenStrings(value: unknown, prefix = ''): Record<string, string> {
  if (typeof value === 'string') return { [prefix]: value }
  if (!value || typeof value !== 'object') return {}
  return Object.assign({}, ...Object.entries(value).map(([key, item]) => flattenStrings(item, prefix ? prefix + '.' + key : key))) as Record<string, string>
}

export const DE_BEFRIENDED_ALLOWLIST: Record<string, string> = {
  'friends.json:friends.guide.description': 'Auf diesem Bildschirm folgst du anderen Teams, wirst mit ihnen befreundet und verwaltest diese Beziehungen.',
  'friends.json:friends.guide.follow.title': 'Folgen und befreundet werden',
}

export function forbiddenBefriended(file: string, messages: unknown): string[] {
  return Object.entries(flattenStrings(messages)).filter(([key, value]) => /befreundet\w*/i.test(value) && DE_BEFRIENDED_ALLOWLIST[file + ':' + key] !== value).map(([key]) => file + ':' + key)
}
