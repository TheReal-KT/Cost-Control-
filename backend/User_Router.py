from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import User
from pydantic import BaseModel, ConfigDict
import bcrypt

router = APIRouter()

class AddUser(BaseModel):
    first_name: str
    last_name: str
    email : str
    password : str
    user_type : str 


@router.post("/add_user")
def add_user(user:AddUser, db: Session= Depends(get_db)):
    existing_email = db.query(User).filter(User.email == user.email).first()

    if existing_email:
        raise HTTPException(
            status_code = 400, 
            detail = "Email already exists"
        )
    
    else:
        hashed_password = bcrypt.hashpw(
        user.password.encode("utf-8"),
        bcrypt.gensalt())
        new_user = User(first_name = user.first_name, last_name = user.last_name, email = user.email, password = hashed_password.decode("utf-8"), user_type = user.user_type)

        db.add(new_user)
        db.commit()
        db.refresh(new_user)
    return new_user


class UpdateUser(BaseModel):
    first_name: str | None = None
    last_name: str | None = None
    email : str | None = None
    password : str | None = None

    model_config = ConfigDict(from_attributes=True)

@router.put("/users/{user_id}")
def update_user( user_id:int, user:UpdateUser, db: Session= Depends(get_db)):
    existing_user = db.query(User).filter(User.user_id == user_id).first()

    if not existing_user:
        raise HTTPException(
            status_code = 404,
            detail = "User  not found"
        )

    if user.first_name is not None and user.email.strip() != "":
        existing_user.first_name = user.first_name

    if user.last_name is not None and user.email.strip() != "":
        existing_user.last_name = user.last_name

    if user.email is not None and user.email.strip() != "":
        existing_user.email= user.email

    if user.password is not None and user.email.strip() != "":
        existing_user.password = user.password

    db.commit()
    db.refresh(existing_user)
    return existing_user

class ViewDetails(BaseModel):
    first_name: str 
    last_name: str 
    email : str 

    class Config:
        from_attribute = True

@router.get("/user/{user_id}", response_model= ViewDetails)
def get_user_details(user_id:int,  db: Session= Depends(get_db)):
    existing_user = db.query(User).filter(User.user_id == user_id).first()
    
    if not existing_user:
        raise HTTPException(
            status_code = 404,
            detail = "User not found"
        )
    return existing_user

class LoginRequest(BaseModel):
    email: str
    password: str

@router.post("/login")
def login_user(login:LoginRequest,db: Session= Depends(get_db)):
    user = db.query(User).filter(
        User.email == login.email).first()

    if not user:
        return{"status": "Unsuccessful",
               "message": "Invalid email or password"}


    stored_password = user.password

    if isinstance(stored_password, str):
        stored_password = stored_password.encode("utf-8")

    password_correct = bcrypt.checkpw(
        login.password.encode("utf-8"), stored_password)

    if not password_correct:
        return{"status": "Unsuccessful",
                "message": "Invalid email or password"}
    return{"status": "Successful",
            "message": "Logged in successfully",

            "user": {
                "user_id": user.user_id,
                "email": user.email
            }

    }


    
    
