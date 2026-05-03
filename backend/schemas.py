from datetime import datetime
from typing import Optional
from pydantic import BaseModel, field_validator


# ── User ─────────────────────────────────────────────────────────────────────

class UserCreate(BaseModel):
    username: str
    password: str

    @field_validator("username")
    @classmethod
    def username_valid(cls, v: str) -> str:
        v = v.strip()
        if len(v) < 3:
            raise ValueError("Username must be at least 3 characters")
        if len(v) > 64:
            raise ValueError("Username must be at most 64 characters")
        if not v.replace("-", "").replace("_", "").isalnum():
            raise ValueError("Username may only contain letters, numbers, - and _")
        return v

    @field_validator("password")
    @classmethod
    def password_valid(cls, v: str) -> str:
        if len(v) < 6:
            raise ValueError("Password must be at least 6 characters")
        return v


class UserOut(BaseModel):
    id: int
    username: str
    created_at: datetime

    model_config = {"from_attributes": True}


# ── Auth ──────────────────────────────────────────────────────────────────────

class LoginRequest(BaseModel):
    username: str
    password: str


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"


# ── Server ────────────────────────────────────────────────────────────────────

class ServerCreate(BaseModel):
    name: str

    @field_validator("name")
    @classmethod
    def name_valid(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("Server name cannot be empty")
        if len(v) > 128:
            raise ValueError("Server name must be at most 128 characters")
        return v


class ServerOut(BaseModel):
    id: int
    name: str
    unique_id: str
    owner_id: int
    created_at: datetime
    member_count: int = 0

    model_config = {"from_attributes": True}


class JoinServerRequest(BaseModel):
    server_unique_id: str


# ── Channels ──────────────────────────────────────────────────────────────────

class ChannelCreate(BaseModel):
    name: str

    @field_validator("name")
    @classmethod
    def name_valid(cls, v: str) -> str:
        v = v.strip().lower().replace(" ", "-")
        if not v:
            raise ValueError("Channel name cannot be empty")
        if len(v) > 64:
            raise ValueError("Channel name must be at most 64 characters")
        return v


class TextChannelOut(BaseModel):
    id: int
    name: str
    server_id: int
    created_at: datetime

    model_config = {"from_attributes": True}


class VoiceChannelOut(BaseModel):
    id: int
    name: str
    server_id: int
    created_at: datetime

    model_config = {"from_attributes": True}
