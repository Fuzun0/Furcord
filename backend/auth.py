"""
Authentication helpers: password hashing, JWT creation & verification,
and the FastAPI dependency that resolves the current user from a Bearer token.
"""
import os
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, HTTPException, status, Query
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy.orm import Session

from database import get_db
import models

# ── Config ────────────────────────────────────────────────────────────────────
# In production, load SECRET_KEY from an environment variable.
SECRET_KEY: str = os.getenv("FURCORD_SECRET", "change-me-in-production-use-a-long-random-string")
ALGORITHM  = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24 * 7  # 7 days

pwd_context    = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme  = OAuth2PasswordBearer(tokenUrl="/auth/login")


# ── Password helpers ──────────────────────────────────────────────────────────

def hash_password(plain: str) -> str:
    return pwd_context.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    return pwd_context.verify(plain, hashed)


# ── JWT helpers ───────────────────────────────────────────────────────────────

def create_access_token(subject: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    payload = {"sub": subject, "exp": expire}
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


def decode_token(token: str) -> Optional[str]:
    """Return the subject (username) or None if the token is invalid/expired."""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return payload.get("sub")
    except JWTError:
        return None


# ── FastAPI dependencies ──────────────────────────────────────────────────────

def _get_user_by_username(db: Session, username: str) -> Optional[models.User]:
    return db.query(models.User).filter(models.User.username == username).first()


def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db),
) -> models.User:
    """Resolve the authenticated user from a Bearer token (HTTP endpoints)."""
    credentials_exc = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    username = decode_token(token)
    if not username:
        raise credentials_exc
    user = _get_user_by_username(db, username)
    if not user:
        raise credentials_exc
    return user


def get_current_user_ws(
    token: str = Query(..., description="JWT access token"),
    db: Session = Depends(get_db),
) -> Optional[models.User]:
    """Resolve the authenticated user from a `?token=` query parameter (WebSocket)."""
    username = decode_token(token)
    if not username:
        return None
    return _get_user_by_username(db, username)
