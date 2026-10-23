"""MediAI dokumentum-feldolgozó szolgáltatás (FastAPI).

    POST /jobs       Spring → Python: dokumentum-feldolgozás (sorba áll, 202)
    POST /summaries  Spring → Python: klinikai összefoglaló (ugyanabba a sorba, 202)
    POST /medical-histories  Spring → Python: kórtörténet (kivonatok, idővonal, összefoglaló; sorba, 202)
    POST /chats      Spring → Python: klinikai asszisztens kérdése (a sor elejére, 202)
    POST /research-contexts     Spring → Python: betegállapot-összefoglaló a kutatáshoz (sorba, 202)
    POST /research-searches     Spring → Python: szakirodalom-keresés (PubMed/iCite, külön szálon, 202)
    POST /research-evaluations  Spring → Python: cikkek értékelése (cikkenként a sorba, 202)
    POST /research-rankings     Spring → Python: az összes találat AI-rangsora (egy feladat a sorban, 202)
    GET  /health   sor állapota

Indítás: python app.py   (ld. README.md)
"""
import hmac
import logging
from typing import Literal

import uvicorn
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import config
from worker import (ArticleEvalJob, ChatJob, Job, JobQueue, MedicalHistoryJob, RankingJob, ResearchContextJob,
                    SearchJob, SearchRunner, SummaryJob)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="MediAI docproc")
jobs = JobQueue()
searches = SearchRunner()


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
    searches.start()


@app.post("/jobs", status_code=202)
def submit(req: JobRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(Job(req.documentId, req.filePath, req.callbackUrl))
    if position is None:
        # Már sorban van / feldolgozás alatt: idempotens, nem dolgozzuk fel kétszer
        return JSONResponse(status_code=202, content={"documentId": req.documentId, "alreadyQueued": True})
    return {"documentId": req.documentId, "queuePosition": position}


class SummaryRequest(BaseModel):
    summaryId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    facts: dict


@app.post("/summaries", status_code=202)
def submit_summary(req: SummaryRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(SummaryJob(req.summaryId, req.callbackUrl, req.facts))
    if position is None:
        return JSONResponse(status_code=202, content={"summaryId": req.summaryId, "alreadyQueued": True})
    return {"summaryId": req.summaryId, "queuePosition": position}


class MedicalHistoryRequest(BaseModel):
    historyId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    progressUrl: str | None = Field(default=None, pattern=r"^https?://")
    facts: dict


@app.post("/medical-histories", status_code=202)
def submit_medical_history(req: MedicalHistoryRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(MedicalHistoryJob(req.historyId, req.callbackUrl, req.facts, req.progressUrl))
    if position is None:
        return JSONResponse(status_code=202, content={"historyId": req.historyId, "alreadyQueued": True})
    return {"historyId": req.historyId, "queuePosition": position}


class ChatTurn(BaseModel):
    question: str
    answer: str


class ChatRequest(BaseModel):
    chatId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    retrieveUrl: str = Field(pattern=r"^https?://")
    question: str = Field(min_length=1, max_length=2000)
    history: list[ChatTurn] = Field(default_factory=list, max_length=3)
    facts: list[dict]


@app.post("/chats", status_code=202)
def submit_chat(req: ChatRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(ChatJob(req.chatId, req.callbackUrl, req.retrieveUrl, req.question,
                                   [t.model_dump() for t in req.history], req.facts))
    if position is None:
        return JSONResponse(status_code=202, content={"chatId": req.chatId, "alreadyQueued": True})
    return {"chatId": req.chatId, "queuePosition": position}


class ResearchContextRequest(BaseModel):
    contextId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    facts: dict


@app.post("/research-contexts", status_code=202)
def submit_research_context(req: ResearchContextRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = jobs.submit(ResearchContextJob(req.contextId, req.callbackUrl, req.facts))
    if position is None:
        return JSONResponse(status_code=202, content={"contextId": req.contextId, "alreadyQueued": True})
    return {"contextId": req.contextId, "queuePosition": position}


class SearchRequest(BaseModel):
    researchId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    progressUrl: str = Field(pattern=r"^https?://")
    query: str = Field(min_length=1, max_length=1000)
    sources: list[Literal["PUBMED", "COCHRANE", "EUROPE_PMC"]] = Field(min_length=1)
    maxResults: int = Field(ge=1, le=200)


@app.post("/research-searches", status_code=202)
def submit_search(req: SearchRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    position = searches.submit(SearchJob(req.researchId, req.callbackUrl, req.progressUrl, req.query,
                                         list(dict.fromkeys(req.sources)), req.maxResults))
    if position is None:
        return JSONResponse(status_code=202, content={"researchId": req.researchId, "alreadyQueued": True})
    return {"researchId": req.researchId, "queuePosition": position}


class EvalArticle(BaseModel):
    id: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    title: str
    abstractText: str | None = None
    journal: str | None = None
    pubYear: int | None = None
    publicationTypes: list[str] = Field(default_factory=list)


class EvaluationRequest(BaseModel):
    researchId: int = Field(gt=0)
    contextText: str = Field(min_length=1, max_length=8000)
    extraPrompt: str | None = Field(default=None, max_length=2000)
    articles: list[EvalArticle] = Field(min_length=1, max_length=50)


@app.post("/research-evaluations", status_code=202)
def submit_evaluations(req: EvaluationRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    queued = 0
    for a in req.articles:
        article = a.model_dump(exclude={"id", "callbackUrl", "abstractText"}) | {"abstract": a.abstractText}
        if jobs.submit(ArticleEvalJob(a.id, a.callbackUrl, article, req.contextText, req.extraPrompt)) is not None:
            queued += 1
    return {"researchId": req.researchId, "queued": queued}


class RankArticle(BaseModel):
    id: int = Field(gt=0)
    title: str
    abstractText: str | None = None
    journal: str | None = None
    pubYear: int | None = None
    publicationTypes: list[str] = Field(default_factory=list)


class RankingRequest(BaseModel):
    researchId: int = Field(gt=0)
    callbackUrl: str = Field(pattern=r"^https?://")
    progressUrl: str = Field(pattern=r"^https?://")
    contextText: str = Field(min_length=1, max_length=8000)
    extraPrompt: str | None = Field(default=None, max_length=2000)
    articles: list[RankArticle] = Field(min_length=1, max_length=200)


@app.post("/research-rankings", status_code=202)
def submit_ranking(req: RankingRequest, x_internal_token: str | None = Header(default=None)):
    _check_token(x_internal_token)
    articles = [a.model_dump(exclude={"abstractText"}) | {"abstract": a.abstractText} for a in req.articles]
    position = jobs.submit(RankingJob(req.researchId, req.callbackUrl, req.progressUrl, articles,
                                      req.contextText, req.extraPrompt))
    if position is None:
        return JSONResponse(status_code=202, content={"researchId": req.researchId, "alreadyQueued": True})
    return {"researchId": req.researchId, "queuePosition": position}


@app.get("/health")
def health():
    return {"status": "UP", "version": config.EXTRACTOR_VERSION, "model": config.LLM_MODEL, **jobs.status()}


if __name__ == "__main__":
    uvicorn.run(app, host=config.HOST, port=config.PORT)
