import random
import string

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from auth import get_current_user
from database import get_db
import models, schemas

router = APIRouter(prefix="/servers", tags=["servers"])

# ── Human-readable ID generation ─────────────────────────────────────────────
_ADJECTIVES = [
    "swift", "brave", "dark", "golden", "iron", "silent", "storm", "crimson",
    "ancient", "cyber", "neon", "wild", "royal", "shadow", "thunder", "frozen",
]
_NOUNS = [
    "lounge", "arena", "haven", "forge", "nexus", "vault", "realm", "station",
    "castle", "outpost", "harbor", "tower", "forge", "bastion", "ridge", "lair",
]


def _generate_unique_id(db: Session) -> str:
    for _ in range(20):
        uid = (
            random.choice(_ADJECTIVES)
            + "-"
            + random.choice(_NOUNS)
            + "-"
            + str(random.randint(10, 99))
        )
        if not db.query(models.Server).filter(models.Server.unique_id == uid).first():
            return uid
    # Fallback: append random hex suffix
    return "server-" + "".join(random.choices(string.hexdigits[:16], k=8))


# ── Helpers ───────────────────────────────────────────────────────────────────

def _get_server_or_404(unique_id: str, db: Session) -> models.Server:
    server = db.query(models.Server).filter(models.Server.unique_id == unique_id).first()
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    return server


def _require_member(server: models.Server, user: models.User) -> None:
    if user not in server.members:
        raise HTTPException(status_code=403, detail="You are not a member of this server")


def _server_out(server: models.Server) -> schemas.ServerOut:
    return schemas.ServerOut(
        id=server.id,
        name=server.name,
        unique_id=server.unique_id,
        owner_id=server.owner_id,
        created_at=server.created_at,
        member_count=len(server.members),
    )


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("", response_model=schemas.ServerOut, status_code=status.HTTP_201_CREATED)
def create_server(
    payload: schemas.ServerCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    unique_id = _generate_unique_id(db)
    server = models.Server(
        name=payload.name,
        unique_id=unique_id,
        owner_id=current_user.id,
    )
    server.members.append(current_user)

    # Create default channels
    server.text_channels.append(models.TextChannel(name="genel"))
    server.voice_channels.append(models.VoiceChannel(name="Lobi"))

    db.add(server)
    db.commit()
    db.refresh(server)
    return _server_out(server)


@router.post("/join", response_model=schemas.ServerOut)
def join_server(
    payload: schemas.JoinServerRequest,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(payload.server_unique_id, db)
    if current_user in server.members:
        return _server_out(server)  # idempotent
    server.members.append(current_user)
    db.commit()
    db.refresh(server)
    return _server_out(server)


@router.get("", response_model=list[schemas.ServerOut])
def list_my_servers(
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    return [_server_out(s) for s in current_user.servers]


@router.get("/{server_unique_id}", response_model=schemas.ServerOut)
def get_server(
    server_unique_id: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)
    return _server_out(server)


@router.delete("/{server_unique_id}", status_code=status.HTTP_204_NO_CONTENT)
def leave_server(
    server_unique_id: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)
    if server.owner_id == current_user.id:
        raise HTTPException(status_code=400, detail="Owner cannot leave; transfer ownership first")
    server.members.remove(current_user)
    db.commit()
