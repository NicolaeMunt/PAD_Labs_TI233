import { useState } from 'react'

const GENRES = ['ACTION', 'ADVENTURE', 'RPG', 'STRATEGY', 'SPORTS', 'SIMULATION']

// Each endpoint describes its inputs; the generic card below does the rest.
// where: 'path' fills {id}, 'query' goes in ?a=b, 'body' goes in the JSON body.
const bodyFields = [
  { name: 'title', where: 'body', placeholder: 'The Witcher 3' },
  { name: 'developer', where: 'body', placeholder: 'CD Projekt Red' },
  { name: 'genre', where: 'body', options: GENRES },
  { name: 'releaseYear', where: 'body', type: 'number', placeholder: '2015' },
]
const idField = { name: 'id', where: 'path', placeholder: 'game id', useLastId: true }

const ENDPOINTS = [
  {
    method: 'GET', path: '/api/games', desc: 'List games (paged, optional genre filter)',
    fields: [
      { name: 'genre', where: 'query', options: ['', ...GENRES] },
      { name: 'page', where: 'query', type: 'number', placeholder: '0' },
      { name: 'size', where: 'query', type: 'number', placeholder: '20' },
      { name: 'sort', where: 'query', placeholder: 'title,asc' },
    ],
  },
  { method: 'GET', path: '/api/games/{id}', desc: 'Get one game (cached in Redis)', fields: [idField] },
  { method: 'POST', path: '/api/games', desc: 'Create a game', fields: bodyFields },
  { method: 'PUT', path: '/api/games/{id}', desc: 'Update a game', fields: [idField, ...bodyFields] },
  { method: 'DELETE', path: '/api/games/{id}', desc: 'Soft-delete a game', fields: [idField] },
  { method: 'GET', path: '/whoami', desc: 'Instance id, warehouse and database', fields: [] },
  { method: 'GET', path: '/actuator/health', desc: 'Health of the gateway', fields: [] },
  { method: 'GET', path: '/gateway/state', desc: 'Gateway: backends, connection pool, cache stats', fields: [] },
]

function EndpointCard({ ep, lastId, onId }) {
  // body genre has no empty option, so default it to the first genre
  const [values, setValues] = useState(
    ep.fields.some(f => f.name === 'genre' && f.where === 'body') ? { genre: GENRES[0] } : {},
  )
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)

  // the id field falls back to the id of the last response until the user types one
  const get = f => (f.useLastId && values[f.name] === undefined ? lastId : values[f.name]) ?? ''
  const set = (name, v) => setValues(s => ({ ...s, [name]: v }))

  async function send() {
    let url = ep.path
    const query = new URLSearchParams()
    const body = {}
    for (const f of ep.fields) {
      const v = get(f)
      if (f.where === 'path') url = url.replace(`{${f.name}}`, encodeURIComponent(v))
      else if (f.where === 'query' && v !== '') query.set(f.name, v)
      else if (f.where === 'body') body[f.name] = f.type === 'number' && v !== '' ? Number(v) : v
    }
    if (query.size) url += '?' + query

    const init = { method: ep.method }
    if (ep.method === 'POST' || ep.method === 'PUT') {
      init.headers = { 'Content-Type': 'application/json' }
      init.body = JSON.stringify(body)
    }

    setLoading(true)
    const t0 = performance.now()
    try {
      const res = await fetch(url, init)
      const text = await res.text()
      let data = text
      try { data = JSON.parse(text) } catch { /* keep raw text */ }
      setResult({
        status: res.status, ok: res.ok, ms: Math.round(performance.now() - t0), url,
        instance: res.headers.get('X-Instance-Id'), cache: res.headers.get('X-Cache'), data,
      })
      if (data && data.id) onId(data.id)
    } catch (e) {
      setResult({ status: 'ERR', ok: false, ms: Math.round(performance.now() - t0), url, data: String(e) })
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="card">
      <header>
        <span className={`method ${ep.method}`}>{ep.method}</span>
        <code>{ep.path}</code>
      </header>
      <p className="desc">{ep.desc}</p>

      {ep.fields.length > 0 && (
        <div className="fields">
          {ep.fields.map(f => (
            <label key={f.where + f.name}>
              {f.name}
              {f.options ? (
                <select value={get(f)} onChange={e => set(f.name, e.target.value)}>
                  {f.options.map(o => <option key={o} value={o}>{o || '(any)'}</option>)}
                </select>
              ) : (
                <input
                  type={f.type || 'text'} value={get(f)} placeholder={f.placeholder}
                  onChange={e => set(f.name, e.target.value)}
                />
              )}
            </label>
          ))}
        </div>
      )}

      <button onClick={send} disabled={loading}>{loading ? 'Sending…' : 'Send'}</button>

      {result && (
        <div className="result">
          <div className={`status ${result.ok ? 'ok' : 'bad'}`}>
            {result.status} · {result.ms} ms{result.instance && ` · instance ${result.instance}`}
            {result.cache && ` · cache ${result.cache}`}
            <span className="url">{ep.method} {result.url}</span>
          </div>
          <pre>{typeof result.data === 'string' ? result.data || '(empty body)' : JSON.stringify(result.data, null, 2)}</pre>
        </div>
      )}
    </section>
  )
}

export default function App() {
  const [lastId, setLastId] = useState('')
  return (
    <main>
      <h1>GameVault API Tester</h1>
      <p className="sub">
        Calls go through the gateway, which load-balances over three API instances and caches
        GET responses in Redis. Each result shows which instance answered and the cache status.
        The id from the last response is pre-filled in the id fields.
      </p>
      {ENDPOINTS.map(ep => (
        <EndpointCard key={ep.method + ep.path} ep={ep} lastId={lastId} onId={setLastId} />
      ))}
    </main>
  )
}
