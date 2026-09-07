import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function getTestToken(memberId = 1) {
    const res = http.post(`${BASE_URL}/api/auth/test-token?memberId=${memberId}`);
    const body = JSON.parse(res.body);
    return body.result.accessToken;
}

export function authHeaders(token) {
    return {
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
    };
}
