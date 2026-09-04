import http from 'k6/http';
import { check, sleep } from 'k6';

const HOSTS = (__ENV.HOSTS || 'http://localhost:8086').split(',');
const EMAIL = __ENV.EMAIL || 'dispatcher@acme.com';
const PASSWORD = __ENV.PASSWORD || 'demo123';

export const options = {
  stages: [
    { duration: '30s', target: 25 },
    { duration: '2m', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const res = http.post(
    `${HOSTS[0]}/api/auth/login`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('token') };
}

export default function (data) {
  const base = HOSTS[__ITER % HOSTS.length];
  const params = { headers: { Authorization: `Bearer ${data.token}` } };

  const drivers = http.get(`${base}/api/drivers`, params);
  check(drivers, { 'drivers 200': (r) => r.status === 200 });

  http.get(`${base}/api/orders`, params);
  http.get(`${base}/api/alerts`, params);

  sleep(0.2);
}
