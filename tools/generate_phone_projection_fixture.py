#!/usr/bin/env python3
"""Freeze public Python request digests for Android projection parity; contains no evaluator labels."""
import argparse
import hashlib
import json
from pathlib import Path
from replay_validation_bundle import PublicReplayProjection

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('public',type=Path)
p.add_argument('--contract-report',type=Path,default=ROOT/'reports/latest/dataset-replay/report.json')
p.add_argument('--output',type=Path,default=ROOT/'code/phone-app/src/androidTest/assets/projection-parity.json')
a=p.parse_args()
projection=PublicReplayProjection(a.public)
contract=json.loads(a.contract_report.read_text())['contract']
indices=[i*(len(projection.anchors)-1)//19 for i in range(20)]
cases=[]
for index in indices:
    request=projection.request(projection.anchors[index],contract,'projection-parity')
    canonical=json.dumps(request,ensure_ascii=False,sort_keys=True,separators=(',',':'))
    cases.append({'source_index':index,'sha256':hashlib.sha256(canonical.encode()).hexdigest(),'history_count':len(request['observations'])})
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps({'contract':contract,'run_id':'projection-parity','cases':cases},indent=2)+'\n')
print(f'Wrote {len(cases)} public request digests: {a.output}')
