/**
 * Registration entry for the UI automation support package.
 *
 * The package no longer owns a page: the switch lives under Root in the host's
 * tool-permission settings, where the rest of the device capabilities are
 * granted. What remains here are the verification tools, so an assistant (or
 * the tool runner) can confirm the service answers.
 */
export function registerToolPkg(): boolean {
  return true;
}
