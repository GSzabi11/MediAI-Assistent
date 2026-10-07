"""MediAI dokumentum-feldolgozó szolgáltatás (FastAPI).

    POST /jobs     Spring → Python: feldolgozás-kérés (sorba áll, 202)
    GET  /health   sor állapota

Indítás: python app.py   (ld. README.md)
"""
import hmac
import logging

import uvicorn
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import config
from worker import Job, JobQueue

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="MediAI docproc")
jobs = JobQueue()


class JobRequest(BaseModel):
    documentId: int = Field(gt=0)
    filePath: str = Field(min_length=1)
    callbackUrl: str = Field(pattern=r"^https?://")


def _check_token(token: str | None) -> None:
    if token is None or not hmac.compare_digest(token.encode(), config.TOKEN.encode()):
        raise HTTPException(status_code=401, detail="Érvénytelen vagy hiányzó belső token")


@app.on_event("startup")
def _start_worker() -> None:
    jobs.start()


@app.post("/jobs", status_code=202)
def submit(req: JobRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(Job(req.documentId, req.filePath, req.callbackUrl))
    if position is None:
        # Már sorban van / feldolgozás alatt: idempotens, nem dolgozzuk fel kétszer
        return JSONResponse(status_code=202, content={"documentId": req.documentId, "alreadyQueued": True})
    return {"documentId": req.documentId, "queuePosition": position}


@app.get("/health")
def health():
    return {"status": "UP", "model": config.LLM_MODEL, **jobs.status()}


if __name__ == "__main__":
    uvicorn.run(app, host=config.HOST, port=config.PORT)
