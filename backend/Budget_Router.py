from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import Budget
from pydantic import BaseModel, ConfigDict
from decimal import Decimal

router = APIRouter()

class AddBudget(BaseModel):
    user_id: int
    budget_limit: Decimal
    currency: str
    month: int
    year: int

@router.post("/budget")
def add_budget(budget:AddBudget, db: Session= Depends(get_db)):
    existing_budget = db.query(Budget).filter(Budget.month == budget.month).first()

    if existing_budget:
        raise HTTPException(
                status_code= 400,
                detail = "Monthly budget already exists")
    else:
        new_budget = Budget(user_id = budget.user_id, budget_limit = budget.budget_limit, currency = budget.currency, month = budget.month, year = budget.year)
        db.add(new_budget)
        db.commit()
        db.refresh(new_budget)

    return new_budget

class UpdateBudget(BaseModel):
    
    budget_limit: Decimal | None = None
    currency: str | None = None
    month: int | None = None
    year: int | None = None

@router.put("/update_budget/{budget_id}")
def update_budget(budget_id:int, budget:UpdateBudget, db: Session= Depends(get_db)):
    existing_budget = db.query(Budget).filter(Budget.budget_id == budget_id).first()

    if not existing_budget:
        raise HTTPException(
            status_code= 404,
            detail= "Subscription Provider not found")

    update_data = budget.model_dump(exclude_unset=True)

    for field, value in update_data.items():
        if value is None:
            continue

        if isinstance(value, str) and value.strip() == "":
            continue
        setattr(existing_budget, field, value)

    db.commit()
    db.refresh(existing_budget)
    return {
            "message": "Budget updated successfully",
            "budget": existing_budget}

class Budgets(BaseModel):
    budget_id: int
    month: int

    model_config = ConfigDict(from_attributes=True)

@router.get("/user/{user_id}/budgets",response_model=list[Budgets])
def get_budgets(user_id: int, db: Session=Depends(get_db)):
    budgets = (db.query(Budget.budget_id, Budget.month)
               .filter(Budget.user_id == user_id).all())
    return budgets

class ViewBudgetDetails(BaseModel):
    budget_limit: Decimal
    currency: str
    month: int
    year: int

@router.get("/view_budget/{budget_id}",response_model=ViewBudgetDetails)
def get_budget_details(budget_id:int, db: Session = Depends(get_db)):
    existing_budget = db.query(Budget).filter(Budget.budget_id == budget_id).first()
    
    if not existing_budget:
        raise HTTPException(
            status_code= 404,
            detail= "Subscription Provider not found")

    return existing_budget

@router.delete("/user/{user_id}/budget/{budget}")
def remove_budget(user_id:int, budget_id:int, db:Session = Depends(get_db)):
    budget = (db.query(Budget).filter(Budget.user_id == user_id, Budget.budget_id == budget_id).first())
    if not budget:
        raise HTTPException(
            status_code= 404,
            detail= "Budget not found")
    else: 
        db.delete(budget)
        db.commit()

    return{ "message": "Budget removed successfully"}





    


    

    
