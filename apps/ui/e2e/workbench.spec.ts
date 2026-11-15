import { expect, test } from "@playwright/test";

test("workbench shows the empty state and the assistant column", async ({ page }) => {
  const response = await page.goto("/");
  expect(response, "workbench document response").not.toBeNull();
  expect(response!.status(), "workbench document status").toBeLessThan(400);

  await expect(page).toHaveTitle("Onto Studio");
  await expect(page.getByText("Open an ontology to browse classes.")).toBeVisible();

  await page.getByRole("button", { name: "Assistant" }).click();
  const assistant = page.getByRole("complementary", { name: "Assistant" });
  await expect(assistant).toBeVisible();
  await expect(assistant.getByRole("log", { name: "Assistant conversation" })).toBeVisible();
  await expect(assistant.getByText(/Ask about the open ontology/)).toBeVisible();
  await expect(page.locator("body")).not.toContainText("ANTHROPIC_API_KEY");
});
