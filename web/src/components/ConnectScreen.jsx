import { useState } from 'react'
import { db } from '../firebase/firebase'
import { doc, getDoc, addDoc, collection, serverTimestamp } from 'firebase/firestore'

const inputStyle = (hasError) => ({
  width: '100%',
  background: '#1E1F22',
  border: `1.5px solid ${hasError ? '#F23F43' : 'transparent'}`,
  borderRadius: '4px',
  padding: '10px 16px',
  color: '#F2F3F5',
  fontSize: '15px',
  outline: 'none',
  boxSizing: 'border-box',
  transition: 'border-color 0.15s',
})

const labelStyle = {
  display: 'block',
  color: '#B5BAC1',
  fontSize: '11px',
  fontWeight: '700',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  marginBottom: '8px',
}

export default function ConnectScreen({ onConnect, currentUser }) {
  const [joinId, setJoinId]           = useState('')
  const [joinError, setJoinError]     = useState('')
  const [joinLoading, setJoinLoading] = useState(false)

  const [createName, setCreateName]       = useState('')
  const [createError, setCreateError]     = useState('')
  const [createLoading, setCreateLoading] = useState(false)

  const handleJoin = async (e) => {
    e.preventDefault()
    const id = joinId.trim()
    if (!id) { setJoinError("Lütfen bir Sunucu ID'si girin."); return }
    setJoinLoading(true)
    setJoinError('')
    try {
      const snap = await getDoc(doc(db, 'servers', id))
      if (!snap.exists()) {
        setJoinError('Bu ID ile bir sunucu bulunamadı.')
      } else {
        onConnect(id, snap.data().name)
      }
    } catch {
      setJoinError('Bağlanırken hata oluştu.')
    } finally {
      setJoinLoading(false)
    }
  }

  const handleCreate = async (e) => {
    e.preventDefault()
    const name = createName.trim()
    if (!name) { setCreateError('Lütfen bir sunucu adı girin.'); return }
    setCreateLoading(true)
    setCreateError('')
    try {
      const serverRef = await addDoc(collection(db, 'servers'), {
        name,
        creatorUid: currentUser.uid,
        createdAt: serverTimestamp(),
      })
      const vcRef = collection(db, 'servers', serverRef.id, 'voiceChannels')
      await addDoc(vcRef, { name: 'Genel', order: 0 })
      await addDoc(vcRef, { name: 'Oyun', order: 1 })
      onConnect(serverRef.id, name)
    } catch {
      setCreateError('Sunucu oluşturulurken hata oluştu.')
    } finally {
      setCreateLoading(false)
    }
  }

  const divider = (
    <div style={{ display: 'flex', alignItems: 'center', gap: '12px', margin: '28px 0' }}>
      <div style={{ flex: 1, height: '1px', background: '#3F4147' }} />
      <span style={{ color: '#6D6F78', fontSize: '12px', fontWeight: '600', textTransform: 'uppercase' }}>veya</span>
      <div style={{ flex: 1, height: '1px', background: '#3F4147' }} />
    </div>
  )

  return (
    <div style={{ height: '100vh', background: '#313338', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ background: '#2B2D31', borderRadius: '8px', padding: '40px 32px', width: '460px', boxShadow: '0 8px 32px rgba(0,0,0,0.6)' }}>

        {/* Header */}
        <div style={{ textAlign: 'center', marginBottom: '32px' }}>
          <div style={{ width: '72px', height: '72px', background: '#5865F2', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 16px', fontSize: '34px' }}>
            🎮
          </div>
          <h1 style={{ color: '#F2F3F5', fontSize: '22px', fontWeight: '700', marginBottom: '6px' }}>Sunucu Lobisi</h1>
          <p style={{ color: '#B5BAC1', fontSize: '14px', lineHeight: 1.4 }}>Mevcut bir sunucuya katıl veya yenisini oluştur.</p>
        </div>

        {/* Join section */}
        <p style={{ color: '#F2F3F5', fontSize: '13px', fontWeight: '700', marginBottom: '14px' }}>SUNUCUYA KATIL</p>
        <form onSubmit={handleJoin}>
          <label style={labelStyle}>SUNUCU ID'Sİ</label>
          <input
            value={joinId}
            onChange={e => { setJoinId(e.target.value); setJoinError('') }}
            placeholder="Sunucu ID'sini yapıştır"
            disabled={joinLoading}
            style={inputStyle(!!joinError)}
            onFocus={e => { if (!joinError) e.target.style.borderColor = '#5865F2' }}
            onBlur={e => { if (!joinError) e.target.style.borderColor = 'transparent' }}
          />
          {joinError && <p style={{ color: '#F23F43', fontSize: '12px', margin: '6px 0 0' }}>{joinError}</p>}
          <button
            type="submit"
            disabled={joinLoading}
            style={{ width: '100%', background: '#5865F2', border: 'none', borderRadius: '4px', padding: '12px', color: 'white', fontSize: '15px', fontWeight: '600', cursor: joinLoading ? 'not-allowed' : 'pointer', marginTop: '16px', opacity: joinLoading ? 0.7 : 1, transition: 'background 0.15s' }}
            onMouseEnter={e => { if (!joinLoading) e.currentTarget.style.background = '#4752C4' }}
            onMouseLeave={e => { if (!joinLoading) e.currentTarget.style.background = '#5865F2' }}
          >
            {joinLoading ? 'Kontrol ediliyor…' : 'Bağlan'}
          </button>
        </form>

        {divider}

        {/* Create section */}
        <p style={{ color: '#F2F3F5', fontSize: '13px', fontWeight: '700', marginBottom: '14px' }}>YENİ SUNUCU OLUŞTUR</p>
        <form onSubmit={handleCreate}>
          <label style={labelStyle}>SUNUCU ADI</label>
          <input
            value={createName}
            onChange={e => { setCreateName(e.target.value); setCreateError('') }}
            placeholder="Sunucuna bir isim ver"
            disabled={createLoading}
            style={inputStyle(!!createError)}
            onFocus={e => { if (!createError) e.target.style.borderColor = '#5865F2' }}
            onBlur={e => { if (!createError) e.target.style.borderColor = 'transparent' }}
          />
          {createError && <p style={{ color: '#F23F43', fontSize: '12px', margin: '6px 0 0' }}>{createError}</p>}
          <button
            type="submit"
            disabled={createLoading}
            style={{ width: '100%', background: '#23A55A', border: 'none', borderRadius: '4px', padding: '12px', color: 'white', fontSize: '15px', fontWeight: '600', cursor: createLoading ? 'not-allowed' : 'pointer', marginTop: '16px', opacity: createLoading ? 0.7 : 1, transition: 'background 0.15s' }}
            onMouseEnter={e => { if (!createLoading) e.currentTarget.style.background = '#1A8A47' }}
            onMouseLeave={e => { if (!createLoading) e.currentTarget.style.background = '#23A55A' }}
          >
            {createLoading ? 'Oluşturuluyor…' : 'Oluştur'}
          </button>
        </form>
      </div>
    </div>
  )
}
