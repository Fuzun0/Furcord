from datetime import datetime

from sqlalchemy import (
    Column, DateTime, ForeignKey, Integer, String, Table, UniqueConstraint,
)
from sqlalchemy.orm import relationship

from database import Base

# ── Association table: Server ↔ User (members) ───────────────────────────────
server_members = Table(
    "server_members",
    Base.metadata,
    Column("user_id",   Integer, ForeignKey("users.id",   ondelete="CASCADE"), primary_key=True),
    Column("server_id", Integer, ForeignKey("servers.id", ondelete="CASCADE"), primary_key=True),
    Column("joined_at", DateTime, default=datetime.utcnow),
)


class User(Base):
    __tablename__ = "users"

    id              = Column(Integer, primary_key=True, index=True)
    username        = Column(String(64), unique=True, index=True, nullable=False)
    hashed_password = Column(String, nullable=False)
    created_at      = Column(DateTime, default=datetime.utcnow)

    owned_servers = relationship("Server", back_populates="owner", cascade="all, delete-orphan")
    servers       = relationship("Server", secondary=server_members, back_populates="members")


class Server(Base):
    __tablename__ = "servers"

    id        = Column(Integer, primary_key=True, index=True)
    name      = Column(String(128), nullable=False)
    unique_id = Column(String(64),  unique=True, index=True, nullable=False)
    owner_id  = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    owner          = relationship("User", back_populates="owned_servers")
    members        = relationship("User", secondary=server_members, back_populates="servers")
    text_channels  = relationship("TextChannel",  back_populates="server", cascade="all, delete-orphan")
    voice_channels = relationship("VoiceChannel", back_populates="server", cascade="all, delete-orphan")


class TextChannel(Base):
    __tablename__ = "text_channels"
    __table_args__ = (UniqueConstraint("server_id", "name"),)

    id         = Column(Integer, primary_key=True, index=True)
    name       = Column(String(64), nullable=False)
    server_id  = Column(Integer, ForeignKey("servers.id", ondelete="CASCADE"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    server = relationship("Server", back_populates="text_channels")


class VoiceChannel(Base):
    __tablename__ = "voice_channels"
    __table_args__ = (UniqueConstraint("server_id", "name"),)

    id         = Column(Integer, primary_key=True, index=True)
    name       = Column(String(64), nullable=False)
    server_id  = Column(Integer, ForeignKey("servers.id", ondelete="CASCADE"), nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)

    server = relationship("Server", back_populates="voice_channels")
