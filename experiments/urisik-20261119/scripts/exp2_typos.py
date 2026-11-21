"""실험 2: 한글 오타 질의 자동 생성.

- DB 레시피 제목에서 시드 고정으로 N개 샘플링
- 변형 유형 6종을 자모 단위로 적용
- 오타는 제목 전체가 아니라 음식명(generate-seed.js 기본 음식명)에 적용
- 원문과 오타의 자모 Damerau(OSA) 거리를 실제로 계산해 1/2/3 구간에 배정 (ES fuzziness와 같은 정의)
  (거리 = JamoUtils.decompose 결과 문자열 간 편집 거리, ES titleJamo 필드와 같은 표현)

사용: python exp2_typos.py --seed 42 --n 200 --out ../raw/exp2/typo-queries-seed42.json
"""
import argparse
import json
import random
import re
import subprocess

CHO = list('ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ')
JUNG = list('ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ')
JONG = [''] + list('ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ')

# 사람이 자주 내는 혼동 쌍
VOWEL_SWAP = {'ㅐ': 'ㅔ', 'ㅔ': 'ㅐ', 'ㅓ': 'ㅗ', 'ㅗ': 'ㅓ', 'ㅕ': 'ㅛ', 'ㅛ': 'ㅕ', 'ㅜ': 'ㅡ', 'ㅡ': 'ㅜ',
              'ㅚ': 'ㅙ', 'ㅙ': 'ㅞ', 'ㅞ': 'ㅚ', 'ㅣ': 'ㅟ', 'ㅟ': 'ㅣ', 'ㅏ': 'ㅑ', 'ㅑ': 'ㅏ', 'ㅒ': 'ㅖ',
              'ㅖ': 'ㅒ', 'ㅘ': 'ㅏ', 'ㅝ': 'ㅓ', 'ㅠ': 'ㅜ', 'ㅢ': 'ㅣ'}
DOUBLE = {'ㄱ': 'ㄲ', 'ㄷ': 'ㄸ', 'ㅂ': 'ㅃ', 'ㅅ': 'ㅆ', 'ㅈ': 'ㅉ'}
UNDOUBLE = {v: k for k, v in DOUBLE.items()}
CHO_SWAP = {'ㄱ': 'ㅋ', 'ㅋ': 'ㄱ', 'ㄷ': 'ㅌ', 'ㅌ': 'ㄷ', 'ㅂ': 'ㅍ', 'ㅍ': 'ㅂ', 'ㅈ': 'ㅊ', 'ㅊ': 'ㅈ',
            'ㄴ': 'ㄹ', 'ㄹ': 'ㄴ', 'ㅇ': 'ㅎ', 'ㅎ': 'ㅇ', 'ㅅ': 'ㅈ', 'ㅁ': 'ㅂ'}
ADD_JONG = ['ㄴ', 'ㅇ', 'ㄱ', 'ㄹ', 'ㅁ']

OPS = ['vowel_swap', 'double_drop', 'double_add', 'cho_swap', 'jong_toggle', 'syllable_drop']



def is_hangul(ch):
    return 0xAC00 <= ord(ch) <= 0xD7A3


def split(ch):
    c = ord(ch) - 0xAC00
    return CHO[c // 588], JUNG[(c % 588) // 28], JONG[c % 28]


def join(cho, jung, jong):
    return chr(0xAC00 + CHO.index(cho) * 588 + JUNG.index(jung) * 28 + JONG.index(jong))


def decompose(text):
    out = []
    for ch in text:
        if is_hangul(ch):
            a, b, c = split(ch)
            out.append(a + b + c)
        else:
            out.append(ch)
    return ''.join(out)


def lev(a, b):
    """Damerau(OSA) 편집 거리. ES fuzziness 기본값(transpositions=true)과 같게 인접 교환을 1로 센다."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            cost = a[i - 1] != b[j - 1]
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[len(a)][len(b)]


def apply_op(text, op, rng):
    """op을 한 번 적용. 적용 불가면 None."""
    chars = list(text)
    idxs = [i for i, ch in enumerate(chars) if is_hangul(ch)]
    rng.shuffle(idxs)
    for i in idxs:
        cho, jung, jong = split(chars[i])
        if op == 'vowel_swap' and jung in VOWEL_SWAP:
            chars[i] = join(cho, VOWEL_SWAP[jung], jong)
        elif op == 'double_drop' and cho in UNDOUBLE:
            chars[i] = join(UNDOUBLE[cho], jung, jong)
        elif op == 'double_add' and cho in DOUBLE:
            chars[i] = join(DOUBLE[cho], jung, jong)
        elif op == 'cho_swap' and cho in CHO_SWAP:
            chars[i] = join(CHO_SWAP[cho], jung, jong)
        elif op == 'jong_toggle':
            chars[i] = join(cho, jung, '' if jong else rng.choice(ADD_JONG))
        elif op == 'syllable_drop' and len(idxs) >= 3:
            del chars[i]
        else:
            continue
        return ''.join(chars)
    return None


def make_typo(title, target, rng, tries=200):
    """자모 거리가 정확히 target인 오타를 만든다. ops 목록도 반환."""
    base = decompose(title)
    for _ in range(tries):
        text, ops = title, []
        while True:
            op = rng.choice(OPS)
            nxt = apply_op(text, op, rng)
            if nxt is None:
                continue
            text, ops = nxt, ops + [op]
            d = lev(base, decompose(text))
            if d == target and text != title:
                return text, ops, d
            if d > target or len(ops) > target + 1:
                break
    return None, None, None


def dish_name(title, bases):
    """제목에 포함된 가장 긴 기본 음식명 (generate-seed.js의 titles 배열 기준)."""
    for b in bases:
        if b in title:
            return b
    return title


def load_bases(seed_js):
    src = open(seed_js, encoding='utf-8').read()
    body = re.search(r"const titles = \[(.*?)\];", src, re.S).group(1)
    return sorted(set(re.findall(r"'([^']*)'", body)), key=len, reverse=True)


def fetch_titles():
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B urisik'],
                       input='select id, title from recipe order by id'.encode(), capture_output=True)
    rows = [line.split('\t') for line in r.stdout.decode('utf-8').splitlines() if line]
    return [(int(a), b) for a, b in rows]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--n', type=int, default=200)
    p.add_argument('--seed-js', default='../../../db/init/generate-seed.js')
    p.add_argument('--out', required=True)
    a = p.parse_args()
    rng = random.Random(a.seed)
    bases = load_bases(a.seed_js)
    titles = fetch_titles()
    # 같은 음식명이 여러 번 뽑히지 않도록 음식명 기준으로 중복 없이 a.n개 샘플링
    shuffled = titles[:]
    rng.shuffle(shuffled)
    sample, seen = [], set()
    for rid, title in shuffled:
        dish = dish_name(title, bases)
        if dish not in seen:
            seen.add(dish)
            sample.append((rid, title))
        if len(sample) == a.n:
            break
    queries = []
    for rid, title in sample:
        # 사용자는 "부산식 소불고기덮밥"이 아니라 "소불고기덥밥"처럼 음식명으로 검색하므로 오타는 음식명에 넣는다
        dish = dish_name(title, bases)
        for d in (1, 2, 3):
            typo, ops, dist = make_typo(dish, d, rng)
            if typo is None:
                continue
            queries.append({'recipeId': rid, 'title': title, 'dish': dish, 'typo': typo,
                            'jamoDistance': dist, 'ops': ops})
    json.dump({'seed': a.seed, 'n': a.n, 'baseTitleCount': len(bases), 'queries': queries},
              open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    by = {}
    for q in queries:
        by[q['jamoDistance']] = by.get(q['jamoDistance'], 0) + 1
    print('generated', len(queries), by)


if __name__ == '__main__':
    main()
