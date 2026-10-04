from urllib.parse import quote_plus
from Database_config import DATABASE, USER, PASSWORD, HOST, PORT 
user = USER
password = PASSWORD
database = DATABASE
port = PORT
host = HOST

class SingletonMeta(type):
    _instance = {}

    def __call__(cls, *args, **kwds):
        if cls not in cls._instance:
            cls._instance[cls] = super().__call__(*args, **kwds)

        return cls._instance[cls]


class AppConfig(metaclass = SingletonMeta):
    def __init__(self):
        encoded_password = quote_plus(password)
        self.database_url = (f"postgresql+psycopg://"
                             f"{user}:{encoded_password}@{host}:{port}/{database}")