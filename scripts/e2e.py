"""Real HTTP integration against the synthetic demo. Mutates DEM-001 and forms."""
import json
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from demo import ROOT, environment, executable

BASE='http://127.0.0.1:18180'
OUT=ROOT/'artifacts/e2e'
checks=[]

def check(name, condition):
    checks.append({'name':name,'ok':bool(condition)})
    print(('PASS ' if condition else 'FAIL ')+name,flush=True)
    if not condition:
        raise AssertionError(name)

def request(path, payload=None, token=None, key=None, status=200):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+path, data=json.dumps(payload).encode() if payload is not None else None,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=45) as res:
            code,raw=res.status,res.read()
    except urllib.error.HTTPError as exc:
        code,raw=exc.code,exc.read()
    body=json.loads(raw)
    check(path+' HTTP '+str(status),code==status)
    return body

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    admin=request('/api/auth/login',{'username':'admin','password':'admin123'})['data']['token']
    viewer=request('/api/auth/login',{'username':'viewer','password':'viewer123'})['data']['token']
    tool='/api/agent/tools/'
    payload={'skus':['DEM-001'],'days':7,'targetCoverDays':14}
    row=request(tool+'suggest-replenish',payload,admin)['data']['items'][0]
    check('DEM-001 48 pieces with dated basis',row['suggestedQty']==48 and row['currentQty']==5 and row['stockSnapDate']=='2026-09-03')
    aud=request(tool+'suggest-replenish',{'skus':['AUD-001'],'days':7,'targetCoverDays':14},admin)['data']['items'][0]
    check('AUD-001 suggestion72',aud['suggestedQty']==72)
    conflict=request(tool+'create-purchase-order',{'items':[{'sku':'AUD-001','qty':72}]},admin,str(uuid.uuid4()),409)
    check('seed pending96 retained',any(x['qty']==96 for x in conflict['data']['existingOrders']))
    draft={'items':[{'sku':'DEM-001','qty':48}]}
    key=str(uuid.uuid4())
    first=request(tool+'create-purchase-order',draft,admin,key)['data']
    po=first['items'][0]['poNo']
    replay=request(tool+'create-purchase-order',draft,admin,key)['data']
    check('same key replay same PO',replay['duplicated'] and replay['items'][0]['poNo']==po)
    conflict=request(tool+'create-purchase-order',draft,admin,str(uuid.uuid4()),409)
    check('different key blocked while pending',any(x['poNo']==po for x in conflict['data']['existingOrders']))
    request(tool+'create-purchase-order',draft,viewer,str(uuid.uuid4()),403)
    request(tool+'confirm-po',{'poNo':po},viewer,status=403)
    request(tool+'confirm-po',{'poNo':po},admin)
    request(tool+'confirm-po',{'poNo':po},admin,status=409)
    stock=request(tool+'query-stock-level',{'skus':['DEM-001']},admin)['data']['items'][0]
    check('purchase confirmation never increments stock',stock['availableQty']==5)
    # Avoid sales keyword precedence in the explicitly scripted provider.
    chat=request('/api/chat',{'message':'DEM-001 补货'},admin)['data']
    check('offline assistant reaches business facts', '48' in json.dumps(chat,ensure_ascii=False))
    request('/api/forms/records/R-1001',status=401)
    request('/api/forms/records/R-1001',token=viewer)
    request('/api/forms/records/R-9999',token=viewer,status=404)
    cwd=ROOT/'form-workflow/pi-agent-runtime'
    command=[executable('node'),'node_modules/tsx/dist/cli.mjs','--tsconfig','tsconfig.json','src/cli.ts','--java','http://127.0.0.1:18183','--traces',str(OUT/'pi')]
    with (OUT/'pi.log').open('wb') as log:
        result=subprocess.run(command,cwd=cwd,env=environment(),stdout=log,stderr=subprocess.STDOUT)
    check('Pi complete cross-process scenarios',result.returncode==0)
    summary=json.loads((OUT/'pi/demo-summary.json').read_text(encoding='utf-8'))
    run_id=summary['scenarios'][0]['run_ids'][0]
    run=request('/api/forms/runs/'+run_id,token=viewer)['data']
    check('actual Pi run visible through reader adapter',run['run_id']==run_id and run['write_count']>=1)
    (OUT/'summary.json').write_text(json.dumps({'checks':checks,'poNo':po,'runId':run_id,'provider':'demo + Pi Faux'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Real HTTP checks:',len(checks),'passed; Pi summary:',OUT/'pi/demo-summary.json')

if __name__=='__main__':
    main()
