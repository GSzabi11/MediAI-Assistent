/**
 * API-kliens réteg. A komponensek CSAK ezt az `api` objektumot használják, soha nem
 * hívnak fetch-et közvetlenül. Mind a 46 backend-végpont itt van bekötve; a DTO ↔
 * nézet leképezés a mappers.ts-ben, a gépi formátumok ellenőrzése az importok.ts-ben.
 */
import { getCurrentUser, type CurrentUser } from './currentUser';
import type {
  AccessLogDto,
  AllergyDto,
  ApprovalDto,
  ChunkDto,
  DocumentDto,
  LabResultDto,
  LoginDto,
  MedicalCareDto,
  MedicationDto,
  PatientCreateDto,
  PatientDto,
  PatientSearchDto,
  RecordingDto,
  StatementDto,
  UserCreateDto,
  UserDto,
  UtteranceDto,
  VoiceProfileDto,
  ClinicalSummaryDto,
  MedicalHistoryDto,
  EpicrisisDto,
  ChatMessageDto,
  KeywordDto,
  RecordingSummaryDto,
  ArticlePageDto,
  BookmarkDto,
  ResearchArticleDto,
  ResearchContextDto,
  ResearchDto,
} from './dto';
import { http } from './http';
import { parseMegszolalasok, parseRagDarabok } from './importok';
import {
  allapotDto,
  dokTipusDto,
  ellatasTipusDto,
  statusOf,
  toAllergia,
  toClaim,
  toDokumentum,
  toEllatas,
  toFelhasznalo,
  toFelvetel,
  toGyogyszer,
  toHangprofil,
  toJovahagyas,
  toLaborIdosor,
  toLaborReszlet,
  toMegszolalas,
  toNaplo,
  toPatient,
  toRagDarab,
  toSourceDto,
  toOsszefoglalo,
  toKortortenet,
  toEpikrizis,
  toChatValasz,
  toFelvetelOsszefoglalo,
  toKulcsszo,
  toCikk,
  toCikkOldal,
  toKonyvjelzo,
  toKutatas,
  toKutatasiOsszefoglalo,
} from './mappers';
import {
  ApiError,
  type ChatValasz,
  type ClaimResponse,
  type EllatasTipus,
  type FelvetelAllapot,
  type FelvetelFeltoltes,
  type Hangprofil,
  type JovahagyasRequest,
  type PatientSearch,
  type UjAllergia,
  type UjBeteg,
  type UjDokumentum,
  type UjFelvetel,
  type UjGyogyszer,
  type UjHangprofil,
  type UjIdezet,
  type UjLaborPanel,
  type KutatasForras,
  type CikkSorrend,
  type CikkRelevanciaSzuro,
} from './types';

/** A belépett felhasználó — minden írási művelet az ő nevében történik. */
function requireUser(): CurrentUser {
  const u = getCurrentUser();
  if (!u) throw new ApiError(401, 'Ehhez be kell jelentkezni.');
  return u;
}

const is404 = (e: unknown) => e instanceof ApiError && e.status === 404;

// ---------- felhasználónevek (jóváhagyó, naplósor) — GET /users/{id}, gyorsítótárazva ----------

const nevCache = new Map<number, Promise<string>>();

function felhasznaloNev(id: number): Promise<string> {
  let p = nevCache.get(id);
  if (!p) {
    p = http.get<UserDto>(`/users/${id}`).then(
      (u) => u.username,
      (e: unknown) => {
        if (!is404(e)) nevCache.delete(id); // hálózati hiba: legközelebb újrapróbálja
        return `felhasználó #${id}`;
      },
    );
    nevCache.set(id, p);
  }
  return p;
}

async function nevek(ids: (number | null)[]): Promise<Map<number, string>> {
  const egyedi = [...new Set(ids.filter((id): id is number => id !== null))];
  const parok = await Promise.all(egyedi.map(async (id) => [id, await felhasznaloNev(id)] as const));
  return new Map(parok);
}

async function toClaims(statements: StatementDto[]): Promise<ClaimResponse[]> {
  const n = await nevek(statements.map((s) => s.reviewedBy));
  return statements.map((s) => toClaim(s, s.reviewedBy === null ? null : (n.get(s.reviewedBy) ?? null)));
}

function toSearchDto(q: PatientSearch): PatientSearchDto {
  return q.mod === 'taj'
    ? { taj: q.taj }
    : { name: q.nev.trim(), motherName: q.anyjaNeve.trim(), birthDate: q.szuletesiDatum };
}

const toCurrentUser = (u: UserDto): CurrentUser => ({ id: u.id, username: u.username, role: u.role });

/** A MediaRecorder típusa szerinti kiterjesztés (a backend úgyis a bájtokból dönt) */
function hangKiterjesztes(b: Blob): string {
  if (b.type.includes('ogg')) return 'ogg';
  if (b.type.includes('mp4')) return 'm4a';
  if (b.type.includes('wav')) return 'wav';
  return 'webm';
}

export const api = {
  // ======================= belépés, felhasználók =======================

  /** POST /auth/login — a jelszó csak a törzsben megy, sosem URL-ben */
  belep: async (req: LoginDto) => toCurrentUser(await http.post<UserDto>('/auth/login', req)),

  /** POST /users */
  regisztral: async (req: UserCreateDto) => toCurrentUser(await http.post<UserDto>('/users', req)),

  /** GET /users/{id} */
  getFelhasznalo: async (id: number) => toFelhasznalo(await http.get<UserDto>(`/users/${id}`)),

  /** GET /users?role= — szerepkör nélkül mindenki */
  listFelhasznalok: async (szerepkor?: 'DOCTOR' | 'ADMIN') =>
    (await http.get<UserDto[]>(szerepkor ? `/users?role=${szerepkor}` : '/users')).map(toFelhasznalo),

  /** POST /users/search — felhasználónévre vagy emailre (törzsben: az email személyes adat); nincs → null */
  keresFelhasznalo: async (q: { felhasznalonev?: string; email?: string }) => {
    try {
      return toFelhasznalo(await http.post<UserDto>('/users/search', { username: q.felhasznalonev, email: q.email }));
    } catch (e) {
      if (is404(e)) return null;
      throw e;
    }
  },

  felhasznaloNev,

  // ======================= hangprofil =======================

  /** GET /voice-profiles/{userId}/active — nincs aktív profil → null */
  getAktivHangprofil: async (userId: number): Promise<Hangprofil | null> => {
    try {
      return toHangprofil(await http.get<VoiceProfileDto>(`/voice-profiles/${userId}/active`));
    } catch (e) {
      if (is404(e)) return null;
      throw e;
    }
  },

  /** GET /voice-profiles/{userId}/history */
  listHangprofilok: async (userId: number) =>
    (await http.get<VoiceProfileDto[]>(`/voice-profiles/${userId}/history`)).map(toHangprofil),

  /** GET /voice-profiles/{id} — a készülő profil állapotának követése */
  getHangprofil: async (id: number) => toHangprofil(await http.get<VoiceProfileDto>(`/voice-profiles/${id}`)),

  /**
   * POST /voice-profiles (multipart, 202) — a felolvasott mondatokból a Python hangszolgáltatás
   * számolja a hanglenyomatot; kész profilnál a korábbi aktív automatikusan inaktív lesz.
   */
  createHangprofil: async (req: UjHangprofil) => {
    const form = new FormData();
    form.append('userId', String(req.felhasznaloId));
    req.mondatok.forEach((b, i) => form.append('sentences', b, `mondat-${i + 1}.${hangKiterjesztes(b)}`));
    if (req.csend) form.append('noise', req.csend, `csend.${hangKiterjesztes(req.csend)}`);
    if (req.mikrofon) form.append('microphone', req.mikrofon);
    return toHangprofil(await http.postForm<VoiceProfileDto>('/voice-profiles', form));
  },

  // ======================= beteg =======================

  /** GET /patients */
  listPatients: async () => (await http.get<PatientDto[]>('/patients')).map(toPatient),

  /** POST /patients/search — érzékeny keresés: POST + törzs, sosem query string (CLAUDE.md) */
  searchPatients: async (q: PatientSearch) =>
    (await http.post<PatientDto[]>('/patients/search', toSearchDto(q))).map(toPatient),

  /** GET /patients/{id} */
  getPatient: async (id: number) => toPatient(await http.get<PatientDto>(`/patients/${id}`)),

  /** POST /patients */
  createPatient: async (b: UjBeteg) =>
    toPatient(
      await http.post<PatientDto>('/patients', {
        name: b.nev.trim(),
        taj: b.taj,
        motherName: b.anyjaNeve.trim(),
        birthDate: b.szuletesiDatum,
        address: b.lakcim?.trim() || null,
      } satisfies PatientCreateDto),
    ),

  // ======================= ellátás =======================

  /** GET /medical-cares?patientId= */
  listEllatasok: async (patientId: number) =>
    (await http.get<MedicalCareDto[]>(`/medical-cares?patientId=${patientId}`)).map(toEllatas),

  /** GET /medical-cares/{id} */
  getEllatas: async (id: number) => toEllatas(await http.get<MedicalCareDto>(`/medical-cares/${id}`)),

  /** POST /medical-cares — a belépett orvos nevében */
  createEllatas: async (patientId: number, tipus: EllatasTipus) =>
    toEllatas(
      await http.post<MedicalCareDto>('/medical-cares', {
        patientId,
        userId: requireUser().id,
        type: ellatasTipusDto[tipus],
      }),
    ),

  /** POST /medical-cares/{id}/close — egyszer sikerül */
  lezarEllatas: async (id: number) => toEllatas(await http.post<MedicalCareDto>(`/medical-cares/${id}/close`)),

  // ======================= felvétel + megszólalás =======================

  /** GET /recordings?patientId= */
  listFelvetelek: async (patientId: number) =>
    (await http.get<RecordingDto[]>(`/recordings?patientId=${patientId}`)).map(toFelvetel),

  /** GET /recordings/{id} */
  getFelvetel: async (id: number) => toFelvetel(await http.get<RecordingDto>(`/recordings/${id}`)),

  /** POST /recordings — a belépett orvos nevében; a hangprofilt a backend választja */
  createFelvetel: async (f: UjFelvetel) =>
    toFelvetel(
      await http.post<RecordingDto>('/recordings', {
        patientId: f.patientId,
        userId: requireUser().id,
        medicalCareId: f.ellatasId,
        mode: f.mod === 'surgossegi' ? 'SBO' : 'ECHO',
        audioPath: f.hangfajl.trim(),
        asrModel: f.asrModell.trim(),
      }),
    ),

  /**
   * POST /recordings (multipart, 202) — a böngészőben rögzített hang; a hangszolgáltatás átírja,
   * szétválasztja a beszélőket, a MedGemma átnézi. Hangprofil nélkül 409.
   */
  feltoltFelvetel: async (f: FelvetelFeltoltes) => {
    const form = new FormData();
    form.append('patientId', String(f.patientId));
    form.append('userId', String(requireUser().id));
    if (f.ellatasId !== null) form.append('medicalCareId', String(f.ellatasId));
    form.append('mode', f.mod === 'surgossegi' ? 'SBO' : 'ECHO');
    form.append('durationSec', String(Math.max(1, Math.round(f.hosszMp))));
    form.append('audio', f.hang, `felvetel.${hangKiterjesztes(f.hang)}`);
    return toFelvetel(await http.postForm<RecordingDto>('/recordings', form));
  },

  /** GET /recordings/{id}/keywords — az AI-javaslatok és az orvos kulcsszavai */
  listKulcsszavak: async (felvetelId: number) =>
    (await http.get<KeywordDto[]>(`/recordings/${felvetelId}/keywords`)).map(toKulcsszo),

  /** POST /recordings/{id}/keywords — a + gomb; csak orvos, csak kész felvételhez */
  addKulcsszo: async (felvetelId: number, kifejezes: string) =>
    toKulcsszo(
      await http.post<KeywordDto>(`/recordings/${felvetelId}/keywords`, {
        userId: requireUser().id,
        term: kifejezes.trim(),
      }),
    ),

  /** POST /recordings/{id}/keywords/{kid}/decision — AI-javaslat elfogadása / elutasítása */
  dontKulcsszo: async (felvetelId: number, kulcsszoId: number, elfogad: boolean) =>
    toKulcsszo(
      await http.post<KeywordDto>(`/recordings/${felvetelId}/keywords/${kulcsszoId}/decision`, {
        userId: requireUser().id,
        status: elfogad ? 'ACCEPTED' : 'REJECTED',
      }),
    ),

  /** GET /recordings/{id}/summaries — a legfrissebb elöl */
  listFelvetelOsszefoglalok: async (felvetelId: number) =>
    (await http.get<RecordingSummaryDto[]>(`/recordings/${felvetelId}/summaries`)).map(toFelvetelOsszefoglalo),

  /** POST /recordings/{id}/summaries (202) — újragenerálás a teljes átiratból + a kulcsszavakból */
  kerFelvetelOsszefoglalo: async (felvetelId: number) =>
    toFelvetelOsszefoglalo(
      await http.post<RecordingSummaryDto>(`/recordings/${felvetelId}/summaries`, { userId: requireUser().id }),
    ),

  /** PUT /recordings/{id}/summaries/{sid}/edited-text — az orvos szerkesztése; az AI eredetije megmarad */
  szerkesztFelvetelOsszefoglalo: async (felvetelId: number, osszefoglaloId: number, szoveg: string) =>
    toFelvetelOsszefoglalo(
      await http.put<RecordingSummaryDto>(`/recordings/${felvetelId}/summaries/${osszefoglaloId}/edited-text`, {
        userId: requireUser().id,
        text: szoveg,
      }),
    ),

  /** PATCH /recordings/{id}/status — PROCESSING-hez kötelező a hossz (másodperc) */
  setFelvetelAllapot: async (id: number, allapot: FelvetelAllapot, hosszMp?: number) =>
    toFelvetel(
      await http.patch<RecordingDto>(`/recordings/${id}/status`, {
        status: allapotDto[allapot],
        durationSec: hosszMp,
      }),
    ),

  /** GET /recordings/{id}/utterances */
  listMegszolalasok: async (felvetelId: number) =>
    (await http.get<UtteranceDto[]>(`/recordings/${felvetelId}/utterances`)).map(toMegszolalas),

  /** POST /recordings/{id}/utterances — backend- vagy Python-pipeline JSON-ból; visszaadja a darabszámot */
  importMegszolalasok: async (felvetelId: number, json: unknown) => {
    const utterances = parseMegszolalasok(json);
    const res = await http.post<UtteranceDto[]>(`/recordings/${felvetelId}/utterances`, { utterances });
    return res.length;
  },

  // ======================= dokumentum + RAG-darab =======================

  /** GET /documents?patientId= */
  listDokumentumok: async (patientId: number) =>
    (await http.get<DocumentDto[]>(`/documents?patientId=${patientId}`)).map(toDokumentum),

  /** GET /documents/{id} (+ GET /documents/{id}/text, ha van kinyert szöveg) */
  getDokumentum: async (id: number) => {
    const d = toDokumentum(await http.get<DocumentDto>(`/documents/${id}`));
    const szoveg = d.vanSzoveg ? await http.getText(`/documents/${id}/text`) : null;
    return { ...d, szoveg };
  },

  /**
   * POST /documents (multipart) — a hash-t, méretet, MIME-típust a szerver számolja.
   * PDF: típus és dátum nélkül (202, a háttérben az AI-feldolgozás sorolja be és tölti ki);
   * más fájl: a típus kötelező (201).
   */
  feltoltDokumentum: async (d: UjDokumentum) => {
    const form = new FormData();
    form.append('file', d.fajl);
    form.append('patientId', String(d.patientId));
    if (d.tipus !== null) form.append('type', dokTipusDto[d.tipus]);
    if (d.tipus !== null && d.klinikaiDatum) form.append('clinicalDate', d.klinikaiDatum);
    return toDokumentum(await http.postForm<DocumentDto>('/documents', form));
  },

  /** POST /documents/{id}/reprocess — sikertelen automatikus feldolgozás újraindítása (202) */
  ujraFeldolgoz: async (id: number) => toDokumentum(await http.post<DocumentDto>(`/documents/${id}/reprocess`)),

  /** PUT /documents/{id}/text — egyszer állítható be (utána 409) */
  setKinyertSzoveg: async (id: number, szoveg: string) =>
    toDokumentum(await http.put<DocumentDto>(`/documents/${id}/text`, { text: szoveg })),

  /** GET /documents/{id}/file — fetch-csel, hogy az X-User-Id is menjen (a naplóban EXPORT) */
  letoltFajl: (id: number) => http.getBlob(`/documents/${id}/file`),

  /** GET /documents/{id}/chunks */
  listRagDarabok: async (dokumentumId: number) =>
    (await http.get<ChunkDto[]>(`/documents/${dokumentumId}/chunks`)).map(toRagDarab),

  /** POST /documents/{id}/chunks — a kinyerő/embedder JSON-kimenetéből; visszaadja a darabszámot */
  importRagDarabok: async (dokumentumId: number, json: unknown) => {
    const batch = parseRagDarabok(json);
    return (await http.post<ChunkDto[]>(`/documents/${dokumentumId}/chunks`, batch)).length;
  },

  // ======================= állítás + jóváhagyás =======================

  /** GET /statements?patientId= */
  listAllitasok: async (patientId: number) =>
    toClaims(await http.get<StatementDto[]>(`/statements?patientId=${patientId}`)),

  /** GET /statements/{id} — a backend számolja a legfrissebb döntést (reviewStatus) */
  getAllitas: async (id: number) => (await toClaims([await http.get<StatementDto>(`/statements/${id}`)]))[0],

  /**
   * POST /statements — kézzel kijelölt, szó szerinti idézet (RETRIEVED_FACT). Az előállító
   * a kijelölést végző felhasználó: az állításnak nincs saját „ki hozta létre” mezője.
   */
  createIdezet: async (q: UjIdezet) =>
    (
      await toClaims([
        await http.post<StatementDto>('/statements', {
          patientId: q.patientId,
          type: 'RETRIEVED_FACT',
          text: q.szoveg,
          label: q.cimke?.trim() || null,
          severity: q.sulyossag.toUpperCase() as 'INFO' | 'WARNING' | 'CRITICAL',
          uncertainty: null,
          source: toSourceDto(q.forras),
          producer: {
            type: 'EXTRACTOR',
            extractorName: `kézi kijelölés (${requireUser().username})`,
            extractorVersion: 'frontend-1',
          },
          clinicalTime: null,
        }),
      ])
    )[0],

  /** POST /statements/{id}/approvals — a belépett orvos nevében */
  jovahagy: async (allitasId: number, req: JovahagyasRequest) => {
    const user = requireUser();
    const a = await http.post<ApprovalDto>(`/statements/${allitasId}/approvals`, {
      userId: user.id,
      status: statusOf[req.dontes],
      modifiedText: req.dontes === 'modositva' ? req.modositottSzoveg : undefined,
    });
    return toJovahagyas(a, await felhasznaloNev(a.userId));
  },

  /** GET /statements/{id}/approvals — a döntések előzménye, legújabb elöl */
  listJovahagyasok: async (allitasId: number) => {
    const lista = await http.get<ApprovalDto[]>(`/statements/${allitasId}/approvals`);
    const n = await nevek(lista.map((a) => a.userId));
    return lista.map((a) => toJovahagyas(a, n.get(a.userId) ?? `felhasználó #${a.userId}`));
  },

  // ======================= labor =======================

  /** GET /lab-results?patientId= (&name= — egy vizsgálat trendje, a szerver szűr) */
  getLaborIdosor: async (patientId: number, nev?: string) =>
    toLaborIdosor(
      patientId,
      await http.get<LabResultDto[]>(
        `/lab-results?patientId=${patientId}${nev ? `&name=${encodeURIComponent(nev)}` : ''}`,
      ),
    ),

  /** GET /lab-results/{id} */
  getLaborMeres: async (id: number) => toLaborReszlet(await http.get<LabResultDto>(`/lab-results/${id}`)),

  // ======================= klinikai státusz =======================

  /** GET /clinical-summaries?patientId= — a legfrissebb elöl */
  listOsszefoglalok: async (patientId: number) =>
    (await http.get<ClinicalSummaryDto[]>(`/clinical-summaries?patientId=${patientId}`)).map(toOsszefoglalo),

  /** POST /clinical-summaries — a belépett orvos nevében; 202, a háttérben készül */
  keszitOsszefoglalot: async (patientId: number) =>
    toOsszefoglalo(await http.post<ClinicalSummaryDto>('/clinical-summaries', { patientId, userId: requireUser().id })),

  // ======================= kórtörténet (Medical history) =======================

  /** GET /medical-histories?patientId= — a legfrissebb elöl */
  listKortortenetek: async (patientId: number) =>
    (await http.get<MedicalHistoryDto[]>(`/medical-histories?patientId=${patientId}`)).map(toKortortenet),

  /** POST /medical-histories — 202, a háttérben készül (kivonatok, idővonal, összefoglaló) */
  keszitKortortenetet: async (patientId: number) =>
    toKortortenet(await http.post<MedicalHistoryDto>('/medical-histories', { patientId, userId: requireUser().id })),

  /** PUT /medical-histories/{id}/edited-html — az orvos szerkesztése; az AI eredetije megmarad */
  szerkesztKortortenetet: async (id: number, html: string) =>
    toKortortenet(
      await http.put<MedicalHistoryDto>(`/medical-histories/${id}/edited-html`, { userId: requireUser().id, html }),
    ),

  /** GET /medical-histories/{id}/file — DOCX (a naplóban EXPORT) */
  letoltKortortenetDocx: (id: number) => http.getBlob(`/medical-histories/${id}/file`),

  // ======================= epikrízis (ellátásonként) =======================

  /** GET /epicrises?patientId= — a beteg összes epikrízise, a legfrissebb elöl */
  listEpikrizisek: async (patientId: number) =>
    (await http.get<EpicrisisDto[]>(`/epicrises?patientId=${patientId}`)).map(toEpikrizis),

  /** POST /epicrises — 202, a háttérben készül az ellátás leleteiből, felvételeiből és az aktuális gyógyszerekből */
  keszitEpikrizist: async (ellatasId: number) =>
    toEpikrizis(await http.post<EpicrisisDto>('/epicrises', { medicalCareId: ellatasId, userId: requireUser().id })),

  /** PUT /epicrises/{id}/edited-html — az orvos szerkesztése (a fejléc nem része); az AI eredetije megmarad */
  szerkesztEpikrizist: async (id: number, html: string) =>
    toEpikrizis(await http.put<EpicrisisDto>(`/epicrises/${id}/edited-html`, { userId: requireUser().id, html })),

  /** GET /epicrises/{id}/file — DOCX (a naplóban EXPORT) */
  letoltEpikrizisDocx: (id: number) => http.getBlob(`/epicrises/${id}/file`),

  /** POST /lab-results — egy panel egy tranzakcióban: hibás sornál egyik sem kerül be */
  createLaborPanel: async (p: UjLaborPanel) =>
    (
      await http.post<LabResultDto[]>('/lab-results', {
        patientId: p.patientId,
        documentId: p.dokumentumId,
        results: p.sorok.map((s) => ({
          groupName: s.csoport,
          name: s.nev,
          value: s.ertek,
          unit: s.mertekegyseg,
          refLow: s.refAlso,
          refHigh: s.refFelso,
          measuredAt: s.idopont,
          code: s.kod,
        })),
      })
    ).length,

  // ======================= gyógyszer + allergia =======================

  /** GET /medications?patientId=&activeOnly= */
  listGyogyszerek: async (patientId: number, csakAktiv: boolean) =>
    (await http.get<MedicationDto[]>(`/medications?patientId=${patientId}&activeOnly=${csakAktiv}`)).map(toGyogyszer),

  /** GET /medications/{id} */
  getGyogyszer: async (id: number) => toGyogyszer(await http.get<MedicationDto>(`/medications/${id}`)),

  /** POST /medications — forrás csak jóváhagyott állítás lehet (a backend ellenőrzi) */
  createGyogyszer: async (g: UjGyogyszer) =>
    toGyogyszer(
      await http.post<MedicationDto>('/medications', {
        patientId: g.patientId,
        name: g.nev.trim(),
        activeIngredient: g.hatoanyag?.trim() || null,
        dose: g.dozis,
        doseUnit: g.dozisEgyseg?.trim() || null,
        route: g.alkalmazas?.trim() || null,
        sourceStatementId: g.forrasAllitasId,
      }),
    ),

  /** POST /medications/{id}/discontinue — egyirányú */
  leallitGyogyszer: async (id: number) => toGyogyszer(await http.post<MedicationDto>(`/medications/${id}/discontinue`)),

  /** GET /allergies?patientId= */
  listAllergiak: async (patientId: number) =>
    (await http.get<AllergyDto[]>(`/allergies?patientId=${patientId}`)).map(toAllergia),

  /** GET /allergies/{id} */
  getAllergia: async (id: number) => toAllergia(await http.get<AllergyDto>(`/allergies/${id}`)),

  /** POST /allergies */
  createAllergia: async (a: UjAllergia) =>
    toAllergia(
      await http.post<AllergyDto>('/allergies', {
        patientId: a.patientId,
        substance: a.anyag.trim(),
        sourceStatementId: a.forrasAllitasId,
      }),
    ),

  // ======================= hozzáférési napló =======================

  /** GET /access-logs?userId= — mit nézett a felhasználó */
  listNaploFelhasznalora: async (userId: number) =>
    (await http.get<AccessLogDto[]>(`/access-logs?userId=${userId}`)).map(toNaplo),

  // ======================= klinikai asszisztens (chat) =======================

  /** GET /chat-messages/{id} — a készülő válasz állapota */
  getChat: async (id: number) => toChatValasz(await http.get<ChatMessageDto>(`/chat-messages/${id}`)),

  /**
   * POST /chat-messages — a belépett orvos nevében; 202, a háttérben készül (getChat-tel követhető).
   * A kérdés a törzsben megy (érzékeny adat lehet), sosem query stringben. `elozmenyIds`: a
   * beszélgetés előző (legfeljebb 3) kész válasza — a modell ezekből érti a visszautaló kérdést.
   */
  kerdez: async (patientId: number, kerdes: string, elozmenyIds: number[]): Promise<ChatValasz> =>
    toChatValasz(
      await http.post<ChatMessageDto>('/chat-messages', {
        patientId,
        userId: requireUser().id,
        question: kerdes,
        previousMessageIds: elozmenyIds,
      }),
    ),

  // ======================= szakirodalmi kutatás =======================

  /** GET /research-contexts?patientId= — a legfrissebb elöl */
  listKutatasiOsszefoglalok: async (patientId: number) =>
    (await http.get<ResearchContextDto[]>(`/research-contexts?patientId=${patientId}`)).map(toKutatasiOsszefoglalo),

  /** POST /research-contexts — 202, a helyi modell a háttérben írja */
  keszitKutatasiOsszefoglalot: async (patientId: number) =>
    toKutatasiOsszefoglalo(
      await http.post<ResearchContextDto>('/research-contexts', { patientId, userId: requireUser().id }),
    ),

  /** PUT /research-contexts/{id}/edited-text — az orvos szerkesztése (az AI eredetije megmarad) */
  szerkesztKutatasiOsszefoglalot: async (id: number, szoveg: string, kifejezes: string) =>
    toKutatasiOsszefoglalo(
      await http.put<ResearchContextDto>(`/research-contexts/${id}/edited-text`, {
        userId: requireUser().id,
        text: szoveg,
        query: kifejezes,
      }),
    ),

  /** GET /research?patientId= — korábbi kutatások */
  listKutatasok: async (patientId: number) =>
    (await http.get<ResearchDto[]>(`/research?patientId=${patientId}`)).map(toKutatas),

  getKutatas: async (id: number) => toKutatas(await http.get<ResearchDto>(`/research/${id}`)),

  /** POST /research — CSAK a kifejezés megy ki a hálózatra (a backend kapuja ellenőrzi) */
  inditKutatast: async (k: {
    patientId: number;
    osszefoglaloId: number | null;
    kontextus: string;
    keres: string;
    kifejezes: string;
    forrasok: KutatasForras[];
    maxTalalat: number;
  }) =>
    toKutatas(
      await http.post<ResearchDto>('/research', {
        patientId: k.patientId,
        userId: requireUser().id,
        contextId: k.osszefoglaloId,
        contextText: k.kontextus,
        extraPrompt: k.keres || null,
        query: k.kifejezes,
        sources: k.forrasok,
        maxResults: k.maxTalalat,
      }),
    ),

  /** GET /research/{id}/articles?page=&filter=&order= — lapozható; AI-rangsor vagy a forrás sorrendje */
  getCikkOldal: async (
    kutatasId: number,
    oldal: number,
    szuro: 'ACTIVE' | 'BOOKMARKED' | 'EXCLUDED',
    sorrend: CikkSorrend = 'AI',
    relevancia: CikkRelevanciaSzuro = 'ALL',
  ) =>
    toCikkOldal(
      await http.get<ArticlePageDto>(
        `/research/${kutatasId}/articles?page=${oldal}&filter=${szuro}&order=${sorrend}&relevance=${relevancia}`,
      ),
    ),

  /** POST /research/{id}/ranking — az AI-rangsor újrakérése (pl. hiba vagy kivételek után) */
  ujraRangsorol: async (kutatasId: number) => toKutatas(await http.post<ResearchDto>(`/research/${kutatasId}/ranking`)),

  /** POST /research/{id}/evaluations — a megnyitott oldal még nem értékelt cikkei */
  ertekelCikkeket: async (kutatasId: number, cikkIds: number[]) =>
    toKutatas(await http.post<ResearchDto>(`/research/${kutatasId}/evaluations`, { articleIds: cikkIds })),

  /** PATCH /research/{id}/articles/{aid} — kivétel / könyvjelző */
  modositCikket: async (kutatasId: number, cikkId: number, m: { kivett?: boolean; konyvjelzo?: boolean }) =>
    toCikk(
      await http.patch<ResearchArticleDto>(`/research/${kutatasId}/articles/${cikkId}`, {
        excluded: m.kivett,
        bookmarked: m.konyvjelzo,
      }),
    ),

  /** GET /research/bookmarks?patientId= — a beteg könyvjelzős cikkei */
  listKonyvjelzok: async (patientId: number) =>
    (await http.get<BookmarkDto[]>(`/research/bookmarks?patientId=${patientId}`)).map(toKonyvjelzo),
};

export type Api = typeof api;
