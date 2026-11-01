const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read cloud_saves or leaderboard", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("cloud_saves").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("leaderboard").where("score", ">=", 0).get());
});

test("Authenticated user: can create and read own cloud_save, cannot read another user's cloud_save", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("cloud_saves").doc(ALICE_UID).set({
      userId: ALICE_UID,
      playerName: "Alice",
      saveDataJson: "{\"lvl\":5}",
      level: 5,
      floor: 3,
      gold: 120,
      metaShards: 15,
      adsRemoved: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(aliceDb.collection("cloud_saves").doc(ALICE_UID).get());

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("cloud_saves").doc(ALICE_UID).get());
});

test("Authenticated user: cannot spoof another user's cloud_save or inject ghost fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.collection("cloud_saves").doc(BOB_UID).set({
      userId: BOB_UID,
      playerName: "Alice",
      saveDataJson: "{}",
      level: 1,
      floor: 1,
      gold: 0,
      metaShards: 0,
      adsRemoved: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertFails(
    aliceDb.collection("cloud_saves").doc(ALICE_UID).set({
      userId: ALICE_UID,
      playerName: "Alice",
      saveDataJson: "{}",
      level: 1,
      floor: 1,
      gold: 0,
      metaShards: 0,
      adsRemoved: false,
      isAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Authenticated user: can create own leaderboard entry and list global leaderboard", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.collection("leaderboard").doc(ALICE_UID).set({
      userId: ALICE_UID,
      playerName: "Alice",
      playerClass: "necromancer",
      score: 12500,
      level: 10,
      floor: 6,
      kills: 180,
      itemsFound: 24,
      createdAt: now,
      updatedAt: now,
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(
    bobDb.collection("leaderboard").where("score", ">=", 0).orderBy("score", "desc").limit(30).get()
  );

  // Bob cannot overwrite Alice's leaderboard entry
  await assertFails(
    bobDb.collection("leaderboard").doc(ALICE_UID).update({
      score: 1,
      updatedAt: now,
    })
  );
});
