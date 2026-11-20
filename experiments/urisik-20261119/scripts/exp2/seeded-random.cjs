// generate-seed.js를 수정하지 않고 시드를 고정하기 위한 preload 스크립트
// 사용: SEED=42 node -r ./seeded-random.cjs db/init/generate-seed.js 5000 > out.sql
// Math.random을 mulberry32(SEED)로 교체한다
let a = parseInt(process.env.SEED || '42', 10) >>> 0;
Math.random = function () {
  a = (a + 0x6D2B79F5) >>> 0;
  let t = a;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
