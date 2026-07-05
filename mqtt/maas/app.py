"""
MaaS (Model as a Service) mikroservis.

Ucitava istrenirani model (model.joblib) i izlaze ga preko REST API-ja:
  GET  /health
  POST /predict  {"humidity": .., "pressure": .., "light": .., "sound": .., "motion": .., "location": "Room A"}
                 -> {"predicted_temperature": ...}
"""
import joblib
import numpy as np
from fastapi import FastAPI
from pydantic import BaseModel

LOCATIONS = ["Outside", "Room A", "Room B", "Room C"]

app = FastAPI(title="MaaS - Temperature Prediction")
model = joblib.load("model.joblib")


class PredictRequest(BaseModel):
    humidity: float
    pressure: float
    light: float
    sound: float
    motion: float
    location: str = "Room A"


@app.get("/health")
def health():
    return {"status": "ok", "model": type(model).__name__}


@app.post("/predict")
def predict(req: PredictRequest):
    loc_flags = [1.0 if req.location == l else 0.0 for l in LOCATIONS]
    features = np.array([[req.humidity, req.pressure, req.light, req.sound, req.motion, *loc_flags]])
    prediction = model.predict(features)[0]
    return {"predicted_temperature": round(float(prediction), 2)}
