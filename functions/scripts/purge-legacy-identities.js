#!/usr/bin/env node
"use strict";
// Deletes what older app versions left behind once the `tts` proxy stops
// accepting them (the TODO.md cutover): every anonymous Firebase user, and
// every `quota` doc without `expireAt` (anonymous uids and legacy
// X-Install-Id keys). docs/delete-account.md and PRIVACY.md promise this.
//
//   npm run build
//   node scripts/purge-legacy-identities.js --project <id>           # dry run
//   node scripts/purge-legacy-identities.js --project <id> --apply   # deletes
//
// Run --apply only after the cutover is deployed and the Anonymous sign-in
// provider is disabled in the Firebase console: older versions sign in
// anonymously before they reach the proxy, so with the provider on, an old
// install opened after the purge creates a new anonymous user. Uses Application Default
// Credentials. The project is required rather than taken from ADC, which can
// resolve to whichever project gcloud last pointed at. Prints the project and
// counts, never uids or doc ids.
const path = require("node:path");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { isAnonymousUser, isLegacyQuotaDoc, parsePurgeArgs } = require(path.join(__dirname, "..", "lib", "legacy"));

const QUOTA_COLLECTION = "quota"; // keep in step with src/index.ts
const AUTH_BATCH = 1000; // deleteUsers() and listUsers() maximum
const FIRESTORE_BATCH = 500; // a WriteBatch's maximum
const AUTH_DELETE_INTERVAL_MS = 1000; // deleteUsers() is limited to 1 QPS
const USAGE =
  "usage: node scripts/purge-legacy-identities.js --project <id> [--apply]";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function anonymousUids(auth) {
  const uids = [];
  let pageToken;
  do {
    const page = await auth.listUsers(AUTH_BATCH, pageToken);
    for (const user of page.users) {
      if (isAnonymousUser(user)) uids.push(user.uid);
    }
    pageToken = page.pageToken;
  } while (pageToken);
  return uids;
}

async function deleteUsers(auth, uids) {
  let failed = 0;
  for (let i = 0; i < uids.length; i += AUTH_BATCH) {
    if (i > 0) await sleep(AUTH_DELETE_INTERVAL_MS);
    const result = await auth.deleteUsers(uids.slice(i, i + AUTH_BATCH));
    failed += result.failureCount;
    for (const { error } of result.errors) {
      console.error(`purge: a user delete failed: ${error.code}`);
    }
  }
  return failed;
}

async function legacyQuotaRefs(db) {
  const snap = await db.collection(QUOTA_COLLECTION).get();
  return snap.docs.filter((doc) => isLegacyQuotaDoc(doc.data())).map((doc) => doc.ref);
}

async function deleteDocs(db, refs) {
  for (let i = 0; i < refs.length; i += FIRESTORE_BATCH) {
    const batch = db.batch();
    for (const ref of refs.slice(i, i + FIRESTORE_BATCH)) batch.delete(ref);
    await batch.commit();
  }
}

async function main() {
  let options;
  try {
    options = parsePurgeArgs(process.argv.slice(2));
  } catch (err) {
    console.error(`purge-legacy-identities: ${err.message}`);
    console.error(USAGE);
    process.exit(2);
  }
  const { projectId, apply } = options;

  initializeApp({ projectId });
  const auth = getAuth();
  const db = getFirestore();

  const uids = await anonymousUids(auth);
  const refs = await legacyQuotaRefs(db);
  console.log(`project: ${projectId}`);
  console.log(`anonymous users: ${uids.length}`);
  console.log(`legacy quota docs: ${refs.length}`);
  if (!apply) {
    console.log("dry run; pass --apply to delete them");
    return;
  }

  const failed = await deleteUsers(auth, uids);
  await deleteDocs(db, refs);
  console.log(`deleted ${uids.length - failed} anonymous users and ${refs.length} legacy quota docs`);
  if (failed > 0) {
    console.error(`purge: ${failed} user deletes failed; run it again to retry them`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error(`purge-legacy-identities failed: ${err.code || err.name}: ${err.message}`);
  process.exit(1);
});
