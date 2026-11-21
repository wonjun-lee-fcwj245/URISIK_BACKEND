"""실험 4 자원 수집기: 5초 간격으로 컨테이너·앱·DB·k6 자원을 CSV에 기록한다.

수집 항목
  - docker stats: 컨테이너별 CPU%, 메모리 (app, mysql, redis, elasticsearch, kafka)
  - actuator: hikaricp.connections.active / pending, tomcat.threads.busy / current,
              jvm.memory.used(heap), process.cpu.usage
  - MySQL: Threads_connected, Threads_running
  - k6 프로세스 CPU% (psutil, 논리 코어 100% 기준 합산값)
사용: python exp4_collect.py --out ../raw/exp4/<label>.resources.csv --stop-file <path>
"""
import argparse
import csv
import json
import os
import subprocess
import time
import urllib.request

import psutil

CONTAINERS = ['urisik-app', 'urisik-mysql', 'urisik-redis', 'urisik-elasticsearch', 'urisik-kafka', 'urisik-k6']
ACT = 'http://localhost:8080/actuator/metrics/'


def act(name, tag=None):
    url = ACT + name + (f'?tag={tag}' if tag else '')
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            d = json.load(r)
        return d['measurements'][0]['value']
    except Exception:
        return ''


def act_all(name, tag=None):
    """타이머/카운터의 통계(COUNT, TOTAL_TIME 등)를 모두 읽는다."""
    url = ACT + name + (f'?tag={tag}' if tag else '')
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            d = json.load(r)
        return {m['statistic']: m['value'] for m in d['measurements']}
    except Exception:
        return {}


def mysql_status():
    r = subprocess.run(['docker', 'exec', '-i', 'urisik-mysql', 'sh', '-c',
                        'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B'],
                       input="show global status where Variable_name in ('Threads_connected','Threads_running')",
                       capture_output=True, text=True)
    out = dict(line.split('\t') for line in r.stdout.strip().splitlines() if '\t' in line)
    return out.get('Threads_connected', ''), out.get('Threads_running', '')


def docker_stats():
    # 이름을 지정하지 않고 전체를 읽어 필터링한다(아직 없는 컨테이너가 있어도 실패하지 않게)
    r = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}'], capture_output=True, text=True)
    res = {}
    for line in r.stdout.strip().splitlines():
        try:
            d = json.loads(line)
        except Exception:
            continue
        if d['Name'] in CONTAINERS:
            res[d['Name']] = (d['CPUPerc'].rstrip('%'), d['MemUsage'].split(' / ')[0])
    return res


def k6_cpu(procs):
    total = 0.0
    for p in list(psutil.process_iter(['name'])):
        if (p.info['name'] or '').lower().startswith('k6'):
            if p.pid not in procs:
                procs[p.pid] = p
                p.cpu_percent(None)
                continue
            try:
                total += procs[p.pid].cpu_percent(None)
            except psutil.Error:
                pass
    return round(total, 1)


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--out', required=True)
    a.add_argument('--stop-file', required=True)
    a.add_argument('--interval', type=float, default=5)
    args = a.parse_args()
    cols = ['ts']
    for c in CONTAINERS:
        cols += [f'{c}_cpu', f'{c}_mem']
    cols += ['hikari_active', 'hikari_pending', 'tomcat_busy', 'tomcat_current', 'heap_used_mb', 'app_process_cpu',
             'mysql_threads_connected', 'mysql_threads_running', 'k6_cpu_pct', 'host_cpu_pct',
             'hikari_usage_count', 'hikari_usage_total_s', 'hikari_acquire_count', 'hikari_acquire_total_s', 'es_cb_not_permitted']
    procs = {}
    psutil.cpu_percent(None)
    with open(args.out, 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(cols)
        while not os.path.exists(args.stop_file):
            t0 = time.time()
            ds = docker_stats()
            row = [time.strftime('%Y-%m-%dT%H:%M:%S')]
            for c in CONTAINERS:
                row += list(ds.get(c, ('', '')))
            heap = act('jvm.memory.used', 'area:heap')
            row += [act('hikaricp.connections.active'), act('hikaricp.connections.pending'),
                    act('tomcat.threads.busy'), act('tomcat.threads.current'),
                    round(heap / 1048576, 1) if heap != '' else '', act('process.cpu.usage')]
            row += list(mysql_status())
            row += [k6_cpu(procs), psutil.cpu_percent(None)]
            us, aq = act_all('hikaricp.connections.usage'), act_all('hikaricp.connections.acquire')
            npc = act_all('resilience4j.circuitbreaker.not.permitted.calls', 'name:elasticsearchapi')
            row += [us.get('COUNT', ''), us.get('TOTAL_TIME', ''), aq.get('COUNT', ''), aq.get('TOTAL_TIME', ''), npc.get('COUNT', '')]
            w.writerow(row)
            f.flush()
            time.sleep(max(0, args.interval - (time.time() - t0)))


if __name__ == '__main__':
    main()
