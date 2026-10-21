"""Actual MCP/Java HTTP checks. Read-only business calls; only synthetic chat rows are appended."""
import json
import uuid
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = 'http://127.0.0.1:18180'
MCP = 'http://127.0.0.1:18181'

def call(base, path, body=None, token=None, headers=None):
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type':'application/json', **({'Authorization':'Bearer '+token} if token else {}), **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def main():
    checks=[]
    _,login=call(JAVA,'/api/auth/login',{'username':'viewer','password':'viewer123'})
    token=login['data']['token']
    identity=0
    def rpc(method, params=None):
        nonlocal identity
        identity+=1
        status,body=call(MCP,'/mcp',{'jsonrpc':'2.0','id':identity,'method':method,'params':params or {}},token,
                         {'MCP-Protocol-Version':'2025-11-25','Accept':'application/json, text/event-stream'})
        assert status==200,(method,status)
        return body
    assert rpc('initialize',{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'portfolio-smoke','version':'1'}})['result']['protocolVersion']=='2025-11-25'
    tools=rpc('tools/list')['result']['tools']
    assert len(tools)==5 and all(row['annotations']['readOnlyHint'] for row in tools)
    checks.append('MCP initialization and five read-only tool discovery')
    stock=rpc('tools/call',{'name':'query-stock-level','arguments':{'skus':['AUD-001']}})['result']
    assert json.loads(stock['content'][0]['text'])['items'][0]['availableQty']==18
    replenish=rpc('tools/call',{'name':'suggest-replenish','arguments':{'skus':['AUD-001'],'days':7,'targetCoverDays':14}})['result']
    item=json.loads(replenish['content'][0]['text'])['items'][0]
    assert (item['targetQty'],item['rawGap'],item['suggestedQty'])==(90,72,72)
    checks.append('MCP stock18 and Java rule target90 gap72 package72')
    form=rpc('tools/call',{'name':'read-form-record','arguments':{'recordId':'R-1001'}})['result']
    assert json.loads(form['content'][0]['text'])['record_id']=='R-1001'
    resource=rpc('resources/read',{'uri':'supply://stock/AUD-001'})['result']
    assert resource['contents'][0]['mimeType']=='application/json'
    checks.append('actual fixed form GET and resource read')
    assert rpc('tools/call',{'name':'create-purchase-order','arguments':{}})['error']['code']==-32602
    assert rpc('tools/call',{'name':'query-sales','arguments':{'sku':'AUD-001','days':True}})['error']['code']==-32602
    assert call(MCP,'/mcp',{})[0]==401
    checks.append('write tool, invalid schema and missing credential rejected')
    request_id=str(uuid.uuid4())
    status,chat=call(JAVA,'/api/chat',{'message':'AUD-001 补货'},token,{'X-Request-Id':request_id})
    assert status==200 and chat['data']['request_id']==request_id
    status,body=call(JAVA,'/api/agent/runs/'+request_id,token=token)
    assert status==200,(status,body)
    trace=body['data']
    assert trace['finished'] and trace['status']=='ok'
    assert [row['kind'] for row in trace['context_layers']]==['system','history','reference','user']
    assert any(row.get('schema_validation')=='passed' for row in trace['events'])
    assert token not in json.dumps(trace) and 'AUD-001' not in json.dumps(trace)
    checks.append('authenticated Java live trace, context layers and schema verdict')
    _,admin=call(JAVA,'/api/auth/login',{'username':'admin','password':'admin123'})
    assert call(JAVA,'/api/agent/runs/'+request_id,token=admin['data']['token'])[0]==404
    assert call(JAVA,'/api/agent/runs/'+request_id)[0]==401
    checks.append('cross-credential and anonymous trace isolation')
    report={'checks':checks,'passed':len(checks),'actual_java_http':True,'actual_mcp_http':True,'business_writes':False,'trace':trace}
    output=ROOT/'artifacts/agent-upgrade-smoke.json'; output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'passed':len(checks),'actual_mcp_http':True,'business_writes':False}))

if __name__=='__main__': main()
