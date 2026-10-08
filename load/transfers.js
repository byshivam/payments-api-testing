// k6 load test: steady UPI-style traffic between 10 accounts.
//
//   k6 run -e BASE_URL=http://localhost:8000 load/transfers.js
//
// Pass/fail is decided by thresholds: under 1% errors, transfer p95 under
// 300 ms, and the total money across the 10 accounts must be the same after
// the test as before it (checked in teardown).
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const JSON_HEADERS = { 'Content-Type': 'application/json' };
const ACCOUNTS = 10;
const OPENING = '100000.00';

const conservationFailures = new Counter('money_conservation_failures');

export const options = {
  scenarios: {
    upi_transfers: {
      executor: 'constant-arrival-rate',
      rate: 40,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 20,
      maxVUs: 60,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:transfer}': ['p(95)<300'],
    checks: ['rate>0.99'],
    money_conservation_failures: ['count==0'],
  },
};

function toPaise(text) {
  const [r, p = '0'] = text.split('.');
  return parseInt(r, 10) * 100 + parseInt((p + '00').slice(0, 2), 10);
}

export function setup() {
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const res = http.post(`${BASE}/accounts`,
      JSON.stringify({ holder_name: `Load Customer ${i + 1}`, opening_balance: OPENING }),
      { headers: JSON_HEADERS, tags: { name: 'setup' } });
    check(res, { 'account opened': (r) => r.status === 201 });
    ids.push(res.json('account_id'));
  }
  return { ids, totalPaise: ACCOUNTS * toPaise(OPENING) };
}

export default function (data) {
  const from = data.ids[Math.floor(Math.random() * ACCOUNTS)];
  let to = from;
  while (to === from) {
    to = data.ids[Math.floor(Math.random() * ACCOUNTS)];
  }
  const amount = `${1 + Math.floor(Math.random() * 49)}.${String(Math.floor(Math.random() * 100)).padStart(2, '0')}`;
  const res = http.post(`${BASE}/transfers`,
    JSON.stringify({ from_account: from, to_account: to, amount }),
    {
      headers: { ...JSON_HEADERS, 'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}` },
      tags: { name: 'transfer' },
    });
  check(res, {
    'transfer 201': (r) => r.status === 201,
    'amount echoed': (r) => r.status !== 201 || r.json('amount') === amount,
  });
}

export function teardown(data) {
  let total = 0;
  for (const id of data.ids) {
    const res = http.get(`${BASE}/accounts/${id}`, { tags: { name: 'teardown' } });
    total += toPaise(String(res.json('balance')));
  }
  const ok = total === data.totalPaise;
  if (!ok) {
    conservationFailures.add(1);
    console.error(`Money not conserved: expected ${data.totalPaise} paise, found ${total}`);
  }
  check(null, { 'money conserved across all accounts': () => ok });
}
