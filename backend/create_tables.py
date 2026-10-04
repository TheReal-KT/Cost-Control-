from ASM_database import engine, base
import Models

print("Registered tables:")
print(base.metadata.tables.keys())
base.metadata.create_all(bind=engine)
print("Done")