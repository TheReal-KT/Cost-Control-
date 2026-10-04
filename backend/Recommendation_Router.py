from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import Recommendation
from pydantic import BaseModel, ConfigDict, Field
from datetime import date 
from typing import Literal

router = APIRouter()

class AddRecommendation(BaseModel):
    user_id: int
    subscription__id: int
    recommendation_type:  Literal[
        "keep",
        "review",
        "cancel",
        "downgrade",
        "pause"
    ]
    reason: str 
    confidence_score :float = Field(ge=0, le=1)
    evidence: dict
    model_name: str 
    model_version: str 
    expires_at: date 
    title: str  


@router.post("/recommendations")
def add_recommendations( recomendation: AddRecommendation, db: Session= Depends(get_db)):
    new_recommendation = Recommendation(user_id = recomendation.user_id, subscription_id = recomendation.subscription__id, recommendation_type = recomendation.recommendation_type, reason = recomendation.reason,confidence_score =recomendation.confidence_score, evidence = recomendation.evidence, model_name = recomendation.model_name, model_version = recomendation.model_version, expires_at = recomendation.expires_at, title=recomendation.title)
    db.add(new_recommendation)
    db.commit()
    db.refresh(new_recommendation)

    return new_recommendation

class Recommendations(BaseModel):
    recommendation_id: int
    title: str 


@router.get("/user/{user_id}/recommendations", response_model=list[Recommendations])
def get_recommendations(user_id:int, db:Session= Depends(get_db)):
    recommendations = (db.query(Recommendation.recommendation_id, Recommendation.title)
                       .filter(Recommendation.user_id == user_id).distinct().all())
    return recommendations

class ViewRecommendationDetails(BaseModel):
    recommendation_type: str
    reason: str 
    confidence_score :float
    evidence: dict
    title: str 

    model_config = ConfigDict(from_attributes=True)

@router.get("/view_recommendation/{recommendation_id}", response_model=ViewRecommendationDetails)
def get_recommendation_details(recommendation_id:int, db: Session= Depends(get_db)):
    existing_recommendation = db.query(Recommendation).filter(Recommendation.recommendation_id == recommendation_id).first()
    if not existing_recommendation:
        raise HTTPException(
                    status_code= 404,
                    detail= "Recommandation not found")
    return existing_recommendation



