import { useState } from 'react'
import ConnectScreen from './components/ConnectScreen'
import ServerLayout from './components/ServerLayout'
import DiscordLayout from './components/DiscordLayout'
import AuthScreen from './components/AuthScreen'
import { getInitialMessages, getInitialVoiceUsers } from './data/mockData'
import { useAuth } from './context/AuthContext'

// ── Loading splash ────────────────────────────────────────────────────────────
function LoadingScreen() {
  return (
    <div style={{ height: '100vh', background: '#313338', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 20 }}>
      <div style={{ width: 72, height: 72, background: '#5865F2', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 34, animation: 'pulse 1.5s ease-in-out infinite' }}>
        🎙️
      </div>
      <p style={{ color: '#B5BAC1', fontSize: 15 }}>Yükleniyor…</p>
      <style>{`@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.5} }`}</style>
    </div>
  )
}

// ── Authenticated app shell ───────────────────────────────────────────────────
function AuthenticatedApp({ currentUser }) {
  const [activeServerId, setActiveServerId]     = useState(null)
  const [activeServerName, setActiveServerName] = useState('')
  const [activeChannel, setActiveChannel]       = useState({ type: 'text', id: 'genel' })
  const [voiceChannel, setVoiceChannel]   = useState(null)
  const [isMuted, setIsMuted]             = useState(false)
  const [isDeafened, setIsDeafened]       = useState(false)
  const [messages, setMessages]           = useState(getInitialMessages)
  const [voiceUsers, setVoiceUsers]       = useState(getInitialVoiceUsers)

  const handleConnect = (id, name) => {
    setActiveServerId(id)
    setActiveServerName(name || id)
  }

  const handleChannelClick = (channel) => {
    setActiveChannel(channel)
    if (channel.type === 'voice' && voiceChannel !== channel.id) {
      if (voiceChannel) {
        setVoiceUsers(prev => ({
          ...prev,
          [voiceChannel]: prev[voiceChannel].filter(u => u.id !== currentUser.uid),
        }))
      }
      setVoiceUsers(prev => ({
        ...prev,
        [channel.id]: [
          ...prev[channel.id].filter(u => u.id !== currentUser.uid),
          { id: currentUser.uid, name: currentUser.username, initials: currentUser.initials, color: currentUser.color },
        ],
      }))
      setVoiceChannel(channel.id)
    }
  }

  const handleLeaveVoice = () => {
    if (!voiceChannel) return
    setVoiceUsers(prev => ({
      ...prev,
      [voiceChannel]: prev[voiceChannel].filter(u => u.id !== currentUser.uid),
    }))
    setVoiceChannel(null)
    if (activeChannel.type === 'voice') setActiveChannel({ type: 'text', id: 'genel' })
  }

  const handleSendMessage = (channelId, text) => {
    setMessages(prev => ({
      ...prev,
      [channelId]: [
        ...(prev[channelId] || []),
        {
          id: Date.now(),
          author: currentUser.username,
          authorId: currentUser.uid,
          initials: currentUser.initials,
          color: currentUser.color,
          timestamp: new Date().toLocaleString('tr-TR'),
          content: text,
          embed: null,
        },
      ],
    }))
  }

  if (!activeServerId) return <ConnectScreen onConnect={handleConnect} currentUser={currentUser} />
  return <DiscordLayout serverId={activeServerId} serverName={activeServerName} />
}

// ── Root ──────────────────────────────────────────────────────────────────────
export default function App() {
  const { user, loading } = useAuth()

  if (loading) return <LoadingScreen />
  if (!user)   return <AuthScreen />
  return <AuthenticatedApp currentUser={user} />
}

