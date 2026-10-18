#!/usr/bin/env node
"use strict";
// Sets one Google-signed-in account's daily TTS limit through a Firebase
// custom claim, read by the `tts` proxy (src/caller.ts).
//
//   npm run build
//   node scripts/set-tts-limit.js <email> <limit|unlimited|default>
//
// Uses Application Default Credentials (`gcloud auth application-default
// login`) against the project in GCLOUD_PROJECT. The email is an argument,
// never committed: the list of people lives only in Firebase.
const path = require("node:path");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { parseTtsSetting, withTtsSetting } = require(path.join(__dirname, "..", "lib", "access"));

async function main() {
  const [email, arg] = process.argv.slice(2);
  if (!email || !arg) {
    console.error("usage: node scripts/set-tts-limit.js <email> <limit|unlimited|default>");
    process.exit(2);
  }
  let setting;
  try {
    setting = parseTtsSetting(arg);
  } catch (err) {
    console.error(`set-tts-limit: ${err.message}`);
    process.exit(2);
  }

  initializeApp();
  const auth = getAuth();
  const user = await auth.getUserByEmail(email);
  if (!user.providerData.some((p) => p.providerId === "google.com")) {
    // The proxy only reads claims on Google sign-ins.
    console.error(`set-tts-limit: ${email} has not signed in with Google; the limit would be ignored`);
    process.exit(1);
  }
  const claims = withTtsSetting(user.customClaims, setting);
  await auth.setCustomUserClaims(user.uid, claims);
  console.log(
    `${email}: daily TTS limit ${setting === "default" ? "back to the default" : `set to ${setting}`}. ` +
      "It applies at the app's next token refresh, within an hour.",
  );
}

main().catch((err) => {
  console.error(`set-tts-limit failed: ${err.code || err.name}: ${err.message}`);
  process.exit(1);
});
