const $ = id => document.getElementById(id);
// Every highlighted term links to its page of the embedded documentation. One map for
// every occurrence, so a term reads the same wherever it appears on the page.
const TERM_DOCS = {
  'job': 'harness.html', 'jobs': 'harness.html',
  'grade': 'harness-models.html', 'grades': 'harness-models.html',
  'catalog': 'harness-models.html', 'pin': 'harness-models.html', 'pins': 'harness-models.html',
  'embeddings': 'harness-models.html',
  'decision': 'tools-deciding.html', 'decision model': 'tools-deciding.html', 'decision thinker': 'tools-deciding.html',
  'admission': 'admission.html', 'spend cap': 'demo.html',
  'thinker': 'tools.html', 'tools': 'tools.html', 'doer': 'tools.html',
  'benchmark': 'tools.html', 'judge': 'tools.html', 'judges': 'tools.html',
  'skill': 'prompt-skill.html', 'skills': 'prompt-skill.html',
};
document.querySelectorAll('span.term').forEach(span => {
  const page = TERM_DOCS[span.textContent.trim().toLowerCase()];
  if (page) {
    const link = document.createElement('a');
    link.className = 'term';
    link.href = 'docs/' + page;
    link.textContent = span.textContent;
    span.replaceWith(link);
  }
});
const esc = v => v === null || v === undefined ? '' : String(v).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const int = n => n === null || n === undefined ? '–' : Math.round(n).toLocaleString('en-US');
const dec = n => n === null || n === undefined ? '–' : (Number.isInteger(n) ? n.toLocaleString('en-US') : n.toLocaleString('en-US', {maximumFractionDigits: 1}));
/** A call can cost a millionth of a dollar: amounts too small for the requested digits get six. */
function money(amount, currency, digits) {
  if (amount === null || amount === undefined) return '–';
  let d = digits ?? 4;
  if (amount !== 0 && Math.abs(amount) < Math.pow(10, -d)) d = 6;
  if (!currency) return amount.toLocaleString('en-US', {maximumFractionDigits: d});
  // a currency a document states comes as the model read it, and "EUROS" is no ISO code
  if (!/^[A-Z]{3}$/.test(currency)) return amount.toLocaleString('en-US', {minimumFractionDigits: d, maximumFractionDigits: d}) + ' ' + currency;
  return new Intl.NumberFormat('en-US', {style: 'currency', currency, minimumFractionDigits: d, maximumFractionDigits: d}).format(amount);
}
function spent(map, digits) {
  const parts = Object.entries(map || {}).map(([c, a]) => money(a, c, digits));
  return parts.length ? parts.join(' + ') : money(0, 'USD', digits);
}
/** Green at 0, red at 1. */
function heat(badness) {
  const b = Math.max(0, Math.min(1, badness));
  return `hsl(${Math.round(130 * (1 - b))}, 70%, 42%)`;
}

// Filled by renderSetup from the catalog, so tables elsewhere (the benchmark) can say
// which Grade a model id belongs to without each endpoint repeating the catalog.
let gradeOf = {};
// The compliance envelope, explained where it bites: a model served only under provider data
// sharing is refused on purpose, and the bubble says so - the refusal is a feature of the
// runtime, not a gap
const envelopeBubble = () => `<details class="bubble"><summary>why?</summary><div class="bubble-body">
  <p>Not permitted by default. Anthropic requires Mythos/Fable class models to share data with the provider. Since Nucleo is designed for regulated work, our default configuration refuses such models.</p>
</div></details>`;

// Filled by renderSetup too: the connection each provider key rides on, so a result that
// names provider keys (the discovery) can speak of the connections a person set up.
let connectionOf = {};

async function call(method, path, body) {
  const response = await fetch(path, {method, headers: body ? {'content-type': 'application/json'} : {}, body: body ? JSON.stringify(body) : undefined});
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!response.ok) {
    throw new Error((data && (data.message || data.error)) || text || ('HTTP ' + response.status));
  }
  return data;
}

/** Runs a form's job with a live elapsed counter and renders the result or the runtime's own refusal. */
function wire(formId, resultId, run, render) {
  const form = $(formId), out = $(resultId);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = form.querySelector('button');
    button.disabled = true;
    const started = Date.now();
    out.innerHTML = '<div class="busy">Running… 0 s</div>';
    const timer = setInterval(() => { out.firstChild.textContent = `Running… ${Math.round((Date.now() - started) / 1000)} s`; }, 1000);
    try {
      const data = await run(Object.fromEntries(new FormData(form)));
      out.innerHTML = render(data, Date.now() - started);
    } catch (e) {
      out.innerHTML = `<div class="callout bad"><h3>The runtime refused or failed</h3>${esc(e.message)}</div>`;
    } finally {
      clearInterval(timer);
      button.disabled = false;
    }
  });
}

// ---------- 1. credentials and catalog ----------
// the status the page last rendered from, so an edit that rewrites an order (the benchmark's
// proposal) starts from the orders the catalog states
let lastStatus = null;
function renderSetup(s) {
  lastStatus = s;
  const configured = s.providers.filter(p => p.configured);
  // per grade, the deployment's order of entries, the first the grade's default; the
  // embeddings and decision entries are single pins
  const orders = s.catalog.orders || {};
  const pins = s.catalog.pins || {};
  // an entry is callable only when its provider holds a credential, the runtime has a seat for
  // it (a graded model, an embeddings model or a decision model), and this process's compliance envelope permits
  // it; an entry of another modality - image, speech, video - is a listed fact about the
  // account, never called, and one the envelope refuses (a model served only under provider
  // data sharing) is one the picker is never allowed to land on
  const keys = new Set(configured.map(p => p.key));
  // the providers this build carries: an entry whose provider is not among them (a native image
  // without an SDK the JVM build has) is one nothing here can call, whatever the credentials
  const present = new Set(s.providers.map(p => p.key));
  // a closed entry (DISABLED by the deployment, DEPRECATED by the vendor, UNLISTED by the
  // account, UNREACHABLE from where this deployment points) is kept so past runs still
  // price, and is never called
  const callable = s.entries.filter(e => keys.has(e.provider) && e.seat && e.permitted && e.status === 'OPEN');
  gradeOf = Object.fromEntries(s.entries.map(e => [e.id, e.kind === 'LLM' ? e.grade : e.kind.toLowerCase()]));
  // What a person actually sets up is a connection: one credential, however many runtime
  // provider keys ride on it. Group by the credentials each provider declares it reads. The
  // fields a disconnected card asks for are the credentials' declared parts - the same parts,
  // under the same variable names, every store reads by - so the page can neither invent a
  // variable nor miss one. What is hand-written here is only the human name and the blurb per
  // known credential id, and a placeholder or a default value per known variable. A blurb is
  // block markup: its explanatory paragraphs carry class "brief", which the short page hides.
  const KNOWN = {
    'aws-access-key-id': ['AWS Bedrock', '<p class="brief">One AWS credential covers every model your account enabled on Bedrock, whoever made it: Anthropic Claude, Amazon Nova, Cohere embeddings, open-weight models.</p>'],
    'openai-api-key': ['OpenAI', '<p class="brief">An OpenAI platform key, for GPT models and OpenAI embeddings.</p>'],
    'anthropic-api-key': ['Anthropic direct', '<p class="brief">An Anthropic API key, calling Claude at Anthropic with no cloud in between.</p>'],
    'azure-foundry-api-key': ['Azure AI Foundry', '<p class="brief">An Azure AI Foundry key, for models deployed into your own Azure resource.</p>'],
    'openai-compatible-api-key': ['OpenAI-compatible endpoint', '<p class="brief">Anything speaking the OpenAI wire protocol. Hugging Face Inference Providers is the one to reach for: a Hugging Face token and https://router.huggingface.co/v1 reach every model the router serves. A self-hosted vLLM or Ollama (http://localhost:11434/v1), a gateway or another vendor works the same way.</p>'],
    // a decision model answers typed questions about a state with probabilities and generates
    // nothing; it is a third client family beside LLMs and embeddings, and steps 8 and 9 at the
    // bottom of this page run on one. There are two connections because there are two ways to
    // reach one: a server on this machine, by its address alone, and an endpoint behind a key.
    // How to get each is folded under its card, on both pages, because neither is a key from a
    // console alone
    'systemone-local': ['Local decision model', `<p class="brief">A <span class="term">decision model</span> in the shape of TypeSafe's Jev, running on this machine: a state and typed questions in, a calibrated probability per option out, and no text generated. It cannot write, invent or explain; it can say which team, whether this is urgent, whether these two are the same, which file is worth opening, for nothing, and steps 8 and 9 put one to work. A server on this machine checks no key, so only its address is asked for.</p>
      <details class="explain"><summary>How to run one on this machine</summary>
        <p><b>Kev</b>, Apache-2.0. It needs:</p>
        <ul class="items">
          <li>a 32 GB Apple Silicon Mac or a GPU box</li>
          <li>Python 3.12 or 3.13</li>
          <li><a href="https://docs.astral.sh/uv/" target="_blank" rel="noopener">uv</a></li>
          <li>about 8 GB of download on the first start</li>
        </ul>
        <pre>git clone https://github.com/jaredpalmer/kev.git &amp;&amp; cd kev
uv sync --extra serve
uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009</pre>
        <p>The address below is already the one this command listens on: press the button.</p>
        <p>Kev-9B is the same command with <code>kev-9b</code>:</p>
        <ul class="items">
          <li>a few points more accurate</li>
          <li>twice the download</li>
          <li>half the speed</li>
        </ul>
        <p>On connect, the server's own listing is read and shown on this card:</p>
        <ul class="items"><li>which model it loaded</li><li>on what device</li><li>at what precision</li></ul>
      </details>`],
    'systemone-api-key': ['TypeSafe-compatible decision model', `<p class="brief">TypeSafe's hosted Jev, or any endpoint that speaks its System One wire behind a key: a <span class="term">decision model</span> at a twentieth of a small chat model's price.</p>
      <details class="explain"><summary>How to set one up</summary>
        <ol class="recipes">
          <li><b>TypeSafe's Jev</b>, the hosted original. Connect with:
            <ul class="items">
              <li>API root: <code>https://api.typesafe.ai</code></li>
              <li>key: from their console</li>
            </ul></li>
          <li><b>Kev on Modal</b>, an HTTPS endpoint on a rented GPU that scales to zero when idle. Nothing to clone. It needs a Modal account.
            <pre>pip install modal &amp;&amp; modal setup
curl -LO https://raw.githubusercontent.com/jaredpalmer/kev/main/skills/kev-deploy/scripts/kev_serve.py
KEV_API_KEY=$(openssl rand -hex 24) modal deploy kev_serve.py</pre>
            Then connect with:
            <ul class="items">
              <li>API root: the <code>https://&lt;workspace&gt;--kev-api.modal.run</code> it prints</li>
              <li>key: the one you generated</li>
            </ul></li>
        </ol>
        <p>On connect, the endpoint's own listing is read and shown on this card:</p>
        <ul class="items"><li>which model it serves</li><li>on what device, when it says</li><li>at what precision, when it says</li></ul>
      </details>`],
  };
  const PLACEHOLDERS = {AWS_ACCESS_KEY_ID: 'AKIA...', AWS_REGION: 'us-east-1',
    AZURE_FOUNDRY_API_KEY_HOST: 'my-resource.services.ai.azure.com', OPENAI_COMPATIBLE_API_KEY_HOST: 'https://router.huggingface.co/v1',
    SYSTEMONE_API_KEY_HOST: 'https://api.typesafe.ai'};
  // a field that already holds the value nearly everyone types: the address the local recipe
  // above starts its server on
  const DEFAULTS = {SYSTEMONE_LOCAL_HOST: 'http://127.0.0.1:8009'};
  const byCred = new Map();
  const connOf = {};
  s.providers.forEach(p => {
    const ids = p.credentials.map(c => c.id).sort();
    const groupKey = ids.join(',');
    let g = byCred.get(groupKey);
    if (!g) {
      const known = ids.map(id => KNOWN[id]).find(k => k);
      const fields = p.credentials.flatMap(c => c.parts.map(f => ({
        label: f.meaning, record: c.id, part: f.part, secret: f.part === 'secret' && !c.id.endsWith('-region'),
        // the part every credential of its shape needs: the secret, or the host of one that has
        // none. A host beside a secret may be optional (OpenAI's names an Azure resource and is
        // absent for OpenAI itself), and the shape does not say which, so the connect's own
        // verdict, shown on the card, is the judge of a missing one
        required: f.part === 'secret' || (f.part === 'host' && !c.parts.some(o => o.part === 'secret')),
        variable: f.variable, placeholder: PLACEHOLDERS[f.variable], value: DEFAULTS[f.variable]})));
      const vars = fields.map(f => f.variable);
      g = {name: known ? known[0] : (vars[0] || p.key), blurb: known ? known[1] : `<p class="brief">${esc(p.credential)}</p>`,
           fields, vars, configured: p.configured, providers: [], facts: {}};
      byCred.set(groupKey, g);
    }
    g.providers.push(p.key);
    g.session = g.session || p.session;
    Object.assign(g.facts, p.facts || {});
    connOf[p.key] = g.name;
  });
  const conns = [...byCred.values()];
  connectionOf = connOf;
  const models = g => s.entries.filter(e => g.providers.includes(e.provider)).length;
  conns.sort((a, b) => (b.configured - a.configured) || (models(b) - models(a)));
  const connected = conns.filter(g => g.configured).length;
  // why an entry cannot answer, or null when it can: a word or two for the badge, the detail
  // for its hover. A grade's order skips such an entry, and a decision or embeddings pin
  // that cannot be called refuses every seat of its slot, so the page says so wherever it
  // names one
  const byId = Object.fromEntries(s.entries.map(e => [e.id, e]));
  const whyNot = id => {
    const e = byId[id];
    if (!e) return {short: 'not in the catalog', long: 'no entry of the catalog has this id'};
    if (!present.has(e.provider)) return {short: 'not in this build', long: 'this build carries no provider for it'};
    if (!keys.has(e.provider)) return {short: 'no credential', long: `it comes through ${connOf[e.provider] || e.provider}, which has no credential`};
    if (e.status === 'UNLISTED') return {short: 'unlisted', long: 'the account no longer lists it'};
    if (e.status === 'UNREACHABLE') return {short: 'unreachable', long: 'its provider is not served where this deployment points'};
    if (e.status === 'DEPRECATED') return {short: 'retired', long: 'the vendor retired it'};
    if (e.status !== 'OPEN') return {short: 'disabled', long: 'it is disabled in the catalog'};
    if (!e.permitted) return {short: 'not permitted', long: 'it is served only under provider data sharing, which this deployment does not allow'};
    return null;
  };
  const cannot = id => { const why = whyNot(id); return why ? ` <span class="badge b-warn" title="${esc(why.long)}">${esc(why.short)}</span>${why.short === 'not permitted' ? envelopeBubble() : ''}` : ''; };
  let html = '';
  if (!configured.length) {
    html += `<div class="callout warn"><h3>No credential yet</h3>Connect a provider below.<span class="brief"> A credential typed here is held for this session only; exporting the same variables before starting the demo works too.</span></div>`;
  }
  if (!s.catalog.found) {
    // the normal first state: no catalog of this account's yet, the providers' shipped
    // defaults serving until a Discover run writes one. The steps are this page's own
    // (connect a provider, press Discover), so nothing here sends a person to a terminal
    html += `<div class="callout ok"><h3>No catalog of your own yet: ${s.entries.length} shipped models</h3>
      <p style="margin:0">Connect a provider below, then press <b>Discover models</b>.<span class="brief"> The shipped entries carry each provider's published models and entry-tier limits, none of your
      account's, and default nothing. The discovery asks your account what it serves, pings each model,
      ${s.catalog.catalogFile ? `writes <code>${esc(s.catalog.catalogFile)}</code>, in the module's resources where every build carries it, and reloads the catalog live`
        : 'and needs a file of the demo\'s own to write, which a process running outside its module does not have'}.</span></p></div>`;
  } else {
    // the defaults as the catalog states them, one bullet each: per grade the first entry of
    // its order and how many follow it, then the embeddings and decision entries; a default that
    // cannot be called says why. The orders are set in the table below
    const ordered = Object.entries(orders);
    const slots = Object.entries(pins);
    html += `<div class="callout ok"><h3>Catalog: ${s.catalog.entries} models <span class="muted" title="${esc(s.catalog.source)}">from ${esc(s.catalog.source.split('/').pop())}</span></h3>
      <ul class="pins">${ordered.map(([g, ids]) => `<li>${esc(g)}: <code>${esc(ids[0])}</code>${cannot(ids[0])}${ids.length > 1 ? ` <span class="muted">first, ${ids.length - 1} more after it</span>` : ''}</li>`).join('')}
        ${slots.map(([k, v]) => `<li>${esc(k)}: <code>${esc(v)}</code>${cannot(v)}</li>`).join('')}
        ${ordered.length || slots.length ? '' : '<li>no order yet: each grade is served by its cheapest callable model</li>'}</ul></div>`;
    if (s.catalog.instructions.length) {
      html += `<div class="callout warn"><ul class="pins">${s.catalog.instructions.map(i => `<li>${esc(i)}</li>`).join('')}</ul></div>`;
    }
  }
  html += `<details class="panel"${configured.length ? '' : ' open'}><summary>Connections <span class="badge ${connected ? 'b-ok' : 'b-muted'}">${connected} of ${conns.length} connected</span></summary>
    ${conns.map(g => `<div class="conn${g.configured ? '' : ' off'}" data-conn="${esc(g.name)}">
      <h4>${esc(g.name)} ${g.configured ? '<span class="badge b-ok">connected</span>' : '<span class="badge b-muted">no credential</span>'}
        ${models(g) ? `<span class="muted">${models(g)} models</span>` : ''}</h4>
      ${Object.keys(g.facts).length ? `<ul class="items">${Object.entries(g.facts).map(([k, v]) => `<li>${esc(k)}: <code>${esc(v)}</code></li>`).join('')}</ul>` : ''}
      ${g.blurb}
      <p class="keys">Environment variables<span class="brief">, or the matching <code>nucleo.credentials</code> Boot properties (a vault, a profile or a Kubernetes secret works the same)</span>:</p>
      <ul class="items">${g.fields.map(f => `<li><code>${esc(f.variable)}</code></li>`).join('')}</ul>
      ${g.configured && !g.session ? '' : '<p class="keys">Or enter them here:</p>'}
      ${g.configured && !g.session ? '' : `<form class="connect-form" style="margin-top:10px">
        ${g.fields.map(f => `<label>${esc(f.label)}<input data-record="${esc(f.record)}" data-part="${esc(f.part)}"
          type="${f.secret ? 'password' : 'text'}"${f.placeholder ? ` placeholder="${esc(f.placeholder)}"` : ''}${f.value ? ` value="${esc(f.value)}"` : ''}
          autocomplete="off" spellcheck="false"${f.required ? ' required' : ''}></label>`).join('')}
        <button>${g.session ? 'Replace for this session' : 'Use for this session'}</button>
        <div class="muted brief" style="flex-basis:100%">Held in memory for this process only - never written to a file,
          never echoed back. Restarting the demo forgets it.</div>
        <div class="connect-error" style="flex-basis:100%;color:var(--bad)"></div>
      </form>`}
      <div class="connect-note" style="margin-top:8px"></div>
    </div>`).join('')}</details>`;
  // the discovery, on a click: only offered once something is connected to discover against
  if (configured.length) {
    html += `<form id="discover-form" style="margin-top:14px;align-items:center">
      <button>Discover models</button>
      <div class="muted brief" style="flex:1 1 320px">Asks the connected providers what they serve, pings each model,
        writes <code>models.json</code> (models and limits - never a key) and reloads the catalog live, so the
        steps below use the result at once. Incremental: a connection already discovered this session is
        skipped, only new or changed credentials are queried. A ping per model: a few cents, a minute or two.</div>
    </form><div class="result" id="discover-result"></div>`;
  }
  // callable entries first; an entry whose provider holds no credential, or that the runtime
  // has no client for (an image, speech or video model), is a listed fact, dimmed and badged
  // in plain words, because the runtime will never serve it
  // The table is the catalog by grade: one group per rung from MICRO up, then the embeddings
  // entries, then the decision entries, then the models the runtime never calls, each group
  // under a separator row. A rung's rows are numbered in the order the runtime walks them: the
  // entries the deployment placed, in its order, then the callable rest by list price, which is
  // how the runtime fills in; a placed entry that cannot be called keeps its place and number,
  // dimmed, because the runtime skips it only while it cannot be called; an unplaced one that
  // cannot be called sits unnumbered at the bottom. The rung's separator row says which entry
  // serves a text request, one with images and one with documents, as the runtime resolves
  // them. Every other group sorts by where it comes through, then by price, then by id. On an
  // editable file a rung's numbered rows drag, or move by their arrows, and any move writes the
  // numbered sequence as the grade's order; the embeddings and decision pins are radios (one
  // entry serves the slot); the grade is a select (a change moves the row to its new group on
  // the re-render); and enabled is a checkbox for the two statuses a person owns. A
  // closed-by-vendor or closed-by-account entry, and a pinned one, show the box but do not
  // offer it, with the reason on hover.
  // editable while the demo has a home to write to: the first edit creates the file from the
  // shipped defaults, so a fresh checkout's table is editable before any file exists
  const editable = !!(s.catalog.editableFile || s.catalog.catalogFile);
  const RUNGS = ['MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'XL', 'MEGA'];
  const groupOf = e => !e.seat ? 'other' : e.kind === 'EMBEDDINGS' ? 'embeddings' : e.kind === 'DECISION' ? 'decision' : e.grade;
  const GROUPS = [...RUNGS, 'embeddings', 'decision', 'other'];
  // a decision entry is on no rung; its badge says which bound it declares: a quota window
  // for a hosted endpoint, a concurrency for a server on this machine
  const bound = e => e.maxConcurrent != null ? (e.maxConcurrent === 1 ? 'one request at a time' : e.maxConcurrent + ' requests at a time')
      : e.tpm != null ? (e.tpm >= 1000000 ? (e.tpm / 1000000) + 'M' : e.tpm >= 1000 ? (e.tpm / 1000) + 'k' : e.tpm) + ' tokens/min' : '';
  const price = e => [e.inputPricePerMillion == null ? Infinity : e.inputPricePerMillion, e.outputPricePerMillion == null ? Infinity : e.outputPricePerMillion];
  const byRow = (a, b) => (connOf[a.provider] || a.provider).localeCompare(connOf[b.provider] || b.provider)
      || price(a)[0] - price(b)[0] || price(a)[1] - price(b)[1] || a.id.localeCompare(b.id);
  // the list price the runtime fills in by: input plus output per million, an unpriced entry
  // after every priced one; the sort is stable, so equal prices keep the catalog's order, as
  // the runtime's do
  const listPrice = e => e.inputPricePerMillion == null || e.outputPricePerMillion == null ? Infinity : e.inputPricePerMillion + e.outputPricePerMillion;
  // a rung's rows as the runtime walks them: the placed entries in the deployment's order (an
  // order may place an entry of a higher grade, which serves the lower one legally), then the
  // callable rest by list price; the unplaced rest that cannot be called goes last, unnumbered
  const rungRows = g => {
    const order = orders[g] || [];
    const placed = order.map(id => byId[id]).filter(e => e);
    const rest = s.entries.filter(e => groupOf(e) === g && !order.includes(e.id));
    const filled = rest.filter(e => callable.includes(e)).sort((a, b) => listPrice(a) - listPrice(b));
    return {placed, filled, off: rest.filter(e => !callable.includes(e)).sort(byRow)};
  };
  const groups = GROUPS.map(g => RUNGS.includes(g) ? {name: g, ...rungRows(g)} : {name: g, off: s.entries.filter(e => groupOf(e) === g).sort(byRow)})
      .filter(g => (g.placed || []).length + (g.filled || []).length + g.off.length);
  const groupTitle = g => g === 'embeddings' ? 'embeddings' : g === 'decision' ? 'decision' : g === 'other' ? 'not callable: image, speech and video models the account offers' : g;
  const pinnedSlots = e => Object.entries(pins).filter(([, v]) => v === e.id).map(([k]) => k);
  const columns = 6 + (editable ? 1 : 0);
  // the first column: on a rung, the row's place in the walk - a strong number for a placed
  // entry, a muted one for an entry the runtime fills in by price, with the drag handle and the
  // arrows on an editable file; on the embeddings and decision groups, the pin's radio
  const placeCell = (e, g, n, placed, last) => {
    if (n) {
      const number = placed ? `<b class="place" title="placed: the deployment's order puts it here">${n}</b>`
          : `<span class="place filled" title="not placed: the runtime fills in the grade's unplaced entries by list price, cheapest first">${n}</span>`;
      // the first row has nowhere to go up and the last nowhere down: their arrow keeps its
      // place, hidden, so the numbers stay in one column
      const arrow = (move, symbol, word, none) => `<button type="button" class="mini arrow" data-move="${move}" title="move ${word}"${none ? ' style="visibility:hidden" tabindex="-1"' : ''}>${symbol}</button>`;
      return `<td class="ord nowrap">${editable ? '<span class="handle" title="drag to reorder the grade">⠿</span>' : ''}${number}${editable
          ? arrow(-1, '▲', 'up', n === 1) + arrow(1, '▼', 'down', last) : ''}</td>`;
    }
    if (!editable || !e.seat || (g !== 'embeddings' && g !== 'decision')) return '<td></td>';
    const pinnedHere = pins[g] === e.id;
    const canPin = e.status === 'OPEN';
    return `<td class="ord"><input type="radio" name="pin-${esc(g)}" data-pin-slot="${esc(g)}" data-pin-id="${esc(e.id)}"${pinnedHere ? ' checked' : ''}${canPin ? '' : ' disabled'}
      title="${pinnedHere ? `the default for ${esc(g)}` : canPin ? `make it the default for ${esc(g)}` : 'only an open entry can be a default'}"></td>`;
  };
  // what an entry accepts beyond text, from its catalog record
  const accepts = e => e.kind !== 'LLM' || !e.seat ? ''
      : (e.vision ? ' <span class="badge b-muted" title="accepts images: its catalog record says supports_vision">images</span>' : '')
      + (e.documents ? ' <span class="badge b-muted" title="accepts documents, a PDF sent whole: its catalog record says supports_documents">documents</span>' : '');
  // per kind of request, the entry the runtime serves it with now, or its refusal on hover
  const serving = g => ['text', 'images', 'documents'].map(kind => {
    const id = (s.serving[g] || {})[kind];
    return id ? `${kind} <code>${esc(id)}</code>` : `${kind} <span class="badge b-muted" title="${esc((s.servingRefusals[g] || {})[kind] || '')}">none</span>`;
  }).join(', ');
  const enabledCell = e => {
    if (!editable) return '';
    if (!e.seat) return '<td></td>';
    const mine = pinnedSlots(e);
    const person = e.status === 'OPEN' || e.status === 'DISABLED';
    const reason = !person ? (e.status === 'UNLISTED' ? 'closed by the account: it no longer lists this model'
            : e.status === 'UNREACHABLE' ? 'closed by the discovery: its provider is not served where this deployment points'
            : 'closed by the vendor: it retired this model')
        : mine.length ? `the default for ${mine.join(', ')}: choose another default before disabling it`
        : e.status === 'OPEN' ? 'served; untick to disable it - it stays in the file so past runs still price' : 'disabled by the deployment; tick to serve it again';
    return `<td class="pin"><input type="checkbox" data-entry-id="${esc(e.id)}" data-enabled${e.status === 'OPEN' ? ' checked' : ''}${!person || mine.length ? ' disabled' : ''} title="${esc(reason)}"></td>`;
  };
  html += `<details class="panel"${keepModelsOpen ? ' open' : ''} id="models-table"><summary>Models <span class="badge ${callable.length ? 'b-ok' : 'b-muted'}">${callable.length === s.entries.length
      ? s.entries.length + ' callable' : callable.length + ' of ' + s.entries.length + ' callable'}</span></summary><div class="scroll"><table>
    <tr><th class="ord" title="a grade's order: the runtime serves a request of the grade with the first entry, top down, that can be called and accepts what the request sends">#</th><th>Model</th><th>Grade</th><th>Comes through</th><th class="num">Input / M tokens</th><th class="num">Output / M tokens</th>${editable ? '<th class="pin" title="served, or disabled by the deployment">enabled</th>' : ''}</tr>
    ${groups.map(g => `<tr class="sep"><td colspan="${columns}">${esc(groupTitle(g.name))}${RUNGS.includes(g.name) ? ` <span class="muted">serves ${serving(g.name)}</span>${editable && orders[g.name]
        ? ` <button type="button" class="mini" data-order-clear="${esc(g.name)}" title="clear the order: the runtime serves the grade's cheapest callable entry">clear order</button>` : ''}`
        : pins[g.name] ? ` <span class="muted">default <code>${esc(pins[g.name])}</code></span>${editable
        ? ` <button type="button" class="mini" data-pin-slot="${esc(g.name)}" data-pin-id="" title="clear the default: the runtime serves the first callable entry">clear</button>` : ''}` : ''}</td></tr>
    ${[...(g.placed || []).map((e, i) => row(e, g.name, i + 1, true, i + 1 === g.placed.length + g.filled.length)),
       ...(g.filled || []).map((e, i) => row(e, g.name, g.placed.length + i + 1, false, i + 1 === g.filled.length)),
       ...g.off.map(e => row(e, g.name, 0, false))].join('')}`).join('')}
  </table></div>${editable ? '<div class="connect-error" id="catalog-edit-error" style="color:var(--bad)"></div>' : ''}</details>`;
  $('setup-body').innerHTML = html;
  $('setup-body').classList.remove('muted');
  bindOrderDrag();
  const grades = [...new Set(callable.filter(e => e.kind === 'LLM' && e.grade).map(e => e.grade))];
  const order = ['MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'XL', 'MEGA'];
  // the same grade dropdown on the quick question and on the agent, each with its own default:
  // the question is a SMALL job, the agent declares MEDIUM; under it, which model the chosen
  // grade lands on, as the runtime resolves it, so the dropdown teaches the orders
  const offered = order.filter(g => grades.includes(g));
  function row(e, g, n, placed, last) {
    return `<tr${n ? ` data-order-grade="${esc(g)}" data-order-id="${esc(e.id)}"${editable ? ' draggable="true"' : ''}` : ''}${callable.includes(e) ? '' : ' style="opacity:.55"'}>${placeCell(e, g, n, placed, last)}<td>${esc(e.id)}${accepts(e)}${n && !callable.includes(e) ? cannot(e.id) : ''}</td><td>${e.kind === 'EMBEDDINGS' ? '<span class="badge b-muted">embeddings</span>'
        : e.kind === 'DECISION' ? `<span class="badge b-muted" title="answers typed questions with probabilities, generates nothing; on no rung">decision</span>${bound(e) ? ` <span class="muted">${esc(bound(e))}</span>` : ''}`
        : e.seat ? (editable ? `<select data-entry-id="${esc(e.id)}" data-grade title="the rung this entry serves; changing it moves the row">${RUNGS.map(r => `<option${e.grade === r ? ' selected' : ''}>${r}</option>`).join('')}</select>` : esc(e.grade))
        : `<span class="badge b-muted" title="what the model produces, as the account lists it">${esc((e.outputModalities || []).join(', ').toLowerCase())} model</span>`}</td>
      <td title="${esc(e.provider)}">${esc(connOf[e.provider] || e.provider)}${!present.has(e.provider) ? ' <span class="badge b-muted" title="no provider artifact in this build serves this entry; it stays in the file for the builds that carry one">not in this build</span>' : keys.has(e.provider) ? '' : ' <span class="badge b-muted">no credential</span>'}${e.seat ? '' : ' <span class="badge b-muted" title="the runtime calls text models, embeddings models and decision models only and has no client for this one; it is listed so the catalog states what your account offers, and it is never called">not callable</span>'}${e.permitted ? '' : ` <span class="badge b-muted">not permitted</span>${envelopeBubble()}`}${e.status === 'OPEN' || e.status === 'DISABLED' ? '' : ` <span class="badge b-muted" title="${e.status === 'UNLISTED' ? 'the account no longer lists this model; kept so past runs still price, never called' : 'the vendor retired this model; kept so past runs still price, never called'}">${e.status.toLowerCase()}</span>`}</td>
      ${priceCell(e, 'input')}${priceCell(e, 'output')}${enabledCell(e)}</tr>`;
  }
  // a price cell: the list price in the entry's currency; on a file of the deployment's own and
  // an entry the runtime calls, a field that writes the price on change. A price the discovery
  // inferred, from a classifier's knowledge or an older relative, is marked unverified, with the
  // discovery's own note on hover, until a person edits it or confirms the entry
  function priceCell(e, which) {
    const amount = which === 'input' ? e.inputPricePerMillion : e.outputPricePerMillion;
    if (which === 'output' && e.kind !== 'LLM') return '<td class="num"></td>';
    const mark = e.unverified && which === 'input' ? ` <span class="badge b-warn" title="${esc(e.note || '')}">unverified</span>${editable
        ? `<button type="button" class="mini" data-entry-id="${esc(e.id)}" data-confirm title="a person checked the grade and the prices: keep them as they are">confirm</button>` : ''}` : '';
    if (!editable || !e.seat) return `<td class="num">${money(amount, e.currency, 2)}${mark}</td>`;
    return `<td class="num nowrap"><input type="number" min="0" step="0.01" data-entry-id="${esc(e.id)}" data-price="${which}"
      value="${amount == null ? '' : amount}" title="${esc(which)} price per million tokens, in ${esc(e.currency || 'the entry\'s currency')}; a change writes it">${mark}</td>`;
  }
  // what serves a pinned slot's seats, as the runtime will do it: a callable default serves; a default
  // no provider in this build serves is set aside and the runtime picks; any other default that
  // cannot be called refuses, and the line names the fix - the slot's first callable entry,
  // or the discovery when nothing callable fills the slot yet
  const callableFor = slot => callable.find(e => slot === 'decision' ? e.kind === 'DECISION'
      : slot === 'embeddings' ? e.kind === 'EMBEDDINGS' : e.kind === 'LLM' && e.grade === slot);
  const pinnedLine = (slot, label) => {
    const id = pins[slot];
    const why = whyNot(id);
    if (!why) return `${label} ${label === 'decisions' ? 'are answered' : 'is served'} by ${id}`;
    if (byId[id] && !present.has(byId[id].provider)) return `${id} is set aside (${why.short}): the first callable entry serves`;
    const alternative = callableFor(slot);
    // a missing credential is fixed where the connection is; the default itself is fine
    if (why.short === 'no credential') {
      const connection = connOf[byId[id].provider] || byId[id].provider;
      return alternative ? `${id} needs ${connection}: connect it above, or make ${alternative.id} the default`
          : `${id} needs ${connection}: connect it above`;
    }
    return alternative ? `${id} is not callable (${why.short}): make ${alternative.id} the default in the models table`
        : `${id} is not callable (${why.short}): press Discover models, then pick a default in the models table`;
  };
  const gradeSelect = (formId, servesId, preset) => {
    const select = $(formId).grade;
    select.innerHTML = (offered.length ? offered : order).map(g => `<option${g === preset ? ' selected' : ''}>${g}</option>`).join('');
    // the runtime's own resolution for a text request of the grade, and how it got there: the
    // grade's order, the price fill-in when nothing is placed, or the nearest grade above
    const serves = () => {
      const g = select.value;
      const id = (s.serving[g] || {}).text;
      if (!id) { $(servesId).textContent = (s.servingRefusals[g] || {}).text || `nothing serves ${g}`; return; }
      const how = byId[id] && byId[id].grade !== g && !(orders[g] || []).includes(id) ? `, of grade ${byId[id].grade}: nothing of ${g} can be called`
          : (orders[g] || []).includes(id) ? (orders[g][0] === id ? ', the first of its order' : ', the first callable entry of its order')
          : orders[g] ? ', the cheapest callable entry: nothing placed in its order can be called' : ', the cheapest callable entry: the grade has no order';
      $(servesId).textContent = `${g} is served by ${id}${how}`;
    };
    select.addEventListener('change', serves);
    if (select.value) serves();
  };
  gradeSelect('ask-form', 'ask-serves', 'SMALL');
  gradeSelect('agent-form', 'agent-serves', 'MEDIUM');
  // the budget is a cap per currency and the runtime never converts, so the only currencies
  // that can cover a call are the ones the callable entries are priced in
  const currencies = [...new Set(callable.map(e => e.currency).filter(Boolean))];
  const offeredCurrencies = currencies.length ? currencies : ['USD'];
  const options = offeredCurrencies.map(c => `<option>${esc(c)}</option>`).join('');
  // with one currency there is nothing to choose: the picker steps aside and the budget names it
  for (const form of [$('extract-form'), $('pricing-form'), $('decide-prices-form')]) {
    form.currency.innerHTML = options;
    form.currency.closest('label').hidden = offeredCurrencies.length === 1;
    form.amount.closest('label').firstChild.textContent = offeredCurrencies.length === 1 ? `Budget (${offeredCurrencies[0]})` : 'Budget';
  }
  if (s.corpus && !$('extract-dir').value) $('extract-dir').value = s.corpus;
  // the pricing steps answer for the day the corpus's price story is written for, not for
  // whatever day the demo happens to run; the person moves it to see the story from another day
  if (s.corpusAsOf && !$('pricing-form').asOf.value) $('pricing-form').asOf.value = s.corpusAsOf;
  if (s.corpusAsOf && !$('decide-prices-form').asOf.value) $('decide-prices-form').asOf.value = s.corpusAsOf;
  // the decision step: the folder is the corpus too, and under it which decision entry answers
  if (s.corpus && !$('decide-dir').value) $('decide-dir').value = s.corpus;
  const deciders = callable.filter(e => e.kind === 'DECISION');
  const decisionConnected = configured.some(p => p.key.endsWith('-decision'));
  $('decide-serves').innerHTML = pins.decision ? esc(pinnedLine('decision', 'decisions'))
      : deciders.length ? `no decision default: the first decision entry the connected endpoint serves answers, and the log says so. Callable:
          <ul class="items">${deciders.map(e => `<li><code>${esc(e.id)}</code></li>`).join('')}</ul>`
      : decisionConnected ? 'a decision model is connected but the catalog has no decision entry yet: press Discover models in step 1, which writes the entry the endpoint serves, then make it the default'
      : `no decision model is callable yet. Connect one in step 1, then press Discover models. The two connections:
          <ul class="items"><li>Local decision model: a Kev on this machine</li><li>TypeSafe-compatible decision model: TypeSafe's Jev, or a Kev behind a key</li></ul>`;
}
call('GET', '/status').then(renderSetup).catch(e => { $('setup-body').innerHTML = `<div class="callout bad">${esc(e.message)}</div>`; });

// The setup panel's own forms are re-rendered with every status, so one delegated listener
// The catalog edits: a grade select, a pin/unpin or enable/disable
// button. Each is one call that writes the file and answers the status the page re-renders
// from; the models table stays open across the re-render so the edit is seen where it was
// made, and a refusal shows under the table in the runtime's own words.
let keepModelsOpen = false;
async function catalogEdit(path, body) {
  keepModelsOpen = true;
  try {
    const status = await call('POST', path, body);
    renderSetup(status);
  } catch (e) {
    const where = $('catalog-edit-error');
    if (where) where.textContent = e.message;
  }
}
$('setup-body').addEventListener('change', event => {
  const t = event.target;
  if (t.matches('select[data-grade]')) catalogEdit('/catalog/entry', {id: t.dataset.entryId, grade: t.value});
  else if (t.matches('input[data-price]') && t.value !== '') {
    catalogEdit('/catalog/entry', {id: t.dataset.entryId, [t.dataset.price === 'input' ? 'inputPricePerMillion' : 'outputPricePerMillion']: Number(t.value)});
  }
  else if (t.matches('input[type=radio][data-pin-slot]') && t.checked) catalogEdit('/catalog/pin', {slot: t.dataset.pinSlot, id: t.dataset.pinId});
  else if (t.matches('input[type=checkbox][data-enabled]')) catalogEdit('/catalog/entry', {id: t.dataset.entryId, status: t.checked ? 'OPEN' : 'DISABLED'});
});
// A grade's order is its numbered rows as the table shows them: any move - an arrow or a drag -
// writes that whole sequence, so the entries the runtime was filling in by price become placed
// where they stood, and the one moved goes where it was put.
const orderRows = grade => [...$('setup-body').querySelectorAll(`tr[data-order-grade="${grade}"]`)];
function moveInOrder(grade, id, to) {
  const ids = orderRows(grade).map(r => r.dataset.orderId);
  const from = ids.indexOf(id);
  ids.splice(from, 1);
  ids.splice(Math.max(0, Math.min(to, ids.length)), 0, id);
  catalogEdit('/catalog/order', {grade, ids});
}
$('setup-body').addEventListener('click', event => {
  const b = event.target.closest('button');
  if (!b || b.disabled) return;
  if (b.dataset.pinSlot !== undefined) { event.preventDefault(); catalogEdit('/catalog/pin', {slot: b.dataset.pinSlot, id: b.dataset.pinId || null}); }
  else if (b.dataset.orderClear) { event.preventDefault(); catalogEdit('/catalog/order', {grade: b.dataset.orderClear, ids: []}); }
  else if (b.dataset.confirm !== undefined) { event.preventDefault(); catalogEdit('/catalog/entry', {id: b.dataset.entryId, confirmed: true}); }
  else if (b.dataset.move) {
    event.preventDefault();
    const tr = b.closest('tr');
    const at = orderRows(tr.dataset.orderGrade).indexOf(tr);
    moveInOrder(tr.dataset.orderGrade, tr.dataset.orderId, at + Number(b.dataset.move));
  }
  else if (b.dataset.entryId && b.dataset.status) { event.preventDefault(); catalogEdit('/catalog/entry', {id: b.dataset.entryId, status: b.dataset.status}); }
});
// Dragging a numbered row within its grade: dropped on the upper half of a row it goes before
// that row, on the lower half after it; a row of another grade takes no drop.
function bindOrderDrag() {
  const body = $('setup-body');
  let dragged = null;
  const target = event => {
    const tr = event.target.closest('tr[data-order-grade]');
    return tr && dragged && tr.dataset.orderGrade === dragged.dataset.orderGrade ? tr : null;
  };
  const clear = () => body.querySelectorAll('tr.drop-before, tr.drop-after').forEach(r => r.classList.remove('drop-before', 'drop-after'));
  const after = (event, tr) => event.clientY > tr.getBoundingClientRect().top + tr.offsetHeight / 2;
  body.querySelectorAll('tr[draggable="true"]').forEach(tr => {
    tr.addEventListener('dragstart', event => { dragged = tr; tr.classList.add('dragging'); event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', tr.dataset.orderId); });
    tr.addEventListener('dragend', () => { tr.classList.remove('dragging'); clear(); dragged = null; });
    tr.addEventListener('dragover', event => {
      const over = target(event);
      if (!over) return;
      event.preventDefault();
      clear();
      over.classList.add(after(event, over) ? 'drop-after' : 'drop-before');
    });
    tr.addEventListener('drop', event => {
      const over = target(event);
      if (!over) return;
      event.preventDefault();
      const rows = orderRows(over.dataset.orderGrade).filter(r => r !== dragged);
      const to = rows.indexOf(over) + (after(event, over) ? 1 : 0);
      const moved = dragged;
      clear();
      if (over !== moved) moveInOrder(moved.dataset.orderGrade, moved.dataset.orderId, to);
    });
  });
}

// handles them all: a connection card's "use for this session", and the discovery.
$('setup-body').addEventListener('submit', async event => {
  const form = event.target;
  event.preventDefault();
  const button = form.querySelector('button');
  button.disabled = true;
  if (form.classList.contains('connect-form')) {
    const connName = form.closest('.conn').dataset.conn;
    const records = {};
    form.querySelectorAll('input[data-record]').forEach(i => {
      const r = records[i.dataset.record] || (records[i.dataset.record] = {id: i.dataset.record});
      if (i.value.trim()) r[i.dataset.part] = i.value.trim();
    });
    const note = form.closest('.conn').querySelector('.connect-note');
    const started = Date.now();
    note.innerHTML = '<span class="muted">Checking the credential against the provider… 0 s</span>';
    const timer = setInterval(() => { note.firstChild.textContent = `Checking the credential against the provider… ${Math.round((Date.now() - started) / 1000)} s`; }, 1000);
    try {
      const out = await call('POST', '/connect', {records: Object.values(records)});
      renderSetup(out.status);
      const card = [...document.querySelectorAll('.conn')].find(c => c.dataset.conn === connName);
      // the re-render collapses the connections panel; the verdict must stay in sight
      if (card && card.closest('details')) card.closest('details').open = true;
      const where = card ? card.querySelector('.connect-note') : $('setup-body');
      where.innerHTML = out.failure
          ? `<span style="color:var(--bad)">The credential is held but did not verify: ${esc(out.failure)}
             Replace it above, or if the endpoint was merely down, press Discover models to retry.</span>`
          : out.verifiedBy
              ? `<span style="color:var(--ok)">Verified: ${out.listed} model${out.listed === 1 ? '' : 's'} listed.</span>
                 <span class="muted">Now press Discover models to bring ${out.listed === 1 ? 'it' : 'them'} into the catalog.</span>`
              : `<span class="muted">${esc(out.note)}</span>`;
    } catch (e) {
      form.querySelector('.connect-error').textContent = e.message;
      note.textContent = '';
      button.disabled = false;
    } finally {
      clearInterval(timer);
    }
    return;
  }
  if (form.id === 'discover-form') {
    const out = $('discover-result');
    const started = Date.now();
    out.innerHTML = '<div class="busy">Discovering… 0 s</div>';
    const timer = setInterval(() => { out.firstChild.textContent = `Discovering… ${Math.round((Date.now() - started) / 1000)} s`; }, 1000);
    try {
      const d = await call('POST', '/discover');
      renderSetup(d.status);
      // one line per connection, as a person set them up: several provider keys ride one
      // credential and read the same account, so the connection listed what its best key listed,
      // and failed only when every key of it failed
      const ids = list => `<ul>${list.map(c => `<li><code>${esc(c)}</code></li>`).join('')}</ul>`;
      const byConnection = new Map();
      for (const p of d.providers) {
        const name = connectionOf[p.key] || p.key;
        if (!byConnection.has(name)) byConnection.set(name, []);
        byConnection.get(name).push(p);
      }
      const line = keys => {
        const listed = keys.filter(p => p.status === 'LISTED');
        if (listed.length) {
          const n = Math.max(...listed.map(p => p.listed));
          return n === 1 ? '1 model listed and pinged' : `${n} models listed, each pinged`;
        }
        const failed = keys.filter(p => p.status === 'LISTING_FAILED');
        if (failed.length === keys.length) return `<span style="color:var(--bad)">could not list its models: ${esc(failed[0].detail)}</span> The next press retries it.`;
        return 'its models were each pinged';
      };
      const discovered = new Set(d.scope.map(k => connectionOf[k] || k));
      const skipped = new Set(d.status.providers.filter(p => p.configured && !discovered.has(connectionOf[p.key] || p.key)).map(p => connectionOf[p.key] || p.key)).size;
      $('discover-result').innerHTML = `<div class="callout ok"><h3>${d.entries} models in the catalog</h3>
        <ul class="pins">${[...byConnection.entries()].map(([name, keys]) => `<li>${esc(name)}: ${line(keys)}</li>`).join('')}
        ${d.classified && d.classified.length ? `<li><b>${d.classified.length} listed models the catalog had no shape for were
          classified by <code>${esc(d.classifiedBy)}</code></b>. The strongest model you can call wrote their entries,
          each pinged live and carrying a note naming what to verify:${ids(d.classified)}</li>` : ''}
        ${skipped > 0 ? `<li>${skipped} connection${skipped > 1 ? 's' : ''} discovered earlier this session ${skipped > 1 ? 'were' : 'was'} left as ${skipped > 1 ? 'they were' : 'it was'}.</li>` : ''}</ul>
        The steps below use it now.</div>`;
    } catch (e) {
      out.innerHTML = `<div class="callout bad"><h3>The discovery refused or failed</h3>${esc(e.message)}</div>`;
      button.disabled = false;
    } finally {
      clearInterval(timer);
    }
  }
});

// ---------- 2. ask ----------
wire('ask-form', 'ask-result', f => call('POST', '/ask', {question: f.question, context: f.context, grade: f.grade}),
  (a, ms) => `<div class="answer">${esc(a.answer)}</div>
    ${a.reasoning ? `<div class="reasoning"><span class="who">the model's reasoning</span>${esc(a.reasoning)}</div>` : ''}
    <div class="facts"><div>Answered by<b title="${esc(a.servedModel ? 'served as ' + a.servedModel : '')}">${esc(a.model || '–')}</b></div>
      <div>Cost<b>${a.cost === null || a.cost === undefined ? 'not priced' : money(a.cost, a.currency)}</b></div><div>Took<b>${int(ms)} ms</b></div></div>`);

// ---------- 3. agent ----------
// The run's timeline: the server streams one JSON line per event of the run and of every job
// under it, each naming its job, parent, type and turn. The page keeps the jobs as a tree and
// draws the agent's children as turns on a rail: a model call is a turn, the tool calls it
// decided on sit under it as cards side by side (they run side by side), a sub-agent is a card
// with a rail of its own, and the runtime's notes between them stay where they fell. Timers tick
// while a job runs; a turn's completion brings the model's reasoning, what it decided and what
// the call cost; a tool's first line brings its arguments and its completion the result.
const traceTone = n => n.status === 'running' ? 'run' : n.status === 'done' ? 'ok' : n.status === 'queued' ? '' : 'bad';
const traceNode = (trace, j) => {
  let n = trace.jobs.get(j.id);
  if (!n) {
    n = {id: j.id, parent: j.parent, type: j.type, name: j.name, tool: j.tool, action: j.action, iteration: j.iteration, own: j.own, status: 'queued',
      queuedAt: null, startedAt: null, doneAt: null, attempt: 1, input: undefined, result: undefined, turn: null, call: null, message: '', percent: null,
      retries: [], admission: null, notes: [], children: []};
    trace.jobs.set(j.id, n);
    if (j.own) trace.root = n;
    else { const p = trace.jobs.get(j.parent); if (p) p.children.push(n); }
  }
  return n;
};
function traceEvent(trace, f) {
  if (f.type !== 'job') return;
  const n = traceNode(trace, f.job);
  if (f.measured) trace.measured = f.measured;
  switch (f.kind) {
    case 'queued': n.status = 'queued'; n.queuedAt = f.at; if (f.input !== undefined) n.input = f.input; break;
    case 'started': n.status = 'running'; n.startedAt = n.startedAt || f.at; n.attempt = f.attempt || 1; break;
    case 'progress': n.message = f.message || ''; n.percent = f.percent; break;
    case 'answer':
      // the runtime streams the typed answer as JSON; the page shows its answer field
      trace.answer = f.content || '';
      try { const parsed = JSON.parse(trace.answer); if (parsed && typeof parsed.answer === 'string') trace.answer = parsed.answer; } catch (e) { /* plain text stays as it is */ }
      break;
    case 'note':
      // the reasoning rides on the turn's own completion; the tool notes repeat the tool jobs' own lines
      if (f.title === 'Reasoning' || f.title === 'Tool Starting' || f.title === 'Tool Completed') break;
      // a note of the agent's own falls between turns: it is shown under the turn that was on
      (n.own && n.children.length ? n.children[n.children.length - 1] : n).notes.push({title: f.title, severity: f.severity, message: f.message, at: f.at});
      break;
    case 'retry': n.retries.push({retry: f.retry, message: f.message, at: f.at}); n.status = 'running'; break;
    case 'admission': n.admission = {state: f.state, limiter: f.limiter, waitMs: f.waitMs}; break;
    case 'completed': n.status = 'done'; n.doneAt = f.at; if (f.turn) n.turn = f.turn; if (f.call) n.call = f.call; if (f.result !== undefined) n.result = f.result; break;
    case 'failed': n.status = 'failed'; n.doneAt = f.at; n.message = f.message || 'failed'; break;
    case 'timed_out': n.status = 'timed out'; n.doneAt = f.at; n.message = f.message || 'timed out'; break;
    case 'cancelled': n.status = 'cancelled'; n.doneAt = f.at; n.message = f.message || 'cancelled'; break;
  }
}
const secs = ms => ms < 1000 ? int(ms) + ' ms' : (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + ' s';
const traceElapsed = (n, now) => n.startedAt ? secs((n.doneAt || now) - n.startedAt) : n.status === 'queued' ? 'queued' : '';
const jsonLine = v => { try { return typeof v === 'string' ? v : JSON.stringify(v); } catch (e) { return String(v); } };
const jsonPretty = v => { try { return typeof v === 'string' ? v : JSON.stringify(v, null, 2); } catch (e) { return String(v); } };
const clip = (s, n) => s.length > n ? s.slice(0, n - 1) + '…' : s;
const retryBadges = n => n.retries.map(r => `<span class="badge b-warn" title="${esc(r.message)}">${r.retry === 'correction' || r.retry === 'truncation' ? 'correction' : 'retry'}</span>`).join(' ');
const admissionBadge = n => !n.admission ? '' : n.admission.state === 'held' && n.status !== 'done'
  ? `<span class="badge b-muted" title="${esc(n.admission.limiter)}">waiting for admission</span>`
  : n.admission.state === 'granted_from_hold' && n.admission.waitMs > 0 ? `<span class="badge b-muted" title="${esc(n.admission.limiter)}">waited ${int(n.admission.waitMs)} ms for admission</span>` : '';
// the agent announces each call it is about to make as a note naming the tool (by its display
// name, "Request Skill" for request_skill); the tool's own card says the same, so a note that
// only names a tool of the run is not shown twice
const plain = s => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
const toolNames = trace => new Set([...trace.jobs.values()].flatMap(j => [plain(j.name), plain(j.tool), ...(j.turn ? j.turn.toolCalls.map(c => plain(c.name)) : [])]));
const noteRows = (trace, n) => n.notes.filter(x => !(x.severity === 'INFO' && toolNames(trace).has(plain(x.message))))
  .map(x => `<div class="trace-note ${esc(x.severity || '')}"><b>${esc(x.title)}</b> ${esc(x.message)}</div>`).join('');
const callLine = c => !c ? '' : `<span class="num" title="${esc(c.servedModel ? 'served as ' + c.servedModel : '')}">${esc(c.model || '')}</span>
  <span class="num" title="tokens in / out${c.cacheReadTokens ? `, ${int(c.cacheReadTokens)} read from cache` : ''}">${int(c.inputTokens)} → ${int(c.outputTokens)} tokens</span>
  <span class="num">${c.cost === null || c.cost === undefined ? '' : money(c.cost, c.currency)}</span>${c.attempts > 1 ? ` <span class="badge b-warn">${c.attempts} attempts</span>` : ''}`;

/**
 * A tool's arguments or result as a person reads them: an object's fields one per line, a
 * list's items under their field, each clipped, the first few only; a plain value as its text.
 * The exact JSON stays under the card's "the whole result".
 */
const FIELDS_SHOWN = 6;
function readable(v, cls) {
  if (v === null || v === undefined) return '';
  if (typeof v !== 'object') return `<div class="${cls}">${esc(clip(String(v), 160))}</div>`;
  const entries = Array.isArray(v) ? v.map((x, i) => [String(i + 1), x]) : Object.entries(v);
  if (!entries.length) return '';
  const value = x => Array.isArray(x)
    ? (x.length ? `<ul>${x.slice(0, FIELDS_SHOWN).map(i => `<li>${esc(clip(jsonLine(i), 120))}</li>`).join('')}${x.length > FIELDS_SHOWN ? `<li>and ${x.length - FIELDS_SHOWN} more</li>` : ''}</ul>` : 'none')
    : esc(clip(jsonLine(x), 120));
  return `<ul class="items ${cls}">${entries.slice(0, FIELDS_SHOWN).map(([k, x]) => `<li>${esc(k)}: ${value(x)}</li>`).join('')}${entries.length > FIELDS_SHOWN ? `<li>and ${entries.length - FIELDS_SHOWN} more</li>` : ''}</ul>`;
}

/** A tool job as a card: its name, state and time, the arguments it was given, the result it returned; a sub-agent carries a rail of its own. */
function traceCard(trace, n, now) {
  const tone = traceTone(n);
  const state = n.status === 'running' ? `<span class="spinner"></span> ${esc(n.message || 'running')}` : n.status === 'queued' ? 'queued' : n.status;
  const args = n.input === undefined ? '' : readable(n.input, 'args');
  const key = 'r:' + n.id;
  const out = n.status !== 'done' ? (n.status === 'failed' || n.status === 'timed out' || n.status === 'cancelled' ? `<div class="out" style="color:var(--bad)">${esc(n.message)}</div>` : '')
    : n.result === undefined ? '' : `${readable(n.result, 'out')}${typeof n.result === 'object' && n.result !== null
        ? `<details data-key="${esc(key)}"${trace.open.has(key) ? ' open' : ''}><summary>the whole result</summary><pre>${esc(jsonPretty(n.result))}</pre></details>` : ''}`;
  const nested = n.type === 'THINKER' || n.type === 'DOER' ? traceRail(trace, n, now) : '';
  return `<div class="card ${tone}"><div class="card-head"><b title="${esc(n.name)}">${esc(n.tool || n.name)}</b><span class="badge ${tone === 'run' ? 'b-run' : tone === 'ok' ? 'b-ok' : tone === 'bad' ? 'b-bad' : 'b-muted'}">${state}</span>
    <span class="muted num">${esc(traceElapsed(n, now))}</span>${n.attempt > 1 ? `<span class="badge b-warn">attempt ${n.attempt}</span>` : ''} ${retryBadges(n)} ${admissionBadge(n)}</div>${args}${out}${noteRows(trace, n)}${nested}</div>`;
}

/** A job's children as turns: each model call heads a turn, the calls it decided on sit under it side by side. */
function traceRail(trace, node, now) {
  const kids = [...node.children].sort((a, b) => (a.queuedAt || 0) - (b.queuedAt || 0));
  const turns = new Map();
  const loose = [];
  for (const k of kids) {
    const key = k.iteration === null || k.iteration === undefined ? 'none' : k.iteration;
    if (k.type === 'LLM_CALL') { const t = turns.get(key) || {llm: null, tools: []}; t.llm = k; turns.set(key, t); }
    else if (key === 'none') loose.push(k);
    else { const t = turns.get(key) || {llm: null, tools: []}; t.tools.push(k); turns.set(key, t); }
  }
  const html = [];
  let number = 0;
  for (const [, t] of [...turns.entries()].sort(([a], [b]) => (a === 'none' ? -1 : a) - (b === 'none' ? -1 : b))) {
    number++;
    const llm = t.llm;
    const tone = llm ? traceTone(llm) : (t.tools.some(x => x.status === 'running') ? 'run' : '');
    const decided = !llm || llm.status !== 'done' ? '' : llm.turn && llm.turn.finalAnswer ? '<span class="badge b-ok">answered</span>'
      : llm.turn && llm.turn.toolCalls.length ? `<span class="muted">asked for ${llm.turn.toolCalls.length} tool call${llm.turn.toolCalls.length === 1 ? '' : 's'}</span>` : '';
    const head = llm ? `<div class="turn-head"><b>Turn ${number}</b>${llm.status === 'running' ? `<span class="spinner"></span> <span>${esc(llm.message || 'the model is thinking')}</span>` : llm.status === 'queued' ? '<span>queued</span>' : ''}
        <span class="num">${esc(traceElapsed(llm, now))}</span>${callLine(llm.call)}${decided}${llm.attempt > 1 ? `<span class="badge b-warn">attempt ${llm.attempt}</span>` : ''} ${retryBadges(llm)} ${admissionBadge(llm)}
        ${llm.status !== 'done' && llm.status !== 'running' && llm.status !== 'queued' ? `<span class="badge b-bad">${esc(llm.status)}</span> <span>${esc(llm.message)}</span>` : ''}</div>`
      : `<div class="turn-head"><b>Turn ${number}</b></div>`;
    const reasoning = llm && llm.turn && llm.turn.reasoning ? `<div class="reasoning"><span class="who">the model's reasoning</span>${esc(llm.turn.reasoning)}</div>` : '';
    const cards = t.tools.length ? `<div class="cards">${t.tools.map(k => traceCard(trace, k, now)).join('')}</div>` : '';
    html.push(`<div class="turn ${tone}">${head}${reasoning}${cards}${llm ? noteRows(trace, llm) : ''}</div>`);
  }
  if (loose.length) html.push(`<div class="turn"><div class="cards">${loose.map(k => traceCard(trace, k, now)).join('')}</div></div>`);
  return html.length ? `<div class="rail">${html.join('')}</div>` : '';
}

function traceBoard(trace, now) {
  const r = trace.root;
  const m = trace.measured;
  const o = trace.outcome;
  const state = trace.error ? 'failed' : o ? 'done' : !r ? 'submitting' : r.status === 'queued' ? 'waiting for admission' : r.status === 'running' ? (r.message || 'running') : r.status;
  const tone = trace.error ? 'b-bad' : o ? 'b-ok' : 'b-run';
  const elapsed = ((trace.ended || now) - trace.started) / 1000;
  const head = `<div class="facts"><span class="badge ${tone}">${esc(state)}</span><div><b>${elapsed.toFixed(1)} s</b> elapsed</div>
    ${m ? `<div><b>${int(m.calls)}</b> model call${m.calls === 1 ? '' : 's'}</div><div><b>${int(m.inputTokens)} → ${int(m.outputTokens)}</b> tokens</div><div><b>${m.cost === null || m.cost === undefined ? '–' : money(m.cost, m.currency)}</b>${o ? '' : ' so far'}</div>${m.servedModel ? `<div>served by <b>${esc(m.servedModel)}</b></div>` : ''}` : ''}
    ${r ? noteRows(trace, r) : ''}</div>`;
  const rail = r ? traceRail(trace, r, now) : '';
  // the answer types itself in once it lands, the way the chat shows it
  const text = trace.answer || (o && o.answer ? o.answer.answer : '');
  const answer = text ? `<div class="answer">${esc(text.slice(0, trace.shown))}${trace.shown < text.length ? '<span class="spinner"></span>' : ''}</div>` : '';
  const skillsUsed = o && o.answer && o.answer.skillsUsed || [];
  // the run's totals are the head row's; what only the finish adds is the Skills and the turns
  const summary = o ? `<div class="label">Skills used${skillsUsed.length ? '' : ': none'}</div>${skillsUsed.length ? `<ul class="items">${skillsUsed.map(k => `<li><code>${esc(k)}</code></li>`).join('')}</ul>` : ''}
    <div class="facts"><div>Turns<b>${r ? r.children.filter(k => k.type === 'LLM_CALL').length : '–'}</b></div></div>` : '';
  const failure = trace.error ? `<div class="callout bad"><h3>The runtime refused or failed</h3>${esc(trace.error)}</div>` : '';
  return head + rail + answer + summary + failure;
}

$('agent-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = $('agent-form'), out = $('agent-result'), button = form.querySelector('button');
  const f = Object.fromEntries(new FormData(form));
  button.disabled = true;
  const trace = {jobs: new Map(), root: null, measured: null, answer: '', shown: 0, outcome: null, error: null, started: Date.now(), ended: null, open: new Set()};
  const board = document.createElement('div');
  board.className = 'trace';
  out.innerHTML = '';
  out.appendChild(board);
  // an opened result stays open across the live repaints
  board.addEventListener('toggle', e => {
    const d = e.target;
    if (!d.dataset || !d.dataset.key) return;
    if (d.open) trace.open.add(d.dataset.key); else trace.open.delete(d.dataset.key);
  }, true);
  // the board is patched in place on every tick, never rebuilt: only an element that is new
  // animates in, an opened result stays open, a selection survives, and the timers tick quietly
  const paint = () => {
    const text = trace.answer || (trace.outcome && trace.outcome.answer ? trace.outcome.answer.answer : '');
    if (text) trace.shown = Math.min(text.length, trace.shown + Math.max(3, Math.ceil(text.length / 40)));
    const next = document.createElement('div');
    next.className = 'trace';
    next.innerHTML = traceBoard(trace, Date.now());
    morph(board, next);
  };
  paint();
  const timer = setInterval(paint, 150);
  try {
    const response = await fetch('/agent', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify({query: f.query, grade: f.grade || null})});
    if (!response.ok) {
      const text = await response.text();
      let data = null;
      try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
      throw new Error((data && (data.message || data.error)) || text || ('HTTP ' + response.status));
    }
    await readLines(response, frame => {
      if (frame.type === 'workflow_complete') { trace.outcome = frame; if (frame.measured) trace.measured = frame.measured; }
      else if (frame.type === 'workflow_failed') throw new Error(frame.message);
      else { traceEvent(trace, frame); paint(); }
    });
    if (!trace.outcome) throw new Error('the run ended without an answer');
  } catch (e) {
    trace.error = e.message;
  } finally {
    trace.ended = Date.now();
    // the answer finishes typing, then the repaints stop
    const settle = setInterval(() => {
      paint();
      const text = trace.answer || (trace.outcome && trace.outcome.answer ? trace.outcome.answer.answer : '');
      if (trace.shown >= text.length) { clearInterval(settle); clearInterval(timer); paint(); }
    }, 150);
    button.disabled = false;
  }
});
call('GET', '/agent').then(c => {
  $('agent-palette').querySelector('div').outerHTML = `<div>
    <div class="label">Tools</div><ul class="items">${c.tools.map(t => `<li><code>${esc(t)}</code></li>`).join('')}</ul>
    <div class="label">Skills it may admit</div><ul class="items">${c.skills.map(s => `<li><code>${esc(typeof s === 'string' ? s : s.name)}</code></li>`).join('')}</ul></div>`;
}).catch(e => { $('agent-palette').querySelector('div').textContent = e.message; });

// ---------- 4. extract ----------
// a note of a sentence or two reads in the row; a longer one (a model's reasoning about a
// file's bytes) folds behind its opening words so no column grows to hold a paragraph
const longNote = note => !note ? '' : note.length <= 90 ? esc(note)
  : `<details class="note-fold"><summary>${esc(clip(note, 70))}</summary>${esc(note)}</details>`;
const tierBadge = t => `<span class="badge ${({DETERMINISTIC: 'b-ok', VISION: 'b-ok', CLASSIFIER: 'b-ok', SKIPPED: 'b-muted', REFUSED: 'b-warn', FAILED: 'b-bad'})[t] || 'b-muted'}">${esc(t)}</span>`;
wire('extract-form', 'extract-result', f => call('POST', '/extract', {directory: f.directory,
    budgets: f.amount === '' ? null : [{amount: Number(f.amount), currency: f.currency}]}),
  r => `<div class="facts">
      <div>Files<b>${int(r.filesSeen)}</b></div><div>Indexed<b>${int(r.indexed)}</b></div><div>Model calls<b>${int(r.llmCalls)}</b></div>
      <div>Spent<b>${spent(r.spent)}</b></div><div>Took<b>${int(r.elapsedMs)} ms</b></div></div>
    <div class="pills" style="margin:0 0 10px">${Object.entries(r.byTier || {}).map(([t, n]) => `${tierBadge(t)} <span class="muted">${n}</span>`).join(' &nbsp; ')}</div>
    <div class="scroll"><table><tr><th>File</th><th>Tier</th><th class="num">Characters</th><th>Read by</th><th class="num" title="reading and indexing">Cost</th><th>Note</th></tr>
    ${r.files.map(f => `<tr><td>${esc(f.path)}</td><td>${tierBadge(f.tier)}</td><td class="num">${int(f.chars)}</td><td class="nowrap">${f.modelId ? esc(f.modelId) : f.tier === 'DETERMINISTIC' ? '<span class="muted">code</span>' : ''}</td>
      <td class="num">${money(f.cost, f.currency)}</td><td class="muted">${longNote(f.note)}</td></tr>`).join('')}</table></div>
    ${r.byModel && r.byModel.length ? `<details><summary>Spend per model</summary><div class="scroll"><table><tr><th>Model</th><th class="num">Calls</th><th class="num">Input tokens</th><th class="num">Output tokens</th><th class="num">Cost</th></tr>
      ${r.byModel.map(m => `<tr><td class="nowrap">${esc(m.modelId)}</td><td class="num">${int(m.calls)}</td><td class="num">${int(m.inputTokens)}</td><td class="num">${int(m.outputTokens)}</td><td class="num">${money(m.cost, m.currency)}</td></tr>`).join('')}</table></div></details>` : ''}`);

// ---------- 5. search ----------
wire('search-form', 'search-result', f => call('POST', '/search', {query: f.query, k: Number(f.k)}),
  hits => hits.length ? `<div class="scroll"><table><tr><th>File</th><th class="num">Similarity</th><th>Excerpt</th></tr>
    ${hits.map(h => `<tr><td>${esc(h.path)}</td><td class="num">${h.score.toFixed(3)}</td><td class="muted">${esc(h.excerpt)}</td></tr>`).join('')}</table></div>`
    : '<div class="muted">Nothing in the index matched.</div>');

// ---------- 6. pricing ----------
const statusClass = s => ({CURRENT: 'b-ok', CONFIRMED: 'b-ok', SCHEDULED: 'b-warn', CONFLICT: 'b-bad', UNAPPLIED: 'b-bad', DISCONTINUED: 'b-bad'})[s] || 'b-muted';
// a status as a word, NOT_IN_FORCE as "not in force"; the colour still comes from the status itself
const priceBadge = s => `<span class="badge ${statusClass(s)}">${esc(String(s).toLowerCase().replace(/_/g, ' '))}</span>`;
// the other names a product went by: the product's own name is its heading, never its alias
const otherNames = p => (p.aliases || []).filter(a => a.trim().toLowerCase() !== String(p.product).trim().toLowerCase());
function pointLine(p) {
  const amount = p.amount !== null && p.amount !== undefined ? money(p.amount, p.currency, 2) : (p.percentChange !== null && p.percentChange !== undefined ? (p.percentChange > 0 ? '+' : '') + p.percentChange + '%' : '–');
  return `<tr><td>${priceBadge(p.status)}</td><td class="num">${amount}</td><td class="nowrap">${esc(p.date)}</td><td>${esc(p.variant)}</td><td>${esc(p.source)}</td>
    <td>${p.quote ? `<span class="quote">“${esc(p.quote)}”</span>` : ''}${p.note ? `<div class="muted">${esc(p.note)}</div>` : ''}${p.appliedTo ? `<div class="muted">applied to ${esc(p.appliedTo)}</div>` : ''}</td></tr>`;
}
// one report, one rendering: step 6 and step 9 answer in the same shape
const pricingBody = f => ({asOf: f.asOf || null, output: f.output || null, budgets: f.amount === '' ? null : [{amount: Number(f.amount), currency: f.currency}]});
const pricingResult = r => `<div class="facts">
      <div>As of<b>${esc(r.asOf)}</b></div><div>Documents read<b>${int(r.documentsRead)}</b></div><div>Prices found<b>${int(r.mentionsFound)}</b></div>
      <div>Products<b>${int(r.products.length)}</b></div><div>Spent<b>${spent(r.spend && r.spend.spent)}</b></div><div>Took<b>${int(r.elapsedMs)} ms</b></div></div>
    ${r.outputFile ? `<div class="muted">Written to <code>${esc(r.outputFile)}</code></div>` : ''}
    ${r.canonicalizationNote ? `<div class="callout warn">${esc(r.canonicalizationNote)}</div>` : ''}
    <div class="scroll"><table><tr><th>Product</th><th>Audience</th><th class="num">Current price</th><th>Since</th><th>Status</th><th>Source</th></tr>
    ${r.products.map(p => p.series.map((s, i) => `<tr>
        <td>${i === 0 ? `<b>${esc(p.product)}</b>${otherNames(p).length ? `<ul class="items">${otherNames(p).map(a => `<li>${esc(a)}</li>`).join('')}</ul>` : ''}${p.discontinuedFrom ? `<div>${priceBadge('DISCONTINUED')} ${esc(p.discontinuedFrom)}</div>` : ''}` : ''}</td>
        <td>${esc(s.audience)} · ${esc(s.currency)}</td>
        <td class="num">${s.current ? money(s.current.amount, s.current.currency || s.currency, 2) : '–'}</td>
        <td>${s.current ? esc(s.current.date) : ''}</td><td>${priceBadge(s.status)}</td><td>${s.current ? esc(s.current.source) : ''}</td></tr>`).join('')).join('')}
    </table></div>
    <ul class="fine">
      <li>Current is the price in force on the day answered for.</li>
      <li>Scheduled starts after it.</li>
      <li>A row with only a status means no price is in force for that audience: the documents carry
        only proposals, former prices or a discontinuation there.</li>
      <li>The details below show every price with the words it came from.</li>
    </ul>
    <details><summary>Every price found, with the words it came from</summary>
    ${r.products.map(p => `<h3>${esc(p.product)}</h3>${p.series.map(s => `<div class="label">${esc(s.audience)} · ${esc(s.currency)}</div>
      <div class="scroll"><table><tr><th>Status</th><th class="num">Price</th><th>Date</th><th>Variant</th><th>Source</th><th>Says</th></tr>${s.points.map(pointLine).join('')}</table></div>`).join('')}
      ${p.costs && p.costs.length ? `<div class="label">Costs</div><div class="scroll"><table>${p.costs.map(pointLine).join('')}</table></div>` : ''}`).join('')}
    ${r.ignored && r.ignored.length ? `<h3>Set aside</h3><div class="scroll"><table><tr><th>Status</th><th class="num">Price</th><th>Date</th><th>Variant</th><th>Source</th><th>Says</th></tr>${r.ignored.map(pointLine).join('')}</table></div>` : ''}
    </details>
    <details><summary>Documents (${r.documents.length})</summary><div class="scroll"><table><tr><th>Document</th><th>Status</th><th class="num">Prices</th><th>Dated</th><th>Model</th><th class="num">Cost</th><th>Note</th></tr>
    ${r.documents.map(d => `<tr><td>${esc(d.path)}</td><td>${priceBadge(d.status)}</td><td class="num">${int(d.mentions)}</td><td class="nowrap">${esc(d.documentDate)}</td><td class="nowrap">${esc(d.modelId)}</td><td class="num">${money(d.cost, d.currency)}</td><td class="muted">${esc(d.note)}</td></tr>`).join('')}
    </table></div></details>`;
wire('pricing-form', 'pricing-result', f => call('POST', '/pricing', pricingBody(f)), pricingResult);

// ---------- 7. benchmark ----------
// The race table: the server streams one JSON line per event of every run, judge and model
// call as it happens (the same events a chat UI renders over its socket). One row per run
// carries its status and what it is doing right now, gains its spend and the judge's verdict
// as they land, and the rows re-sort by the judge's score - price only breaks ties - so the
// best answers rise to the top while the race is still on. When the last line lands, the
// report's ledger figures replace the running ones and the answer heads the table.
const RACE_BADGE = {queued: 'b-muted', running: 'b-run', retrying: 'b-warn', correcting: 'b-warn', answered: 'b-ok', judging: 'b-run', judged: 'b-ok',
  reference: 'b-ok', 'judge failed': 'b-warn', unjudged: 'b-warn', failed: 'b-bad', 'timed out': 'b-bad', cancelled: 'b-bad'};
const RACE_BUSY = status => status === 'running' || status === 'retrying' || status === 'correcting';
// An upstream retry is the provider failing or throttling; a correction is the model's own answer
// being re-asked because it did not parse or validate. The table counts them apart.
function raceRetry(r, f) {
  if (f.retry === 'correction' || f.retry === 'truncation') { r.status = 'correcting'; r.corrections++; }
  else { r.status = 'retrying'; r.retries++; }
  r.note = f.message;
}
const raceCounts = r => [r.retries ? `${r.retries} upstream retr${r.retries === 1 ? 'y' : 'ies'}` : '', r.corrections ? `${r.corrections} correction${r.corrections === 1 ? '' : 's'}` : ''].filter(Boolean).join(', ');
const scored = r => r.judgeScore !== null && r.judgeScore !== undefined;

/** The rows in table order: the reference first, then scored ones by the judge's score, price breaking ties; the rest in race order. */
function raceOrder(race) {
  const rows = [...race.rows.values()];
  const rank = r => !r.model ? 0 : scored(r) ? 1 : 2;
  return rows.map((r, i) => [r, i]).sort(([a, i], [b, j]) =>
    (rank(a) - rank(b)) || (scored(a) ? ((b.judgeScore - a.judgeScore) || ((a.cost ?? Infinity) - (b.cost ?? Infinity))) : 0) || (i - j)).map(([r]) => r);
}

function raceHeader(race, now) {
  const rows = [...race.rows.values()];
  const count = status => rows.filter(r => r.status === status).length;
  const running = rows.filter(r => RACE_BUSY(r.status) || r.status === 'judging').length;
  const o = race.outcome;
  return (o ? `<div class="answer">${esc(o.answer && o.answer.answer)}</div>
    <div class="facts"><div>Reference Grade<b>${esc(o.report.grade)}</b></div><div>Runs per model<b>${int(o.report.runs)}</b></div>
      <div>Judged by<b>${esc(o.report.judgeModel || '–')}</b></div><div>Took<b>${int(o.report.elapsedMs)} ms</b></div></div>
    ${o.report.judgeFailure ? `<div class="callout warn">The judge could not run, so no answer is scored: ${esc(o.report.judgeFailure)}</div>` : ''}` : '') +
    `<div class="facts"><div>${esc(race.overall)}</div><div><b>${running}</b> running</div><div><b>${rows.filter(scored).length}</b> scored</div>
      <div><b>${count('failed') + count('timed out') + count('cancelled')}</b> failed</div><div><b>${((o ? race.ended : now) - race.started) / 1000 | 0} s</b> elapsed</div></div>`;
}

function raceTable(race, now) {
  const rows = raceOrder(race);
  const elapsed = r => r.wallMs !== null && r.wallMs !== undefined ? `${int(r.wallMs)} ms` : r.startedAt ? `${((r.doneAt || now) - r.startedAt) / 1000 | 0} s` : '';
  const costs = rows.map(r => r.cost).filter(c => c !== null && c !== undefined);
  const cheapest = Math.min(...costs), dearest = Math.max(...costs);
  const costHeat = c => c === null || c === undefined ? '' : `style="color:${heat(dearest > cheapest ? (c - cheapest) / (dearest - cheapest) : 0)}"`;
  const scoreHeat = v => v === null || v === undefined ? '' : `style="color:${heat(1 - v / 10)}"`;
  // the judge's reason and a failure's message are long: folded under the row's short note,
  // opened per row, and the open ones stay open across the live repaints
  // the verdict's two parts and the tool fault lead, the judge's reason follows, one bullet each
  const verdict = r => `<ul class="items">${[scored(r) ? `accuracy: ${dec(r.judgeAccuracy)} of 7` : '', scored(r) ? `shown work: ${dec(r.judgeReasoning)} of 3` : '',
    r.judgeToolFault ? `tool fault: ${r.judgeToolFault}` : '', r.judgeReason ? `reason: ${r.judgeReason}` : ''].filter(Boolean).map(x => `<li>${esc(x)}</li>`).join('')}</ul>`;
  const why = r => r.failure ? ['why', esc(r.failure)] : r.judgeReason ? ['reason', verdict(r)] : null;
  const noteCell = r => why(r)
    ? `<span class="why-toggle" data-row="${esc(r.key)}">${race.open.has(r.key) ? '▾' : '▸'} ${esc(why(r)[0])}</span>`
    : `<div class="note" title="${esc(r.note)}">${esc(r.note)}</div>`;
  const whyRow = r => why(r) && race.open.has(r.key) ? `<tr class="why-row"><td colspan="10">${why(r)[1]}</td></tr>` : '';
  return `<div class="scroll"><table><tr><th>Model</th><th>Grade</th><th class="num">Run</th><th>Status</th><th>Right now</th><th class="num">Time</th><th class="num">Calls</th><th class="num">Tokens in/out</th><th class="num">Cost</th><th class="num" title="accuracy of 7 plus shown work of 3">Judge</th></tr>
    ${rows.map(r => `<tr><td class="nowrap" title="${r.servedModel ? `served as ${esc(r.servedModel)}` : ''}">${r.model ? esc(r.model) : '<span class="badge b-muted">reference</span>'}</td>
      <td>${esc(r.model ? gradeOf[r.model] || '' : '')}</td><td class="num">${r.model ? r.run : ''}</td>
      <td><span class="badge ${RACE_BADGE[r.status] || 'b-muted'}">${esc(r.status)}</span>${raceCounts(r) ? ` <span class="muted">${esc(raceCounts(r))}</span>` : ''}</td>
      <td>${noteCell(r)}</td><td class="num">${elapsed(r)}</td>
      <td class="num">${r.calls ? int(r.calls) : ''}</td><td class="num">${r.calls ? `${int(r.inputTokens)} / ${int(r.outputTokens)}` : ''}</td>
      <td class="num heat" ${costHeat(r.cost)}>${r.cost === null || r.cost === undefined ? '' : money(r.cost, r.currency)}</td>
      <td class="num heat" ${scoreHeat(r.judgeScore)}>${scored(r) ? r.judgeScore : ''}</td></tr>${whyRow(r)}`).join('')}
    </table></div>
    <ul class="fine">
      <li>The reference is the strongest model this deployment serves, the same one that judges.</li>
      <li>Rows sort by the judge's score as verdicts land, best answers on top.</li>
      <li>Price only breaks ties.</li>
      <li>Unscored rows wait below in race order.</li>
      <li>Cost goes from green for the cheapest run to red for the dearest.</li>
      <li>The judge's score goes from green at 10 to red at 0.</li>
    </ul>`;
}

const raceBoard = (race, now) => raceHeader(race, now) + raceTable(race, now);

// The race's verdict as a grade order: per grade, the models the judge scored ranked by value -
// the cheapest first among those whose mean score is within VALUE_MARGIN points of the grade's
// best, then the rest by score, price breaking ties. The models the grade's order placed before
// and the race did not score keep their places after these. "Apply this order" writes each
// grade's proposal as its order, the same call the models table's moves make.
const VALUE_MARGIN = 1;
function proposeOrder(where, rows) {
  const byModel = new Map();
  // every model that ran, scored or not: a placed one that failed or went unjudged is said so
  const raced = new Set(rows.filter(w => !w.reference).map(w => w.modelId));
  for (const w of rows) {
    if (w.reference || !w.succeeded || w.judgeScore === null || w.judgeScore === undefined) continue;
    const m = byModel.get(w.modelId) || {id: w.modelId, scores: [], costs: []};
    m.scores.push(w.judgeScore);
    if (w.cost !== null && w.cost !== undefined) m.costs.push(w.cost);
    byModel.set(w.modelId, m);
  }
  const mean = xs => xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : Infinity;
  const byGrade = new Map();
  for (const m of byModel.values()) {
    const grade = gradeOf[m.id];
    if (!['MICRO', 'SMALL', 'MEDIUM', 'LARGE', 'XL', 'MEGA'].includes(grade)) continue;
    m.score = mean(m.scores);
    m.cost = mean(m.costs);
    if (!byGrade.has(grade)) byGrade.set(grade, []);
    byGrade.get(grade).push(m);
  }
  if (!byGrade.size) { where.innerHTML = ''; return; }
  const orders = (lastStatus && lastStatus.catalog.orders) || {};
  const proposed = [...byGrade.entries()].map(([grade, models]) => {
    const best = Math.max(...models.map(m => m.score));
    const close = models.filter(m => m.score >= best - VALUE_MARGIN).sort((a, b) => a.cost - b.cost);
    const rest = models.filter(m => m.score < best - VALUE_MARGIN).sort((a, b) => (b.score - a.score) || (a.cost - b.cost));
    const ranked = [...close, ...rest];
    const kept = (orders[grade] || []).filter(id => !byModel.has(id));
    return {grade, ranked, kept, close: close.length};
  });
  const currency = rows.find(w => w.currency) ? rows.find(w => w.currency).currency : null;
  const editable = lastStatus && (lastStatus.catalog.editableFile || lastStatus.catalog.catalogFile);
  where.innerHTML = `<div class="callout ok"><h3>Proposed order</h3>
    <p style="margin:0 0 6px">This race can put every model in your catalog in order. Within each grade, the model that answered well for the least money comes first, so the grade's everyday work goes to it, and the others follow as its fallbacks. Apply it, and step 1's catalog serves each grade in this order.</p>
    <ul class="items">
      <li>The order is only as good as the query it was measured on. Race one that is representative of your normal workloads.</li>
      <li>You can run a different benchmark at any time and apply its order instead.</li>
      <li>You can change the order by hand in step 1's models table whenever you like.</li>
    </ul>
    <p class="brief" style="margin:0 0 6px">Per grade, the cheapest model within ${VALUE_MARGIN} point of the grade's best mean score comes first. The models the grade's order placed before, and this race did not score, keep their places after these.</p>
    <ul class="pins">${proposed.map(p => `<li>${esc(p.grade)}:<ol>${p.ranked.map((m, i) => `<li><code>${esc(m.id)}</code> <span class="muted">score ${dec(m.score)}, ${m.cost === Infinity ? 'no cost' : money(m.cost, currency)} a run${i < p.close ? '' : ', below the margin'}</span></li>`).join('')}
      ${p.kept.map(id => `<li><code>${esc(id)}</code> <span class="muted">placed before, ${raced.has(id) ? 'no score in this race' : 'not in this race'}</span></li>`).join('')}</ol></li>`).join('')}</ul>
    ${editable ? '<button type="button" class="apply-order">Apply this order</button> <span class="apply-note"></span>'
      : '<p class="muted" style="margin:0">The catalog in use is not a file this demo may edit, so the order cannot be applied here.</p>'}</div>`;
  const button = where.querySelector('.apply-order');
  if (!button) return;
  button.addEventListener('click', async () => {
    button.disabled = true;
    const note = where.querySelector('.apply-note');
    try {
      let status = null;
      for (const p of proposed) status = await call('POST', '/catalog/order', {grade: p.grade, ids: [...p.ranked.map(m => m.id), ...p.kept]});
      keepModelsOpen = true;
      renderSetup(status);
      note.textContent = 'Applied: the models table in step 1 shows the new order.';
    } catch (e) {
      note.textContent = e.message;
      note.style.color = 'var(--bad)';
      button.disabled = false;
    }
  });
}

function raceEvent(race, f) {
  if (f.type === 'status' && f.status === 'plan') {
    for (const row of f.rows) race.rows.set(row.key, {...row, status: 'queued', note: '', retries: 0, corrections: 0, startedAt: null, doneAt: null,
      calls: 0, inputTokens: 0, outputTokens: 0, cost: null, currency: null, servedModel: null, wallMs: null,
      judgeScore: null, judgeAccuracy: null, judgeReasoning: null, judgeToolFault: null, judgeReason: '', failure: ''});
    return;
  }
  if (f.type === 'status' && f.status === 'overall') { race.overall = f.message; return; }
  if (f.type !== 'progress') return;
  const r = race.rows.get(f.row);
  if (!r) return;
  const attempt = f.attempt > 1 ? ` (attempt ${f.attempt})` : '';
  if (f.measured) {
    // the row's spend so far, summed server-side from the responses its finished jobs carried
    Object.assign(r, f.measured);
  }
  if (f.role === 'judge') {
    // the judge of this row's answer: its own events, and its one call's
    if (f.kind === 'started' && !f.child) { r.status = 'judging'; r.note = 'the judge reads the answer' + attempt; }
    else if (f.kind === 'retry') { r.status = 'judging'; r.note = f.message; }
    else if (f.kind === 'completed' && !f.child) {
      r.status = 'judged'; r.note = 'judged';
      if (f.score !== undefined) { r.judgeScore = f.score; r.judgeAccuracy = f.accuracy; r.judgeReasoning = f.reasoning; r.judgeToolFault = f.toolFault || null; r.judgeReason = f.reason || ''; }
    }
    else if ((f.kind === 'failed' || f.kind === 'timed_out') && !f.child) { r.status = 'judge failed'; r.note = 'the judge failed'; r.judgeReason = 'the judge failed: ' + (f.message || ''); }
    return;
  }
  if (f.child) {
    // a call the run made: it says what the run is waiting on, never changes the run's outcome
    if (f.kind === 'started') { if (RACE_BUSY(r.status)) { r.status = 'running'; r.note = `calling the model${attempt}`; } }
    else if (f.kind === 'retry') { if (RACE_BUSY(r.status)) raceRetry(r, f); }
    else if (f.kind === 'completed') { if (RACE_BUSY(r.status)) { r.status = 'running'; r.note = 'the model answered; the agent reasons on'; } }
    else if (f.kind === 'failed') { if (RACE_BUSY(r.status)) r.note = 'a call failed: ' + (f.message || ''); }
    else if (f.kind === 'progress' && f.message) r.note = f.message;
    return;
  }
  switch (f.kind) {
    case 'queued': r.status = 'queued'; r.note = 'waiting for admission'; break;
    case 'started': r.status = 'running'; r.startedAt = r.startedAt || f.at; r.note = 'running' + attempt; break;
    case 'progress': if (f.message) r.note = f.message; break;
    case 'retry': raceRetry(r, f); break;
    case 'completed': r.doneAt = f.at; if (r.model) { r.status = 'answered'; r.note = 'answered; waiting for the judge'; } else { r.status = 'reference'; r.note = 'the ground truth the others are judged against'; } break;
    case 'failed': r.status = 'failed'; r.doneAt = f.at; r.note = 'failed'; r.failure = f.message || 'failed'; break;
    case 'timed_out': r.status = 'timed out'; r.doneAt = f.at; r.note = 'timed out'; r.failure = f.message || 'timed out'; break;
    case 'cancelled': r.status = 'cancelled'; r.doneAt = f.at; r.note = 'cancelled'; r.failure = f.message || 'cancelled'; break;
  }
}

/** Patches a live element to match a freshly rendered one, child by child, so a repaint changes only what changed. */
function morph(live, fresh) {
  if (live.nodeType !== fresh.nodeType || live.nodeName !== fresh.nodeName) { live.replaceWith(fresh); return; }
  if (live.nodeType === Node.TEXT_NODE) { if (live.nodeValue !== fresh.nodeValue) live.nodeValue = fresh.nodeValue; return; }
  for (const {name} of [...live.attributes]) if (!fresh.hasAttribute(name)) live.removeAttribute(name);
  for (const {name, value} of [...fresh.attributes]) if (live.getAttribute(name) !== value) live.setAttribute(name, value);
  if (live.nodeName === 'DETAILS' && live.open !== fresh.open) live.open = fresh.open;
  const a = [...live.childNodes], b = [...fresh.childNodes];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (!a[i]) live.appendChild(b[i]);
    else if (!b[i]) a[i].remove();
    else morph(a[i], b[i]);
  }
}

/** Reads a newline-delimited JSON response as it arrives, one parsed line at a time. */
async function readLines(response, onLine) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const {value, done} = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, {stream: true});
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) onLine(JSON.parse(line));
    }
  }
}

$('bench-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = $('bench-form'), out = $('bench-result'), button = form.querySelector('button');
  const f = Object.fromEntries(new FormData(form));
  button.disabled = true;
  const race = {rows: new Map(), overall: 'Submitting the race…', started: Date.now(), open: new Set()};
  const board = document.createElement('div');
  out.innerHTML = '';
  out.appendChild(board);
  // a row's toggle opens or closes its reason row; the set survives the live repaints
  board.addEventListener('click', e => {
    const toggle = e.target.closest('.why-toggle');
    if (!toggle) return;
    if (race.open.has(toggle.dataset.row)) race.open.delete(toggle.dataset.row); else race.open.add(toggle.dataset.row);
    paint();
  });
  const paint = () => { board.innerHTML = raceBoard(race, Date.now()); };
  paint();
  const timer = setInterval(paint, 1000);
  let outcome = null;
  try {
    const response = await fetch('/benchmark', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify({query: f.query, runs: Number(f.runs)})});
    if (!response.ok) {
      const text = await response.text();
      let data = null;
      try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
      throw new Error((data && (data.message || data.error)) || text || ('HTTP ' + response.status));
    }
    await readLines(response, frame => {
      if (frame.type === 'workflow_complete') outcome = frame;
      else if (frame.type === 'workflow_failed') throw new Error(frame.message);
      else { raceEvent(race, frame); paint(); }
    });
    if (!outcome) throw new Error('the race ended without a report');
    // the report's ledger figures are the record: they replace the running sums, and the
    // judge's verdicts and each run's outcome come from it too
    for (const w of outcome.report.rows) {
      const r = race.rows.get(w.reference ? 'reference' : `${w.modelId}#${w.run}`);
      if (!r) continue;
      Object.assign(r, {calls: w.calls, inputTokens: w.inputTokens, outputTokens: w.outputTokens, cost: w.cost, currency: w.currency,
        servedModel: w.servedModelId, wallMs: w.wallMs, judgeScore: w.judgeScore, judgeAccuracy: w.judgeAccuracy, judgeReasoning: w.judgeReasoning,
        judgeToolFault: w.judgeToolFault || null, judgeReason: w.judgeReason || ''});
      if (!w.succeeded) { r.status = 'failed'; r.note = 'failed'; r.failure = w.failure || r.failure; }
      else if (!r.model) { r.status = 'reference'; r.note = 'the ground truth the others are judged against'; }
      else if (scored(r)) { r.status = 'judged'; r.note = 'judged'; }
      else { r.status = 'unjudged'; r.note = 'no verdict'; }
    }
    race.outcome = outcome;
    race.ended = Date.now();
    race.overall = 'The race is over';
    paint();
    const proposal = document.createElement('div');
    out.appendChild(proposal);
    proposeOrder(proposal, outcome.report.rows);
  } catch (e) {
    const failure = document.createElement('div');
    failure.className = 'callout bad';
    failure.innerHTML = `<h3>The runtime refused or failed</h3>${esc(e.message)}`;
    out.appendChild(failure);
  } finally {
    clearInterval(timer);
    button.disabled = false;
  }
});

// ---------- 8. decide ----------
// The decision run's board: the server streams one JSON line per event of the thinker and of
// every job under it. A decision's completion carries the whole exchange (the state the model
// saw, the questions, every distribution it answered), a tool's completion what it produced.
// A turn is the decision that opened it and the tool that ran on it, by the turn the thinker
// stamped on both; the page draws the distributions as bars, the chosen option marked.
const decideNode = (run, j) => {
  let n = run.jobs.get(j.id);
  if (!n) {
    n = {id: j.id, parent: j.parent, type: j.type, name: j.name, tool: j.tool, decision: j.decision, iteration: j.iteration, own: j.own, status: 'queued',
      queuedAt: null, startedAt: null, doneAt: null, attempt: 1, exchange: null, result: undefined, message: '', retries: [], admission: null, notes: []};
    run.jobs.set(j.id, n);
    if (j.own) run.root = n; else run.children.push(n);
  }
  return n;
};
function decideEvent(run, f) {
  if (f.type !== 'job') return;
  const n = decideNode(run, f.job);
  if (f.measured) run.measured = f.measured;
  switch (f.kind) {
    case 'queued': n.status = 'queued'; n.queuedAt = f.at; break;
    case 'started': n.status = 'running'; n.startedAt = n.startedAt || f.at; n.attempt = f.attempt || 1; break;
    case 'progress': n.message = f.message || ''; break;
    case 'note': n.notes.push({title: f.title, severity: f.severity, message: f.message}); break;
    case 'retry': n.retries.push({retry: f.retry, message: f.message}); n.status = 'running'; break;
    case 'admission': n.admission = {state: f.state, limiter: f.limiter, waitMs: f.waitMs}; break;
    case 'completed': n.status = 'done'; n.doneAt = f.at; if (f.decision) n.exchange = f.decision; if (f.result !== undefined) n.result = f.result; break;
    case 'failed': n.status = 'failed'; n.doneAt = f.at; n.message = f.message || 'failed'; break;
    case 'timed_out': n.status = 'timed out'; n.doneAt = f.at; n.message = f.message || 'timed out'; break;
    case 'cancelled': n.status = 'cancelled'; n.doneAt = f.at; n.message = f.message || 'cancelled'; break;
  }
}
const pct = p => Math.round(p * 100) + '%';
// a decision on a model priced at nothing, one on this machine, costs nothing, and says so in words
const freeOr = (cost, currency) => cost === null || cost === undefined ? '' : cost === 0 ? 'free' : money(cost, currency);
// a state line or a candidate's description reads «artifact:type~id» (type): words; the words alone for a label
const refOf = s => { const m = String(s).match(/«artifact:[^»]*»/); return m ? m[0] : null; };
const refWords = s => String(s).replace(/^«artifact:[^»]*»\s*\([^)]*\):\s*/, '');
/** One bar per option, highest first, the chosen ones marked; past the limit the rest is counted. */
const bars = (probabilities, chosen, labelOf, limit) => {
  const rows = Object.entries(probabilities || {}).map(([k, p]) => ({k, p, label: labelOf ? labelOf(k) : k}));
  rows.sort((a, b) => b.p - a.p);
  const shown = limit && rows.length > limit ? rows.slice(0, limit) : rows;
  return `<div class="dist">${shown.map(r => `<div class="bar${chosen.has(r.k) ? ' chosen' : ''}" title="${esc(r.label)}"><span class="fill" style="width:${Math.max(1, Math.round(r.p * 100))}%"></span><span class="lbl">${esc(r.label)}</span><span class="p">${pct(r.p)}</span></div>`).join('')}
    ${shown.length < rows.length ? `<div class="more">and ${rows.length - shown.length} more, each at ${pct(shown[shown.length - 1].p)} or less</div>` : ''}</div>`;
};
function decideCard(run, n, now) {
  const tone = traceTone(n);
  const state = n.status === 'running' ? `<span class="spinner"></span> ${esc(n.message || 'running')}` : n.status === 'queued' ? 'queued' : n.status;
  const key = 'r:' + n.id;
  const out = n.status !== 'done' ? (n.status === 'failed' || n.status === 'timed out' || n.status === 'cancelled' ? `<div class="out" style="color:var(--bad)">${esc(n.message)}</div>` : '')
    : n.result === undefined ? '' : `<div class="out">→ ${esc(n.result.digest || '')}</div>${n.result.iterands && n.result.iterands.length
      ? `<details data-key="${esc(key)}"${run.open.has(key) ? ' open' : ''}><summary>the ${n.result.iterands.length} produced</summary><pre>${esc(n.result.iterands.map(i => i.digest).join('\n'))}</pre></details>` : ''}`;
  return `<div class="card ${tone}"><div class="card-head"><b title="${esc(n.name)}">${esc(n.tool || n.name)}</b><span class="badge ${tone === 'run' ? 'b-run' : tone === 'ok' ? 'b-ok' : tone === 'bad' ? 'b-bad' : 'b-muted'}">${state}</span>
    <span class="muted num">${esc(traceElapsed(n, now))}</span>${retryBadges(n)} ${admissionBadge(n)}</div>${out}</div>`;
}
function decideTurn(run, number, turn, now) {
  const d = turn.decision, t = turn.tool;
  const x = d && d.exchange;
  const tone = d ? traceTone(d) : (t ? traceTone(t) : '');
  // the turn's time is its decision's, shown once beside the number; the entry is the catalog's name for the model
  const call = x && x.call ? `<span class="num" title="${esc(x.call.servedModel ? 'served as ' + x.call.servedModel : '')}">${esc(x.call.model || x.call.servedModel || '')}</span><span class="num">${int(x.call.inputTokens)} tokens read</span><span class="num">${freeOr(x.call.cost, x.call.currency)}</span>` : '';
  let head = `<div class="turn-head"><b>Turn ${number}</b>`;
  if (d && d.status === 'running') head += `<span class="spinner"></span> <span>the model is deciding</span>`;
  else if (d && d.status === 'queued') head += '<span>queued</span>';
  if (d) head += `<span class="num">${esc(traceElapsed(d, now))}</span>${call}${d.attempt > 1 ? `<span class="badge b-warn">attempt ${d.attempt}</span>` : ''} ${retryBadges(d)} ${admissionBadge(d)}`;
  if (d && d.status !== 'done' && d.status !== 'running' && d.status !== 'queued') head += `<span class="badge b-bad">${esc(d.status)}</span> <span>${esc(d.message)}</span>`;
  head += '</div>';
  let body = '';
  if (x) {
    const next = x.answers.next, q = x.questions.next;
    if (next && q) {
      body += `<div class="reasoning"><span class="who">what runs next</span>${bars(next.probabilities, new Set([next.choice]), k => k === 'finish' ? 'finish: the answer is in hand' : k, 0)}</div>`;
      const argQ = x.questions[next.choice + '_input'], arg = x.answers[next.choice + '_input'];
      if (argQ && arg) body += `<div class="reasoning"><span class="who">which artifact ${esc(next.choice)} runs on, of ${Object.keys(argQ.options).length}</span>${bars(arg.probabilities, new Set([arg.choice]), k => refWords(argQ.options[k] || k), 8)}</div>`;
    }
    const selections = Object.keys(x.answers).filter(k => k.startsWith('select_'));
    if (selections.length && (!next || next.choice === 'finish')) {
      const probs = Object.fromEntries(selections.map(k => [k, x.answers[k].probability]));
      const refFor = k => refOf(x.questions[k] ? x.questions[k].instructions : '') || k;
      const label = k => { const ref = refFor(k); const line = (x.state.artifacts || []).find(a => a.startsWith(ref)); return line ? refWords(line) : ref; };
      const picked = new Set(run.outcome ? run.outcome.answer.map(a => a.ref) : []);
      const chosen = new Set(selections.filter(k => picked.has(refFor(k))));
      body += `<div class="reasoning"><span class="who">which statements belong in the answer, of ${selections.length} asked</span>${bars(probs, chosen, label, 12)}</div>`;
    }
    const key = 's:' + d.id;
    body += `<details class="decision-state" data-key="${esc(key)}"${run.open.has(key) ? ' open' : ''}><summary>what the model saw: ${(x.state.artifacts || []).length} artifact${(x.state.artifacts || []).length === 1 ? '' : 's'}, ${(x.state.done || []).length} move${(x.state.done || []).length === 1 ? '' : 's'} made</summary>
      <pre>${esc(x.state.objective)}\n\nartifacts:\n${(x.state.artifacts || []).map(a => '  ' + a).join('\n')}\n\ndone:\n${(x.state.done || []).map(a => '  ' + a).join('\n')}</pre></details>`;
  }
  const cards = t ? `<div class="cards">${decideCard(run, t, now)}</div>` : '';
  return `<div class="turn ${tone}">${head}${body}${cards}</div>`;
}
function decideBoard(run, now) {
  const r = run.root, m = run.measured, o = run.outcome;
  const state = run.error ? 'failed' : o ? 'done' : !r ? 'submitting' : r.status === 'queued' ? 'waiting for admission' : r.status === 'running' ? (r.message || 'running') : r.status;
  const tone = run.error ? 'b-bad' : o ? 'b-ok' : 'b-run';
  const elapsed = ((run.ended || now) - run.started) / 1000;
  const head = `<div class="facts"><span class="badge ${tone}">${esc(state)}</span><div><b>${elapsed.toFixed(1)} s</b> elapsed</div>
    ${m ? `<div><b>${int(m.calls)}</b> decision${m.calls === 1 ? '' : 's'}</div><div><b>${int(m.inputTokens)}</b> tokens read</div><div><b>${m.cost === null || m.cost === undefined ? '–' : freeOr(m.cost, m.currency)}</b>${o ? '' : ' so far'}</div>${m.model || m.servedModel ? `<div>decided by <b title="${esc(m.servedModel ? 'served as ' + m.servedModel : '')}">${esc(m.model || m.servedModel)}</b></div>` : ''}` : ''}</div>`;
  const turns = new Map();
  for (const k of run.children) {
    const key = k.iteration === null || k.iteration === undefined ? 'none' : k.iteration;
    const t = turns.get(key) || {decision: null, tool: null};
    if (k.decision) t.decision = k; else t.tool = k;
    turns.set(key, t);
  }
  let number = 0;
  const rail = [...turns.entries()].sort(([a], [b]) => (a === 'none' ? 0 : a) - (b === 'none' ? 0 : b)).map(([, t]) => decideTurn(run, ++number, t, now)).join('');
  const answer = o ? `<div class="answer">${o.answer.length ? `<b>${o.answer.length === 1 ? '1 statement decides' : o.answer.length + ' statements decide'} a price change:</b><ol>${o.answer.map(a => `<li>${esc(refWords(a.digest))}</li>`).join('')}</ol>` : 'No statement was selected.'}</div>` : '';
  // the run's totals are the head row's; the finish adds the turns
  const summary = o ? `<div class="facts"><div>Turns<b>${o.turns ? o.turns.length : number}</b></div></div>` : '';
  const failure = run.error ? `<div class="callout bad"><h3>The runtime refused or failed</h3>${esc(run.error)}</div>` : '';
  return head + (rail ? `<div class="rail">${rail}</div>` : '') + answer + summary + failure;
}
$('decide-form').addEventListener('submit', async event => {
  event.preventDefault();
  const form = $('decide-form'), out = $('decide-result'), button = form.querySelector('button');
  const f = Object.fromEntries(new FormData(form));
  button.disabled = true;
  const run = {jobs: new Map(), root: null, children: [], measured: null, outcome: null, error: null, started: Date.now(), ended: null, open: new Set()};
  const board = document.createElement('div');
  board.className = 'trace';
  out.innerHTML = '';
  out.appendChild(board);
  board.addEventListener('toggle', e => {
    const d = e.target;
    if (!d.dataset || !d.dataset.key) return;
    if (d.open) run.open.add(d.dataset.key); else run.open.delete(d.dataset.key);
  }, true);
  const paint = () => {
    const next = document.createElement('div');
    next.className = 'trace';
    next.innerHTML = decideBoard(run, Date.now());
    morph(board, next);
  };
  paint();
  const timer = setInterval(paint, 150);
  try {
    const response = await fetch('/decide', {method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify({directory: f.directory})});
    if (!response.ok) {
      const text = await response.text();
      let data = null;
      try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
      throw new Error((data && (data.message || data.error)) || text || ('HTTP ' + response.status));
    }
    await readLines(response, frame => {
      if (frame.type === 'workflow_complete') { run.outcome = frame; if (frame.measured) run.measured = frame.measured; }
      else if (frame.type === 'workflow_failed') throw new Error(frame.message);
      else { decideEvent(run, frame); paint(); }
    });
    if (!run.outcome) throw new Error('the run ended without an answer');
  } catch (e) {
    run.error = e.message;
  } finally {
    run.ended = Date.now();
    clearInterval(timer);
    paint();
    button.disabled = false;
  }
});
call('GET', '/decide').then(c => {
  $('decide-palette').querySelector('div').innerHTML = `<p class="muted" style="margin:6px 0">The objective, on every turn: <i>${esc(c.objective)}</i></p>
    <p class="muted" style="margin:6px 0">The palette:</p>
    <ul class="items">
      <li>the run starts from a <code>${esc(c.takes)}</code></li>
      <li>the model is offered a tool when an artifact of the type it takes is in the run and the pair has not run yet</li>
      <li>the model is offered <code>finish</code> once a <code>${esc(c.answers)}</code> exists</li>
    </ul>
    <div class="scroll"><table><tr><th>Tool</th><th>Takes</th><th>Produces</th><th>What it does</th></tr>
    ${c.tools.map(t => `<tr><td><code>${esc(t.name)}</code></td><td>${esc(t.takes)}</td><td>${esc(t.produces)}</td><td class="muted">${esc(t.description)}</td></tr>`).join('')}</table></div>`;
}).catch(e => { $('decide-palette').querySelector('div').textContent = e.message; });

// ---------- 9. prices, decided ----------
// the same request and the same rendering as step 6; only the endpoint differs
wire('decide-prices-form', 'decide-prices-result', f => call('POST', '/decide-prices', pricingBody(f)), pricingResult);

// ---------- theme switch ----------
// "system" clears the override so the prefers-color-scheme media query decides again.
const themeButtons = [...$('theme-switch').querySelectorAll('button')];
function applyTheme(mode) {
  if (mode === 'light' || mode === 'dark') {
    document.documentElement.dataset.theme = mode;
    localStorage.setItem('nucleo-theme', mode);
  } else {
    delete document.documentElement.dataset.theme;
    localStorage.removeItem('nucleo-theme');
  }
  themeButtons.forEach(b => b.classList.toggle('active', b.dataset.mode === (mode || 'system')));
}
themeButtons.forEach(b => b.addEventListener('click', () => applyTheme(b.dataset.mode)));
applyTheme(localStorage.getItem('nucleo-theme') || 'system');
