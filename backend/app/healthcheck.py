"""Container probe: connect over loopback with a specific, trusted Host header."""
import argparse
import json
import re
import sys
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler

# Internal probes must not follow HTTP_PROXY out of the container.
urlopen=build_opener(ProxyHandler({})).open


def select_probe_host(allowed_hosts, public_url='', configured=''):
    hosts=[value.strip().lower() for value in allowed_hosts.split(',') if value.strip()]

    def valid(host):
        return bool(re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?',host))

    def permitted(host):
        return any(host==allowed or (allowed.startswith('*.') and host.endswith(allowed[1:])) for allowed in hosts)

    if configured:
        host=configured.strip().lower()
        if not valid(host) or not permitted(host):
            raise ValueError('HEALTHCHECK_HOST must be a specific hostname covered by ALLOWED_HOSTS')
        return host
    public_host=(urlsplit(public_url).hostname or '').lower()
    if valid(public_host) and permitted(public_host):
        return public_host
    for host in hosts:
        if valid(host):
            return host
        if host.startswith('*.') and valid(host[2:]):
            return 'healthcheck.'+host[2:]
    raise ValueError('ALLOWED_HOSTS must contain a specific host or named subdomain for the health probe')


def probe(url,host,timeout=8):
    request=Request(url,headers={'Host':host,'User-Agent':'PhoneProof-healthcheck/1'})
    with urlopen(request,timeout=timeout) as response:
        body=json.load(response)
        return response.status==200 and body.get('status')=='ready'


def main():
    from .config import settings
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url',default='http://127.0.0.1:8000/ready')
    args=parser.parse_args()
    try:
        host=select_probe_host(settings.allowed_hosts,settings.public_report_base_url,settings.healthcheck_host)
        healthy=probe(args.url,host,settings.readiness_timeout_seconds+2)
    except Exception:
        healthy=False
    # Probe output deliberately excludes URLs, credentials and dependency errors.
    print('ready' if healthy else 'not ready')
    return 0 if healthy else 1


if __name__=='__main__':
    sys.exit(main())
