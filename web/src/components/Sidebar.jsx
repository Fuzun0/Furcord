import { useState, useEffect, useRef } from 'react'
import UserPanel from './UserPanel'
import { SERVER_CONFIG } from '../data/mockData'
import {
  HashIcon, VolumeIcon, ChevronDownIcon, ChevronRightIcon, PlusIcon,
  SignalIcon, CameraOffIcon, PhoneLeaveIcon, Avatar,
} from './icons'

// ── Small helpers ────────────────────────────────────────────────────────────

function IconBtn({ children, onClick, title, danger = false }) {
  const [hov, setHov] = useState(false)
  return (
    <button
      onClick={onClick} title={title}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        background: hov ? (danger ? '#F23F43' : '#35373C') : 'transparent',
        border: 'none', cursor: 'pointer', padding: '4px', borderRadius: '4px',
        color: hov ? 'white' : '#B5BAC1',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}
    >
      {children}
    </button>
  )
}

function SectionHeader({ title, expanded, onToggle, onAdd }) {
  const [hov, setHov] = useState(false)
  return (
    <div
      style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 8px 4px 16px', cursor: 'pointer', color: hov ? '#B5BAC1' : '#80848E' }}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
    >
      <button
        onClick={onToggle}
        style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px', padding: 0, fontSize: '11px', fontWeight: '700', textTransform: 'uppercase', letterSpacing: '0.04em' }}
      >
        {expanded ? <ChevronDownIcon /> : <ChevronRightIcon />}
        {title}
      </button>
      <button
        onClick={onAdd} title="Kanal ekle"
        style={{ background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', padding: '2px', borderRadius: '4px', display: 'flex', alignItems: 'center' }}
        onMouseEnter={e => e.currentTarget.style.color = '#F2F3F5'} onMouseLeave={e => e.currentTarget.style.color = ''}
      >
        <PlusIcon />
      </button>
    </div>
  )
}

function ChannelRow({ icon, name, active, badge, onClick }) {
  const [hov, setHov] = useState(false)
  const bg = active ? '#404249' : hov ? '#35373C' : 'transparent'
  const col = active ? '#F2F3F5' : hov ? '#D4D7DC' : '#80848E'
  return (
    <div
      onClick={onClick}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: '6px',
        padding: '6px 8px', margin: '1px 8px', borderRadius: '4px',
        cursor: 'pointer', background: bg, color: col,
        fontSize: '15px', fontWeight: active ? '500' : '400',
        userSelect: 'none', transition: 'background 0.1s',
      }}
    >
      <span style={{ display: 'flex', flexShrink: 0, opacity: 0.7 }}>{icon}</span>
      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}</span>
      {badge && (
        <span style={{ width: 8, height: 8, background: '#23A559', borderRadius: '50%', flexShrink: 0 }} />
      )}
    </div>
  )
}

function VoiceUserRow({ user }) {
  const [hov, setHov] = useState(false)
  return (
    <div
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        display: 'flex', alignItems: 'center', gap: '8px',
        padding: '2px 8px 2px 40px', margin: '1px 8px', borderRadius: '4px',
        cursor: 'pointer', background: hov ? '#35373C' : 'transparent',
      }}
    >
      <Avatar initials={user.initials} color={user.color} size={18} />
      <div>
        <div style={{ color: '#B5BAC1', fontSize: '13px' }}>{user.name}</div>
        <div style={{ color: '#80848E', fontSize: '11px' }}>Bir kanal durumu belirle</div>
      </div>
    </div>
  )
}

// ── Call timer ───────────────────────────────────────────────────────────────
function CallTimer() {
  const [secs, setSecs] = useState(0)
  const ref = useRef(null)
  useEffect(() => {
    ref.current = setInterval(() => setSecs(s => s + 1), 1000)
    return () => clearInterval(ref.current)
  }, [])
  const m = String(Math.floor(secs / 60)).padStart(1, '0')
  const s = String(secs % 60).padStart(2, '0')
  return <span>{m}:{s}</span>
}

// ── Main Sidebar ─────────────────────────────────────────────────────────────
export default function Sidebar({
  serverName, activeChannel, onChannelClick,
  voiceChannel, onLeaveVoice,
  isMuted, setIsMuted, isDeafened, setIsDeafened,
  voiceUsers, currentUser,
}) {
  const [textOpen, setTextOpen] = useState(true)
  const [voiceOpen, setVoiceOpen] = useState(true)
  const [serverHov, setServerHov] = useState(false)
  const inCall = voiceChannel !== null

  const activeVoiceChannelName =
    SERVER_CONFIG.voiceChannels.find(c => c.id === voiceChannel)?.name ?? ''

  return (
    <div style={{ width: 240, minWidth: 240, background: '#2B2D31', display: 'flex', flexDirection: 'column', height: '100vh' }}>

      {/* Server header */}
      <div
        onMouseEnter={() => setServerHov(true)} onMouseLeave={() => setServerHov(false)}
        style={{
          height: 48, padding: '0 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          borderBottom: '1px solid #1E1F22', boxShadow: '0 1px 0 rgba(4,4,5,0.2)',
          cursor: 'pointer', fontWeight: '600', color: '#F2F3F5', fontSize: '15px',
          background: serverHov ? '#35373C' : 'transparent', flexShrink: 0,
          transition: 'background 0.1s',
        }}
      >
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{serverName}</span>
        <ChevronDownIcon />
      </div>

      {/* Channel list */}
      <div style={{ flex: 1, overflowY: 'auto', paddingTop: 8 }}>

        {/* Static nav rows */}
        <ChannelRow icon="📅" name="Etkinlikler" active={false} onClick={() => {}} />
        <ChannelRow icon="🛡️" name="Sunucu Takviyeleri" active={false} onClick={() => {}} />

        <div style={{ height: 1, background: '#35373C', margin: '6px 8px' }} />

        {/* Text channels */}
        <SectionHeader title="Metin Kanalları" expanded={textOpen} onToggle={() => setTextOpen(o => !o)} onAdd={() => alert('Kanal ekle')} />
        {textOpen && SERVER_CONFIG.textChannels.map(ch => (
          <ChannelRow
            key={ch.id}
            icon={<HashIcon />}
            name={ch.name}
            active={activeChannel.type === 'text' && activeChannel.id === ch.id}
            onClick={() => onChannelClick({ type: 'text', id: ch.id })}
          />
        ))}

        <div style={{ marginTop: 8 }}>
          <SectionHeader title="Ses Kanalları" expanded={voiceOpen} onToggle={() => setVoiceOpen(o => !o)} onAdd={() => alert('Ses kanalı ekle')} />
          {voiceOpen && SERVER_CONFIG.voiceChannels.map(ch => {
            const users = voiceUsers[ch.id] || []
            return (
              <div key={ch.id}>
                <ChannelRow
                  icon={<VolumeIcon />}
                  name={ch.name}
                  active={activeChannel.type === 'voice' && activeChannel.id === ch.id}
                  badge={voiceChannel === ch.id}
                  onClick={() => onChannelClick({ type: 'voice', id: ch.id })}
                />
                {/* User list under channel */}
                {users.map(u => <VoiceUserRow key={u.id} user={u} />)}
              </div>
            )
          })}
        </div>
      </div>

      {/* Voice call bar */}
      {inCall && (
        <div style={{ background: '#232428', padding: '8px 10px 4px', borderTop: '1px solid #1E1F22', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 5, color: '#23A559', fontSize: '12px', fontWeight: '600' }}>
                <SignalIcon /> Ses Bağlantısı Kuruldu
              </div>
              <div style={{ color: '#B5BAC1', fontSize: '11px', marginTop: 1 }}>
                {activeVoiceChannelName} / {serverName}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 2 }}>
              <IconBtn title="Kamera" onClick={() => {}}><CameraOffIcon /></IconBtn>
              <IconBtn title="Kanaldan ayrıl" onClick={onLeaveVoice} danger><PhoneLeaveIcon /></IconBtn>
            </div>
          </div>
          {/* Timer row */}
          <div style={{ color: '#80848E', fontSize: '11px', marginTop: 2 }}>
            <CallTimer />
          </div>
        </div>
      )}

      <UserPanel
        user={currentUser}
        isMuted={isMuted} setIsMuted={setIsMuted}
        isDeafened={isDeafened} setIsDeafened={setIsDeafened}
        inVoiceCall={inCall}
      />
    </div>
  )
}
