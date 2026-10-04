from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import User_Decision
from pydantic import BaseModel, ConfigDict
from datetime import date
from typing import Literal

router = APIRouter()

class AddDecision(BaseModel):
    user_id: int 
    recommendatio_id: int
    decision: Literal[
        "approved",
        "rejected",
        "ignored"
    ]
    decision_date: date
    title: str 

@router.post("/user_decision")
def add_decision(decision: AddDecision, db:Session= Depends(get_db)):
    new_decision = User_Decision(user_id= decision.user_id, recommendation_id = decision.recommendatio_id ,decision = decision.decision, decision_date =decision.decision_date, title = decision.title)
    db.add(new_decision)
    db.commit()
    db.refresh(new_decision)

    return new_decision

class Decisions(BaseModel):
    
    decision_id: int
    title: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/user/{user_id}/decision", response_model=list[Decisions])
def get_decisions(user_id:int,db: Session=Depends(get_db)):
    decisions = (db.query(User_Decision.decision_id, User_Decision.title).filter(User_Decision.user_id== user_id).all())
    return decisions

class ViewDecision(BaseModel):
    decision: str
    decision_date: date
    title: str 

    model_config = ConfigDict(from_attributes=True)

@router.get("/view_decision/{decision_id}", response_model=ViewDecision)
def get_decision(decision_id:int, db:Session = Depends(get_db)):
    existing_decision = db.query(User_Decision).filter(User_Decision.decision_id == decision_id).first()
    if not existing_decision:
                raise HTTPException(
                    status_code= 400,
                    detail= "Decision not found")
    return existing_decision

@router.delete("/user/{user_id}/decision/{decision_id}")
def delete_decision(user_id:int, decision_id:int,db:Session = Depends(get_db)):
    decision = (db.query(User_Decision).filter(User_Decision.user_id  == user_id, User_Decision.decision_id == decision_id).first())

    if not decision:
        raise HTTPException(
            status_code= 404,
            detail= "Decision not found")
    else:
         db.delete(decision)
         db.commit() 
    return{ "message": "Decision removed successfully"}



