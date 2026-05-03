export const SERVER_CONFIG = {
  textChannels: [
    { id: 'genel', name: 'genel' },
    { id: 'oyun-genel', name: 'oyun-genel' },
    { id: 'muzik', name: 'müzik' },
  ],
  voiceChannels: [
    { id: 'lobi', name: 'Lobi' },
    { id: 'oyun', name: 'Oyun' },
    { id: 'lol', name: 'LOL' },
    { id: 'cs', name: 'CS' },
  ],
}

export function getInitialMessages() {
  return {
    genel: [
      {
        id: 1,
        author: 'fuzun',
        authorId: 'fuzun',
        initials: 'FZ',
        color: '#5865F2',
        timestamp: '29.04.2026 21:04',
        content: 'https://x.com/Ozzny_CS2/status/2049527246415675711?s=20',
        embed: {
          type: 'twitter',
          author: 'Ozzny (@Ozzny_CS2)',
          text: 'xQc & Cache, name a better duo 😭',
          hasMedia: true,
          mediaColor: '#0f1923',
          footerSite: 'X',
          footerDate: '29.04.2026 19:32',
        },
      },
      {
        id: 2,
        author: 'fuzun',
        authorId: 'fuzun',
        initials: 'FZ',
        color: '#5865F2',
        timestamp: null,
        content: 'https://x.com/Ozzny_CS2/status/2049498556319281293?s=20',
        embed: {
          type: 'twitter',
          author: 'Ozzny (@Ozzny_CS2)',
          text: 'Easy and useful A smokes for the new Cache ‼️',
          hasMedia: true,
          mediaColor: '#0d1b2a',
          footerSite: 'X',
          footerDate: '29.04.2026 17:38',
        },
      },
    ],
    'oyun-genel': [
      {
        id: 10,
        author: 'xQc',
        authorId: 'xqc',
        initials: 'XQ',
        color: '#57F287',
        timestamp: '01.05.2026 10:15',
        content: 'CS2 oynuyoruz, kim var? 🎮',
        embed: null,
      },
      {
        id: 11,
        author: 'fuzun',
        authorId: 'fuzun',
        initials: 'FZ',
        color: '#5865F2',
        timestamp: '01.05.2026 10:16',
        content: "Ben varım! Lobi'ye girdim",
        embed: null,
      },
    ],
    muzik: [],
  }
}

export function getInitialVoiceUsers() {
  return {
    lobi: [{ id: 'fuzun', name: 'fuzun', initials: 'FZ', color: '#5865F2' }],
    oyun: [{ id: 'xqc', name: 'xQc', initials: 'XQ', color: '#57F287' }],
    lol: [],
    cs: [],
  }
}
