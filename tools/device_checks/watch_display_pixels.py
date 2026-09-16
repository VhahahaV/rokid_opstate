import argparse,shutil,subprocess,time,json,hashlib,re,io
from pathlib import Path
from PIL import Image,ImageChops
parser=argparse.ArgumentParser(description='Optional 20-round physical silence screenshot companion; run alongside DisplaySequenceDeviceTest.')
parser.add_argument('--phone',required=True);parser.add_argument('--glasses',required=True)
parser.add_argument('--adb',default=shutil.which('adb'));parser.add_argument('--output',type=Path,default=Path('reports/latest/display-sequence-pixels'))
args=parser.parse_args()
if not args.adb:parser.error('adb must be on PATH or supplied with --adb')
ADB=args.adb;P=args.phone;G=args.glasses;OUT=args.output;OUT.mkdir(parents=True,exist_ok=True)
seen=set();before={};comparisons=[];end=time.monotonic()+500
while time.monotonic()<end:
 r=subprocess.run([ADB,'-s',P,'exec-out','run-as','ai.opstate.rokid.phone','cat','files/validation/display-sequence-stage.json'],capture_output=True)
 try:s=json.loads(r.stdout)
 except Exception:time.sleep(.15);continue
 if s.get('stage') in ('COMPLETE','FAILED') and seen:break
 if s.get('stage')!='PIXEL_CHECK':time.sleep(.15);continue
 token=s['token']
 if token in seen:time.sleep(.15);continue
 if not re.fullmatch(r'(before|after)-\d+',token):raise RuntimeError('Invalid checkpoint token')
 png=subprocess.check_output([ADB,'-s',G,'exec-out','screencap','-p']);image=Image.open(io.BytesIO(png)).convert('RGB')
 (OUT/(token+'.png')).write_bytes(png);i=int(token.split('-')[1]);pixel_hash=hashlib.sha256(image.tobytes()).hexdigest()
 if token.startswith('before'):before[i]=image
 else:
  diff=ImageChops.difference(before[i],image);changed=sum(pixel!=(0,0,0) for pixel in diff.getdata())
  comparisons.append({'round':i,'changed_pixels':changed,'pixel_sha256':pixel_hash,'width':image.width,'height':image.height})
  print(f'Silence {i+1}/20: {changed} changed pixels',flush=True)
 command="run-as ai.opstate.rokid.phone sh -c 'printf %s "+token+" > files/validation/display-pixel.signal'"
 subprocess.run([ADB,'-s',P,'shell',command],check=True,capture_output=True)
 seen.add(token)
 result={'status':'PASS' if len(comparisons)==20 and all(x['changed_pixels']==0 for x in comparisons) else 'INCOMPLETE_OR_CHANGED','comparisons':comparisons,'scope':'ACTUAL_GLASSES_SCREEN_PIXELS'}
 (OUT/'result.json').write_text(json.dumps(result,indent=2))
 time.sleep(.1)
else:raise RuntimeError('Display sequence timed out')
print('Pixel monitor completed.',flush=True)
