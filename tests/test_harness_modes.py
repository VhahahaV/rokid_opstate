import asyncio
import hashlib
import json
import unittest
from pathlib import Path
import httpx
from fastapi.testclient import TestClient
from test_mock_harness import harness, ROOT


class HarnessModesTests(unittest.TestCase):
    def request(self,contract=None):
        value=json.loads((ROOT/'tests/fixtures/sample_inference_request.json').read_text())
        value['current_image_sha256']=hashlib.sha256(b'image').hexdigest()
        if contract:
            for key in ('protocol_version','model_id','prompt_hash','preprocess_hash','parser_hash'): value[key]=contract[key]
        return value
    def send(self,client,value):
        return client.post('/v1/evaluate',data={'request':json.dumps(value)},files={'image':('current.jpg',b'image','image/jpeg')})
    def test_script_has_silence_service_duplicate_and_reactivation_raw_decisions(self):
        app=harness.create_app(harness.MockOptions(mode='scripted'))
        with TestClient(app) as client:
            actual=[]
            for index in range(4):
                value=self.request();value['request_id']=f'step-{index}'
                actual.append(self.send(client,value).json()['decision'])
            self.assertEqual(actual,['NO_SERVICE','SERVICE','SERVICE','SERVICE'])
    def test_contract_metadata_is_enforced_in_frozen_mode(self):
        app=harness.create_app(harness.MockOptions(strict_contract=True))
        with TestClient(app) as client:
            self.assertEqual(client.get('/v1/health').status_code,200)
            contract=client.get('/v1/contract').json()
            self.assertEqual(self.send(client,self.request()).status_code,409)
            self.assertEqual(self.send(client,self.request(contract)).status_code,200)
    def test_malformed_and_expired_modes_are_real_fault_responses(self):
        with TestClient(harness.create_app(harness.MockOptions(mode='malformed'))) as client:
            response=self.send(client,self.request())
            self.assertEqual(response.status_code,200)
            with self.assertRaises(ValueError): response.json()
        with TestClient(harness.create_app(harness.MockOptions(mode='expired'))) as client:
            value=self.send(client,self.request()).json()
            self.assertLess(value['expires_at_epoch_ms'],__import__('time').time()*1000)
    def test_concurrent_retries_share_one_decision(self):
        app=harness.create_app(harness.MockOptions(mode='delay',delay_ms=50))
        async def run():
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
                value=self.request()
                async def post():
                    return await client.post('/v1/evaluate',data={'request':json.dumps(value)},files={'image':('current.jpg',b'image','image/jpeg')})
                first,second=await asyncio.gather(post(),post())
                self.assertEqual(first.json(),second.json())
                self.assertEqual(app.state.counter,1)
        asyncio.run(run())

    def test_full_causal_history_can_exceed_the_framework_default_one_megabyte_part_limit(self):
        app=harness.create_app(harness.MockOptions(mode='always_no_service',strict_contract=True))
        with TestClient(app) as client:
            value=self.request(client.get('/v1/contract').json())
            value['observations']=[{'observation_id':f'obs-{index}','kind':'SYNTHETIC_CONTEXT',
                'occurred_at_epoch_ms':0,'available_at_epoch_ms':0,'content':'history '*700} for index in range(220)]
            self.assertGreater(len(json.dumps(value).encode()),1024*1024)
            response=self.send(client,value)
            self.assertEqual(response.status_code,200,response.text)
            self.assertIsNone(response.json()['service'])


if __name__=='__main__': unittest.main()
