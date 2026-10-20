"""Run isolated regression suites; no live model or full-stack writes."""
import json
import subprocess
import sys
import time
from pathlib import Path
from demo import ROOT, environment, executable

def main():
    env = environment()
    out = ROOT / 'artifacts/verify'
    out.mkdir(parents=True, exist_ok=True)
    steps = [
        ('java', 'java-app', [executable('mvn'), '-B', 'test']),
        ('forms-java', 'form-workflow/java-flow-service', [executable('mvn'), '-B', 'test']),
        ('python', 'fastapi-service', [sys.executable, '-m', 'pytest', '-q']),
        ('web', 'web-app', [executable('npm'), 'test']),
        ('web-build', 'web-app', [executable('npm'), 'run', 'build']),
        ('pi-types', 'form-workflow/pi-agent-runtime', [executable('npm'), 'run', 'typecheck']),
        ('pi-tests', 'form-workflow/pi-agent-runtime', [executable('npm'), 'test']),
        ('current-business', 'fastapi-service', [sys.executable, '-m', 'evals.run', '--suite', 'current-business.v1']),
    ]
    results=[]
    for name, cwd, command in steps:
        start=time.monotonic()
        with (out / (name+'.log')).open('wb') as log:
            result=subprocess.run(command, cwd=ROOT/cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        row={'step':name,'exit_code':result.returncode,'seconds':round(time.monotonic()-start,2)}
        results.append(row)
        print(json.dumps(row),flush=True)
        if result.returncode:
            print((out/(name+'.log')).read_text(encoding='utf-8',errors='replace')[-5000:])
    (out/'summary.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    return int(any(x['exit_code'] for x in results))

if __name__=='__main__':
    raise SystemExit(main())
