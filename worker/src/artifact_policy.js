/**
 * Stage 3F (Workstream D): one upload/artifact policy shared by the artifact
 * download path (worker.js) and the UNIVERSAL execution path (browser_worker.js).
 *
 * - A single 10 MiB ceiling, matching the existing Workday/Zoho/Ashby caps.
 * - The declared file type must match the file's actual leading bytes, so a
 *   renamed or corrupted file is rejected before it is ever attached.
 * - File names used on disk are reduced to a safe basename.
 */
import path from 'node:path';

export const MAX_UNIVERSAL_UPLOAD_BYTES = 10 * 1024 * 1024;

const PDF_MAGIC = Buffer.from('%PDF-', 'ascii');
const ZIP_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]); // .docx is a ZIP container
const OLE_MAGIC = Buffer.from([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1]); // legacy .doc

export class ArtifactPolicyError extends Error {
  constructor(reason) {
    super(reason);
    this.name = 'ArtifactPolicyError';
    this.reason = reason;
  }
}

/** Declared format from content type or file extension: 'pdf' | 'docx' | 'doc' | 'text' | null. */
export function declaredFormat({ contentType, fileName } = {}) {
  const type = String(contentType ?? '').toLowerCase().split(';')[0].trim();
  if (type === 'application/pdf') return 'pdf';
  if (type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx';
  if (type === 'application/msword') return 'doc';
  if (type === 'text/plain' || type === 'text/markdown') return 'text';
  const ext = path.extname(String(fileName ?? '')).toLowerCase();
  if (ext === '.pdf') return 'pdf';
  if (ext === '.docx') return 'docx';
  if (ext === '.doc') return 'doc';
  if (ext === '.txt' || ext === '.md') return 'text';
  return null;
}

/** True when the bytes really are the declared format. */
export function signatureMatches(bytes, format) {
  const buf = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes ?? []);
  switch (format) {
    case 'pdf': return buf.length >= PDF_MAGIC.length && buf.subarray(0, PDF_MAGIC.length).equals(PDF_MAGIC);
    case 'docx': return buf.length >= ZIP_MAGIC.length && buf.subarray(0, ZIP_MAGIC.length).equals(ZIP_MAGIC);
    case 'doc': return buf.length >= OLE_MAGIC.length && buf.subarray(0, OLE_MAGIC.length).equals(OLE_MAGIC);
    case 'text': {
      if (buf.includes(0)) return false;
      try {
        new TextDecoder('utf-8', { fatal: true }).decode(buf);
        return true;
      } catch {
        return false;
      }
    }
    default: return false;
  }
}

/**
 * Throws ArtifactPolicyError unless the artifact is within the size cap and its
 * bytes match its declared type. An undeclared/unknown type is rejected.
 */
export function assertArtifactAcceptable(bytes, { contentType, fileName, maxBytes = MAX_UNIVERSAL_UPLOAD_BYTES } = {}) {
  const length = Buffer.isBuffer(bytes) ? bytes.length : Buffer.byteLength(bytes ?? '');
  if (length === 0) throw new ArtifactPolicyError('ARTIFACT_EMPTY');
  if (length > maxBytes) throw new ArtifactPolicyError('ARTIFACT_TOO_LARGE');
  const format = declaredFormat({ contentType, fileName });
  if (!format) throw new ArtifactPolicyError('ARTIFACT_TYPE_UNSUPPORTED');
  if (!signatureMatches(bytes, format)) throw new ArtifactPolicyError('ARTIFACT_SIGNATURE_MISMATCH');
  return format;
}

/** A filesystem-safe basename: no directories, no traversal, no control or reserved characters. */
export function safeArtifactFileName(raw, fallback = 'document') {
  const base = path.basename(String(raw ?? '').replace(/\\/g, '/'));
  const cleaned = base.replace(/[\u0000-\u001f\u007f<>:"/\\|?*]/g, '_').replace(/^\.+/, '').slice(0, 120);
  return cleaned && cleaned !== '.' && cleaned !== '..' ? cleaned : fallback;
}
