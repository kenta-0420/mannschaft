import { test as setup } from '@playwright/test'
import path from 'path'
import { loginViaApi } from '../fixtures/auth'

const USER_AUTH_FILE = path.join('tests/e2e/.auth', 'user.json')

setup('一般ユーザーでログイン', async ({ page }) => {
  await loginViaApi(page, {
    email: process.env.TEST_USER_EMAIL ?? '',
    password: process.env.TEST_USER_PASSWORD ?? '',
  })
  await page.context().storageState({ path: USER_AUTH_FILE })
})
