// 既存dashboard設定を正本にする。開始と表示保存は独立し開始を再送しない。
export function useRanchVisibility() {
 const api = useApi()
 async function setVisible(visible: boolean) {
  const response = await api<{ data: {widgetKey: string; visible: boolean; sortOrder: number}[] }>('/api/v1/dashboard/widgets', { query: { scopeType: 'personal' } })
  const widgets = response.data.map(item => ({ widgetKey: item.widgetKey, isVisible: item.widgetKey === 'PERSONAL_DINOSAUR_RANCH' ? visible : item.visible, sortOrder: item.sortOrder }))
  if (!widgets.some(item => item.widgetKey === 'PERSONAL_DINOSAUR_RANCH')) widgets.push({ widgetKey: 'PERSONAL_DINOSAUR_RANCH', isVisible: visible, sortOrder: widgets.length })
  await api('/api/v1/dashboard/widgets', { method: 'PUT', body: { scopeType: 'personal', widgets } })
  await refreshNuxtData('dashboard-widgets:personal:0')
 }
 return { setVisible }
}
