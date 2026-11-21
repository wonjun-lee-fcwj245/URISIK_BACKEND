"""k6 요약(--summary-export) JSON에는 setup()이 반환한 데이터가 그대로 들어 있어, /api/auth/test-token으로 받은
Bearer 토큰(JWT)이 평문으로 남는다. 결과 파일을 저장소에 올리기 전에 토큰을 자리표시자로 바꾼다(JSON 형식은 유지).
  python scrub_tokens.py [경로...]      # 기본: 이 실험 폴더 전체(experiments/urisik-20261119)
러너(exp5_connhold.py, exp4b_runner.py)는 k6가 끝나면 scrub_file()을 자동 호출한다. 오래된 러너(exp1~exp4)의 결과는
커밋 전에 이 스크립트를 직접 실행한다.
"""
import os
import re
import sys

PATTERN = re.compile(rb'eyJ[A-Za-z0-9_\-]{8,}\.eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}')
PLACEHOLDER = b'<redacted-jwt>'
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))


def scrub_file(path):
    """파일 안의 JWT를 자리표시자로 바꾼다. 바꾼 개수를 돌려준다."""
    try:
        data = open(path, 'rb').read()
    except OSError:
        return 0
    new, n = PATTERN.subn(PLACEHOLDER, data)
    if n:
        open(path, 'wb').write(new)
    return n


def scrub_tree(root):
    files = total = 0
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ('.git', '__pycache__')]
        for name in names:
            n = scrub_file(os.path.join(base, name))
            if n:
                files += 1
                total += n
    return files, total


if __name__ == '__main__':
    targets = sys.argv[1:] or [ROOT]
    f = t = 0
    for target in targets:
        if os.path.isdir(target):
            a, b = scrub_tree(target)
        else:
            a, b = (1, scrub_file(target)) if scrub_file(target) else (0, 0)
        f += a
        t += b
    print(f'토큰을 지운 파일 {f}개, 토큰 {t}개')
