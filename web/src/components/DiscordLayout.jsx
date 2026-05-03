import { useState, useEffect, useCallback } from 'react'
import { collection, onSnapshot, query, orderBy, doc, updateDoc, arrayUnion, arrayRemove } from 'firebase/firestore'
import { db } from '../firebase/firebase'
import { useAuth } from '../context/AuthContext'
import { MicOnIcon, MicOffIcon, HeadphonesIcon, DeafenIcon, SettingsIcon, Avatar } from './icons'
import UserSettingsModal from './UserSettingsModal'

// ── Ctrl button ────────────────────────────────────────────────────────────────
function CtrlBtn({ children, active, title, onClick }) {
  const [hov, setHov] = useState(false)
  return (
    <button title={title} onClick={onClick}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        width: 32, height: 32, border: 'none', borderRadius: 4, cursor: 'pointer',
        background: hov ? '#35373C' : 'transparent',
        color: active ? '#F23F43' : (hov ? '#F2F3F5' : '#B5BAC1'),
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0, transition: 'background 0.1s, color 0.1s',
      }}>
      {children}
    </button>
  )
}

// ── User avatar mini ───────────────────────────────────────────────────────────
function UserAvatar({ u, size = 20 }) {
  if (u.photoURL) return (
    <img src={u.photoURL} alt={u.username} title={u.username}
      style={{ width: size, height: size, borderRadius: '50%', objectFit: 'cover', flexShrink: 0 }} />
  )
  const initials = (u.username ?? '?').slice(0, 2).toUpperCase()
  const color    = u.color ?? '#5865F2'
  return (
    <div title={u.username} style={{
      width: size, height: size, borderRadius: '50%', background: color,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontSize: size * 0.38, fontWeight: 700, color: '#fff', flexShrink: 0,
    }}>{initials}</div>
  )
}

// ── Channel row with active users ──────────────────────────────────────────────
function ChannelRow({ channel, connected, selected, activeUsers, currentUser, onJoin }) {
  const [hov, setHov] = useState(false)
  const isConnected   = connected
  return (
    <div style={{ marginBottom: 2 }}>
      <div
        onClick={onJoin}
        onMouseEnter={() => setHov(true)}
        onMouseLeave={() => setHov(false)}
        style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '5px 8px', borderRadius: 4, cursor: 'pointer',
          background: selected ? '#404249' : (hov ? '#35373C' : 'transparent'),
          color: isConnected ? '#23A55A' : (selected ? '#F2F3F5' : (hov ? '#DBDEE1' : '#8E9297')),
          fontSize: 15, fontWeight: isConnected ? 700 : (selected ? 600 : 400),
          transition: 'background 0.1s, color 0.1s', userSelect: 'none',
        }}>
        {/* Speaker icon */}
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" style={{ flexShrink: 0, opacity: isConnected ? 1 : 0.7 }}>
          <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/>
        </svg>
        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {channel.name}
        </span>
        {activeUsers.length > 0 && (
          <span style={{ fontSize: 11, color: '#6D6F78', fontWeight: 400 }}>{activeUsers.length}</span>
        )}
      </div>

      {/* Active users list */}
      {activeUsers.length > 0 && (
        <div style={{ paddingLeft: 30 }}>
          {activeUsers.map(u => (
            <div key={u.uid} style={{
              display: 'flex', alignItems: 'center', gap: 8,
              padding: '3px 4px', borderRadius: 4,
              color: u.uid === currentUser?.uid ? '#23A55A' : '#B5BAC1',
              fontSize: 13,
            }}>
              <UserAvatar u={u} size={20} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {u.username}{u.uid === currentUser?.uid ? ' (sen)' : ''}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Voice connected panel ──────────────────────────────────────────────────────
function VoiceConnectedPanel({ channelName, onDisconnect }) {
  return (
    <div style={{
      background: '#232428', borderTop: '1px solid #1E1F22',
      padding: '8px 12px', flexShrink: 0,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#23A55A', flexShrink: 0 }} />
          <span style={{ color: '#23A55A', fontSize: 12, fontWeight: 700 }}>Ses Bağlantısı Kuruldu</span>
        </div>
        <button
          onClick={onDisconnect}
          title="Kanaldan ayrıl"
          style={{
            background: 'transparent', border: 'none', cursor: 'pointer',
            color: '#B5BAC1', padding: 4, borderRadius: 4, display: 'flex',
            transition: 'color 0.1s, background 0.1s',
          }}
          onMouseEnter={e => { e.currentTarget.style.color = '#F23F43'; e.currentTarget.style.background = '#35373C' }}
          onMouseLeave={e => { e.currentTarget.style.color = '#B5BAC1'; e.currentTarget.style.background = 'transparent' }}
        >
          {/* Phone hang-up icon */}
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
            <path d="M20.01 15.38c-1.23 0-2.42-.2-3.53-.56a.977.977 0 00-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3.9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-.99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-.45-.99-.99-.99z"/>
          </svg>
        </button>
      </div>
      <div style={{ color: '#8E9297', fontSize: 12, paddingLeft: 14 }}>
        {channelName}
      </div>
    </div>
  )
}

// ── User panel ─────────────────────────────────────────────────────────────────
function UserPanel({ user, isMuted, setIsMuted, isDeafened, setIsDeafened }) {
  const { logout } = useAuth()
  const [settingsOpen, setSettingsOpen] = useState(false)
  return (
    <>
      {settingsOpen && <UserSettingsModal onClose={() => setSettingsOpen(false)} />}
      <div style={{
        background: '#232428', padding: '0 8px',
        height: 52, display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0,
        borderTop: '1px solid #1E1F22',
      }}>
        <div style={{ position: 'relative', flexShrink: 0 }}>
          {user.photoURL
            ? <img src={user.photoURL} alt="" style={{ width: 32, height: 32, borderRadius: '50%', objectFit: 'cover' }} />
            : <Avatar initials={user.initials} color={user.color} size={32} />
          }
          <span style={{ position: 'absolute', bottom: -2, right: -2, width: 12, height: 12, background: '#23A559', borderRadius: '50%', border: '2px solid #232428' }} />
        </div>
        <div style={{ flex: 1, overflow: 'hidden' }}>
          <div style={{ color: '#F2F3F5', fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', lineHeight: 1.2 }}>{user.username}</div>
          <div style={{ color: '#80848E', fontSize: 11, lineHeight: 1.2 }}>Çevrimiçi</div>
        </div>
        <CtrlBtn active={isMuted} title={isMuted ? 'Susturmayı kaldır' : 'Mikrofonu sustur'} onClick={() => setIsMuted(m => !m)}>
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

// ── DiscordLayout ──────────────────────────────────────────────────────────────
export default function DiscordLayout({ serverId, serverName }) {
  const { user }    = useAuth()
  const [voiceChannels, setVoiceChannels]               = useState([])
  const [channelUsers, setChannelUsers]                 = useState({})   // { channelId: [userObj, ...] }
  const [selectedChannelId, setSelectedChannelId]       = useState(null) // visually selected
  const [connectedVoiceChannelId, setConnectedVoiceChannelId] = useState(null)
  const [isMuted, setIsMuted]       = useState(false)
  const [isDeafened, setIsDeafened] = useState(false)
  const [loading, setLoading]       = useState(true)

  // ── Listen to voiceChannels collection ────────────────────────────────────
  useEffect(() => {
    if (!serverId) return
    const q = query(collection(db, 'servers', serverId, 'voiceChannels'), orderBy('order', 'asc'))
    const unsub = onSnapshot(q, snap => {
      const channels = snap.docs.map(d => ({ id: d.id, ...d.data() }))
      setVoiceChannels(channels)
      if (channels.length > 0) setSelectedChannelId(prev => prev ?? channels[0].id)
      setLoading(false)
    }, () => setLoading(false))
    return unsub
  }, [serverId])

  // ── Listen to activeUsers on every voiceChannel ───────────────────────────
  useEffect(() => {
    if (!serverId || voiceChannels.length === 0) return
    const unsubs = voiceChannels.map(ch => {
      return onSnapshot(doc(db, 'servers', serverId, 'voiceChannels', ch.id), snap => {
        const users = snap.data()?.activeUsers ?? []
        setChannelUsers(prev => ({ ...prev, [ch.id]: users }))
      })
    })
    return () => unsubs.forEach(u => u())
  }, [serverId, voiceChannels])

  // ── Join a voice channel ─────────────────────────────────────────────────
  const joinChannel = useCallback(async (ch) => {
    if (!user) return
    const userEntry = {
      uid:      user.uid,
      username: user.username,
      color:    user.color ?? '#5865F2',
      photoURL: user.photoURL ?? '',
    }
    // Leave current channel first
    if (connectedVoiceChannelId && connectedVoiceChannelId !== ch.id) {
      const prev = channelUsers[connectedVoiceChannelId] ?? []
      const filtered = prev.filter(u => u.uid !== user.uid)
      await updateDoc(doc(db, 'servers', serverId, 'voiceChannels', connectedVoiceChannelId), {
        activeUsers: filtered,
      })
    }
    // Join new channel
    const existing = (channelUsers[ch.id] ?? []).filter(u => u.uid !== user.uid)
    await updateDoc(doc(db, 'servers', serverId, 'voiceChannels', ch.id), {
      activeUsers: [...existing, userEntry],
    })
    setConnectedVoiceChannelId(ch.id)
    setSelectedChannelId(ch.id)
  }, [user, serverId, connectedVoiceChannelId, channelUsers])

  // ── Leave voice channel ──────────────────────────────────────────────────
  const leaveChannel = useCallback(async () => {
    if (!connectedVoiceChannelId || !user) return
    const prev = channelUsers[connectedVoiceChannelId] ?? []
    await updateDoc(doc(db, 'servers', serverId, 'voiceChannels', connectedVoiceChannelId), {
      activeUsers: prev.filter(u => u.uid !== user.uid),
    })
    setConnectedVoiceChannelId(null)
  }, [connectedVoiceChannelId, user, serverId, channelUsers])

  // ── Cleanup on unmount ───────────────────────────────────────────────────
  useEffect(() => {
    return () => { if (connectedVoiceChannelId) leaveChannel() }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const connectedChannel = voiceChannels.find(c => c.id === connectedVoiceChannelId)
  const selectedChannel  = voiceChannels.find(c => c.id === selectedChannelId)

  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden', fontFamily: "'gg sans', 'Noto Sans', sans-serif" }}>

      {/* ── Sidebar ── */}
      <div style={{ width: 240, flexShrink: 0, background: '#2B2D31', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

        {/* Server name */}
        <div style={{ height: 48, padding: '0 16px', display: 'flex', alignItems: 'center', borderBottom: '1px solid #1E1F22', color: '#F2F3F5', fontWeight: 700, fontSize: 15, flexShrink: 0, cursor: 'pointer', boxShadow: '0 1px 0 rgba(4,4,5,0.2)' }}>
          <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{serverName || serverId}</span>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="#B5BAC1" style={{ flexShrink: 0 }}>
            <path d="M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z"/>
          </svg>
        </div>

        {/* Channel list */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 8px 0', scrollbarWidth: 'thin', scrollbarColor: '#1E1F22 transparent' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 4px 4px 6px', marginBottom: 4, color: '#8E9297', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.05em' }}>
            <span>Ses Kanalları</span>
          </div>

          {loading && <div style={{ color: '#8E9297', fontSize: 13, padding: '8px 6px' }}>Yükleniyor…</div>}

          {!loading && voiceChannels.map(ch => (
            <ChannelRow
              key={ch.id}
              channel={ch}
              connected={connectedVoiceChannelId === ch.id}
              selected={selectedChannelId === ch.id}
              activeUsers={channelUsers[ch.id] ?? []}
              currentUser={user}
              onJoin={() => joinChannel(ch)}
            />
          ))}

          {!loading && voiceChannels.length === 0 && (
            <div style={{ color: '#8E9297', fontSize: 13, padding: '8px 6px', textAlign: 'center' }}>Henüz kanal yok.</div>
          )}
        </div>

        {/* Voice connected panel */}
        {connectedChannel && (
          <VoiceConnectedPanel
            channelName={connectedChannel.name}
            onDisconnect={leaveChannel}
          />
        )}

        {/* User panel */}
        {user && (
          <UserPanel
            user={user}
            isMuted={isMuted}
            setIsMuted={setIsMuted}
            isDeafened={isDeafened}
            setIsDeafened={setIsDeafened}
          />
        )}
      </div>

      {/* ── Main area ── */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden', minWidth: 0 }}>
        <div style={{ height: 48, padding: '0 16px', display: 'flex', alignItems: 'center', gap: 8, background: '#313338', borderBottom: '1px solid #1E1F22', boxShadow: '0 1px 0 rgba(4,4,5,0.2)', flexShrink: 0 }}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="#8E9297">
            <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/>
          </svg>
          <span style={{ color: '#F2F3F5', fontWeight: 700, fontSize: 15 }}>
            {selectedChannel?.name ?? '—'}
          </span>
        </div>

        {/* Main content */}
        {connectedVoiceChannelId && connectedVoiceChannelId === selectedChannelId ? (
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: '#313338', padding: 32, overflow: 'auto' }}>
            {/* Active users grid */}
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16 }}>
              {(channelUsers[connectedVoiceChannelId] ?? []).map(u => (
                <div key={u.uid} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, width: 100 }}>
                  <div style={{ position: 'relative' }}>
                    <UserAvatar u={u} size={64} />
                    <span style={{ position: 'absolute', bottom: 0, right: 0, width: 14, height: 14, borderRadius: '50%', background: '#23A55A', border: '2px solid #313338' }} />
                  </div>
                  <span style={{ color: '#F2F3F5', fontSize: 13, fontWeight: 600, textAlign: 'center', wordBreak: 'break-word' }}>
                    {u.username}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', background: '#313338', gap: 16 }}>
            <svg width="64" height="64" viewBox="0 0 24 24" fill="#3F4147">
              <path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/>
            </svg>
            <div style={{ fontSize: 20, fontWeight: 700, color: '#F2F3F5' }}>{selectedChannel?.name ?? '—'}</div>
            <div style={{ fontSize: 14, color: '#80848E' }}>Kanala katılmak için adına tıkla.</div>
          </div>
        )}
      </div>
    </div>
  )
}