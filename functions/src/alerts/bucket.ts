/**
 * The Cloud Storage bucket the alerts ingester publishes to: public,
 * readable by anyone, holding only public alert data (docs/ALERTS.md →
 * Architecture → B). `scripts/create-alerts-bucket.sh` creates it.
 */
import { getStorage } from "firebase-admin/storage";

import { INDEX_PATH, STATE_PATH, TILES_PREFIX, type AlertStore, type IngestState } from "./ingest";

// The index is short-lived, so a phone sees a new alert within a minute of a
// run and Google's edge caches don't hold a removed one long. A tile file
// never changes (its name is its contents' hash), so it can be cached long.
const INDEX_CACHE_CONTROL = "public, max-age=60";
const TILE_CACHE_CONTROL = "public, max-age=86400, immutable";

/** The bucket's name for [projectId]: one per project, by convention. */
export function alertsBucketName(projectId: string): string {
  return `${projectId}-alerts`;
}

function isNotFound(err: unknown): boolean {
  return (err as { code?: number }).code === 404;
}

// Generation 0 means "only if it doesn't exist yet".
function ifGeneration(generation: string | null) {
  return { ifGenerationMatch: generation === null ? 0 : Number(generation) };
}

export function bucketStore(bucketName: string): AlertStore {
  const bucket = getStorage().bucket(bucketName);

  async function generationOf(path: string): Promise<string | null> {
    try {
      const [metadata] = await bucket.file(path).getMetadata();
      return metadata.generation === undefined ? null : String(metadata.generation);
    } catch (err) {
      if (isNotFound(err)) return null;
      throw err;
    }
  }

  async function saveJson(path: string, body: unknown, cacheControl: string, generation?: string | null) {
    await bucket.file(path).save(JSON.stringify(body), {
      gzip: true,
      contentType: "application/json",
      metadata: { cacheControl },
      ...(generation === undefined ? {} : { preconditionOpts: ifGeneration(generation) }),
      resumable: false,
    });
  }

  return {
    async readState() {
      // Pin the generation first, so the contents and the precondition the
      // save checks belong to the same write.
      const generation = await generationOf(STATE_PATH);
      if (generation === null) return { state: null, generation: null };
      try {
        const [contents] = await bucket.file(STATE_PATH, { generation }).download();
        return { state: JSON.parse(contents.toString("utf8")) as IngestState, generation };
      } catch (err) {
        // Replaced since the metadata read: start from nothing, and let the
        // save's precondition refuse this run.
        if (isNotFound(err)) return { state: null, generation };
        throw err;
      }
    },
    async writeState(state, generation) {
      await saveJson(STATE_PATH, state, "no-store", generation);
    },
    indexGeneration: () => generationOf(INDEX_PATH),
    async writeIndex(index, generation) {
      await saveJson(INDEX_PATH, index, INDEX_CACHE_CONTROL, generation);
    },
    async listTiles() {
      const [files] = await bucket.getFiles({ prefix: TILES_PREFIX, autoPaginate: true });
      return files.map((file) => ({
        path: file.name,
        createdAt: new Date(file.metadata.timeCreated ?? 0),
      }));
    },
    async writeTile(path, body) {
      await saveJson(path, body, TILE_CACHE_CONTROL);
    },
    async delete(path) {
      await bucket.file(path).delete({ ignoreNotFound: true });
    },
  };
}
