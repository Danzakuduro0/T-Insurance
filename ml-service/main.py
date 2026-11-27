from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict
from typing import List, Optional
import time
import cv2
import numpy as np
import os

app = FastAPI(title="T-Insurance ML Service")

# --- Модели (без изменений) ---
class AnalyzeRequest(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

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

class AnalyzeResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    document_id: str
    quality: QualityResult
    damage: Optional[DamageResult] = None
    model_version: str
    processing_ms: int

# --- Логика проверки качества (ТЗ Раздел 6.3) ---
def check_quality(image_path: str) -> QualityResult:
    if not os.path.exists(image_path):
        raise HTTPException(status_code=404, detail="Image not found")
        
    img = cv2.imread(image_path)
    if img is None:
        raise HTTPException(status_code=422, detail="Cannot decode image")

    issues = []
    
    # 1. BLURRY (Laplacian variance < 100)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    laplacian_var = cv2.Laplacian(gray, cv2.CV_64F).var()
    if laplacian_var < 100:
        issues.append("BLURRY")

    # 2. Яркость (LOW_LIGHT < 40, OVEREXPOSED > 230)
    mean_brightness = np.mean(gray)
    if mean_brightness < 40:
        issues.append("LOW_LIGHT")
    elif mean_brightness > 230:
        issues.append("OVEREXPOSED")

    # 3. Разрешение (min сторона < 480 px)
    h, w = gray.shape
    if min(h, w) < 480:
        issues.append("LOW_RESOLUTION")

    # Расчет общего score (упрощенно для MVP)
    score = 1.0
    if "BLURRY" in issues: score -= 0.4
    if "LOW_LIGHT" in issues or "OVEREXPOSED" in issues: score -= 0.3
    if "LOW_RESOLUTION" in issues: score -= 0.3
    score = max(0.0, min(1.0, score))

    return QualityResult(
        score=round(score, 2),
        acceptable=len(issues) == 0,
        issues=issues
    )

# --- Эндпоинты ---
@app.get("/health")
def health_check():
    return {"status": "UP", "model_version": "v1.0.0"}

@app.post("/analyze", response_model=AnalyzeResponse)
def analyze_image(request: AnalyzeRequest):
    start_time = time.time()
    
    # Определяем путь к файлу. 
    # Если запускаем в Docker: /data/files/...
    # Если локально для демо: ищем в папке ./test_images/
    if os.path.exists("/data/files"):
        file_path = os.path.join("/data/files", request.storage_key)
    else:
        # Локальный режим: берется только имя файла из storage_key
        filename = os.path.basename(request.storage_key)
        file_path = os.path.join("./test_images", filename)

    # 1. Проверка качества
    quality = check_quality(file_path)
    
    # 2. Классификация (пока заглушка)
    damage = None
    if quality.acceptable:
        damage = DamageResult(
            type="DENT",
            confidence=0.85,
            top_classes=[
                DamageClass(label="DENT", confidence=0.85),
                DamageClass(label="SCRATCH", confidence=0.10)
            ]
        )

    processing_ms = int((time.time() - start_time) * 1000)
    
    return AnalyzeResponse(
        document_id=request.document_id,
        quality=quality,
        damage=damage,
        model_version="v1.0.0",
        processing_ms=processing_ms
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)