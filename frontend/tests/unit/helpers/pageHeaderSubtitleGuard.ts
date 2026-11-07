import { parse } from '@vue/compiler-sfc'
import { parse as parseTemplate, NodeTypes, type TemplateChildNode } from '@vue/compiler-dom'

export interface SubtitleViolation { line: number; tag: string; source: string }

/** 名前付きスロットを除外し、既定スロットの部分木だけを調べる。 */
export function pageHeaderSubtitleViolations(source: string): SubtitleViolation[] {
  const { descriptor, errors } = parse(source)
  if (errors.length) throw new Error(String(errors[0]))
  if (!descriptor.template) return []
  const root = parseTemplate(descriptor.template.content)
  const violations: SubtitleViolation[] = []
  function inspect(node: TemplateChildNode): void {
    if (node.type !== NodeTypes.ELEMENT) return
    const slot = node.props.find(p => p.type === NodeTypes.DIRECTIVE && p.name === 'slot')
    if (slot?.type === NodeTypes.DIRECTIVE && slot.arg?.type === NodeTypes.SIMPLE_EXPRESSION && slot.arg.content !== 'default') return
    const classes = node.props.filter(p => p.type === NodeTypes.ATTRIBUTE && p.name === 'class').map(p => p.type === NodeTypes.ATTRIBUTE ? p.value?.content ?? '' : '').join(' ')
    const hasText = node.children.some(c => c.type === NodeTypes.INTERPOLATION || (c.type === NodeTypes.TEXT && c.content.trim().length > 0))
    const plainSpan = node.tag === 'span' && /\btext-(sm|xs)\b/.test(classes) && !/(rounded|px-|bg-)/.test(classes)
    if (hasText && (node.tag === 'p' || node.tag === 'div' || plainSpan)) {
      violations.push({ line: descriptor.template!.loc.start.line + node.loc.start.line - 1, tag: node.tag, source: node.loc.source })
    }
    node.children.forEach(inspect)
  }
  function visit(node: TemplateChildNode): void {
    if (node.type !== NodeTypes.ELEMENT) return
    if (node.tag === 'PageHeader' || node.tag === 'page-header') node.children.forEach(inspect)
    node.children.forEach(visit)
  }
  root.children.forEach(visit)
  return violations
}
