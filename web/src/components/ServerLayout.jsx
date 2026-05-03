import Sidebar from './Sidebar'
import ChatArea from './ChatArea'
import VoiceChannelArea from './VoiceChannelArea'
import { SERVER_CONFIG } from '../data/mockData'

export default function ServerLayout({
  serverName, activeChannel, onChannelClick,
  voiceChannel, onLeaveVoice,
  isMuted, setIsMuted, isDeafened, setIsDeafened,
  messages, onSendMessage, voiceUsers, currentUser,
}) {
  const getChannelLabel = () => {
    if (activeChannel.type === 'text')
      return SERVER_CONFIG.textChannels.find(c => c.id === activeChannel.id)?.name ?? activeChannel.id
    return SERVER_CONFIG.voiceChannels.find(c => c.id === activeChannel.id)?.name ?? activeChannel.id
  }

  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      <Sidebar
        serverName={serverName}
        activeChannel={activeChannel}
        onChannelClick={onChannelClick}
        voiceChannel={voiceChannel}
        onLeaveVoice={onLeaveVoice}
        isMuted={isMuted}
        setIsMuted={setIsMuted}
        isDeafened={isDeafened}
        setIsDeafened={setIsDeafened}
        voiceUsers={voiceUsers}
        currentUser={currentUser}
      />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: '#313338', overflow: 'hidden', minWidth: 0 }}>
        {activeChannel.type === 'text' ? (
          <ChatArea
            channelName={getChannelLabel()}
            messages={messages[activeChannel.id] || []}
            onSendMessage={text => onSendMessage(activeChannel.id, text)}
          />
        ) : (
          <VoiceChannelArea
            channelName={getChannelLabel()}
            channelId={activeChannel.id}
            users={voiceUsers[activeChannel.id] || []}
            voiceChannel={voiceChannel}
            isMuted={isMuted}
            onToggleMute={() => setIsMuted(m => !m)}
            onLeaveVoice={onLeaveVoice}
            onJoinVoice={() => onChannelClick(activeChannel)}
          />
        )}
      </div>
    </div>
  )
}
