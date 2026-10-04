from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import Subscription
from pydantic import BaseModel, ConfigDict
from datetime import date
from decimal import Decimal
from typing import Literal

router = APIRouter()

class AddSubscription(BaseModel):
    user_id:int
    provider_id: int
    subscription_name: str
    subscription_price: Decimal
    billing_cycle: Literal["monthly", "annual"]
    start_date: date
    renewal_date: date 
    status: str = "active"
    usage_level: str |None = None

@router.post("/subscription")
def add_subscription(subscription: AddSubscription, db: Session= Depends(get_db)):
    new_subscription = Subscription(user_id = subscription.user_id, provider_id = subscription.provider_id, subscription_name = subscription.subscription_name,subscription_price=subscription.subscription_price,
        billing_cycle=subscription.billing_cycle,
        start_date=subscription.start_date,
        renewal_date=subscription.renewal_date,
        status=subscription.status,
        usage_level=subscription.usage_level )
    db.add(new_subscription)
    db.commit()
    db.refresh(new_subscription)

    return new_subscription

class UpdateSubscription(BaseModel):
    subscription_name: str |None = None
    subscription_price: Decimal |None = None
    billing_cycle: str |None = None
    start_date: date |None = None
    renewal_date: date |None = None
    status: Literal["active", "paused", "cancelled"] | None = None
    usage_level: str |None = None

@router.put("/update_subscription/{subscription_id}")
def update_subscription(subscription_id:int, subscription:UpdateSubscription,db: Session= Depends(get_db)):
    existing_subscription = (db.query(Subscription).filter(Subscription.subscription_id == subscription_id).first())

    if not existing_subscription:
        raise HTTPException(status_code=404,
                            detail= "Subscription not found")

    update_data = subscription.model_dump(exclude_unset=True)

    for field, value in update_data.items():
        if value is None:
            continue

        if isinstance(value, str) and value.strip() == "":
            continue

        setattr(existing_subscription, field, value)

    db.commit()
    db.refresh(existing_subscription)
    return {
        "message": "Subscription updated successfully",
        "subscription": existing_subscription}



class SubscriptionName(BaseModel):
    subscription_id : int
    subscription_name: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/user/{user_id}/subscription", response_model=list[SubscriptionName])
def get_user_subscription_names(user_id: int, db: Session=Depends(get_db)):
    subscriptions = (db.query(Subscription.subscription_id, Subscription.subscription_name).filter(Subscription.user_id == user_id).all())

    return subscriptions

class ViewSubscriptionDetails(BaseModel):
    subscription_name: str
    subscription_price: Decimal
    billing_cycle: str
    start_date: date
    renewal_date: date 
    status: str 
    usage_level: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/view_subscription/{subscription_id}", response_model=ViewSubscriptionDetails)
def get_subscription_details(subscription_id: int, db: Session = Depends(get_db)):
    existing_subscription = db.query(Subscription).filter(Subscription.subscription_id == subscription_id).first()

    if not existing_subscription:
            raise HTTPException(
                status_code= 404,
                detail= "Subscription not found")

    return existing_subscription

@router.delete("/users_delete_subscriptions/{user_id}/subscriptions/{subscription_id}")
def remove_subscription_from_user(user_id:int, subscription_id:int, db:Session = Depends(get_db)):
    subscription = (db.query(Subscription).filter(Subscription.user_id == user_id, Subscription.subscription_id == subscription_id).first())
    if not subscription:
            raise HTTPException(
                status_code= 404,
                detail= "Subscription not found")
    else:
        db.delete(subscription)
        db.commit()

    return{ "message": "Subscription removed successfully"}

    
    


    



