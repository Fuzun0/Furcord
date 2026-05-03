import { useState } from 'react'
import { signInWithPopup } from 'firebase/auth'
import { doc, getDoc, setDoc, serverTimestamp } from 'firebase/firestore'
import { auth, db, googleProvider } from '../firebase/firebase'

function GoogleIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 48 48" style={{ flexShrink: 0 }}>
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.18 1.48-4.97 2.31-8.16 2.31-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
    </svg>
  )
}

export default function AuthScreen() {
  const [loading, setLoading] = useState(false)
  const [error, setError]     = useState('')
  const [hov, setHov]         = useState(false)

  const friendlyError = (code) => {
    const MAP = {
      'auth/popup-blocked':           "Pop-up engellendi. Lütfen tarayıcınızın pop-up'larına izin verin.",
      'auth/account-exists-with-different-credential': 'Bu e-posta farklı bir yöntemle kayıtlı.',
      'auth/network-request-failed':  'Ağ hatası. İnternet bağlantınızı kontrol edin.',
      'auth/cancelled-popup-request': '',
    }
    return MAP[code] ?? `Bir hata oluştu (${code}).`
  }

  const handleGoogleSignIn = async () => {
    setError('')
    setLoading(true)
    try {
      const result = await signInWithPopup(auth, googleProvider)
      const { user } = result

      const userRef  = doc(db, 'users', user.uid)
      const userSnap = await getDoc(userRef)

      if (!userSnap.exists()) {
        await setDoc(userRef, {
          uid:       user.uid,
          email:     user.email,
          username:  user.displayName ?? user.email.split('@')[0],
          createdAt: serverTimestamp(),
        })
      }
    } catch (err) {
      if (err.code !== 'auth/popup-closed-by-user') {
        setError(friendlyError(err.code))
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', background: '#313338', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#2B2D31', borderRadius: 12, padding: '48px 40px', width: 420, boxShadow: '0 16px 48px rgba(0,0,0,0.7)', textAlign: 'center' }}>

        <div style={{ width: 80, height: 80, background: '#5865F2', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 20px', fontSize: 38 }}>
          🎙️
        </div>

        <h1 style={{ color: '#F2F3F5', fontSize: 26, fontWeight: 700, margin: '0 0 10px' }}>
          Furcord'a Hoş Geldin
        </h1>
        <p style={{ color: '#B5BAC1', fontSize: 15, lineHeight: 1.5, margin: '0 0 36px' }}>
          Devam etmek için Google hesabınla giriş yap.
        </p>

        <button
          onClick={handleGoogleSignIn}
          disabled={loading}
          onMouseEnter={() => setHov(true)}
          onMouseLeave={() => setHov(false)}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12,
            width: '100%',
            background: loading ? '#35363C' : hov ? '#3A3B41' : '#2F3136',
            border: '1.5px solid #4E5058', borderRadius: 8,
            padding: '14px 20px', color: '#F2F3F5',
            fontSize: 16, fontWeight: 600,
            cursor: loading ? 'not-allowed' : 'pointer',
            transition: 'background 0.15s',
            opacity: loading ? 0.7 : 1,
          }}
        >
          {loading ? (
            <>
              <span style={{ width: 20, height: 20, border: '2.5px solid #4E5058', borderTopColor: '#5865F2', borderRadius: '50%', display: 'inline-block', animation: 'spin 0.7s linear infinite' }} />
              Giriş yapılıyor…
            </>
          ) : (
            <>
              <GoogleIcon />
              Google ile Giriş Yap
            </>
          )}
        </button>

        {error && (
          <div style={{ marginTop: 20, background: 'rgba(242,63,67,0.15)', border: '1px solid rgba(242,63,67,0.4)', borderRadius: 6, padding: '10px 14px' }}>
            <p style={{ color: '#F23F43', fontSize: 13, margin: 0 }}>{error}</p>
          </div>
        )}

        <p style={{ marginTop: 28, color: '#4E5058', fontSize: 12 }}>
          Giriş yaparak kullanım koşullarını kabul etmiş olursunuz.
        </p>
      </div>

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}
