"""
Furcord Backend
===============
FastAPI application combining:
  • User authentication (register / login / JWT)
  • Server & channel management (SQLite via SQLAlchemy)
  • WebRTC signaling over WebSocket (members-only, JWT-authenticated)
"""
import json
import sys
from collections import defaultdict
from contextlib import asynccontextmanager

import uvicorn
from fastapi import (
    Depends, FastAPI, Query, WebSocket, WebSocketDisconnect, status,
)
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

import models
from auth import decode_token
from database import Base, SessionLocal, engine, get_db
from routers import channels, servers, users


# ── DB initialisation ─────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(bind=engine)
    yield

app = FastAPI(title="Furcord API", version="1.0.0", lifespan=lifespan)


# ── CORS (allow the Vite dev-server and any deployed frontend) ────────────────
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Routers ───────────────────────────────────────────────────────────────────
app.include_router(users.router)
app.include_router(servers.router)
app.include_router(channels.router)


# ── WebRTC Signaling (WebSocket) ──────────────────────────────────────────────
# Room key: server_unique_id  →  { username: WebSocket }
_rooms: dict[str, dict[str, WebSocket]] = defaultdict(dict)


def _auth_ws(token: str, db: Session) -> models.User | None:
    """Decode JWT and return the user, or None if invalid."""
    username = decode_token(token)
    if not username:
        return None
    return db.query(models.User).filter(models.User.username == username).first()


def _is_server_member(server_unique_id: str, user: models.User, db: Session) -> bool:
    server = (
        db.query(models.Server)
        .filter(models.Server.unique_id == server_unique_id)
        .first()
    )
    return server is not None and user in server.members


async def _broadcast(room_id: str, sender: str, message: dict) -> None:
    """Forward a signaling message to every peer in the room except the sender."""
    evicted: list[str] = []
    for peer, ws in _rooms[room_id].items():
        if peer == sender:
            continue
        try:
            await ws.send_text(json.dumps(message))
        except Exception:
            evicted.append(peer)
    for peer in evicted:
        _rooms[room_id].pop(peer, None)


@app.websocket("/ws/{server_id}/{user_id}")
async def signaling_endpoint(
    websocket: WebSocket,
    server_id: str,
    user_id: str,
    token: str = Query(..., description="JWT access token"),
    db: Session = Depends(get_db),
) -> None:
    # ── Authenticate ──────────────────────────────────────────────────────────
    user = _auth_ws(token, db)
    if user is None:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    # Ensure the URL user_id matches the token subject (prevents spoofing)
    if user.username != user_id:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    # ── Authorise: must be a server member ───────────────────────────────────
    if not _is_server_member(server_id, user, db):
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    _rooms[server_id][user_id] = websocket
    print(
        f"[+] {user_id!r} joined room {server_id!r}  "
        f"(peers: {list(_rooms[server_id].keys())})"
    )

    try:
        while True:
            raw = await websocket.receive_text()

            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_text(json.dumps({"error": "Invalid JSON"}))
                continue

            msg_type = data.get("type")
            if msg_type not in ("offer", "answer", "ice-candidate"):
                await websocket.send_text(
                    json.dumps({"error": f"Unknown message type: {msg_type!r}"})
                )
                continue

            data["from"] = user_id
            await _broadcast(server_id, user_id, data)

    except WebSocketDisconnect:
        _rooms[server_id].pop(user_id, None)
        print(
            f"[-] {user_id!r} left room {server_id!r}  "
            f"(peers: {list(_rooms[server_id].keys())})"
        )
        if not _rooms[server_id]:
            del _rooms[server_id]
            print(f"[*] Room {server_id!r} removed (empty)")


# ── Health check ──────────────────────────────────────────────────────────────
@app.get("/health", tags=["meta"])
def health():
    return {"status": "ok"}


# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
