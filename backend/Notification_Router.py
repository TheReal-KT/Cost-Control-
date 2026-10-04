from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import Notification
from pydantic import BaseModel, ConfigDict
from datetime import date

router = APIRouter()

class AddNotification(BaseModel):
    user_id: int
    subscription_id: int
    notification_type: str
    message: str 
    notification_date: date
    status: str = "UNREAD"
    title: str 

@router.post("/notification")
def add_notification(notification: AddNotification, db: Session=Depends(get_db)):
    new_notification = Notification(user_id= notification.user_id, subscription_id = notification.subscription_id  ,notification_type = notification.notification_type, message = notification.message, notification_date = notification.notification_date, status = notification.status, title = notification.title)
    db.add(new_notification)
    db.commit()
    db.refresh(new_notification)

    return new_notification

class Notifications(BaseModel):
    
    notification_id: int
    title: str
    status: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/user/{user_id}/notifications", response_model=list[Notifications])
def get_notifications(user_id:int, db: Session=Depends(get_db)):
    notifications = (db.query(Notification.notification_id, Notification.title, Notification.status).filter(Notification.user_id == user_id).all())
    return notifications

class ViewNotificationDetails(BaseModel):
    notification_type: str
    message: str 
    notification_date: date
    title: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/view_notification/{notification_id}", response_model=ViewNotificationDetails)
def get_notification_details(notification_id:int,db: Session = Depends(get_db)):
    existing_notification = db.query(Notification).filter(Notification.notification_id == notification_id).first()

    if not existing_notification:
        raise HTTPException(
            status_code= 400,
            detail= "Subscription not found")
    return existing_notification

@router.delete("/user/{user_id}/notification/{notification_id}")
def delete_notification(user_id:int, notification_id:int, db:Session=Depends(get_db)):
    notification = (db.query(Notification).filter(Notification.user_id == user_id, Notification.notification_id == notification_id).first())
    if not notification:
        raise HTTPException(
            status_code= 404,
            detail= "Notification not found")
    else:
        db.delete(notification)
        db.commit()

    return{ "message": "Notification removed successfully"}


