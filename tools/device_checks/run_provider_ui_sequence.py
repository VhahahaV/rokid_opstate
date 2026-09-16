import argparse,shutil,subprocess,json,time,hashlib,re,xml.etree.ElementTree as ET
from pathlib import Path
parser=argparse.ArgumentParser(description='Optional 20 real API requests through the prepared phone UI; incurs provider usage. Requires BVL-AN00 portrait layout.')
parser.add_argument('--phone',required=True);parser.add_argument('--adb',default=shutil.which('adb'))
parser.add_argument('--output',type=Path,default=Path('reports/latest/provider-ui-sequence'))
args=parser.parse_args()
if not args.adb:parser.error('adb must be on PATH or supplied with --adb')
ADB=args.adb;SERIAL=args.phone;OUT=args.output;OUT.mkdir(parents=True,exist_ok=True)
def adb(*args,check=True):
 r=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,timeout=25)
 if check and r.returncode:raise RuntimeError('ADB_COMMAND_FAILED')
 return r

def snapshot():
 adb('shell','uiautomator','dump','/sdcard/opstate-sequence-ui.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/opstate-sequence-ui.xml').stdout)
def node(root,text):
 return next((n for n in root.iter('node') if n.get('text')==text),None)
def tap(n):
 if n is None:raise RuntimeError('UI_TARGET_NOT_FOUND')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 if x2<=x1 or y2<=y1:raise RuntimeError('UI_TARGET_NOT_VISIBLE')
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def probe_file():
 r=adb('exec-out','run-as','ai.opstate.rokid.phone','cat','files/validation/model-api-probe.json',check=False)
 try:return json.loads(r.stdout)
 except (ValueError,TypeError):return []
def request_id(rows):
 for row in rows:
  if row.get('format')=='OPSTATE_V2_NORMALIZED':
   return json.loads(row['body'])['request_id']
 return None

result={'status':'RUNNING','scope':'PHONE_UI_REAL_PROVIDER_PROTOCOL_SEQUENCE','image_source':'GENERATED_API_KEY_PROBE','physical_camera':False,'completed':0}
start_pid=adb('shell','pidof','ai.opstate.rokid.phone').stdout.decode().strip();result['study_pid_before']=start_pid
seen={request_id(probe_file())};expected_model=None
try:
 root=snapshot()
 if node(root,'历史已就绪') is None:raise RuntimeError('STUDY_NOT_IN_HISTORY_PREPARATION')
 if any(n.get('focused')=='true' and n.get('class','').endswith('EditText') for n in root.iter('node')):raise RuntimeError('USER_EDIT_IN_PROGRESS')
 for _ in range(8):
  root=snapshot();target=node(root,'检查')
  if target is not None and target.get('enabled')=='true' and int(re.findall(r'\d+',target.get('bounds'))[3])<2400:break
  adb('shell','input','swipe','650','2200','650','1050','400')
 else:raise RuntimeError('MODEL_VALIDATION_BUTTON_NOT_FOUND')
 for index in range(20):
  if adb('shell','pidof','ai.opstate.rokid.phone').stdout.decode().strip()!=start_pid:raise RuntimeError('STUDY_PROCESS_CHANGED')
  target=None
  for locate_attempt in range(6):
   root=snapshot();target=node(root,'检查')
   if target is not None and target.get('enabled')=='true' and int(re.findall(r'\d+',target.get('bounds'))[3])<2350:break
   if node(root,'在线验证') is None:raise RuntimeError('USER_LEFT_OVERVIEW')
   adb('shell','input','swipe','650','2180','650','1700','250')
  else:raise RuntimeError('MODEL_CHECK_UNAVAILABLE')
  time.sleep(.25)
  tap(target)
  deadline=time.monotonic()+24; records=[]
  while time.monotonic()<deadline:
   records=probe_file();rid=request_id(records)
   if rid and rid not in seen:break
   time.sleep(.4)
  else:
   (OUT/f'{index+1:02}-failure.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
   raise RuntimeError('PROVIDER_VALIDATION_DID_NOT_COMPLETE')
  if len(records)!=2:raise RuntimeError('RAW_NORMALIZED_PAIR_REQUIRED')
  for row in records:
   if row['http_status']!=200 or hashlib.sha256(row['body'].encode()).hexdigest()!=row['sha256']:raise RuntimeError('RAW_HASH_OR_HTTP_FAILURE')
  provider=next(row for row in records if row['format'].startswith('PROVIDER_'))
  normalized=json.loads(next(row['body'] for row in records if row['format']=='OPSTATE_V2_NORMALIZED'))
  raw=json.loads(provider['body']);model=raw['model']
  if expected_model is None:expected_model=model
  if model!=expected_model:raise RuntimeError('MODEL_CHANGED_DURING_SEQUENCE')
  if provider['format']=='PROVIDER_CHAT_COMPLETIONS':
   if raw['choices'][0]['finish_reason']!='stop':raise RuntimeError('INCOMPLETE_PROVIDER_OUTPUT')
   actual=json.loads(raw['choices'][0]['message']['content'])
  else:
   if raw['status']!='completed':raise RuntimeError('INCOMPLETE_PROVIDER_OUTPUT')
   actual=json.loads(next(p['text'] for m in raw['output'] if m['type']=='message' for p in m['content'] if p['type']=='output_text'))
  if any(normalized[k]!=actual[k] for k in ('decision','service','evidence_refs')):raise RuntimeError('NORMALIZATION_CHANGED_DECISION')
  if not set(actual['evidence_refs'])<={'probe-context','probe-image'}:raise RuntimeError('INVALID_EVIDENCE')
  if actual['decision']=='NO_SERVICE' and actual['service'] is not None:raise RuntimeError('NO_SERVICE_WITH_HOW')
  if actual['decision']=='SERVICE' and (not 0<len(actual['service']['title'])<=40 or not 0<len(actual['service']['body'])<=140):raise RuntimeError('SERVICE_TOO_LONG')
  seen.add(rid)
  (OUT/f'{index+1:02}.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
  result.update(completed=index+1,model=model,status='RUNNING')
  (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
  print(f'Validated {index+1}/20',flush=True)
 result['status']='PASS'
except Exception as e:
 result.update(status='FAIL',failure_code=str(e))
 print('Sequence stopped:',str(e),flush=True)
finally:
 try:
  root=snapshot();home=node(root,'总览')
  if home is not None:tap(home)
  root=snapshot()
  for _ in range(8):
   if node(root,'历史已就绪') is not None:break
   adb('shell','input','swipe','650','1000','650','2200','350');root=snapshot()
  result['history_ready_after']=node(root,'历史已就绪') is not None
 except Exception:result['history_ready_after']=None
 result['study_pid_after']=adb('shell','pidof','ai.opstate.rokid.phone',check=False).stdout.decode().strip()
 (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
 print(json.dumps(result,ensure_ascii=False),flush=True)

raise SystemExit(0 if result["status"] == "PASS" else 1)
