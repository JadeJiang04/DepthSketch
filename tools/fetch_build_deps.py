"""Optional workaround for Gradle JVM networking: fetch its missing Maven Central artifacts."""
from pathlib import Path
import requests

ROOT = Path(__file__).resolve().parent / 'maven'
COORDS = [('org.jetbrains.kotlin', a, '1.9.20') for a in
          ['kotlin-reflect', 'kotlin-stdlib', 'kotlin-stdlib-common', 'kotlin-stdlib-jdk7', 'kotlin-stdlib-jdk8']]
COORDS += [('org.jetbrains', 'annotations', '23.0.0')]
COORDS += [('com.microsoft.onnxruntime', 'onnxruntime-android', '1.27.0')]
for group, artifact, version in COORDS:
    relative = '/'.join([group.replace('.', '/'), artifact, version])
    folder = ROOT / relative
    folder.mkdir(parents=True, exist_ok=True)
    for ext in ['pom', 'aar' if artifact == 'onnxruntime-android' else 'jar', 'module']:
        name = f'{artifact}-{version}.{ext}'
        path = folder / name
        if path.exists():
            continue
        response = requests.get(f'https://repo.maven.apache.org/maven2/{relative}/{name}', timeout=30)
        if response.status_code == 404 and ext == 'module':
            continue
        response.raise_for_status()
        path.write_bytes(response.content)
        print('Fetched', name, flush=True)
