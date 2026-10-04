from fastapi import APIRouter, Depends, HTTPException
from ASM_database import get_db, engine
from sqlalchemy.orm import Session
from Models import Provider, Subscription
from pydantic import BaseModel, ConfigDict

router = APIRouter()

class AddProvider(BaseModel):
    
    provider_name: str
    provider_website:str
    category: str
    user_id: int


@router.post("/provider")
def add_provider(provider: AddProvider, db: Session= Depends(get_db)):
    existing_provider = db.query(Provider).filter(Provider.provider_name == provider.provider_name).first()

    if existing_provider:
        raise HTTPException(
            status_code= 404,
            detail = "Subscription Provider already exists"
        )
    else:
        new_provider = Provider(provider_name = provider.provider_name, provider_website = provider.provider_website, category = provider.category, user_id= provider.user_id)
        db.add(new_provider)
        db.commit()
        db.refresh(new_provider)
    return new_provider

class UpdateProvider(BaseModel):
    provider_name: str | None = None
    provider_website:str | None = None
    category: str | None = None

@router.put("/update_provider/{provider_id}")
def update_provider(provider_id:int, provider: UpdateProvider,db: Session= Depends(get_db)):
    existing_provider = db.query(Provider).filter(Provider.provider_id== provider_id).first()

    if not existing_provider:
        raise HTTPException(
            status_code= 404,
            detail= "Subscription Provider not found"
        )
    if provider.provider_name is not None and provider.provider_name.strip() != "":
        existing_provider.provider_name = provider.provider_name

    if provider.provider_website is not None and provider.provider_website.strip() != "":
        existing_provider.provider_website = provider.provider_website

    if provider.category is not None and provider.category.strip() != "":
            existing_provider.category= provider.category

    
    db.commit()
    db.refresh(existing_provider)
    return existing_provider

class ProviderName(BaseModel):
    provider_id: int
    provider_name: str

    model_config = ConfigDict(from_attributes=True)

@router.get("/user/{user_id}/providers", response_model = list[ProviderName])
def get_user_provider_names(user_id:int, db: Session= Depends(get_db)):
    providers = (db.query(Provider.provider_id, Provider.provider_name)
                 .filter(Provider.user_id== user_id).distinct().all())

    return providers 



class ViewProviderDetails(BaseModel):
    provider_id: int
    provider_name: str
    provider_website:str
    category: str

    model_config = ConfigDict(from_attributes=True) 


@router.get("/view_provider/{provider_id}", response_model= ViewProviderDetails)
def get_provider_details(provider_id: int, db: Session= Depends(get_db)):
    existing_provider = db.query(Provider).filter(Provider.provider_id== provider_id).first()
    
    if not existing_provider:
        raise HTTPException(
            status_code= 404,
            detail= "Subscription Provider not found")

    return existing_provider


@router.delete("/users_delete_providers/{user_id}/providers/{provider_id}")
def remove_provider_from_user(user_id:int, provider_id:int, db: Session= Depends(get_db)):
    provider = (db.query(Provider).filter(Provider.provider_id == provider_id ,Provider.user_id == user_id).first())
    if not provider:
            raise HTTPException(
                status_code= 404,
                detail= "Subscription Provider not found")
    linked_subscription = (db.query(Subscription))

    if not linked_subscription:
                raise HTTPException(
                    status_code= 409,
                    detail= "There subscription still linked to this provider")                      

    db.delete(provider)
    db.commit()

    return{ "message": "Provider removed successfully"}

