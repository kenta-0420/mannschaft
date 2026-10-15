import type { Locator, Page, Response } from "@playwright/test";
import { expect, loginViaApi, test } from "../fixtures/auth";
import { waitForHydration } from "../helpers/wait";

const apiBase = process.env.API_BASE_URL ?? "http://localhost:8081";
const ownerCredentials = {
  email: process.env.TEST_USER_EMAIL ?? "e2e-user@test.mannschaft.local",
  password: process.env.TEST_USER_PASSWORD ?? "TestPass2026!",
};
const outsiderCredentials = {
  email:
    process.env.TEST_OUTSIDER_EMAIL ?? "e2e-outsider@test.mannschaft.local",
  password: process.env.TEST_OUTSIDER_PASSWORD ?? "TestPass2026!",
};

// 親組織を指定しない作成は全ログインユーザー共通であり、既存組織のロール差はない。
// 作成後のPRIVATE内部は作成者と非所属アカウントの実画面で境界を確認する。
// API補助はログインと、このspec自身が作成した組織の後始末のみ。対象操作は全てUI。
test.use({ storageState: { cookies: [], origins: [] } });

function organizationResponse(
  response: Response,
  method: string,
  suffix = "",
): boolean {
  return (
    response.request().method() === method &&
    new URL(response.url()).pathname === `/api/v1/organizations${suffix}`
  );
}

async function openCreateDialog(page: Page) {
  await page.goto("/organizations", { waitUntil: "commit" });
  await waitForHydration(page);
  await page.getByRole("button", { name: "組織を作成", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "組織を作成", exact: true });
  await expect(dialog).toBeVisible();
  return dialog;
}

// FE規約§3b: 実ブラウザのクリック領域を測る。softで後続の認可検証と後始末も続ける。
async function expectTouchTarget(target: Locator, name: string) {
  const bounds = await target.boundingBox();
  expect.soft(bounds, `${name}の実クリック領域`).not.toBeNull();
  if (bounds) {
    expect.soft(bounds.width, `${name}の幅`).toBeGreaterThanOrEqual(44);
    expect.soft(bounds.height, `${name}の高さ`).toBeGreaterThanOrEqual(44);
  }
}

test.describe("CMP-261010-1129 組織作成の公開範囲と入力エラー（実BE）", () => {
  test("PRIVATEを画面から作成・保持更新し、非所属者の一覧と直URLには内部を出さない", async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    await page.setViewportSize({ width: 768, height: 1024 });
    await loginViaApi(page, ownerCredentials, { apiBaseUrl: apiBase });
    const organizationName = `組織実機 ${Date.now()}`;
    let createdSlug: string | undefined;
    const outsiderContext = await browser.newContext({
      storageState: { cookies: [], origins: [] },
    });
    try {
      const dialog = await openCreateDialog(page);
      // enter transitionを終端へ進め、44px境界の測定をアニメーション倍率に依存させない。
      await test.info().attach("組織作成タブレット", {
        body: await dialog.screenshot({ animations: "disabled" }),
        contentType: "image/png",
      });
      await expectTouchTarget(
        dialog.locator(".p-dialog-header button"),
        "閉じる",
      );
      await expectTouchTarget(
        dialog.getByRole("button", { name: "キャンセル", exact: true }),
        "キャンセル",
      );
      await expectTouchTarget(
        page.getByTestId("entity-create-submit"),
        "組織を作成",
      );
      const textInputs = dialog.locator("input.p-inputtext, textarea");
      await expect(textInputs).toHaveCount(5);
      for (let index = 0; index < (await textInputs.count()); index++) {
        const input = textInputs.nth(index);
        await expectTouchTarget(input, `入力欄${index + 1}`);
        const fontSize = await input.evaluate((element) =>
          Number.parseFloat(getComputedStyle(element).fontSize),
        );
        expect
          .soft(fontSize, `入力欄${index + 1}の文字サイズ`)
          .toBeGreaterThanOrEqual(16);
      }
      const selects = dialog.locator(".p-select");
      await expect(selects).toHaveCount(4);
      for (let index = 0; index < (await selects.count()); index++) {
        await expectTouchTarget(selects.nth(index), `選択欄${index + 1}`);
      }
      // スイッチ本体の視覚寸法は維持し、関連labelのクリック領域で44pxを確保する。
      await expectTouchTarget(
        page.getByTestId("entity-create-supporter-label"),
        "サポーター設定ラベル",
      );
      await dialog.locator("input").first().fill(organizationName);
      // slugの可用性チェックとの競合を避け、実UIで空にしてBE自動生成を選ぶ。
      await page.getByTestId("entity-create-slug").fill("");
      await page.getByTestId("entity-create-visibility").click();
      await expect(page.getByRole("option")).toHaveText(["公開", "限定公開"]);
      await page.getByRole("option", { name: "限定公開", exact: true }).click();
      await expect(page.getByTestId("entity-create-visibility")).toContainText(
        "限定公開",
      );

      const supporter = page.locator("#entity-create-supporter");
      await expect(supporter).not.toBeChecked();
      await page.getByTestId("entity-create-supporter-label").click();
      await expect(supporter).toBeChecked();
      await page.getByTestId("entity-create-supporter-label").click();
      await expect(supporter).not.toBeChecked();
      await page.getByTestId("entity-create-supporter-label").click();
      await expect(supporter).toBeChecked();

      const [createdResponse] = await Promise.all([
        page.waitForResponse((response) =>
          organizationResponse(response, "POST"),
        ),
        page.getByTestId("entity-create-submit").click(),
      ]);
      expect(createdResponse.status(), await createdResponse.text()).toBe(201);
      const created = (await createdResponse.json()).data as { slug: string };
      createdSlug = created.slug;
      expect(createdSlug).toBeTruthy();
      expect(createdResponse.request().postDataJSON()).toMatchObject({
        visibility: "PRIVATE",
        supporterEnabled: true,
      });
      await expect(page).toHaveURL(
        new RegExp(`/organizations/${createdSlug}$`),
        { timeout: 20_000 },
      );
      await expect(
        page.getByRole("dialog", { name: "組織を作成", exact: true }),
      ).toBeHidden();
      await expect(
        page.getByRole("heading", { name: organizationName, exact: true }),
      ).toBeVisible();

      // ページ再読込のGETを観測して永続化を確認する。APIによる対象操作の代替はしない。
      const detailPath = `/${createdSlug}`;
      const [detailResponse] = await Promise.all([
        page.waitForResponse((response) =>
          organizationResponse(response, "GET", detailPath),
        ),
        page.goto(`/organizations/${createdSlug}/info`, {
          waitUntil: "commit",
        }),
      ]);
      expect(detailResponse.status()).toBe(200);
      expect((await detailResponse.json()).data.visibility.visibility).toBe(
        "PRIVATE",
      );
      await waitForHydration(page);
      // 新規組織の初期権限案内を閉じる。権限設定は変更しない。
      const permissionSetup = page.getByTestId("member-permission-setup");
      if (
        await permissionSetup
          .waitFor({ state: "visible", timeout: 10_000 })
          .then(
            () => true,
            () => false,
          )
      ) {
        await permissionSetup
          .getByRole("button", { name: "あとで決める" })
          .click();
        await expect(permissionSetup).toBeHidden();
      }
      const visibilityValue = page
        .locator("label")
        .filter({ hasText: /^公開設定$/ })
        .locator("..")
        .locator("p");
      await expect.soft(visibilityValue).toHaveText("限定公開");

      // 作成値を保持したままUIで保存しても、PRIVATEがPUBLICへ戻らない。
      await page.getByTestId("org-basic-info-edit-button").click();
      await expect(page.locator("#org-basic-info-name")).toHaveValue(
        organizationName,
      );
      const [updatedResponse] = await Promise.all([
        page.waitForResponse((response) =>
          organizationResponse(response, "PATCH", detailPath),
        ),
        page.getByTestId("org-basic-info-save").click(),
      ]);
      expect(updatedResponse.status(), await updatedResponse.text()).toBe(200);
      expect((await updatedResponse.json()).data.visibility.visibility).toBe(
        "PRIVATE",
      );
      await expect(page.getByTestId("org-basic-info-edit-dialog")).toBeHidden();
      await expect.soft(visibilityValue).toHaveText("限定公開");

      const outsider = await outsiderContext.newPage();
      // newContextはbaseURLを継承しないため、同一FEオリジンを明示する。
      const frontendBase = new URL(page.url()).origin;
      await outsider.goto(`${frontendBase}/robots.txt`);
      await loginViaApi(outsider, outsiderCredentials, {
        apiBaseUrl: apiBase,
        deferNavigation: true,
      });
      await outsider.goto(`${frontendBase}/organizations`, {
        waitUntil: "commit",
      });
      await waitForHydration(outsider);
      await expect(
        outsider.getByRole("button", { name: "組織を作成", exact: true }),
      ).toBeVisible();
      await expect(
        outsider.getByRole("heading", { name: organizationName, exact: true }),
      ).toHaveCount(0);
      const [searchResponse] = await Promise.all([
        outsider.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === "/api/v1/organizations/search",
        ),
        outsider.goto(
          `${frontendBase}/organizations/search?keyword=${encodeURIComponent(organizationName)}`,
          { waitUntil: "commit" },
        ),
      ]);
      expect(searchResponse.status()).toBe(200);
      expect(JSON.stringify(await searchResponse.json())).not.toContain(
        createdSlug,
      );
      await expect(
        outsider.getByText("該当する組織が見つかりませんでした", {
          exact: true,
        }),
      ).toBeVisible();
      const [forbiddenResponse] = await Promise.all([
        outsider.waitForResponse((response) =>
          organizationResponse(response, "GET", detailPath),
        ),
        outsider.goto(`${frontendBase}/organizations/${createdSlug}`, {
          waitUntil: "commit",
        }),
      ]);
      expect(forbiddenResponse.status()).toBe(403);
      await expect(
        outsider.getByText("情報を取得できませんでした", { exact: true }),
      ).toBeVisible();
      await expect(
        outsider.getByRole("heading", { name: organizationName, exact: true }),
      ).toHaveCount(0);
    } finally {
      await outsiderContext.close();
      if (createdSlug) {
        const deleted = await page.request.delete(
          `${apiBase}/api/v1/organizations/${createdSlug}`,
        );
        expect(
          deleted.status(),
          `実機で作成した組織の後始末: ${await deleted.text()}`,
        ).toBe(204);
      }
    }
  });

  test("旧下書きの空orgTypeと空nameを実BEが拒否し、両方のfieldErrorsを画面表示する", async ({
    page,
  }) => {
    await loginViaApi(page, ownerCredentials, { apiBaseUrl: apiBase });
    // 下書き復元は既存仕様。対応入力欄のないorgTypeエラーをモックなしで再現する。
    await page.evaluate(() => {
      const user = JSON.parse(localStorage.getItem("currentUser") ?? "{}") as {
        id: number;
      };
      localStorage.setItem(
        `entity-create-draft-${user.id}-organization`,
        JSON.stringify({
          name: "",
          orgType: "",
          visibility: "PUBLIC",
          supporterEnabled: false,
        }),
      );
    });
    const dialog = await openCreateDialog(page);
    const [response] = await Promise.all([
      page.waitForResponse((result) => organizationResponse(result, "POST")),
      page.getByTestId("entity-create-submit").click(),
    ]);
    expect(response.status()).toBe(400);
    const fieldErrors = (await response.json()).error.fieldErrors as {
      field: string;
      message: string;
    }[];
    expect(fieldErrors.map((error) => error.field)).toEqual(
      expect.arrayContaining(["name", "orgType"]),
    );
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("input").first()).toHaveClass(/p-invalid/);
    await expect(
      dialog.locator("input").first().locator("..").locator("small"),
    ).toBeVisible();
    const otherErrors = page.getByTestId("entity-create-other-errors");
    await expect(otherErrors).toContainText("入力内容を確認してください");
    await expect(otherErrors.locator("li")).toHaveText([/^orgType: .+/]);
    await expect(page).toHaveURL(/\/organizations$/);
  });

  test("未認証では組織一覧・組織詳細の直URLともログインへ誘導する", async ({
    page,
  }) => {
    for (const path of [
      "/organizations",
      "/organizations/org-private-auth-check",
    ]) {
      await page.goto(path, { waitUntil: "commit" });
      await waitForHydration(page);
      await expect(page).toHaveURL(/\/login(?:\?|$)/);
      await expect(
        page.getByRole("button", { name: "ログイン", exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole("button", { name: "組織を作成", exact: true }),
      ).toHaveCount(0);
    }
  });
});
