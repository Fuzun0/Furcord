from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from auth import get_current_user
from database import get_db
import models, schemas
from routers.servers import _get_server_or_404, _require_member

router = APIRouter(prefix="/servers/{server_unique_id}", tags=["channels"])


# ── Text channels ─────────────────────────────────────────────────────────────

@router.get("/text-channels", response_model=list[schemas.TextChannelOut])
def list_text_channels(
    server_unique_id: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)
    return server.text_channels


@router.post(
    "/text-channels",
    response_model=schemas.TextChannelOut,
    status_code=status.HTTP_201_CREATED,
)
def add_text_channel(
    server_unique_id: str,
    payload: schemas.ChannelCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)

    if any(c.name == payload.name for c in server.text_channels):
        raise HTTPException(status_code=409, detail="Text channel already exists")

    ch = models.TextChannel(name=payload.name, server_id=server.id)
    db.add(ch)
    db.commit()
    db.refresh(ch)
    return ch


# ── Voice channels ────────────────────────────────────────────────────────────

@router.get("/voice-channels", response_model=list[schemas.VoiceChannelOut])
def list_voice_channels(
    server_unique_id: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)
    return server.voice_channels


@router.post(
    "/voice-channels",
    response_model=schemas.VoiceChannelOut,
    status_code=status.HTTP_201_CREATED,
)
def add_voice_channel(
    server_unique_id: str,
    payload: schemas.ChannelCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)

    if any(c.name == payload.name for c in server.voice_channels):
        raise HTTPException(status_code=409, detail="Voice channel already exists")

    ch = models.VoiceChannel(name=payload.name, server_id=server.id)
    db.add(ch)
    db.commit()
    db.refresh(ch)
    return ch


# ── Members ───────────────────────────────────────────────────────────────────

@router.get("/members", response_model=list[schemas.UserOut])
def list_members(
    server_unique_id: str,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_user),
):
    server = _get_server_or_404(server_unique_id, db)
    _require_member(server, current_user)
    return server.members
