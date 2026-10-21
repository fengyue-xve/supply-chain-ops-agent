import asyncio
import json
import time
from fastapi.testclient import TestClient
from app.agent.events import AgentEvent
from app.agent.live_trace import LiveTraceSink, trace_identity
from app.agent.run import RunContext


def test_http_trace_is_owned_and_contains_layers_not_prompt_or_credentials(app_env, payloads):
    app, _, _ = app_env(tool_payloads=payloads)
    with TestClient(app) as client:
        response = client.post('/agent/chat', json={'message':'AUD-001 库存多少？'}, headers={'Authorization':'Bearer owner-a', 'X-Request-Id':'trace-owned-1'})
        assert response.status_code == 200
        result = client.get('/agent/traces/trace-owned-1', headers={'Authorization':'Bearer owner-a'})
        assert result.status_code == 200
        trace = result.json()['data']
        assert trace['finished'] and trace['status']=='ok'
        assert [layer['kind'] for layer in trace['context_layers']]==['system','history','reference','user']
        assert any(event.get('schema_validation')=='passed' for event in trace['events'])
        serialized=json.dumps(trace,ensure_ascii=False)
        assert 'AUD-001' not in serialized and 'owner-a' not in serialized and '库存多少' not in serialized
        assert client.get('/agent/traces/trace-owned-1',headers={'Authorization':'Bearer owner-b'}).status_code==404
        assert client.get('/agent/traces/trace-owned-1').status_code==401


def test_live_trace_cancel_marks_the_actual_coroutine_cancelled():
    async def scenario():
        sink=LiveTraceSink()
        started=asyncio.Event()
        async def worker():
            now=time.monotonic()
            run=RunContext('cancel-id','conversation',now,now+25,25,5,'synthetic')
            with trace_identity('Bearer owner'):
                sink.begin(run); started.set()
                try: await asyncio.sleep(10)
                except asyncio.CancelledError:
                    run.stop_reason='cancelled'; raise
                finally: run.finish(); sink.end(run)
        task=asyncio.create_task(worker()); await started.wait()
        assert sink.cancel('Bearer other','cancel-id') is False
        assert sink.cancel('Bearer owner','cancel-id') is True
        try: await task
        except asyncio.CancelledError: pass
        assert sink.get('Bearer owner','cancel-id')['status']=='cancelled'
    asyncio.run(scenario())


def test_trace_limit_ttl_and_event_projection_do_not_leak_untrusted_fields():
    async def scenario():
        sink=LiveTraceSink(limit=2,ttl_seconds=900)
        with trace_identity('Bearer owner'):
            for number in range(3):
                now=time.monotonic()
                run=RunContext(str(number),'conversation',now,now+25,25,5,'synthetic')
                sink.begin(run)
                sink.emit(AgentEvent('tool_blocked',str(number),fields={'tool':'Bearer-secret','raw':'secret'},trace_fields={'reason':'schema_validation_failed'},run_key=run.run_key))
                run.stop_reason='answered'; run.finish(); sink.end(run)
            assert sink.get('Bearer owner','0') is None
            record=sink.get('Bearer owner','2')
            assert record['events'][0]['tool']=='unregistered'
            assert 'secret' not in json.dumps(record)
            duration=record['duration_ms']; await asyncio.sleep(.01)
            assert sink.get('Bearer owner','2')['duration_ms']==duration
            sink.ttl_seconds=0
            assert sink.get('Bearer owner','2') is None
    asyncio.run(scenario())
