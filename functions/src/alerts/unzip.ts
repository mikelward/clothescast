/**
 * Just enough of the zip format to read a feed that ships as one archive
 * (DWD's warnings in force): the central directory, then each file, stored
 * or deflated. No encryption, no zip64, no multi-disk archives. Pure, so
 * it's unit testable.
 */
import { inflateRawSync } from "node:zlib";

export interface ZipEntry {
  name: string;
  data: Buffer;
}

const END_OF_CENTRAL_DIRECTORY = 0x06054b50;
const CENTRAL_FILE_HEADER = 0x02014b50;
const LOCAL_FILE_HEADER = 0x04034b50;
const STORED = 0;
const DEFLATED = 8;

/** Every file in [zip], directories left out; throws when it isn't a zip it can read. */
export function unzip(zip: Buffer): ZipEntry[] {
  // The end record sits in the last 22 bytes plus up to 64 KB of comment.
  let end = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (zip.readUInt32LE(i) === END_OF_CENTRAL_DIRECTORY) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("not a zip archive");
  const count = zip.readUInt16LE(end + 10);
  let at = zip.readUInt32LE(end + 16);

  const entries: ZipEntry[] = [];
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(at) !== CENTRAL_FILE_HEADER) throw new Error("corrupt zip directory");
    const method = zip.readUInt16LE(at + 10);
    const compressedSize = zip.readUInt32LE(at + 20);
    const nameLength = zip.readUInt16LE(at + 28);
    const extraLength = zip.readUInt16LE(at + 30);
    const commentLength = zip.readUInt16LE(at + 32);
    const localAt = zip.readUInt32LE(at + 42);
    const name = zip.toString("utf8", at + 46, at + 46 + nameLength);
    at += 46 + nameLength + extraLength + commentLength;
    if (name.endsWith("/")) continue;

    if (zip.readUInt32LE(localAt) !== LOCAL_FILE_HEADER) throw new Error(`corrupt zip entry ${name}`);
    const dataAt = localAt + 30 + zip.readUInt16LE(localAt + 26) + zip.readUInt16LE(localAt + 28);
    const raw = zip.subarray(dataAt, dataAt + compressedSize);
    if (method === STORED) entries.push({ name, data: Buffer.from(raw) });
    else if (method === DEFLATED) entries.push({ name, data: inflateRawSync(raw) });
    else throw new Error(`zip entry ${name} uses method ${method}`);
  }
  return entries;
}
