import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TeamProjectDetail from '~/pages/teams/[slug]/projects/[projectId].vue'
import OrgProjectDetail from '~/pages/organizations/[slug]/projects/[projectId].vue'
import MyProjectDetail from '~/pages/my/projects/[projectId].vue'

/**
 * CMP-261105-1507 A1 回帰テスト。
 * ダイアログは自身の localForm を編集して emit('save', form) で渡す。
 * ページが引数を無視して未更新の milestoneForm / editForm を送っていた欠陥（空タイトルで 400）の再発防止。
 */

const api = {
  getProject: vi.fn(),
  listMilestones: vi.fn(),
  getProjectTodos: vi.fn(),
  getGatesSummary: vi.fn(),
  createMilestone: vi.fn(),
  updateProject: vi.fn(),
  getOrgProject: vi.fn(),
  listOrgMilestones: vi.fn(),
  getOrgProjectTodos: vi.fn(),
  createOrgMilestone: vi.fn(),
  updateOrgProject: vi.fn(),
}

mockNuxtImport('useProjectApi', () => () => api)
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(true),
  isAdminOrDeputy: ref(true),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'scope-1', projectId: '7' }, query: {} }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: vi.fn(),
  warn: vi.fn(),
  showError: vi.fn(),
  showSuccess: vi.fn(),
}))

const project = {
  id: 7,
  title: 'P',
  status: 'ACTIVE',
  progressRate: 0,
  completedTodos: 0,
  totalTodos: 0,
  milestones: { total: 0, completed: 0 },
  createdBy: { id: 1 },
}

const MILESTONE_FORM = { title: '入力したタイトル', dueDate: '2026-12-01', sortOrder: 1 }
const PROJECT_FORM = { title: '新題名', description: '', emoji: '', color: '#EF4444', dueDate: '' }

const stubs = {
  ProjectMilestoneDialog: {
    props: ['visible', 'form', 'editing'],
    emits: ['save'],
    setup(_: unknown, { emit }: { emit: (e: 'save', f: typeof MILESTONE_FORM) => void }) {
      return { fire: () => emit('save', MILESTONE_FORM) }
    },
    template: '<button data-testid="ms-save" @click="fire" />',
  },
  ProjectEditDialog: {
    props: ['visible', 'form'],
    emits: ['save'],
    setup(_: unknown, { emit }: { emit: (e: 'save', f: typeof PROJECT_FORM) => void }) {
      return { fire: () => emit('save', PROJECT_FORM) }
    },
    template: '<button data-testid="pj-save" @click="fire" />',
  },
  ProjectMilestoneList: true,
  ForceUnlockDialog: true,
  GateProgressGauge: true,
  InitializeGateButton: true,
  PageHeader: true,
  PageLoading: true,
  SectionCard: { template: '<div><slot /></div>' },
  ProgressBar: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  const ok = { data: project }
  api.getProject.mockResolvedValue(ok)
  api.getOrgProject.mockResolvedValue(ok)
  api.listMilestones.mockResolvedValue({ data: [] })
  api.listOrgMilestones.mockResolvedValue({ data: [] })
  api.getProjectTodos.mockResolvedValue({ data: [] })
  api.getOrgProjectTodos.mockResolvedValue({ data: [] })
  api.getGatesSummary.mockResolvedValue({ data: null })
})

describe('teams プロジェクト詳細', () => {
  it('マイルストーン追加は emit された入力値で保存する', async () => {
    api.createMilestone.mockResolvedValue({})
    const w = await mountSuspended(TeamProjectDetail, { global: { stubs } })
    await flushPromises()
    await w.find('[data-testid="ms-save"]').trigger('click')
    await flushPromises()
    expect(api.createMilestone).toHaveBeenCalledWith('scope-1', 7, MILESTONE_FORM)
  })

  it('プロジェクト編集は emit された入力値で保存する', async () => {
    api.updateProject.mockResolvedValue({})
    const w = await mountSuspended(TeamProjectDetail, { global: { stubs } })
    await flushPromises()
    await w.find('[data-testid="pj-save"]').trigger('click')
    await flushPromises()
    expect(api.updateProject).toHaveBeenCalledWith('scope-1', 7, PROJECT_FORM)
  })
})

describe('organizations プロジェクト詳細', () => {
  it('マイルストーン追加は emit された入力値で保存する', async () => {
    api.createOrgMilestone.mockResolvedValue({})
    const w = await mountSuspended(OrgProjectDetail, { global: { stubs } })
    await flushPromises()
    await w.find('[data-testid="ms-save"]').trigger('click')
    await flushPromises()
    expect(api.createOrgMilestone).toHaveBeenCalledWith('scope-1', 7, MILESTONE_FORM)
  })

  it('プロジェクト編集は emit された入力値で保存する', async () => {
    api.updateOrgProject.mockResolvedValue({})
    const w = await mountSuspended(OrgProjectDetail, { global: { stubs } })
    await flushPromises()
    await w.find('[data-testid="pj-save"]').trigger('click')
    await flushPromises()
    expect(api.updateOrgProject).toHaveBeenCalledWith('scope-1', 7, PROJECT_FORM)
  })
})

describe('my プロジェクト詳細', () => {
  it('マイルストーン追加は emit された入力値で保存する', async () => {
    api.createMilestone.mockResolvedValue({})
    const w = await mountSuspended(MyProjectDetail, { global: { stubs } })
    await flushPromises()
    await w.find('[data-testid="ms-save"]').trigger('click')
    await flushPromises()
    expect(api.createMilestone).toHaveBeenCalledWith(null, 7, MILESTONE_FORM)
  })
})
