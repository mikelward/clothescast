// Classifies the identities older app versions left behind, for
// scripts/purge-legacy-identities.js. Kept here, not in the script, so the
// rules that decide what gets deleted are unit-tested.

/** The shape of a firebase-admin UserRecord this module reads. */
export interface AuthUserLike {
  uid: string;
  providerData: ReadonlyArray<unknown>;
}

/**
 * An anonymous Firebase user: one with no linked sign-in provider. Older
 * app versions created one per install for the TTS proxy; a Google
 * sign-in always carries the `google.com` provider, so it never matches.
 */
export function isAnonymousUser(user: AuthUserLike): boolean {
  return user.providerData.length === 0;
}

/**
 * A `quota` doc written for an anonymous uid or a legacy `X-Install-Id`.
 * Signed-in callers' docs always carry `expireAt` for the TTL policy;
 * legacy ones never do, so they would otherwise live forever.
 */
export function isLegacyQuotaDoc(data: Record<string, unknown> | undefined): boolean {
  return data !== undefined && !("expireAt" in data);
}

export interface PurgeOptions {
  projectId: string;
  apply: boolean;
}

// Firebase project IDs: 6-30 lowercase letters, digits and hyphens,
// starting with a letter.
const PROJECT_ID = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;

/**
 * Parses the purge script's arguments. `--project` is required so the target
 * is named on the command line rather than taken from whatever project
 * Application Default Credentials resolve to.
 */
export function parsePurgeArgs(args: ReadonlyArray<string>): PurgeOptions {
  let projectId: string | undefined;
  let apply = false;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--apply") {
      apply = true;
    } else if (arg === "--project") {
      projectId = args[++i];
      if (projectId === undefined) throw new Error("--project needs a value");
    } else if (arg.startsWith("--project=")) {
      projectId = arg.slice("--project=".length);
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  if (projectId === undefined) throw new Error("--project is required");
  if (!PROJECT_ID.test(projectId)) throw new Error(`not a project ID: ${projectId}`);
  return { projectId, apply };
}
