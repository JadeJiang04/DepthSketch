"""Read a failed offline Gradle log, fetch unresolved coordinates from official repositories."""
from pathlib import Path
import re
import requests

ROOT=Path(__file__).resolve().parents[1]
log=(ROOT/'artifacts/build.log').read_text(encoding='utf-16' if (ROOT/'artifacts/build.log').read_bytes().startswith(b'\xff\xfe') else 'utf-8', errors='replace')
coords=set(re.findall(r'Could not resolve ([\w.\-]+):([\w.\-]+):([\w.\-]+)\.',log))
coords.update(re.findall(r'Could not download [^\s]+ \(([\w.\-]+):([\w.\-]+):([\w.\-]+)\)',log))
for group,artifact,version in sorted(coords):
    relative='/'.join([group.replace('.','/'),artifact,version])
    folder=ROOT/'tools/maven'/relative;folder.mkdir(parents=True,exist_ok=True)
    base='https://dl.google.com/dl/android/maven2' if group.startswith(('com.android','androidx.','com.google.testing')) else 'https://repo.maven.apache.org/maven2'
    for ext in ['pom','jar','module']:
        name=f'{artifact}-{version}.{ext}';path=folder/name
        if path.exists():continue
        response=requests.get(f'{base}/{relative}/{name}',timeout=45)
        if response.status_code==404:continue
        response.raise_for_status();path.write_bytes(response.content)
        print('Fetched',name,flush=True)
