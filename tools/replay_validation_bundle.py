#!/usr/bin/env python3
"""Replay public R6 inputs through an HTTP mock; never report this as model accuracy or live capture."""
from __future__ import annotations
import argparse
import bisect
import hashlib
import json
import statistics
import time
import uuid
from datetime import datetime
from pathlib import Path

import httpx
from jsonschema import Draft202012Validator

ROOT=Path(__file__).resolve().parents[1]


def epoch(value: str) -> int:
    return int(datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()*1000)


class PublicReplayProjection:
    def __init__(self,public: Path):
        self.public=public
        self.catalog=json.loads((public/'catalog.json').read_text())
        self.anchors=[json.loads(line) for line in (public/'anchors.jsonl').read_text().splitlines()]
        self.observations=[]; self.times=[]
        revisions={}; last_text={}
        for line in (public/'observations.jsonl').read_text().splitlines():
            row=json.loads(line);payload=row['payload'];available=epoch(row['available_at'])
            handle=payload.get('task_episode_handle')
            revision=None
            if handle is not None:
                key=(handle,payload.get('surface'))
                if last_text.get(key)!=payload.get('text'):
                    revisions[handle]=revisions.get(handle,0)+1;last_text[key]=payload.get('text')
                revision=revisions[handle]
            visual=row['modality']=='visual'
            asset=payload.get('current_asset')
            occurred=epoch(asset['timestamp']) if asset else available
            if occurred>available: raise ValueError('observation occurs after its availability')
            content=(f"Recorded visual observation: {asset['image_id']}. Historical pixels are not attached." if visual else payload['text'])
            self.observations.append({'observation_id':row['observation_id'],'kind':'IMAGE' if visual else 'SYNTHETIC_CONTEXT',
                'occurred_at_epoch_ms':occurred,'available_at_epoch_ms':available,'content':content,
                'asset_id':asset['image_id'] if asset else None,'episode_handle':handle,'material_revision':revision})
            self.times.append(available)
        if self.times!=sorted(self.times): raise ValueError('non-causal replay ordering')

    def request(self,anchor: dict,contract: dict,run_id: str):
        at=epoch(anchor['available_at'])
        visible=[({**row,'content':'Current egocentric image attached as the multipart image.'}
            if row['asset_id']==anchor['current_asset']['image_id'] else row)
            for row in self.observations[:bisect.bisect_right(self.times,at)]]
        handle=anchor['task_episode_handle']
        # The source query is public input, not a Gold-derived scheduler feature.
        query={'observation_id':'query-'+anchor['anchor_id'],'kind':'SYNTHETIC_CONTEXT',
            'occurred_at_epoch_ms':at,'available_at_epoch_ms':at,'content':anchor['query'],
            'asset_id':None,'episode_handle':handle,'material_revision':None}
        request={'protocol_version':contract['protocol_version'],'request_id':f'{run_id}-{anchor["anchor_index"]}',
            'anchor_id':anchor['anchor_id'],'anchor_time_epoch_ms':at,
            'current_asset_id':anchor['current_asset']['image_id'],'current_image_sha256':anchor['current_asset']['sha256'],
            'candidate':{'object_handle':'obj_'+hashlib.sha256(handle.encode()).hexdigest()[:16],'episode_handle':handle},
            'observations':[*visible,query],'receipts':[]}
        for name in ('model_id','prompt_hash','preprocess_hash','parser_hash'): request[name]=contract[name]
        return request


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dataset',type=Path)
    parser.add_argument('--endpoint',default='http://127.0.0.1:8765')
    parser.add_argument('--limit',type=int)
    parser.add_argument('--output',type=Path,default=ROOT/'reports/latest/dataset-replay')
    args=parser.parse_args()
    projection=PublicReplayProjection(args.dataset/'public')
    release=next((args.dataset/'source').rglob('RELEASE_MANIFEST.json')).parent
    expected={row['anchor_id']:row['decision_gold'] for row in map(json.loads,(release/'evaluator/service_gold.jsonl').read_text().splitlines())}
    args.output.mkdir(parents=True,exist_ok=True)
    run_id='replay-'+str(uuid.uuid4())
    failures=[]; latencies=[]; counts={'SERVICE':0,'NO_SERVICE':0}
    response_schema=Draft202012Validator(json.loads((ROOT/'schemas/inference_response_v2.schema.json').read_text()))
    with httpx.Client(base_url=args.endpoint,timeout=30,trust_env=False) as client, (args.output/'cases.jsonl').open('w') as out:
        contract=client.get('/v1/contract').raise_for_status().json()
        if contract['mock_mode']!='dataset' or not contract['strict_contract']: raise ValueError('run a strict dataset mock for this fixture replay')
        anchors=projection.anchors[:args.limit] if args.limit else projection.anchors
        for index,anchor in enumerate(anchors):
            request=projection.request(anchor,contract,run_id)
            image=(projection.public/'images'/anchor['current_asset']['relative_path']).read_bytes()
            assert hashlib.sha256(image).hexdigest()==request['current_image_sha256']
            assert all(row['available_at_epoch_ms']<=request['anchor_time_epoch_ms'] for row in request['observations'])
            started=time.monotonic()
            record={'anchor_id':anchor['anchor_id'],'request_id':request['request_id'],'history_count':len(request['observations'])}
            try:
                def send():
                    return client.post('/v1/evaluate',data={'request':json.dumps(request,ensure_ascii=False,separators=(',',':'))},
                        files={'image':('current.jpg',image,'image/jpeg')},headers={'Idempotency-Key':request['request_id']})
                reply=send().raise_for_status().json()
                elapsed=(time.monotonic()-started)*1000
                response_schema.validate(reply)
                assert reply['request_id']==request['request_id']
                assert reply['decision']==expected[anchor['anchor_id']]
                assert set(reply['evidence_refs'])<={row['observation_id'] for row in request['observations']}
                assert reply['expires_at_epoch_ms']>reply['produced_at_epoch_ms']
                if index%25==0: assert send().raise_for_status().json()==reply
                counts[reply['decision']]+=1;latencies.append(elapsed)
                record.update(status='PASS',decision=reply['decision'],latency_ms=round(elapsed,3))
            except Exception as error:
                record.update(status='FAIL',error=str(error));failures.append(record)
            out.write(json.dumps(record,ensure_ascii=False)+'\n');out.flush()
            if (index+1)%20==0: print(f'Replayed {index+1}/{len(anchors)} anchors; failures={len(failures)}',flush=True)
    report={'status':'PASS' if not failures else 'FAIL','kind':'HTTP_MOCK_FIXTURE_REPLAY','run_id':run_id,
        'dataset_sha256':projection.catalog['source_archive_sha256'],'case_count':len(anchors),'decisions':counts,
        'causal_inputs_checked':True,'teacher_receipts_used':False,'model_accuracy_claimed':False,'live_camera_claimed':False,
        'median_ms':round(statistics.median(latencies),3) if latencies else None,
        'p95_ms':round(sorted(latencies)[min(len(latencies)-1,int(len(latencies)*.95))],3) if latencies else None,
        'failures':failures,'contract':contract}
    (args.output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({key:value for key,value in report.items() if key!='contract'},ensure_ascii=False,indent=2))
    raise SystemExit(1 if failures else 0)


if __name__=='__main__': main()
