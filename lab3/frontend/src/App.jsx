import { useCallback, useEffect, useState } from 'react'

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

// Warehouses: each one has N API instances and its own replica set with max(3, N) database
// copies. The gateway starts and stops all of them on request (PUT /gateway/warehouses/{name}
// {"replicas": n}); the work runs in the background, so the table is polled.
const DB_STATE_CLASS = {
  PRIMARY: 'primary', SECONDARY: 'secondary',
  STARTUP: 'syncing', STARTUP2: 'syncing', RECOVERING: 'syncing', ROLLBACK: 'syncing',
}

function Warehouses({ selected, onSelect }) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [newName, setNewName] = useState('')
  const [newReplicas, setNewReplicas] = useState(1)

  const load = useCallback(async () => {
    try {
      const res = await fetch('/gateway/warehouses')
      const body = await res.json()
      setData(body)
      if (!selected) onSelect(body.defaultWarehouse)
    } catch (e) {
      setError('Gateway unreachable: ' + e)
    }
  }, [selected, onSelect])

  // poll, so new instances are seen going STARTING → UP and Mongo nodes STARTUP2 → SECONDARY
  useEffect(() => {
    load()
    const timer = setInterval(load, 2000)
    return () => clearInterval(timer)
  }, [load])

  async function call(method, name, replicas) {
    setBusy(true)
    setError(null)
    try {
      const init = { method }
      if (replicas !== undefined) {
        init.headers = { 'Content-Type': 'application/json' }
        init.body = JSON.stringify({ replicas })
      }
      const res = await fetch(`/gateway/warehouses/${encodeURIComponent(name)}`, init)
      if (!res.ok) {
        const problem = await res.json().catch(() => ({}))
        setError(`${res.status}: ${problem.detail || res.statusText}`)
      }
      await load()
    } finally {
      setBusy(false)
    }
  }

  async function add(e) {
    e.preventDefault()
    const name = newName.trim().toLowerCase()
    if (!name) return
    if (data?.warehouses.some(w => w.name === name)) {
      setError(`Warehouse ${name} already exists; use + to add instances`)
      return
    }
    await call('PUT', name, Number(newReplicas))
    onSelect(name)
    setNewName('')
  }

  async function remove(name) {
    if (!window.confirm(`Remove ${name}? This stops its API instances and deletes all its database copies `
        + `and their data.`)) return
    await call('DELETE', name)
  }

  if (!data) return <section className="card">{error || 'Loading warehouses…'}</section>
  const max = data.maxReplicas
  const minDb = data.minDbCopies ?? 3
  const n = Math.max(1, Number(newReplicas) || 1)

  return (
    <section className="card warehouses">
      <header><strong>Warehouses</strong>
        <span className="desc">
          {data.scalingEnabled ? 'click a row to send requests to it' : 'scaling is off (gateway not running in Docker)'}
        </span>
      </header>

      <table>
        <thead>
          <tr><th></th><th>Warehouse</th><th>API instances</th><th>Database copies</th><th>Replicas</th><th></th></tr>
        </thead>
        <tbody>
          {data.warehouses.map(w => {
            const fixed = w.instances.filter(i => !i.managed).length
            const desired = w.desiredReplicas ?? w.replicas
            return (
              <tr key={w.name} className={w.name === selected ? 'selected' : ''} onClick={() => onSelect(w.name)}>
                <td><input type="radio" readOnly checked={w.name === selected} /></td>
                <td>
                  <div>{w.name}{w.name === data.defaultWarehouse && <span className="tag">default</span>}</div>
                  {w.phase && <span className={`phase ${w.phase}`}>{w.phase.toLowerCase()}</span>}
                  <div className="dbname"><code>{w.database}</code></div>
                  {w.lastError && <div className="error" title={w.lastError}>{w.lastError}</div>}
                </td>
                <td>
                  <div className="chips">
                    {w.instances.map(i => (
                      <span key={i.name} className={`chip ${i.state}`} title={i.lastError || i.url}>
                        {i.name.replace(/:\d+$/, '')} · {i.state.toLowerCase()}
                        {i.directUrl && (
                          <a href={`${i.directUrl}/whoami`} target="_blank" rel="noreferrer"
                             onClick={e => e.stopPropagation()}> :{i.hostPort}</a>
                        )}
                      </span>
                    ))}
                  </div>
                </td>
                <td>
                  {w.replicaSet && <div className="rsname"><code>{w.replicaSet}</code></div>}
                  <div className="chips">
                    {(w.dbNodes ?? []).map(d => (
                      <span key={d.name} className={`chip db ${DB_STATE_CLASS[d.state] ?? 'bad'}`} title={d.host}>
                        {d.name} · {d.state.toLowerCase()}
                      </span>
                    ))}
                  </div>
                </td>
                <td className="replicas" onClick={e => e.stopPropagation()}>
                  <button disabled={busy || !data.scalingEnabled || desired <= Math.max(fixed, 1)}
                          onClick={() => call('PUT', w.name, desired - 1)}>−</button>
                  <span title="healthy / running (wanted)">
                    {w.healthy}/{w.replicas}{desired !== w.replicas && ` → ${desired}`}
                  </span>
                  <button disabled={busy || !data.scalingEnabled || desired >= max}
                          onClick={() => call('PUT', w.name, desired + 1)}>+</button>
                </td>
                <td onClick={e => e.stopPropagation()}>
                  {fixed === 0 && w.name !== data.defaultWarehouse && (
                    <button disabled={busy || !data.scalingEnabled} onClick={() => remove(w.name)}>Remove</button>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>

      {data.scalingEnabled && (
        <form className="add" onSubmit={add}>
          <label>new warehouse
            <input value={newName} placeholder="warehouse-4" onChange={e => setNewName(e.target.value)} />
          </label>
          <label>API instances
            <input type="number" min="1" max={max} value={newReplicas} onChange={e => setNewReplicas(e.target.value)} />
          </label>
          <span className="desc">+ {Math.max(minDb, n)} database copies</span>
          <button disabled={busy || !newName.trim()}>{busy ? 'Working…' : 'Add warehouse'}</button>
        </form>
      )}
      {error && <div className="status bad">{error}</div>}
    </section>
  )
}

function EndpointCard({ ep, lastId, onId, warehouse }) {
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

    // every call goes to the selected warehouse (its own database)
    const init = { method: ep.method, headers: warehouse ? { 'X-Warehouse': warehouse } : {} }
    if (ep.method === 'POST' || ep.method === 'PUT') {
      init.headers['Content-Type'] = 'application/json'
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
        instance: res.headers.get('X-Instance-Id'), cache: res.headers.get('X-Cache'),
        warehouse: res.headers.get('X-Warehouse'), data,
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
            {result.status} · {result.ms} ms{result.warehouse && ` · ${result.warehouse}`}
            {result.instance && ` · instance ${result.instance}`}
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
  const [warehouse, setWarehouse] = useState('')
  return (
    <main>
      <h1>GameVault API Tester</h1>
      <p className="sub">
        Calls go through the gateway to the selected warehouse. A warehouse has 1–5 API
        instances and its own MongoDB replica set with as many database copies (at least 3):
        API instance i reads from copy i, writes go to the primary. The gateway load-balances
        between the instances and caches GET responses in Redis. Each result shows the
        warehouse, the instance that answered and the cache status. The id from the last
        response is pre-filled in the id fields.
      </p>
      <Warehouses selected={warehouse} onSelect={setWarehouse} />
      {ENDPOINTS.map(ep => (
        <EndpointCard key={ep.method + ep.path} ep={ep} lastId={lastId} onId={setLastId} warehouse={warehouse} />
      ))}
    </main>
  )
}
