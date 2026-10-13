/**
 * Hangfelvétel a böngészőben (MediaRecorder) + élő hangerőszint (Web Audio). A hang nem
 * élőben megy a szerverre: a felvétel végén egyben töltjük fel (docs/voice-module.md).
 * A minőség valódi ellenőrzése (VAD, zajarány) a Python hangszolgáltatásban fut; itt csak
 * a durva hibákat jelezzük azonnal (túl rövid, túl halk), hogy ne kelljen a végén újrakezdeni.
 */

export interface Felvevo {
  /** 0–1 közötti pillanatnyi szint (RMS, a mérőhöz) */
  szint(): number;
  /** a felvétel eddigi csúcsértéke (0–1) */
  csucs(): number;
  /** eltelt idő másodpercben */
  hossz(): number;
  stop(): Promise<Blob>;
  /** felvétel eldobása, a mikrofon elengedése */
  megszakit(): void;
}

const MIME_PREFERENCIA = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/webm', 'audio/mp4'];

export function felvetelTamogatott(): boolean {
  return typeof window !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof MediaRecorder !== 'undefined';
}

export async function mikrofonNeve(stream: MediaStream): Promise<string | null> {
  return stream.getAudioTracks()[0]?.label || null;
}

/**
 * A mikrofon megnyitása. A böngésző zajszűrését és automatikus erősítését kikapcsoljuk: a
 * hanglenyomat és a diarizáció a nyers hangon pontosabb, és a profil ugyanígy készül.
 */
export async function mikrofonMegnyitasa(): Promise<MediaStream> {
  if (!felvetelTamogatott()) {
    throw new Error('Ez a böngésző nem támogatja a hangfelvételt (MediaRecorder).');
  }
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });
  } catch (e) {
    const name = (e as DOMException).name;
    if (name === 'NotAllowedError') throw new Error('A mikrofon használata nincs engedélyezve a böngészőben.');
    if (name === 'NotFoundError') throw new Error('Nem található mikrofon.');
    throw new Error(`A mikrofon nem nyitható meg: ${(e as Error).message}`);
  }
}

export function felvetelInditasa(stream: MediaStream): Felvevo {
  const mimeType = MIME_PREFERENCIA.find((m) => MediaRecorder.isTypeSupported(m));
  const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
  const chunks: Blob[] = [];
  recorder.ondataavailable = (e) => e.data.size > 0 && chunks.push(e.data);

  const ctx = new AudioContext();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 2048;
  ctx.createMediaStreamSource(stream).connect(analyser);
  const buf = new Float32Array(analyser.fftSize);
  let peak = 0;
  let rms = 0;
  let timer: number | undefined = window.setInterval(() => {
    analyser.getFloatTimeDomainData(buf);
    let sum = 0;
    for (const v of buf) {
      sum += v * v;
      if (Math.abs(v) > peak) peak = Math.abs(v);
    }
    rms = Math.sqrt(sum / buf.length);
  }, 50);
  const started = performance.now();
  recorder.start(1000);

  const leallit = () => {
    if (timer !== undefined) window.clearInterval(timer);
    timer = undefined;
    void ctx.close();
  };

  return {
    szint: () => rms,
    csucs: () => peak,
    hossz: () => (performance.now() - started) / 1000,
    stop: () =>
      new Promise<Blob>((resolve) => {
        recorder.onstop = () => {
          leallit();
          resolve(new Blob(chunks, { type: recorder.mimeType || mimeType || 'audio/webm' }));
        };
        recorder.stop();
      }),
    megszakit: () => {
      leallit();
      if (recorder.state !== 'inactive') recorder.stop();
    },
  };
}

export function mikrofonElengedese(stream: MediaStream | null) {
  stream?.getTracks().forEach((t) => t.stop());
}
