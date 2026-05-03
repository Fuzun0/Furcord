import { useState, useRef, useEffect } from 'react'
import Message from './Message'
import { HashIcon, AttachIcon, EmojiIcon, GifIcon } from './icons'

export default function ChatArea({ channelName, messages, onSendMessage }) {
  const [input, setInput] = useState('')
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const send = () => {
    const txt = input.trim()
    if (txt) { onSendMessage(txt); setInput('') }
  }

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', background: '#313338' }}>

      {/* Channel header */}
      <div style={{
        height: 48, padding: '0 16px', display: 'flex', alignItems: 'center', gap: 8,
        boxShadow: '0 1px 0 rgba(4,4,5,0.2)', flexShrink: 0,
        borderBottom: '1px solid rgba(4,4,5,0.2)',
      }}>
        <span style={{ color: '#80848E', display: 'flex' }}><HashIcon size={24} /></span>
        <span style={{ fontWeight: '700', color: '#F2F3F5', fontSize: 16 }}>{channelName}</span>
        <div style={{ flex: 1 }} />
        <span style={{ color: '#B5BAC1', fontSize: 13, cursor: 'pointer' }} title="Kanal Bilgisi">ℹ️</span>
      </div>

      {/* Messages */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px 0 4px' }}>
        {messages.length === 0 && (
          <div style={{ padding: '60px 16px', textAlign: 'center', color: '#80848E' }}>
            <div style={{
              width: 68, height: 68, background: '#35373C', borderRadius: '50%',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              margin: '0 auto 16px', fontSize: 30,
            }}>
              #
            </div>
            <p style={{ fontWeight: '700', fontSize: 20, color: '#F2F3F5', marginBottom: 6 }}>
              #{channelName}'e Hoş Geldiniz!
            </p>
            <p style={{ fontSize: 14 }}>Bu kanalın geçmişinin başlangıcı burası.</p>
          </div>
        )}

        {messages.map((msg, idx) => {
          const prev = messages[idx - 1]
          const showHeader = idx === 0 || prev.author !== msg.author || msg.timestamp !== null
          return <Message key={msg.id} message={msg} showHeader={showHeader} />
        })}
        <div ref={bottomRef} />
      </div>

      {/* Input bar */}
      <div style={{ padding: '0 16px 24px', flexShrink: 0 }}>
        <div style={{ background: '#383A40', borderRadius: 8, display: 'flex', alignItems: 'center', padding: '0 8px 0 4px' }}>
          {/* Attach */}
          <InputIconBtn title="Dosya ekle" onClick={() => {}}>
            <AttachIcon />
          </InputIconBtn>

          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder={`#${channelName} kanalına mesaj gönder`}
            style={{
              flex: 1, background: 'transparent', border: 'none', outline: 'none',
              color: '#F2F3F5', fontSize: 15, padding: '11px 4px',
              caretColor: '#F2F3F5',
            }}
          />

          {/* Right icons */}
          <div style={{ display: 'flex', gap: 2, alignItems: 'center' }}>
            <InputIconBtn title="GIF"><GifIcon /></InputIconBtn>
            <InputIconBtn title="Sticker">🎨</InputIconBtn>
            <InputIconBtn title="Emoji"><EmojiIcon /></InputIconBtn>
          </div>
        </div>
      </div>
    </div>
  )
}

function InputIconBtn({ children, onClick, title }) {
  const [hov, setHov] = useState(false)
  return (
    <button
      onClick={onClick} title={title}
      onMouseEnter={() => setHov(true)} onMouseLeave={() => setHov(false)}
      style={{
        background: 'none', border: 'none', cursor: 'pointer', padding: '6px',
        borderRadius: 4,
        color: hov ? '#F2F3F5' : '#B5BAC1',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 18,
        transition: 'color 0.1s',
      }}
    >
      {children}
    </button>
  )
}
