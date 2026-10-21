import json
import httpx
import pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.models.fake import FakeModelProvider
from app.tools.java_client import JavaToolClient

SCHEMA = json.loads((__import__('pathlib').Path(__file__).resolve().parents[2] / 'contracts/tools-schema.json').read_text(encoding='utf-8'))

@pytest.fixture
def mcp():
    calls = []
    def handler(request):
        calls.append(request)
        if request.headers.get('authorization') != 'Bearer viewer-demo':
            return httpx.Response(401, json={'code':401})
        if request.url.path.endswith('/schema'):
            return httpx.Response(200, json={'code':0,'data':SCHEMA})
        return httpx.Response(200, json={'code':0,'data':{'items':[{'sku':'AUD-001','availableQty':18,'snapDate':'2026-09-03'}], 'record_id':'R-1001'}})
    client = JavaToolClient('http://java.test', transport=httpx.MockTransport(handler))
    app = create_app(java_client=client, provider=FakeModelProvider())
    with TestClient(app) as browser:
        yield browser, calls


def rpc(client, method, params=None, **kwargs):
    return client.post('/mcp', json={'jsonrpc':'2.0','id':1,'method':method,'params':params or {}},
                       headers={'Authorization':'Bearer viewer-demo','MCP-Protocol-Version':'2025-11-25', **kwargs.pop('headers',{})}, **kwargs)


def test_initialize_notification_discovery_and_readonly_annotations(mcp):
    client, _ = mcp
    response = rpc(client,'initialize',{'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'test','version':'1'}})
    assert response.json()['result']['protocolVersion'] == '2025-11-25'
    notification = client.post('/mcp',json={'jsonrpc':'2.0','method':'notifications/initialized'},headers={'Authorization':'Bearer viewer-demo'})
    assert notification.status_code == 202 and notification.content == b''
    tools = rpc(client,'tools/list').json()['result']['tools']
    assert {tool['name'] for tool in tools} == {'query-stock-level','query-sales','suggest-replenish','read-form-record','read-form-run'}
    assert all(tool['annotations']['readOnlyHint'] and not tool['annotations']['destructiveHint'] for tool in tools)


@pytest.mark.parametrize('name,args', [('query-stock-level', {'skus':['AUD-001']}), ('query-sales',{'sku':'AUD-001','days':7}), ('suggest-replenish',{'skus':['AUD-001'],'days':7,'targetCoverDays':14}),('read-form-record',{'recordId':'R-1001'}),('read-form-run',{'runId':'run-012345abcdef'})])
def test_call_routes_to_java_with_callers_identity(mcp,name,args):
    client,calls=mcp
    result=rpc(client,'tools/call',{'name':name,'arguments':args}).json()['result']
    assert result['isError'] is False
    assert calls[-1].headers['authorization']=='Bearer viewer-demo'
    if name.startswith('read-form'): assert calls[-1].method=='GET'
    else: assert json.loads(calls[-1].content)==args


@pytest.mark.parametrize('name,args',[('create-purchase-order',{}),('read-form-record',{'recordId':'../../api/approve'}),('query-sales',{'sku':'AUD-001','days':True}),('query-stock-level',{'skus':['AUD-001'],'force':True}),('query-sales',{'sku':'AUD-001','category':'audio','days':7})])
def test_invalid_or_write_tools_have_no_business_endpoint_effect(mcp,name,args):
    client,calls=mcp
    response=rpc(client,'tools/call',{'name':name,'arguments':args})
    assert response.json()['error']['code']==-32602
    assert all(request.url.path.endswith('/schema') for request in calls)


def test_resources_templates_and_fixed_uri_read(mcp):
    client,_=mcp
    assert len(rpc(client,'resources/list').json()['result']['resources'])==2
    assert len(rpc(client,'resources/templates/list').json()['result']['resourceTemplates'])==3
    result=rpc(client,'resources/read',{'uri':'supply://stock/AUD-001'}).json()['result']
    assert result['contents'][0]['mimeType']=='application/json'
    assert json.loads(result['contents'][0]['text'])['items'][0]['availableQty']==18
    assert rpc(client,'resources/read',{'uri':'http://evil.invalid/'}).json()['error']['code']==-32602


def test_authentication_origin_version_and_parse_errors(mcp):
    client,_=mcp
    assert client.post('/mcp',json={}).status_code==401
    assert client.post('/mcp',json={},headers={'Authorization':'Bearer forged'}).status_code==401
    assert rpc(client,'tools/list',headers={'Origin':'https://evil.invalid'}).status_code==403
    assert rpc(client,'tools/list',headers={'MCP-Protocol-Version':'unknown'}).status_code==400
    assert rpc(client,'unknown').json()['error']['code']==-32601
    assert client.get('/mcp').status_code==405
