/**
 * Gépi formátumok (Python hangmodul, RAG-darabolás, kinyerő) importja: ellenőrzés és
 * leképezés a backend kérés-formátumára, MIELŐTT a backendhez küldjük — így a hiba
 * itt, érthetően jelenik meg, és rossz szerkezetű adat nem is indul el.
 */
import type { ChunkBatchDto, SpeakerDto, StatementCreateDto, UtteranceCreateDto } from './dto';

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const isInt = (v: unknown): v is number => isNum(v) && Number.isInteger(v);

/** A Python `beszed_pipeline.py` szerepkör-kimenete → backend Speaker (voice-module.md) */
const pythonSzerep: Record<string, SpeakerDto> = {
  orvos: 'DOCTOR',
  beteg: 'PATIENT',
  egyéb: 'OTHER',
  egyeb: 'OTHER',
};

/**
 * Megszólalások két formátumban:
 *  - backend: `{ "utterances": [{ turnId, speaker, startMs, endMs, text, ... }] }`
 *  - Python `beszed_pipeline.py` kimenet: `{ "chat": [{ id, role, start, end, text }] }`
 *    → role: orvos/beteg/egyéb → DOCTOR/PATIENT/OTHER, start/end másodperc → ms
 */
export function parseMegszolalasok(json: unknown): UtteranceCreateDto[] {
  if (!isObj(json)) throw new Error('A fájl nem JSON-objektum.');

  if (Array.isArray(json.utterances)) {
    return json.utterances.map((u, i) => {
      if (!isObj(u)) throw new Error(`utterances[${i}]: nem objektum`);
      const speaker = u.speaker;
      if (speaker !== 'DOCTOR' && speaker !== 'PATIENT' && speaker !== 'OTHER') {
        throw new Error(`utterances[${i}].speaker: DOCTOR, PATIENT vagy OTHER lehet`);
      }
      if (!isInt(u.turnId) || !isInt(u.startMs) || !isInt(u.endMs) || typeof u.text !== 'string') {
        throw new Error(`utterances[${i}]: turnId, startMs, endMs egész szám, text szöveg kell`);
      }
      return {
        turnId: u.turnId,
        speaker,
        similarity: isNum(u.similarity) ? u.similarity : null,
        startMs: u.startMs,
        endMs: u.endMs,
        text: u.text,
        asrConfidence: isNum(u.asrConfidence) ? u.asrConfidence : null,
      };
    });
  }

  if (Array.isArray(json.chat)) {
    return json.chat.map((c, i) => {
      if (!isObj(c)) throw new Error(`chat[${i}]: nem objektum`);
      const speaker = typeof c.role === 'string' ? pythonSzerep[c.role] : undefined;
      if (!speaker) throw new Error(`chat[${i}].role: orvos, beteg vagy egyéb lehet (ez: ${String(c.role)})`);
      if (!isInt(c.id) || !isNum(c.start) || !isNum(c.end) || typeof c.text !== 'string') {
        throw new Error(`chat[${i}]: id egész szám, start/end másodperc, text szöveg kell`);
      }
      return {
        turnId: c.id,
        speaker,
        similarity: null,
        startMs: Math.round(c.start * 1000),
        endMs: Math.round(c.end * 1000),
        text: c.text,
        asrConfidence: null,
      };
    });
  }

  throw new Error('Ismeretlen formátum: "utterances" (backend) vagy "chat" (Python pipeline) tömb kell.');
}

/** RAG-darabok: `{ "embeddingModel": "...", "chunks": [{ charStart, charEnd, embedding: [1024 szám] }] }` */
export function parseRagDarabok(json: unknown): ChunkBatchDto {
  if (!isObj(json)) throw new Error('A fájl nem JSON-objektum.');
  if (typeof json.embeddingModel !== 'string' || !json.embeddingModel.trim()) {
    throw new Error('embeddingModel: kötelező (pl. "BAAI/bge-m3")');
  }
  if (!Array.isArray(json.chunks) || json.chunks.length === 0) throw new Error('chunks: nem üres tömb kell');
  const chunks = json.chunks.map((c, i) => {
    if (!isObj(c) || !isInt(c.charStart) || !isInt(c.charEnd)) {
      throw new Error(`chunks[${i}]: charStart és charEnd egész szám kell`);
    }
    if (!Array.isArray(c.embedding) || c.embedding.length !== 1024 || !c.embedding.every(isNum)) {
      throw new Error(`chunks[${i}].embedding: pontosan 1024 szám kell`);
    }
    return { charStart: c.charStart, charEnd: c.charEnd, embedding: c.embedding as number[] };
  });
  return { embeddingModel: json.embeddingModel, chunks };
}

/**
 * Állítások (kinyerő/modell kimenete) backend-formátumban: tömb vagy `{ "statements": [...] }`.
 * A `patientId` hiányozhat (az aktív beteg kerül bele); ha más beteget ad, az elem hibás —
 * az identitás-kaput itt is betartjuk, nem csak a backendben.
 */
export function parseAllitasok(json: unknown, patientId: number): (StatementCreateDto | Error)[] {
  const lista = Array.isArray(json) ? json : isObj(json) && Array.isArray(json.statements) ? json.statements : null;
  if (!lista) throw new Error('Állítások tömbje kell (vagy { "statements": [...] }).');
  return lista.map((s, i) => {
    if (!isObj(s)) return new Error(`[${i}]: nem objektum`);
    if (s.patientId !== undefined && s.patientId !== patientId) {
      return new Error(`[${i}]: más beteg állítása (patientId ${String(s.patientId)}) — kihagyva`);
    }
    if (s.type === 'DETERMINISTIC_RULE') {
      return new Error(`[${i}]: szabály-állítást csak a szabálymotor hozhat létre`);
    }
    return { ...(s as unknown as StatementCreateDto), patientId };
  });
}

/** JSON-fájl beolvasása (a böngészőben, feltöltés nélkül) */
export async function readJsonFile(file: File): Promise<unknown> {
  const text = await file.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${file.name}: nem érvényes JSON`);
  }
}
