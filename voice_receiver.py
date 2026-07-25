import os, time
from pathlib import Path
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

VAULT = os.getenv("VAULT_PATH", "/data/vault")
API_KEY = os.getenv("VOICE_API_KEY", "")

app = FastAPI()

class Note(BaseModel):
    text: str = Field(min_length=1, max_length=10000)

@app.post("/voice-note")
async def receive(note: Note, request: Request):
    if API_KEY:
        auth = request.headers.get("Authorization", "")
        if auth != f"Bearer {API_KEY}":
            raise HTTPException(status_code=401, detail="unauthorized")
    now = time.strftime("%Y-%m-%d %H:%M")
    now_file = time.strftime("%Y-%m-%d-%H%M%S")
    base = Path(VAULT) / f"voice-{now_file}"
    path = base.with_suffix(".md")
    counter = 1
    while path.exists():
        path = base.with_name(f"{base.name}-{counter}.md")
        counter += 1
    path.parent.mkdir(parents=True, exist_ok=True)
    content = f"---\ndate: {now}\ntags: [notatka, glosowa]\nsource: wear-os\n---\n\n{note.text.strip()}\n"
    path.write_text(content, encoding="utf-8")
    return {"ok": True, "file": path.name}

@app.get("/health")
async def health():
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=5001)
