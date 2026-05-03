import { useState } from 'react'
import { Avatar, MicOnIcon, MicOffIcon, PhoneLeaveIcon } from './icons'

function UserCard({ user, isSelf, isMuted }) {
  return (
    <div style={{
      display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10,
      padding: '20px 16px',
      background: '#2B2D31', borderRadius: 8, minWidth: 100,
      border: `2px solid ${isSelf ? '#23A559' : 'transparent'}`,
    }}>
      <div style={{ position: 'relative' }}>
        <Avatar initials={user.initials} color={user.color} size={64} />
        {isSelf && isMuted && (
          <div style={{
            position: 'absolute', bottom: -4, right: -4,
            width: 22, height: 22, background: '#F23F43', borderRadius: '50%',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '2px solid #313338',
          }}>
            <MicOffIcon size={12} />
          </div>
        )}
      </div>
      <span style={{ color: '#F2F3F5', fontSize: 13, fontWeight: '500', textAlign: 'center' }}>{user.name}</span>
    </div>
  )
}

export default function VoiceChannelArea({
  channelName, channelId, users,
  voiceChannel, isMuted, onToggleMute, onLeaveVoice, onJoinVoice,
}) {
  const isJoined = voiceChannel === channelId
  const isEmpty = users.length === 0

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#313338' }}>

      {/* Header */}
      <div style={{
        height: 48, padding: '0 16px', display: 'flex', alignItems: 'center', gap: 8,
        boxShadow: '0 1px 0 rgba(4,4,5,0.2)', flexShrink: 0,
        borderBottom: '1px solid rgba(4,4,5,0.2)',
      }}>
        <span style={{ fontSize: 20 }}>🔈</span>
        <span style={{ fontWeight: '700', color: '#F2F3F5', fontSize: 16 }}>{channelName}</span>
        {isJoined && (
          <span style={{ marginLeft: 8, background: '#23A559', color: 'white', fontSize: 11, fontWeight: '600', padding: '2px 8px', borderRadius: 10 }}>
            Bağlandı
          </span>
        )}
      </div>

      {/* Main area */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 32, padding: 32 }}>

        {/* User grid */}
        {isJoined && !isEmpty ? (
          <>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, justifyContent: 'center', maxWidth: 600 }}>
              {users.map(u => (
                <UserCard key={u.id} user={u} isSelf={u.id === 'fuzun'} isMuted={isMuted} />
              ))}
            </div>

            {/* Controls */}
            <div style={{ display: 'flex', gap: 16 }}>
              <VoiceCtrlBtn
                label={isMuted ? 'Susturuldu' : 'Mikrofon Açık'}
                active={isMuted}
                onClick={onToggleMute}
              >
                {isMuted ? <MicOffIcon size={22} /> : <MicOnIcon size={22} />}
              </VoiceCtrlBtn>
              <VoiceCtrlBtn label="Kanaldan Ayrıl" danger onClick={onLeaveVoice}>
                <PhoneLeaveIcon size={22} />
              </VoiceCtrlBtn>
            </div>
          </>
        ) : (
          <div style={{ textAlign: 'center', color: '#80848E' }}>
            <div style={{ fontSize: 64, marginBottom: 16 }}>🔈</div>
            <p style={{ fontSize: 20, fontWeight: '700', color: '#F2F3F5', marginBottom: 8 }}>
              {channelName}
            </p>
            {isEmpty && !isJoined && (
              <p style={{ fontSize: 14, marginBottom: 24 }}>Henüz kimse yok. İlk katılan sen ol!</p>
            )}
            {!isJoined && (
              <button
                onClick={onJoinVoice}
                style={{
                  background: '#23A559', border: 'none', borderRadius: 4,
                  color: 'white', fontSize: 15, fontWeight: '600',
                  padding: '10px 24px', cursor: 'pointer',
                }}
                onMouseEnter={e => e.currentTarget.style.background = '#1a7a40'}
                onMouseLeave={e => e.currentTarget.style.background = '#23A559'}
              >
                Kanala Katıl
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function VoiceCtrlBtn({ children, label, active, danger, onClick }) {
  const [hov, setHov] = useState(false)
  const base = danger ? '#F23F43' : (active ? '#F23F43' : '#4E5058')
  const hovColor = danger ? '#DA373C' : (active ? '#DA373C' : '#5C6070')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
      <button
        onClick={onClick}
        onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
        style={{
          width: 56, height: 56,
          background: hov ? hovColor : base,
          border: 'none', borderRadius: '50%', cursor: 'pointer',
          color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center',
          transition: 'background 0.15s',
        }}
      >
        {children}
      </button>
      <span style={{ color: '#B5BAC1', fontSize: 12 }}>{label}</span>
    </div>
  )
}
