import { useState } from 'react'
import { MicOnIcon, MicOffIcon, HeadphonesIcon, DeafenIcon, SettingsIcon, Avatar } from './icons'
import { useAuth } from '../context/AuthContext'
import UserSettingsModal from './UserSettingsModal'

function CtrlBtn({ children, active, title, onClick }) {
  const [hov, setHov] = useState(false)
  return (
    <button
      title={title} onClick={onClick}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        width: 32, height: 32,
        background: hov ? '#35373C' : 'transparent',
        border: 'none', borderRadius: '4px', cursor: 'pointer',
        color: active ? '#F23F43' : (hov ? '#F2F3F5' : '#B5BAC1'),
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        position: 'relative', flexShrink: 0,
        transition: 'background 0.1s, color 0.1s',
      }}
    >
      {children}
      {/* Red dot when muted/deafened */}
      {active && (
        <span style={{ position: 'absolute', bottom: 5, right: 5, width: 6, height: 6, background: '#F23F43', borderRadius: '50%', border: '1.5px solid #232428' }} />
      )}
    </button>
  )
}

export default function UserPanel({ user, isMuted, setIsMuted, isDeafened, setIsDeafened, inVoiceCall }) {
  const { logout } = useAuth()
  const [settingsOpen, setSettingsOpen] = useState(false)
  return (
    <>
      {settingsOpen && <UserSettingsModal onClose={() => setSettingsOpen(false)} />}
      <div style={{ background: '#232428', padding: '0 8px', height: 52, display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>

      {/* Avatar with status dot */}
      <div style={{ position: 'relative', flexShrink: 0 }}>
        <Avatar initials={user.initials} color={user.color} size={32} />
        <span style={{
          position: 'absolute', bottom: -2, right: -2,
          width: 12, height: 12,
          background: '#23A559',
          borderRadius: '50%', border: '2px solid #232428',
        }} />
      </div>

      {/* Name / status */}
      <div style={{ flex: 1, overflow: 'hidden' }}>
        <div style={{ color: '#F2F3F5', fontSize: 13, fontWeight: '600', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', lineHeight: 1.2 }}>
          {user.username}
        </div>
        <div style={{ color: '#80848E', fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', lineHeight: 1.2 }}>
          {inVoiceCall ? 'Sesli aramada' : 'Çevrimiçi'}
        </div>
      </div>

      {/* Controls */}
      <CtrlBtn active={isMuted}     title={isMuted ? 'Susturma kaldır' : 'Mikrofonu sustur'} onClick={() => setIsMuted(m => !m)}>
        {isMuted ? <MicOffIcon /> : <MicOnIcon />}
      </CtrlBtn>
      <CtrlBtn active={isDeafened} title={isDeafened ? 'Sağırlamayı kaldır' : 'Sağırla'} onClick={() => setIsDeafened(d => !d)}>
        {isDeafened ? <DeafenIcon /> : <HeadphonesIcon />}
      </CtrlBtn>
      <CtrlBtn title="Kullanıcı Ayarları" onClick={() => setSettingsOpen(true)}>
        <SettingsIcon />
      </CtrlBtn>
      <CtrlBtn title="Çıkış Yap" onClick={logout}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
          <path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5-5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"/>
        </svg>
      </CtrlBtn>
    </div>
    </>
  )
}
