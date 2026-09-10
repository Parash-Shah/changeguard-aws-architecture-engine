import http from 'k6/http';
import { check } from 'k6';
export const options = { vus: 2, iterations: 20, thresholds: { http_req_failed: ['rate<0.01'] } };
const resources = {};
for (let i = 0; i < 5000; i++) resources['Volume' + i] = { Type: 'AWS::EC2::Volume', Properties: { Encrypted: true, VolumeType: 'gp3' } };
export default function () {
  const headers = { 'Content-Type': 'application/json' };
  if (__ENV.CHANGEGUARD_API_KEY) headers['X-API-Key'] = __ENV.CHANGEGUARD_API_KEY;
  const response = http.post((__ENV.CHANGEGUARD_URL || 'http://localhost:8080') + '/v1/reviews', JSON.stringify({ template: JSON.stringify({ Resources: resources }), format: 'CLOUDFORMATION' }), { headers, timeout: '120s' });
  check(response, { 'review passes': r => r.status === 200 && r.json('status') === 'PASS', '5000 resources assessed': r => r.status === 200 && r.json('resources') === 5000 });
}
