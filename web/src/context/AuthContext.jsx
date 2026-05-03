import { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { onAuthStateChanged, signOut } from 'firebase/auth'
import { doc, getDoc } from 'firebase/firestore'
import { auth, db } from '../firebase/firebase'

// ── Context definition ────────────────────────────────────────────────────────

const AuthContext = createContext(null)

/** Derive avatar initials from a display name. */
function toInitials(name = '') {
  const parts = name.trim().split(/\s+/)
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
  return name.slice(0, 2).toUpperCase() || '??'
}

/** Stable color derived from a string (so each username gets a consistent color). */
function usernameColor(name = '') {
  const COLORS = [
    '#5865F2', '#57F287', '#FEE75C', '#EB459E',
    '#ED4245', '#3BA55D', '#FAA61A', '#00AFF4',
  ]
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) & 0xffff
  return COLORS[hash % COLORS.length]
}

// ── Provider ──────────────────────────────────────────────────────────────────

export function AuthProvider({ children }) {
  // `user` shape: { uid, email, username, initials, color } | null
  const [user, setUser]       = useState(undefined) // undefined = loading
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, async (firebaseUser) => {
      if (!firebaseUser) {
        setUser(null)
        setLoading(false)
        return
      }

      // Fetch the Firestore profile to get the username
      try {
        const snap = await getDoc(doc(db, 'users', firebaseUser.uid))
        const username = snap.exists() ? snap.data().username : firebaseUser.email.split('@')[0]

        setUser({
          uid:      firebaseUser.uid,
          email:    firebaseUser.email,
          username,
          initials: toInitials(username),
          color:    usernameColor(username),
        })
      } catch {
        // Firestore unavailable – fall back to email prefix
        const username = firebaseUser.email.split('@')[0]
        setUser({
          uid:      firebaseUser.uid,
          email:    firebaseUser.email,
          username,
          initials: toInitials(username),
          color:    usernameColor(username),
        })
      }

      setLoading(false)
    })

    return unsubscribe
  }, [])

  const logout = () => signOut(auth)

  const refreshUser = useCallback(async () => {
    const firebaseUser = auth.currentUser
    if (!firebaseUser) return
    try {
      await firebaseUser.reload()
      const snap = await getDoc(doc(db, 'users', firebaseUser.uid))
      const username = snap.exists() ? snap.data().username : firebaseUser.displayName ?? firebaseUser.email.split('@')[0]
      setUser({
        uid:      firebaseUser.uid,
        email:    firebaseUser.email,
        username,
        photoURL: snap.exists() ? snap.data().photoURL : firebaseUser.photoURL,
        initials: toInitials(username),
        color:    usernameColor(username),
      })
    } catch { /* ignore */ }
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  )
}

// ── Consumer hook ─────────────────────────────────────────────────────────────

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (ctx === null) throw new Error('useAuth must be used inside <AuthProvider>')
  return ctx
}
