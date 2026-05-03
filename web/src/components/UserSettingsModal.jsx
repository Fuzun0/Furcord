import { useState, useEffect, useRef } from 'react'
import { updateProfile } from 'firebase/auth'
import { doc, setDoc } from 'firebase/firestore'
import { auth, db } from '../firebase/firebase'
import { useAuth } from '../context/AuthContext'
import { Avatar } from './icons'

// ── Sidebar nav item ──────────────────────────────────────────────────────────
function NavItem({ label, active, onClick }) {
  const [hov, setHov] = useState(false)
  return (
    <button
      onClick={onClick}
      onMouseEnter={() => setHov(true)}
      onMouseLeave={() => setHov(false)}
      style={{
        display: 'block', width: '100%', textAlign: 'left',
        padding: '8px 10px', border: 'none', borderRadius: '4px', cursor: 'pointer',
        background: active ? '#404249' : hov ? '#35373C' : 'transparent',
        color: active ? '#F2F3F5' : hov ? '#DBDEE1' : '#B5BAC1',
        fontSize: 14, fontWeight: active ? 600 : 400,
        transition: 'background 0.1s, color 0.1s',
      }}
    >
      {label}
    </button>
  )
}

// ── Section heading ────────────────────────────────────────────────────────────
function SectionHeading({ children }) {
  return (
    <div style={{ color: '#B5BAC1', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8 }}>
      {children}
    </div>
  )
}

// ── Input field ───────────────────────────────────────────────────────────────
function Field({ label, value, onChange, placeholder, type = 'text', disabled }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <label style={{ display: 'block', color: '#B5BAC1', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder}
        disabled={disabled}
        style={{
          width: '100%', boxSizing: 'border-box',
          background: '#1E1F22', border: '1px solid #3F4147',
          borderRadius: 4, padding: '10px 12px',
          color: disabled ? '#6D6F78' : '#F2F3F5', fontSize: 14, outline: 'none',
          transition: 'border-color 0.15s',
        }}
        onFocus={e => { if (!disabled) e.target.style.borderColor = '#5865F2' }}
        onBlur={e => e.target.style.borderColor = '#3F4147'}
      />
    </div>
  )
}

// ── Select field ──────────────────────────────────────────────────────────────
function SelectField({ label, value, onChange, options }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <label style={{ display: 'block', color: '#B5BAC1', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>
        {label}
      </label>
      <select
        value={value}
        onChange={e => onChange(e.target.value)}
        style={{
          width: '100%', boxSizing: 'border-box',
          background: '#1E1F22', border: '1px solid #3F4147',
          borderRadius: 4, padding: '10px 12px',
          color: '#F2F3F5', fontSize: 14, outline: 'none', cursor: 'pointer',
        }}
      >
        {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  )
}

// ── Main Modal ────────────────────────────────────────────────────────────────
export default function UserSettingsModal({ onClose }) {
  const { user, refreshUser } = useAuth()
  const [tab, setTab]         = useState('account')
  const fileRef               = useRef()

  // Account tab state
  const [displayName, setDisplayName] = useState(user?.username ?? '')
  const [photoURL,    setPhotoURL]    = useState(user?.photoURL ?? '')
  const [avatarPreview, setAvatarPreview] = useState(user?.photoURL ?? '')
  const [saving,  setSaving]  = useState(false)
  const [saveMsg, setSaveMsg] = useState('')

  // Voice tab state
  const [inputDevice,  setInputDevice]  = useState('default')
  const [outputDevice, setOutputDevice] = useState('default')
  const [inputVol,  setInputVol]  = useState(100)
  const [outputVol, setOutputVol] = useState(100)

  // ESC key to close
  useEffect(() => {
    const handler = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [onClose])

  const handleFileChange = (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => {
      setAvatarPreview(ev.target.result)
      setPhotoURL(ev.target.result)
    }
    reader.readAsDataURL(file)
  }

  const handleSave = async () => {
    setSaving(true)
    setSaveMsg('')
    try {
      const firebaseUser = auth.currentUser
      if (!firebaseUser) throw new Error('Oturum bulunamadı.')

      await updateProfile(firebaseUser, {
        displayName: displayName.trim() || firebaseUser.displayName,
        photoURL:    photoURL.trim()    || firebaseUser.photoURL,
      })

      await setDoc(doc(db, 'users', firebaseUser.uid), {
        uid:       firebaseUser.uid,
        email:     firebaseUser.email,
        username:  displayName.trim() || firebaseUser.displayName,
        photoURL:  photoURL.trim()    || firebaseUser.photoURL,
      }, { merge: true })

      await refreshUser()
      setSaveMsg('Değişiklikler kaydedildi!')
    } catch (err) {
      setSaveMsg(`Hata: ${err.message}`)
    } finally {
      setSaving(false)
    }
  }

  const SIDEBAR_SECTIONS = [
    { heading: 'KULLANICI AYARLARI', items: [
      { id: 'account',  label: 'Hesabım' },
      { id: 'profile',  label: 'Profil' },
    ]},
    { heading: 'UYGULAMA AYARLARI', items: [
      { id: 'voice',    label: 'Ses & Video' },
      { id: 'appearance', label: 'Görünüm' },
    ]},
  ]

  return (
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 1000,
        background: 'rgba(0,0,0,0.85)',
        display: 'flex', animation: 'fadeIn 0.12s ease',
      }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <style>{`
        @keyframes fadeIn { from { opacity: 0 } to { opacity: 1 } }
        input[type=range] { -webkit-appearance: none; appearance: none; height: 4px; border-radius: 2px; background: #4E5058; outline: none; }
        input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 14px; height: 14px; border-radius: 50%; background: #5865F2; cursor: pointer; }
        ::-webkit-scrollbar { width: 6px } ::-webkit-scrollbar-thumb { background: #1E1F22; border-radius: 3px }
      `}</style>

      <div style={{ display: 'flex', width: '100%', height: '100%' }}>

        {/* ── Left Sidebar ── */}
        <div style={{
          width: 240, flexShrink: 0,
          background: '#2B2D31',
          overflowY: 'auto',
          padding: '60px 8px 20px',
        }}>
          {SIDEBAR_SECTIONS.map(section => (
            <div key={section.heading} style={{ marginBottom: 20 }}>
              <div style={{ color: '#6D6F78', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', padding: '4px 10px 6px' }}>
                {section.heading}
              </div>
              {section.items.map(item => (
                <NavItem key={item.id} label={item.label} active={tab === item.id} onClick={() => setTab(item.id)} />
              ))}
            </div>
          ))}

          <div style={{ borderTop: '1px solid #3F4147', paddingTop: 12, marginTop: 8 }}>
            <button
              onClick={onClose}
              style={{
                display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                padding: '8px 10px', border: 'none', borderRadius: 4, cursor: 'pointer',
                background: 'transparent', color: '#B5BAC1', fontSize: 14,
              }}
              onMouseEnter={e => { e.currentTarget.style.background = '#35373C'; e.currentTarget.style.color = '#F23F43' }}
              onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#B5BAC1' }}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5-5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"/>
              </svg>
              Çıkış Yap
            </button>
          </div>
        </div>

        {/* ── Content ── */}
        <div style={{ flex: 1, overflowY: 'auto', background: '#313338', padding: '60px 40px 40px', minWidth: 0 }}>

          {/* ── Account tab ── */}
          {tab === 'account' && (
            <div style={{ maxWidth: 680 }}>
              <h2 style={{ color: '#F2F3F5', fontSize: 20, fontWeight: 700, marginBottom: 24 }}>Hesabım</h2>

              {/* Banner + avatar */}
              <div style={{ background: '#111214', borderRadius: 8, overflow: 'hidden', marginBottom: 24 }}>
                <div style={{ height: 100, background: 'linear-gradient(135deg, #5865F2, #EB459E)' }} />
                <div style={{ padding: '0 16px 16px', position: 'relative' }}>
                  {/* Avatar */}
                  <div style={{ position: 'absolute', top: -40, left: 16 }}>
                    <div
                      style={{ position: 'relative', cursor: 'pointer', width: 80, height: 80 }}
                      onClick={() => fileRef.current?.click()}
                      title="Avatar değiştir"
                    >
                      {avatarPreview ? (
                        <img
                          src={avatarPreview}
                          alt=""
                          style={{ width: 80, height: 80, borderRadius: '50%', objectFit: 'cover', border: '4px solid #313338' }}
                        />
                      ) : (
                        <div style={{ width: 80, height: 80, borderRadius: '50%', border: '4px solid #313338', display: 'flex', alignItems: 'center', justifyContent: 'center', background: user?.color ?? '#5865F2', fontSize: 28, color: '#fff', fontWeight: 700 }}>
                          {user?.initials ?? '??'}
                        </div>
                      )}
                      <div style={{
                        position: 'absolute', inset: 0, borderRadius: '50%',
                        background: 'rgba(0,0,0,0.5)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        opacity: 0, transition: 'opacity 0.15s',
                        fontSize: 11, color: '#fff', fontWeight: 700, textAlign: 'center',
                      }}
                        onMouseEnter={e => e.currentTarget.style.opacity = 1}
                        onMouseLeave={e => e.currentTarget.style.opacity = 0}
                      >
                        DEĞİŞTİR
                      </div>
                    </div>
                    <input ref={fileRef} type="file" accept="image/*" hidden onChange={handleFileChange} />
                  </div>

                  <div style={{ marginTop: 48, color: '#F2F3F5', fontWeight: 700, fontSize: 18 }}>
                    {user?.username}
                    <span style={{ color: '#B5BAC1', fontWeight: 400, fontSize: 14 }}> #{user?.uid?.slice(0, 4)}</span>
                  </div>
                </div>
              </div>

              {/* Edit form */}
              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24 }}>
                <SectionHeading>Kullanıcı Adı</SectionHeading>
                <Field
                  label="GÖRÜNEN AD"
                  value={displayName}
                  onChange={setDisplayName}
                  placeholder="Görünen adınız"
                />

                <Field
                  label="AVATAR URL'Sİ (opsiyonel)"
                  value={photoURL}
                  onChange={(v) => { setPhotoURL(v); setAvatarPreview(v) }}
                  placeholder="https://i.imgur.com/..."
                />

                <Field
                  label="E-POSTA"
                  value={user?.email ?? ''}
                  onChange={() => {}}
                  disabled
                />

                {saveMsg && (
                  <div style={{
                    padding: '10px 14px', borderRadius: 4, marginBottom: 16,
                    background: saveMsg.startsWith('Hata') ? 'rgba(242,63,67,0.15)' : 'rgba(35,165,90,0.15)',
                    color: saveMsg.startsWith('Hata') ? '#F23F43' : '#23A55A',
                    fontSize: 13,
                  }}>
                    {saveMsg}
                  </div>
                )}

                <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                  <button
                    onClick={handleSave}
                    disabled={saving}
                    style={{
                      background: '#5865F2', border: 'none', borderRadius: 4,
                      padding: '10px 24px', color: '#fff', fontSize: 14, fontWeight: 600,
                      cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1,
                      transition: 'background 0.15s',
                    }}
                    onMouseEnter={e => { if (!saving) e.currentTarget.style.background = '#4752C4' }}
                    onMouseLeave={e => { if (!saving) e.currentTarget.style.background = '#5865F2' }}
                  >
                    {saving ? 'Kaydediliyor…' : 'Değişiklikleri Kaydet'}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* ── Profile tab ── */}
          {tab === 'profile' && (
            <div style={{ maxWidth: 680 }}>
              <h2 style={{ color: '#F2F3F5', fontSize: 20, fontWeight: 700, marginBottom: 24 }}>Profil</h2>
              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24, color: '#B5BAC1', fontSize: 14 }}>
                Yakında eklenecek profil özelleştirme seçenekleri.
              </div>
            </div>
          )}

          {/* ── Voice & Video tab ── */}
          {tab === 'voice' && (
            <div style={{ maxWidth: 680 }}>
              <h2 style={{ color: '#F2F3F5', fontSize: 20, fontWeight: 700, marginBottom: 24 }}>Ses & Video</h2>

              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24, marginBottom: 16 }}>
                <SectionHeading>GİRİŞ</SectionHeading>
                <SelectField
                  label="GİRİŞ CİHAZI"
                  value={inputDevice}
                  onChange={setInputDevice}
                  options={[
                    { value: 'default', label: 'Varsayılan Mikrofon' },
                    { value: 'mic1',    label: 'Dahili Mikrofon' },
                    { value: 'mic2',    label: 'Harici Mikrofon' },
                  ]}
                />
                <label style={{ display: 'block', color: '#B5BAC1', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8 }}>
                  GİRİŞ SESİ — {inputVol}%
                </label>
                <input
                  type="range" min={0} max={200} value={inputVol}
                  onChange={e => setInputVol(Number(e.target.value))}
                  style={{ width: '100%', accentColor: '#5865F2' }}
                />
              </div>

              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24, marginBottom: 16 }}>
                <SectionHeading>ÇIKIŞ</SectionHeading>
                <SelectField
                  label="ÇIKIŞ CİHAZI"
                  value={outputDevice}
                  onChange={setOutputDevice}
                  options={[
                    { value: 'default',    label: 'Varsayılan Hoparlör' },
                    { value: 'speakers',   label: 'Dahili Hoparlörler' },
                    { value: 'headphones', label: 'Kulaklık' },
                  ]}
                />
                <label style={{ display: 'block', color: '#B5BAC1', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8 }}>
                  ÇIKIŞ SESİ — {outputVol}%
                </label>
                <input
                  type="range" min={0} max={200} value={outputVol}
                  onChange={e => setOutputVol(Number(e.target.value))}
                  style={{ width: '100%', accentColor: '#5865F2' }}
                />
              </div>

              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24 }}>
                <SectionHeading>GELİŞMİŞ</SectionHeading>
                <label style={{ display: 'flex', alignItems: 'center', gap: 12, cursor: 'pointer', color: '#F2F3F5', fontSize: 14, marginBottom: 12 }}>
                  <input type="checkbox" defaultChecked style={{ accentColor: '#5865F2', width: 16, height: 16 }} />
                  Eko Giderimi
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 12, cursor: 'pointer', color: '#F2F3F5', fontSize: 14 }}>
                  <input type="checkbox" defaultChecked style={{ accentColor: '#5865F2', width: 16, height: 16 }} />
                  Gürültü Bastırma
                </label>
              </div>
            </div>
          )}

          {/* ── Appearance tab ── */}
          {tab === 'appearance' && (
            <div style={{ maxWidth: 680 }}>
              <h2 style={{ color: '#F2F3F5', fontSize: 20, fontWeight: 700, marginBottom: 24 }}>Görünüm</h2>
              <div style={{ background: '#2B2D31', borderRadius: 8, padding: 24, color: '#B5BAC1', fontSize: 14 }}>
                Yakında eklenecek tema ve görünüm seçenekleri.
              </div>
            </div>
          )}
        </div>

        {/* ── Close button ── */}
        <div style={{ width: 72, flexShrink: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '60px 0 0' }}>
          <button
            onClick={onClose}
            title="Kapat (ESC)"
            style={{
              width: 36, height: 36, borderRadius: '50%',
              background: 'transparent', border: '2px solid #B5BAC1',
              cursor: 'pointer', color: '#B5BAC1', fontSize: 16,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              transition: 'background 0.15s, border-color 0.15s, color 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = '#B5BAC1'; e.currentTarget.style.color = '#313338' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = '#B5BAC1' }}
          >
            ✕
          </button>
          <span style={{ color: '#B5BAC1', fontSize: 11, marginTop: 4 }}>ESC</span>
        </div>
      </div>
    </div>
  )
}
