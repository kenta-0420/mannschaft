import { test as setup } from '@playwright/test'
import path from 'path'
import { loginViaApi } from '../fixtures/auth'

const USER_AUTH_FILE = path.join('tests/e2e/.auth', 'user.json')
const ADMIN_AUTH_FILE = path.join('tests/e2e/.auth', 'admin.json')

setup('一般ユーザーでログイン', async ({ page }) => {
  await loginViaApi(page, {
    email: process.env.TEST_USER_EMAIL ?? '',
    password: process.env.TEST_USER_PASSWORD ?? '',
  })
  await page.context().storageState({ path: USER_AUTH_FILE })
})

setup('管理者でログイン', async ({ page }) => {
  await loginViaApi(page, {
    email: process.env.TEST_ADMIN_EMAIL ?? '',
    password: process.env.TEST_ADMIN_PASSWORD ?? '',
  })
  await page.context().storageState({ path: ADMIN_AUTH_FILE })
})
