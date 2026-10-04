from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, declarative_base
from Singleton import AppConfig

config = AppConfig()


engine = create_engine(config.database_url, echo = True, pool_pre_ping=True)

SessionLocal = sessionmaker( autocommit = False, autoflush=False, bind=engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

base = declarative_base()