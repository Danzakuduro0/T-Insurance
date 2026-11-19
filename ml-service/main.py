from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Optional
import time

app = FastAPI(title="T-Insurance ML Service")

# --- Модели запроса и ответа (по ML_CONTRACT.md) ---

class AnalyzeRequest(BaseModel):
    document_id: str
    claim_id: str
    storage_key: str
    model_version: str
    document_type: str
    event_type: Optional[str] = None

class QualityResult(BaseModel):
    score: float
    acceptable: bool
    issues: List[str]

class DamageClass(BaseModel):
    label: str
    confidence: float

class DamageResult(BaseModel):
    type: str
    confidence: float
    top_classes: List[DamageClass]
    # car_part и area пока не обязательны, бэкенд их проигнорирует или сохранит как null

class AnalyzeResponse(BaseModel):
    document_id: str
    quality: QualityResult
    damage: Optional[DamageResult] = None
    model_version: str
    processing_ms: int

# --- Эндпоинты ---

@app.get("/health")
def health_check():
    return {"status": "UP", "model_version": "v1.0.0"}

@app.post("/analyze", response_model=AnalyzeResponse)
def analyze_image(request: AnalyzeRequest):
    start_time = time.time()
    
    # TODO: Здесь потом будет логика OpenCV и PyTorch
    # Пока возвращаем "заглушку", чтобы бэкенд мог принять ответ
    
    processing_ms = int((time.time() - start_time) * 1000)
    
    return AnalyzeResponse(
        document_id=request.document_id,
        quality=QualityResult(
            score=0.95,
            acceptable=True,
            issues=[]
        ),
        damage=DamageResult(
            type="DENT",
            confidence=0.85,
            top_classes=[
                DamageClass(label="DENT", confidence=0.85),
                DamageClass(label="SCRATCH", confidence=0.10)
            ]
        ),
        model_version="v1.0.0",
        processing_ms=processing_ms
    )
