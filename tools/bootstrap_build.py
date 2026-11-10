"""Install isolated build dependencies from official distributions; no system changes."""
from pathlib import Path
import concurrent.futures
import hashlib
import json
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.tools'
DEST.mkdir(exist_ok=True)

def download(url, path, checksum=None, algorithm='sha256'):
    if not path.exists():
        print('Downloading', path.name, flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, path.open('wb') as out:
            while block := response.read(1024 * 1024):
                out.write(block)
    if checksum and hashlib.new(algorithm, path.read_bytes()).hexdigest() != checksum:
        raise RuntimeError(f'Checksum mismatch: {path}')

def java():
    try:
        with urllib.request.urlopen('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows', timeout=60) as r:
            package = json.load(r)[0]['binary']['package']
    except urllib.error.HTTPError:
        request = urllib.request.Request('https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest', headers={'User-Agent': 'PhoneProof-build-bootstrap'})
        with urllib.request.urlopen(request, timeout=60) as response:
            assets = json.load(response)['assets']
        asset = next(item for item in assets if item['name'].startswith('OpenJDK17U-jdk_x64_windows_hotspot_') and item['name'].endswith('.zip'))
        checksum_asset = next(item for item in assets if item['name'] == asset['name'] + '.sha256.txt')
        with urllib.request.urlopen(checksum_asset['browser_download_url'], timeout=60) as response:
            checksum = response.read().decode().split()[0]
        package = {'name': asset['name'], 'link': asset['browser_download_url'], 'checksum': checksum}
    archive = DEST / package['name']
    download(package['link'], archive, package['checksum'])
    with zipfile.ZipFile(archive) as z:
        folder = DEST / z.namelist()[0].split('/')[0]
        if not (folder / 'bin/java.exe').exists():
            z.extractall(DEST)
    print('JAVA_HOME=' + str(folder), flush=True)

def gradle():
    url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    with urllib.request.urlopen(url + '.sha256') as r:
        checksum = r.read().decode().strip()
    archive = DEST / 'gradle-8.11.1-bin.zip'
    download(url, archive, checksum)
    if not (DEST / 'gradle-8.11.1/bin/gradle.bat').exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(DEST)
    print('Gradle installed', flush=True)

def sdk():
    import os
    sdk_root = Path(os.environ['LOCALAPPDATA']) / 'Android/Sdk'
    with urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml') as r:
        root = ET.fromstring(r.read())
    for package_path, destination in [('platforms;android-36', 'platforms/android-36'), ('build-tools;35.0.0', 'build-tools/35.0.0')]:
        if (sdk_root / destination / 'source.properties').exists():
            continue
        package = next(x for x in root if x.attrib.get('path') == package_path)
        archives = package.find('archives')
        archive_info = next(x for x in archives if x.findtext('host-os') in (None, 'windows'))
        complete = archive_info.find('complete')
        url = complete.findtext('url')
        checksum = complete.find('checksum')
        archive = DEST / url
        download('https://dl.google.com/android/repository/' + url, archive, checksum.text, checksum.attrib.get('type', 'sha1'))
        target = sdk_root / destination
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            prefix = z.namelist()[0].split('/')[0] + '/'
            for item in z.infolist():
                rel = item.filename.removeprefix(prefix)
                if not rel or item.is_dir():
                    continue
                output = (target / rel).resolve()
                if not output.is_relative_to(target.resolve()):
                    raise ValueError('Unsafe archive path')
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(z.read(item))
        print('Installed', package_path, flush=True)

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(lambda f: f(), [java, gradle, sdk]):
            pass
