import { test as setup } from '@playwright/test'
import path from 'path'
import { loginAs } from '../fixtures/auth'

const ADMIN_AUTH_FILE = path.join('tests/e2e/.auth', 'admin.json')

setup('管理者でログイン', async ({ page }) => {
  await loginAs(page, {
    email: process.env.TEST_ADMIN_EMAIL ?? '',
    password: process.env.TEST_ADMIN_PASSWORD ?? '',
  })
  await page.context().storageState({ path: ADMIN_AUTH_FILE })
})
