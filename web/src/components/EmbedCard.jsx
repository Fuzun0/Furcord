// Embed card for Twitter/X link previews

export default function EmbedCard({ embed }) {
  if (embed.type !== 'twitter') return null

  return (
    <div style={{
      marginTop: 8,
      maxWidth: 440,
      background: '#2B2D31',
      border: '1px solid #1E1F22',
      borderLeft: '4px solid #1D9BF0',
      borderRadius: '4px',
      overflow: 'hidden',
    }}>
      <div style={{ padding: '12px 16px 4px' }}>
        {/* Site label */}
        <div style={{ color: '#80848E', fontSize: 12, marginBottom: 6 }}>
          𝕏 / Twitter
        </div>

        {/* Tweet author */}
        <div style={{ color: '#F2F3F5', fontWeight: '700', fontSize: 14, marginBottom: 4 }}>
          {embed.author}
        </div>

        {/* Tweet text */}
        <div style={{ color: '#DBDEE1', fontSize: 14, lineHeight: 1.5, marginBottom: embed.hasMedia ? 10 : 0 }}>
          {embed.text}
        </div>
      </div>

      {/* Media placeholder */}
      {embed.hasMedia && (
        <div style={{
          margin: '0 16px 12px',
          height: 200,
          background: embed.mediaColor || '#1a2a3a',
          borderRadius: 4,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          position: 'relative', overflow: 'hidden',
        }}>
          {/* Gradient overlay */}
          <div style={{
            position: 'absolute', inset: 0,
            background: 'linear-gradient(135deg, rgba(0,0,0,0.3) 0%, rgba(0,0,0,0.1) 100%)',
          }} />
          {/* Play button */}
          <div style={{
            width: 56, height: 56, background: 'rgba(0,0,0,0.7)',
            borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '2px solid rgba(255,255,255,0.25)', zIndex: 1,
          }}>
            <svg width="26" height="26" viewBox="0 0 24 24" fill="white">
              <path d="M8 5v14l11-7z"/>
            </svg>
          </div>
          {/* Kick-style bottom bar */}
          <div style={{
            position: 'absolute', bottom: 0, left: 0, right: 0, padding: '6px 10px',
            background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', gap: 8,
          }}>
            <div style={{ width: 20, height: 20, background: '#53FC18', borderRadius: 3, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <span style={{ fontSize: 10, fontWeight: '900', color: '#000' }}>K</span>
            </div>
            <span style={{ color: 'white', fontSize: 11, opacity: 0.85 }}>KICK.COM/XQC</span>
          </div>
        </div>
      )}

      {/* Footer */}
      <div style={{ padding: '0 16px 10px', color: '#80848E', fontSize: 11 }}>
        {embed.footerSite} • {embed.footerDate}
      </div>
    </div>
  )
}
