from sqlalchemy import Column, BigInteger, String,  TIMESTAMP, ForeignKey,   DateTime,Boolean,Index, Double, Date, text, CheckConstraint, Numeric, UniqueConstraint, Integer
from sqlalchemy.orm import relationship 
from ASM_database import base
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.sql import func

class User(base):
    __tablename__ = "users"

    user_id = Column(BigInteger, primary_key=True, autoincrement=True)
    first_name = Column(String(50), nullable=False)
    last_name = Column(String(50), nullable=False)
    email = Column(String(50), nullable=False, unique=True, index=True)
    password = Column(String(255), nullable=False)
    user_type = Column(String(50), nullable=False)
    subscriptions = relationship("Subscription",back_populates="users")

class Provider(base):
    __tablename__ = "providers"

    provider_id = Column(BigInteger, primary_key=True, autoincrement=True)
    provider_name = Column(String(50), nullable=False)
    provider_website = Column(String(50), nullable=False)
    category =  Column(String(50), nullable=False)
    user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)

    subscriptions = relationship("Subscription",back_populates="providers")


class Subscription(base):
    __tablename__ = "subscriptions"

    subscription_id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)
    provider_id = Column(BigInteger,ForeignKey("providers.provider_id"),nullable=False)
    subscription_name = Column(String(50), nullable=False)
    subscription_price = Column(Numeric(12,2), nullable=False)
    billing_cycle = Column(String(50), nullable=False)
    start_date = Column(Date, nullable=False)
    renewal_date = Column(Date, nullable=False)
    status = Column(String(50), nullable=False, default="active")
    usage_level = Column(String(20),nullable=True)
    auto_renew = Column(Boolean,nullable=False,default=True)
    created_at = Column(TIMESTAMP, server_default=text("CURRENT_TIMESTAMP"))
    updated_at = Column(TIMESTAMP, server_default=text("CURRENT_TIMESTAMP"), onupdate=text ("CURRENT_TIMESTAMP"))

    users = relationship("User",back_populates="subscriptions")
    providers = relationship("Provider",back_populates="subscriptions")

    __table_args__ = (
        CheckConstraint(
            "subscription_price >= 0",
            name="ck_subscription_price_positive"
        ),

        Index(
            "ix_subscription_user_status_renewal",
            "user_id",
            "status",
            "renewal_date"
        ),

        Index(
            "ix_subscription_user_provider_status",
            "user_id",
            "provider_id",
            "status"
        ),
    )

class Recommendation(base):
    __tablename__ = "recommendations"

    recommendation_id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)
    subscription_id = Column(BigInteger,ForeignKey("subscriptions.subscription_id"),nullable=False)
    recommendation_type = Column(String(50), nullable=False)
    reason = Column(String(1000), nullable=False)
    confidence_score = Column(Numeric(5,4), nullable=False)
    evidence = Column(JSONB, nullable=True)
    model_name = Column(String(100), nullable=True)
    model_version = Column(String(50), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(TIMESTAMP, server_default=text("CURRENT_TIMESTAMP"))
    title = Column(String(150),nullable=False)

    __table_args__ = (

        CheckConstraint(
            "confidence_score >= 0 AND confidence_score <= 1",
            name="ck_recommendation_confidence"
        ),


        Index(
            "ix_recommendation_subscription",
            "subscription_id"
        ),

        Index(
            "ix_recommendation_created_at",
            "created_at"
        ),
    )

class Notification(base):
    __tablename__ = "notifications"

    notification_id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)
    subscription_id = Column(BigInteger,ForeignKey("subscriptions.subscription_id"),nullable=False)
    notification_type = Column(String(50), nullable=False)
    message = Column(String(1000), nullable=False)
    notification_date = Column(DateTime(timezone=True),nullable=False,server_default=func.now())
    status = Column(String(20),nullable=False,default="UNREAD")
    title = Column(String(150),nullable=False)

    __table_args__ = (
        Index(
            "ix_notification_user_status",
            "user_id",
            "status"
        ),

        Index(
            "ix_notification_user_date",
            "user_id",
            "notification_date"
        ),

        Index(
            "ix_notification_subscription",
            "subscription_id"
        ),
    )

class User_Decision(base):
    __tablename__ = "user_decision"

    decision_id = Column(BigInteger, primary_key=True, autoincrement=True)
    user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)
    recommendation_id = Column(BigInteger,ForeignKey("recommendations.recommendation_id"),nullable=False)
    decision = Column(String(20),nullable=False)
    decision_date = Column(DateTime(timezone=True),nullable=False,server_default=func.now())
    title = Column(String(150),nullable=False)

    __table_args__ = (
    UniqueConstraint(
        "recommendation_id",
        name="uq_user_decision_recommendation"
    ),

    Index(
        "ix_user_decision_user",
        "user_id"
    ),

    Index(
        "ix_user_decision_date",
        "decision_date"
    ),
)

class Budget(base):
        __tablename__ = "budgets"
    
        budget_id = Column(BigInteger, primary_key=True, autoincrement=True)
        user_id = Column(BigInteger,ForeignKey("users.user_id"),nullable=False)
        budget_limit = Column(Numeric(12, 2),nullable=False)
        currency = Column(String(3), nullable=False,default="ZAR")
        month = Column(Integer,nullable=False)
        year = Column(Integer,nullable=False)
        
        __table_args__ = (
            CheckConstraint(
                "budget_limit >= 0",
                name="ck_budget_limit_non_negative"
            ),

            CheckConstraint(
                "total_spending >= 0",
                name="ck_total_spending_non_negative"
            ),

            CheckConstraint(
                "month BETWEEN 1 AND 12",
                name="ck_budget_month"
            ),

            UniqueConstraint(
                "user_id",
                "month",
                "year",
                name="uq_budget_user_month_year"
            ),

            Index(
                "ix_budget_user",
                "user_id"
            ),
)

