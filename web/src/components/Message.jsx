import { useState } from 'react'
import { Avatar } from './icons'
import EmbedCard from './EmbedCard'

export default function Message({ message, showHeader }) {
  const [hov, setHov] = useState(false)
  const timeOnly = message.timestamp ? message.timestamp.split(' ')[1] : ''

  return (
    <div
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        display: 'flex', gap: 16,
        padding: showHeader ? '6px 16px 2px' : '2px 16px',
        background: hov ? 'rgba(4,4,5,0.07)' : 'transparent',
        marginTop: showHeader ? 16 : 0,
      }}
    >
      {/* Left column: avatar on first, hover-time on continuations */}
      <div style={{ width: 40, flexShrink: 0, display: 'flex', justifyContent: 'center', paddingTop: showHeader ? 2 : 0 }}>
        {showHeader
          ? <Avatar initials={message.initials} color={message.color} size={40} />
          : (
            <span style={{ color: '#80848E', fontSize: 11, display: hov ? 'block' : 'none', paddingTop: 5, textAlign: 'right', width: '100%' }}>
              {timeOnly}
            </span>
          )
        }
      </div>

      {/* Content */}
      <div style={{ flex: 1, minWidth: 0 }}>
        {showHeader && (
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
            <span
              style={{ color: message.color || '#F2F3F5', fontWeight: '500', fontSize: 15, cursor: 'pointer' }}
              onMouseEnter={e => e.currentTarget.style.textDecoration = 'underline'}
              onMouseLeave={e => e.currentTarget.style.textDecoration = 'none'}
            >
              {message.author}
            </span>
            <span style={{ color: '#80848E', fontSize: 12 }}>{message.timestamp}</span>
          </div>
        )}

        <p style={{ color: '#F2F3F5', fontSize: 15, lineHeight: 1.375, wordBreak: 'break-word' }}>
          {message.content}
        </p>

        {message.embed && <EmbedCard embed={message.embed} />}
      </div>
    </div>
  )
}
