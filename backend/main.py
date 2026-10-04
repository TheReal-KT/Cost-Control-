from fastapi import FastAPI
import uvicorn

from User_Router import router as user_router
from Provider_Router import router as provider_router
from User_Decision_Router import router as decision_router 
from Recommendation_Router import router as recommendation_router 
from Budget_Router import router as budget_router 
from Notification_Router import router as notification_router
from Subscription_router import router as subscription_router 


app = FastAPI()

app.include_router(user_router)
app.include_router(provider_router)
app.include_router(decision_router)
app.include_router(recommendation_router)
app.include_router(budget_router)
app.include_router(notification_router)
app.include_router(subscription_router)


if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host= "127.0.0.1",
        port= 8000,
        reload= True)