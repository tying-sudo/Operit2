/**
 * Companion plugin for the UI automation service that ships in the app.
 *
 * The accessibility service now lives inside the main application, so this
 * package no longer installs anything: it reports the system switch, opens the
 * system settings page and reads one live snapshot through the host tools.
 */

/** Component the system stores once the user enables the built-in service. */
export const SERVICE_COMPONENT = "app.operit/app.operit.OperitAccessibilityService";

/**
 * Reports whether the built-in accessibility service is switched on.
 *
 * The value is the same secure setting Android writes when the user flips the
 * switch, so it needs no accessibility binding of its own.
 */
export async function isServiceEnabled(): Promise<boolean> {
  const data = await Tools.System.getSetting("enabled_accessibility_services", "secure");
  return String(data.value ?? "")
    .split(":")
    .some((entry) => entry.trim().toLowerCase() === SERVICE_COMPONENT);
}

/**
 * Reads one page snapshot through the host's UI automation provider.
 *
 * This exercises the whole path the tools use: the shared runtime asks the
 * platform host, and the host answers from the accessibility service that
 * lives in the application.
 */
export async function readPageSnapshot(): Promise<{
  packageName: string;
  activityName: string;
  nodeCount: number;
  truncated: boolean;
  firstTexts: string[];
}> {
  const page = await Tools.UI.getPageInfo();
  const texts: string[] = [];
  const walk = (node: { text?: string | null; contentDesc?: string | null; children?: unknown[] }): void => {
    const label = (node.text ?? node.contentDesc ?? "").toString().trim();
    if (label && texts.length < 8) texts.push(label);
    const children = (node.children ?? []) as typeof node[];
    for (const child of children) if (texts.length < 8) walk(child);
  };
  let count = 0;
  const countNodes = (node: { children?: unknown[] }): void => {
    count += 1;
    const children = (node.children ?? []) as typeof node[];
    for (const child of children) countNodes(child);
  };
  const root = page.uiElements as unknown as { children?: unknown[] };
  countNodes(root);
  walk(root as never);
  return {
    packageName: page.packageName ?? "",
    activityName: page.activityName ?? "",
    nodeCount: count,
    truncated: Boolean(page.truncated),
    firstTexts: texts,
  };
}
