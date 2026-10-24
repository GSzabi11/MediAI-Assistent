"""MediAI hangszolgáltatás (FastAPI) — a hangprototípus bekötése a fő alkalmazásba.

    POST /profiles   Spring → Python: hangprofil a felolvasott mondatokból (sorba áll, 202)
    POST /recordings Spring → Python: felvétel átírása, szerepkiosztás, MedGemma-átnézés (202)
    POST /recording-summaries  Spring → Python: összefoglaló az átiratból + kulcsszavakból + csoportokból (202)
    POST /recording-findings   Spring → Python: csoportok (panasz, diagnózis, beutaló, …) az átiratból (202)
    GET  /health     sor állapota

A hang nem élőben jön: a böngésző a felvétel végén a Springnek tölti fel, a Spring a fájl útját
adja át (a két szolgáltatás egy gépen fut), az eredmény callbackkel megy vissza. A belső token
ugyanaz, mint a docproc-nál. Ld. docs/voice-module.md.

Indítás (az ai_env-ből):  python service.py   → http://127.0.0.1:8002
"""
import hmac
import logging

import uvicorn
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import voice_config as config
from worker import EnrollmentJob, FindingsJob, JobQueue, RecordingJob, SummaryJob

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="MediAI voice")
jobs = JobQueue()


def _check_token(token: str | None) -> None:
    if token is None or not hmac.compare_digest(token.encode(), config.TOKEN.encode()):
        raise HTTPException(status_code=401, detail="Érvénytelen vagy hiányzó belső token")


@app.on_event("startup")
def _start_worker() -> None:
    jobs.start()


class EnrollmentRequest(BaseModel):
    profileId: int = Field(gt=0)
    sentencePaths: list[str] = Field(min_length=1, max_length=12)
    noisePath: str | None = None
    outputWavPath: str = Field(min_length=1)
    callbackUrl: str = Field(pattern=r"^https?://")


@app.post("/profiles", status_code=202)
def submit_profile(req: EnrollmentRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(EnrollmentJob(req.profileId, req.sentencePaths, req.noisePath,
                                         req.outputWavPath, req.callbackUrl))
    if position is None:
        return JSONResponse(status_code=202, content={"profileId": req.profileId, "alreadyQueued": True})
    return {"profileId": req.profileId, "queuePosition": position}


class RecordingRequest(BaseModel):
    recordingId: int = Field(gt=0)
    audioPath: str = Field(min_length=1)
    mode: str = Field(pattern=r"^(SBO|ECHO)$")
    doctorEmbedding: list[float] = Field(min_length=256, max_length=256)
    callbackUrl: str = Field(pattern=r"^https?://")


@app.post("/recordings", status_code=202)
def submit_recording(req: RecordingRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(RecordingJob(req.recordingId, req.audioPath, req.mode, req.doctorEmbedding,
                                        req.callbackUrl))
    if position is None:
        return JSONResponse(status_code=202, content={"recordingId": req.recordingId, "alreadyQueued": True})
    return {"recordingId": req.recordingId, "queuePosition": position}


class SummaryTurn(BaseModel):
    turnId: int = Field(ge=0)
    speaker: str = Field(pattern=r"^(DOCTOR|PATIENT|OTHER)$")
    text: str


class SummaryFinding(BaseModel):
    category: str = Field(pattern=r"^(COMPLAINT|DIAGNOSIS|REFERRAL|EXAM_FINDING|MEDICATION|RECOMMENDATION)$")
    text: str = Field(min_length=1, max_length=1000)
    bnoCode: str | None = None
    turnIds: list[int] = Field(default_factory=list, max_length=50)


class SummaryRequest(BaseModel):
    summaryId: int = Field(gt=0)
    mode: str = Field(pattern=r"^(SBO|ECHO)$")
    utterances: list[SummaryTurn] = Field(min_length=1, max_length=5000)
    keywords: list[str] = Field(default_factory=list, max_length=100)
    findings: list[SummaryFinding] = Field(default_factory=list, max_length=200)
    callbackUrl: str = Field(pattern=r"^https?://")


@app.post("/recording-summaries", status_code=202)
def submit_summary(req: SummaryRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(SummaryJob(req.summaryId, req.mode, [u.model_dump() for u in req.utterances],
                                      req.keywords, req.callbackUrl,
                                      tuple(f.model_dump() for f in req.findings)))
    if position is None:
        return JSONResponse(status_code=202, content={"summaryId": req.summaryId, "alreadyQueued": True})
    return {"summaryId": req.summaryId, "queuePosition": position}


class FindingsRequest(BaseModel):
    extractionId: int = Field(gt=0)
    mode: str = Field(pattern=r"^(SBO|ECHO)$")
    utterances: list[SummaryTurn] = Field(min_length=1, max_length=5000)
    callbackUrl: str = Field(pattern=r"^https?://")


@app.post("/recording-findings", status_code=202)
def submit_findings(req: FindingsRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(FindingsJob(req.extractionId, req.mode, [u.model_dump() for u in req.utterances],
                                       req.callbackUrl))
    if position is None:
        return JSONResponse(status_code=202, content={"extractionId": req.extractionId, "alreadyQueued": True})
    return {"extractionId": req.extractionId, "queuePosition": position}


@app.get("/health")
def health():
    import beszed_pipeline as bp
    return {"status": "UP", "version": config.SERVICE_VERSION, "asrModel": bp.ASR_MODEL, "llmModel": bp.LLM_MODEL,
            "device": bp.DEVICE, **jobs.status()}


if __name__ == "__main__":
    uvicorn.run(app, host=config.HOST, port=config.PORT)
