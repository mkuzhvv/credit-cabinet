import test, { beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { build } from 'esbuild';
import React, { useState } from 'react';
import Renderer, { act } from 'react-test-renderer';

const storage = new Map([['alfa_api_mode', 'real']]);
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key),
};
const bundled = await build({
  stdin: {
    contents: "export { default as ApplicationPage } from './src/pages/ApplicationPage.jsx'; export { default as AssistantWidget } from './src/components/AssistantWidget.jsx'; export * from './src/api/client.js';",
    resolveDir: resolve('.'), loader: 'jsx',
  },
  bundle: true, platform: 'node', format: 'esm', packages: 'external', jsx: 'automatic', write: false,
});
const directory = resolve('node_modules/.cache/frontend-tests');
await mkdir(directory, { recursive: true });
await writeFile(resolve(directory, 'components.mjs'), bundled.outputFiles[0].contents);
const ui = await import(pathToFileURL(resolve(directory, 'components.mjs')));
const emptyForm = {
  amount: null, termMonths: null, averageMonthlyInflow: null, averageMonthlyOutflow: null,
  financingPurpose: null, projectBudget: null, trancheTermMonths: null, governmentContract: null, cashBalance: null,
};
const formKeys = Object.keys(emptyForm).sort();
let tree;
let calls;
let backend;
let intercept;
const originalFetch = globalThis.fetch;
const response = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const drain = async () => { await new Promise(done => setImmediate(done)); };

beforeEach(() => {
  calls = [];
  intercept = null;
  ui.setApiMode('real');
  globalThis.fetch = async (url, options = {}) => {
    const body = options.body ? JSON.parse(options.body) : null;
    calls.push({ url, ...options, body });
    const overridden = await intercept?.(url, options, body);
    if (overridden) return overridden;
    if (url.endsWith('/form')) {
      assert.equal(body.expectedVersion, backend.version);
      assert.deepEqual(Object.keys(body.form).sort(), formKeys);
      backend = { ...backend, form: body.form, version: backend.version + 1 };
      return response(backend);
    }
    if (url.includes('/applications/')) return response(backend);
    if (url.endsWith('/csrf')) return response({ token: 'csrf', headerName: 'X-CSRF-TOKEN' });
    if (url.endsWith('/demo/session') || url.endsWith('/me')) return response({ user: { id: 'user' } });
    throw new Error(`Unexpected request: ${url}`);
  };
});

afterEach(async () => {
  if (tree) await act(async () => { tree.unmount(); });
  tree = null;
  globalThis.fetch = originalFetch;
});

function Harness({ initial }) {
  const [application, setApplication] = useState(initial);
  return React.createElement(ui.ApplicationPage, { application, setApplication, apiMode: 'real' });
}

async function mount(code = 'WORKING_CAPITAL') {
  backend = {
    id: `application-${code}`, version: 0, status: 'DRAFT',
    product: structuredClone(ui.MOCK_PRODUCTS.find(product => product.code === code)),
    form: { ...emptyForm, amount: '500000.50', termMonths: code === 'LONG_TERM' ? 36 : code === 'REVOLVING_LINE' ? 24 : 12 },
    reportingPeriod: { months: 6, startDate: '2026-04-01', endDate: '2026-09-30' },
  };
  await act(async () => { tree = Renderer.create(React.createElement(Harness, { initial: structuredClone(backend) })); });
}

function input(label) {
  return tree.root.findAllByType('input').find(element => element.props.id === label || element.props['aria-label'] === label);
}

async function change(label, value) {
  assert.ok(input(label), `Missing input: ${label}`);
  await act(async () => { input(label).props.onChange({ target: { value } }); });
}

for (const code of ['WORKING_CAPITAL', 'OVERDRAFT', 'REVOLVING_LINE', 'LONG_TERM', 'GOVERNMENT_CONTRACT_LINE']) {
  test(`sends the current full form for ${code} without losing cents`, async () => {
    await mount(code);
    await change('Среднемесячные поступления', '2000000.25');
    await change('Среднемесячные расходы', '800000.10');
    await change('form.cashBalance', '1000000.50');
    if (code === 'LONG_TERM') {
      await change('form.financingPurpose', 'Покупка печи');
      await change('form.projectBudget', '600000.99');
    }
    if (code === 'REVOLVING_LINE') await change('form.trancheTermMonths', '3');
    if (code === 'GOVERNMENT_CONTRACT_LINE') {
      for (const [key, value] of Object.entries({ number: 'DEMO-44', contractAmount: '10000000.00', advanceAmount: '0', executionEndDate: '2026-10-26', finalPaymentDate: '2026-11-26' })) {
        await change(`form.governmentContract.${key}`, value);
      }
    }
    await act(async () => { input('form.cashBalance').props.onBlur(); await drain(); });
    assert.equal(backend.form.amount, '500000.50');
    assert.equal(backend.form.averageMonthlyInflow, '2000000.25');
    assert.equal(backend.form.cashBalance, '1000000.50');
    assert.equal(backend.form.financingPurpose, code === 'LONG_TERM' ? 'Покупка печи' : null);
    assert.equal(backend.form.trancheTermMonths, code === 'REVOLVING_LINE' ? 3 : null);
    assert.equal(backend.form.governmentContract?.lawCode ?? null, code === 'GOVERNMENT_CONTRACT_LINE' ? '44_FZ' : null);
  });
}

test('serializes overlapping saves and preserves edits made while waiting', async () => {
  await mount();
  const first = deferred();
  let count = 0;
  intercept = async url => { if (url.endsWith('/form') && ++count === 1) await first.promise; };
  await change('Среднемесячные поступления', '1200000.15');
  await act(async () => { input('Среднемесячные поступления').props.onBlur(); await drain(); });
  await change('Среднемесячные расходы', '700000.99');
  await act(async () => { input('Среднемесячные расходы').props.onBlur(); await drain(); });
  assert.equal(calls.filter(call => call.method === 'PUT').length, 1);
  await act(async () => { first.resolve(); await drain(); });
  assert.deepEqual(calls.filter(call => call.method === 'PUT').map(call => call.body.expectedVersion), [0, 1]);
  assert.equal(backend.form.averageMonthlyOutflow, '700000.99');
  assert.equal(input('Среднемесячные расходы').props.value, '700000.99');
});

test('reloads form values after a version conflict before the next write', async () => {
  await mount();
  let conflict = true;
  intercept = async url => {
    if (url.endsWith('/form') && conflict) {
      conflict = false;
      backend = { ...backend, version: 7, form: { ...backend.form, averageMonthlyOutflow: '777777.77', averageMonthlyInflow: '2000000.50' } };
      return response({ status: 409, code: 'VERSION_CONFLICT', title: 'Версия изменилась' }, 409);
    }
  };
  await change('Среднемесячные поступления', '1');
  await act(async () => { input('Среднемесячные поступления').props.onBlur(); await drain(); });
  assert.equal(input('Среднемесячные расходы').props.value, '777777.77');
  assert.equal(input('Среднемесячные поступления').props.value, '2000000.50');
  await change('form.cashBalance', '100.99');
  await act(async () => { input('form.cashBalance').props.onBlur(); await drain(); });
  assert.equal(calls.filter(call => call.method === 'PUT').at(-1).body.expectedVersion, 7);
  assert.equal(backend.form.averageMonthlyOutflow, '777777.77');
});

test('uses one idempotency key after an uncertain network failure', async () => {
  let attempt = 0;
  intercept = async url => {
    if (url.endsWith('/applications')) {
      if (++attempt === 1) throw new TypeError('Network interrupted');
      return response({ id: 'created' }, 201);
    }
  };
  await assert.rejects(ui.createApplication('WORKING_CAPITAL'));
  await ui.createApplication('WORKING_CAPITAL');
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
});

test('does not mix local products into the server catalog', async () => {
  intercept = async () => response({ items: [{ code: 'LONG_TERM', terms: { version: 'server' } }] });
  const result = await ui.fetchProducts();
  assert.deepEqual(result.items.map(product => product.code), ['LONG_TERM']);
});

test('does not create a conversation before the session is ready', async () => {
  await act(async () => { tree = Renderer.create(React.createElement(ui.AssistantWidget, { enabled: false, apiMode: 'real', userId: 'user' })); });
  assert.equal(calls.length, 0);
  const session = deferred();
  let csrfCount = 0;
  intercept = async url => {
    if (url.endsWith('/csrf')) { await session.promise; return response({ token: ++csrfCount === 1 ? 'first' : 'new-csrf', headerName: 'X-CSRF-TOKEN' }); }
    if (url.endsWith('/demo/session') || url.endsWith('/me')) return response({ user: { id: 'user' } });
    return url.endsWith('/assistant/conversations') ? response({ id: 'server-conversation' }, 201)
      : response({ items: [], hasMore: false, lastSequence: 0 });
  };
  await act(async () => { tree.update(React.createElement(ui.AssistantWidget, { enabled: true, apiMode: 'real', userId: 'user' })); await drain(); });
  assert.equal(calls.filter(call => call.url.endsWith('/assistant/conversations')).length, 0);
  await act(async () => { session.resolve(); await drain(); });
  assert.equal(storage.get('alfa_real_user_conversation_id'), 'server-conversation');
  const creation = calls.filter(call => call.url.endsWith('/assistant/conversations'));
  assert.equal(creation.length, 1);
  assert.equal(creation[0].headers['X-CSRF-TOKEN'], 'new-csrf');
});
