from pathlib import Path
import hashlib
import io
import json
import zipfile
import requests

folder=Path(__file__).resolve().parents[1]/'app/src/main/assets/licenses'
folder.mkdir(parents=True,exist_ok=True)
sources={'Depth-Anything-V2-Apache-2.0.txt':'https://raw.githubusercontent.com/DepthAnything/Depth-Anything-V2/main/LICENSE'}
for name,url in sources.items():
 response=requests.get(url,timeout=30);response.raise_for_status()
 (folder/name).write_bytes(response.content)
 print(name,len(response.content),flush=True)

# The Maven AAR omits license files. Obtain the same upstream version's notices
# from its official PyPI distribution, with PyPI's published archive hash checked.
metadata=requests.get('https://pypi.org/pypi/onnxruntime/1.27.0/json',timeout=30)
metadata.raise_for_status()
wheel=next(f for f in metadata.json()['urls'] if f['filename']=='onnxruntime-1.27.0-cp311-cp311-win_amd64.whl')
archive=requests.get(wheel['url'],timeout=90);archive.raise_for_status()
assert hashlib.sha256(archive.content).hexdigest()==wheel['digests']['sha256']
with zipfile.ZipFile(io.BytesIO(archive.content)) as bundle:
 for source,target in [('onnxruntime/LICENSE','ONNX-Runtime-MIT.txt'),('onnxruntime/ThirdPartyNotices.txt','ONNX-Runtime-ThirdPartyNotices.txt')]:
  data=bundle.read(source);(folder/target).write_bytes(data);print(target,len(data),flush=True)
(folder/'sources.json').write_text(json.dumps({'upstream_version':'1.27.0','notice_distribution':wheel['url'],'archive_sha256':wheel['digests']['sha256']},indent=2),encoding='utf-8')
