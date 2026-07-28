/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.db.console;

/** The console's single-page UI. Plain HTML and JS — no build step, no CDN, no dependency. */
final class Console {

    static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>ZeroZ DB console</title>
              <style>
                :root { color-scheme: light dark; }
                body { font: 14px/1.5 system-ui, sans-serif; margin: 0; }
                header { padding: .75rem 1rem; border-bottom: 1px solid #8884; display: flex;
                         gap: 1rem; align-items: baseline; }
                header h1 { font-size: 1rem; font-weight: 600; margin: 0; }
                nav button { font: inherit; padding: .3rem .7rem; margin-right: .25rem;
                             border: 1px solid #8884; background: transparent; cursor: pointer;
                             border-radius: 4px; }
                nav button.on { background: #8883; }
                main { padding: 1rem; }
                table { border-collapse: collapse; width: 100%; }
                th, td { text-align: left; padding: .3rem .6rem; border-bottom: 1px solid #8882;
                         vertical-align: top; }
                th { font-weight: 600; }
                code, .mono { font-family: ui-monospace, monospace; }
                a { color: inherit; cursor: pointer; text-decoration: underline dotted; }
                .crumb { margin-bottom: .75rem; }
                .tag { font-size: .8em; padding: .05rem .4rem; border: 1px solid #8884;
                       border-radius: 3px; margin-left: .4rem; }
                .bad { color: #b00; font-weight: 600; }
                .ok { color: #0a0; }
                select, input { font: inherit; padding: .25rem; }
              </style>
            </head>
            <body>
            <header>
              <h1>ZeroZ DB</h1>
              <select id="store"></select>
              <nav>
                <button data-tab="overview" class="on">Overview</button>
                <button data-tab="browse">Data</button>
                <button data-tab="queries">Queries</button>
                <button data-tab="schema">Schema</button>
              </nav>
            </header>
            <main id="view">loading…</main>
            <script>
            const view = document.getElementById('view');
            const storeSelect = document.getElementById('store');
            let tab = 'overview', path = '';

            const esc = s => String(s ?? '').replace(/[&<>]/g, c =>
                ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
            const get = async (url) => (await fetch(url)).json();

            document.querySelectorAll('nav button').forEach(b => b.onclick = () => {
              document.querySelectorAll('nav button').forEach(x => x.classList.remove('on'));
              b.classList.add('on');
              tab = b.dataset.tab; path = ''; render();
            });
            storeSelect.onchange = () => { path = ''; render(); };

            async function init() {
              const data = await get('api/overview');
              storeSelect.innerHTML = data.stores.map(s =>
                  `<option>${esc(s.name)}</option>`).join('');
              render();
            }

            async function render() {
              if (tab === 'overview') return renderOverview();
              if (tab === 'browse') return renderBrowse();
              if (tab === 'queries') return renderQueries();
              if (tab === 'schema') return renderSchema();
            }

            async function renderOverview() {
              const d = await get('api/overview');
              view.innerHTML = `<table>
                <tr><th>uptime</th><td>${esc(d.uptime)}</td></tr>
                <tr><th>pid</th><td>${esc(d.pid)}</td></tr>
                <tr><th>heap</th><td>${esc(d.heapUsedMb)} / ${esc(d.heapMaxMb)} MB</td></tr>
                </table>
                <h3>Stores</h3>
                <table><tr><th>store</th><th>commits</th><th>root type</th></tr>
                ${d.stores.map(s => `<tr><td>${esc(s.name)}</td><td>${esc(s.commitSequence)}</td>
                  <td class="mono">${esc(s.rootType)}</td></tr>`).join('')}</table>`;
            }

            async function renderBrowse() {
              const d = await get(`api/browse?store=${encodeURIComponent(storeSelect.value)}`
                  + `&path=${encodeURIComponent(path)}`);
              const crumbs = ['<a onclick="go(\\'\\')">root</a>'];
              let acc = '';
              (path ? path.split('/') : []).forEach(step => {
                acc = acc ? acc + '/' + step : step;
                crumbs.push(`<a onclick="go('${esc(acc)}')">${esc(step)}</a>`);
              });
              let rows = '';
              if (d.kind === 'object') {
                rows = d.fields.map(f => row(f.name, f)).join('');
              } else if (d.kind === 'map') {
                rows = d.entries.map(e => row(e.key, e)).join('');
              } else if (d.kind === 'collection' || d.kind === 'array') {
                rows = d.elements.map(e => row('[' + e.index + ']', e)).join('');
              } else {
                rows = `<tr><td colspan="3" class="mono">${esc(d.value)}</td></tr>`;
              }
              view.innerHTML = `<div class="crumb">${crumbs.join(' / ')}
                  <span class="tag">${esc(d.kind)}</span>
                  ${d.size !== undefined ? `<span class="tag">${esc(d.size)} entries</span>` : ''}
                  <span class="tag mono">${esc(d.type ?? '')}</span></div>
                <table><tr><th>name</th><th>value</th><th>type</th></tr>${rows}</table>`;
            }

            function row(label, node) {
              const value = node.kind === 'value'
                  ? `<span class="mono">${esc(node.value)}</span>`
                  : `<a onclick="go('${esc(node.path)}')">${esc(node.kind)}`
                    + `${node.size !== undefined ? ' (' + esc(node.size) + ')' : ''}</a>`;
              return `<tr><td>${esc(label)}</td><td>${value}</td>
                  <td class="mono">${esc(node.type ?? '')}</td></tr>`;
            }

            function go(p) { path = p; renderBrowse(); }
            window.go = go;

            async function renderQueries() {
              const d = await get(`api/queries?store=${encodeURIComponent(storeSelect.value)}`);
              if (!d.queries.length) {
                view.innerHTML = '<p>This store publishes no named queries. '
                    + 'Register a QueryCatalog to make queries available here.</p>';
                return;
              }
              view.innerHTML = d.queries.map(q => `<form onsubmit="runQuery(event,'${esc(q.name)}')">
                  <h3>${esc(q.name)}</h3><p>${esc(q.description)}</p>
                  ${q.parameters.map(p =>
                      `<label>${esc(p)} <input name="${esc(p)}"></label> `).join('')}
                  <button>run</button></form>`).join('<hr>')
                  + '<pre id="qresult"></pre>';
            }

            async function runQuery(event, name) {
              event.preventDefault();
              const params = new URLSearchParams(new FormData(event.target));
              params.set('store', storeSelect.value); params.set('query', name);
              const d = await get('api/run?' + params.toString());
              document.getElementById('qresult').textContent = JSON.stringify(d, null, 2);
            }
            window.runQuery = runQuery;

            async function renderSchema() {
              const d = await get('api/schema');
              if (!d.classes) { view.innerHTML = `<p>${esc(d.note)}</p>`; return; }
              const changes = d.changes ? `<h3>Against baseline
                  <span class="${d.rollbackCompatible ? 'ok' : 'bad'}">
                  ${d.rollbackCompatible ? 'compatible' : 'INCOMPATIBLE'}</span></h3>
                  <table><tr><th>severity</th><th>class</th><th>detail</th></tr>
                  ${d.changes.map(c => `<tr><td class="${c.severity === 'SAFE' ? 'ok' : 'bad'}">
                    ${esc(c.severity)}</td><td class="mono">${esc(c.class)}</td>
                    <td>${esc(c.detail)}</td></tr>`).join('')}</table>` : '';
              view.innerHTML = changes + '<h3>Model</h3>' + d.classes.map(c =>
                  `<h4 class="mono">${esc(c.name)}</h4><table>
                   ${c.fields.map(f => `<tr><td>${esc(f.name)}</td>
                     <td class="mono">${esc(f.type)}</td></tr>`).join('')}</table>`).join('');
            }

            init();
            </script>
            </body>
            </html>
            """;

    private Console() {
    }
}
