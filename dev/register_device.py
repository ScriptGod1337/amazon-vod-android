#!/usr/bin/env python3
"""
One-time Amazon device registration.
Reads AMAZON_EMAIL from .env, prompts for password interactively via getpass.
Password is never written to disk or passed to any agent.
Saves token to .device-token (chmod 600).
"""
import os, json, time, sys, getpass, requests, mechanicalsoup, traceback
from uuid import uuid4
from hashlib import sha256
from base64 import urlsafe_b64encode, b64encode, b16encode
from urllib.parse import urlencode, urlparse, parse_qs

SCRIPT_DIR  = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
TOKEN_PATH  = os.path.join(PROJECT_DIR, '.device-token')
ENV_PATH    = os.path.join(PROJECT_DIR, '.env')
DTID       = 'A43PXU4ZN2AL1'
HEADERS    = {
    'Accept-Charset':    'utf-8',
    'User-Agent':        'Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV RQ1A.210105.003)',
    'X-Requested-With':  'com.amazon.avod.thirdpartyclient',
    'x-gasc-enabled':    'true',
}

def device_data(device_id):
    return {
        'domain':        'DeviceLegacy',
        'device_type':   DTID,
        'device_serial': device_id,
        'app_name':      'com.amazon.avod.thirdpartyclient',
        'app_version':   '296016847',
        'device_model':  'mdarcy/nvidia/SHIELD Android TV',
        'os_version':    'NVIDIA/mdarcy/mdarcy:11/RQ1A.210105.003/7094531_2971.7725:user/release-keys',
    }

def register(email, password, domain='amazon.com'):
    device_id = uuid4().hex.lower()
    clientid  = b16encode((device_id + '#A1MPSLFC7L5AFK').encode()).decode().lower()
    verifier  = urlsafe_b64encode(os.urandom(32)).rstrip(b'=')
    challenge = urlsafe_b64encode(sha256(verifier).digest()).rstrip(b'=')
    frc       = b64encode(os.urandom(313)).decode('ascii')
    map_md    = {
        'device_registration_data': {'software_version': '130050002'},
        'app_identifier': {
            'package':          'com.amazon.avod.thirdpartyclient',
            'SHA-256':          ['2f19adeb284eb36f7f07786152b9a1d14b21653203ad0b04ebbf9c73ab6d7625'],
            'app_version':      '351003955',
            'app_version_name': '3.0.351.3955',
            'app_sms_hash':     'e0kK4QFSWp0',
            'map_version':      'MAPAndroidLib-1.3.14913.0',
        },
        'app_info': {'auto_pv': 0, 'auto_pv_with_smsretriever': 0,
                     'smartlock_supported': 0, 'permission_runtime_grant': 0},
    }

    br = mechanicalsoup.StatefulBrowser(soup_config={'features': 'html.parser'})
    br.set_verbose(0)
    br.session.headers.update(HEADERS)
    br.session.cookies.update({'frc': frc, 'map-md': b64encode(json.dumps(map_md).encode()).decode(), 'sid': ''})

    query = {
        'openid.assoc_handle':             'amzn_piv_android_v2_us',
        'openid.return_to':                f'https://www.{domain}/ap/maplanding',
        'openid.oa2.response_type':        'code',
        'openid.oa2.code_challenge_method': 'S256',
        'openid.oa2.code_challenge':       challenge.decode(),
        'pageId':                          'amzn_dv_ios_blue',
        'openid.ns.oa2':                   'http://www.amazon.com/ap/ext/oauth/2',
        'openid.oa2.client_id':            f'device:{clientid}',
        'openid.ns.pape':                  'http://specs.openid.net/extensions/pape/1.0',
        'openid.oa2.scope':                'device_auth_access',
        'openid.mode':                     'checkid_setup',
        'openid.identity':                 'http://specs.openid.net/auth/2.0/identifier_select',
        'openid.ns':                       'http://specs.openid.net/auth/2.0',
        'accountStatusPolicy':             'P1',
        'openid.claimed_id':               'http://specs.openid.net/auth/2.0/identifier_select',
        'language':                        'en_US',
        'disableLoginPrepopulate':         0,
        'openid.pape.max_auth_age':        0,
    }
    br.session.headers.update({
        'upgrade-insecure-requests': '1',
        'accept':          'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'accept-language': 'en-US,en;q=0.9',
        'host':            f'www.{domain}',
    })

    signin_url = f'https://www.{domain}/ap/signin?{urlencode(query)}'
    print(f'[1/6] Opening PKCE signin page directly...')
    br.open(signin_url)
    print(f'      URL: {br.get_url()[:80]}')

    # Dump available forms for diagnostics
    page_forms = br.get_current_page().find_all('form')
    print(f'      Forms on page: {[f.get("name") or f.get("id") or "?" for f in page_forms]}')

    print('[2/6] Submitting email/password...')
    try:
        br.select_form('form[name="signIn"]')
    except Exception as e:
        page = str(br.get_current_page())
        # Check for error messages in the page
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(page, 'html.parser')
        alert = soup.find(id='auth-error-message-id') or soup.find(class_='a-alert-content')
        hint = alert.get_text(strip=True) if alert else '(no error message found in page)'
        raise RuntimeError(f'Could not find signIn form.\nPage hint: {hint}\nURL: {br.get_url()}') from e
    br['email']    = email
    br['password'] = password
    password = None  # clear immediately
    br.submit_selected()

    url      = br.get_url()
    response = str(br.get_current_page())
    print(f'      Post-login URL: {url[:80]}')

    # Check for wrong password / captcha before MFA loop
    if 'auth-error-message' in response or 'ap_error' in response:
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(response, 'html.parser')
        alert = soup.find(id='auth-error-message-id') or soup.find(class_='a-alert-content')
        hint = alert.get_text(strip=True) if alert else 'unknown login error'
        raise RuntimeError(f'Login rejected by Amazon: {hint}')

    # Handle OTP / MFA
    mfa_round = 0
    while any(k in response for k in ['auth-mfa-form', 'verifyOtp', 'fwcim-form']):
        mfa_round += 1
        print(f'[3/6] MFA challenge #{mfa_round} detected.')
        otp = input('MFA / OTP code: ').strip()
        try:
            br.select_form('form[id="auth-mfa-form"]')
        except mechanicalsoup.LinkNotFoundError:
            br.select_form('form[id="verification-code-form"]')
        br['otpCode'] = otp
        br.submit_selected()
        url      = br.get_url()
        response = str(br.get_current_page())
        print(f'      Post-MFA URL: {url[:80]}')

    if 'openid.oa2.authorization_code' not in url:
        from bs4 import BeautifulSoup
        soup = BeautifulSoup(response, 'html.parser')
        title = soup.find('title')
        alert = soup.find(id='auth-error-message-id') or soup.find(class_='a-alert-content')
        hint  = (alert.get_text(strip=True) if alert else '') or (title.get_text(strip=True) if title else '(no title)')
        raise RuntimeError(
            f'Login failed — no authorization_code in redirect.\n'
            f'Page title/error: {hint}\n'
            f'URL: {url}'
        )

    print('[4/6] Got authorization code, registering device...')
    auth_code = parse_qs(urlparse(url).query)['openid.oa2.authorization_code'][0]
    payload   = {
        'auth_data': {
            'client_id':          clientid,
            'authorization_code': auth_code,
            'code_verifier':      verifier.decode(),
            'code_algorithm':     'SHA-256',
            'client_domain':      'DeviceLegacy',
        },
        'registration_data':      device_data(device_id),
        'requested_token_type':   ['bearer', 'website_cookies'],
        'requested_extensions':   ['device_info', 'customer_info'],
        'cookies':                {'domain': f'.{domain}', 'website_cookies': []},
    }
    reg_headers = {**HEADERS,
                   'x-amzn-identity-auth-domain': f'api.{domain}',
                   'x-amzn-requestid':            uuid4().hex,
                   'Content-Type':                'application/json'}
    resp = requests.post(f'https://api.{domain}/auth/register',
                         headers=reg_headers, data=json.dumps(payload))
    print(f'[5/6] Register HTTP {resp.status_code}')
    if not resp.ok:
        raise RuntimeError(f'Registration request failed: HTTP {resp.status_code}\n{resp.text[:500]}')
    data = resp.json()
    if 'error' in data.get('response', {}):
        raise RuntimeError(data['response']['error']['message'])
    if 'success' not in data.get('response', {}):
        raise RuntimeError(f'Unexpected registration response structure:\n{json.dumps(data, indent=2)[:500]}')

    print('[6/6] Extracting bearer tokens...')
    bearer = data['response']['success']['tokens']['bearer']
    return {
        'access_token':  bearer['access_token'],
        'refresh_token': bearer['refresh_token'],
        'device_id':     device_id,
        'expires_in':    int(bearer['expires_in']),
    }

if __name__ == '__main__':
    env = {}
    with open(ENV_PATH) as f:
        for line in f:
            line = line.strip()
            if '=' in line and not line.startswith('#'):
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip()

    email = env.get('AMAZON_EMAIL')
    if not email:
        print('ERROR: AMAZON_EMAIL not set in .env')
        sys.exit(1)

    print(f'Registering device for {email}')
    password = getpass.getpass('Amazon Password: ')

    try:
        token = register(email, password, domain='amazon.com')
        password = None
    except Exception as e:
        password = None
        print(f'ERROR: {e}')
        traceback.print_exc()
        sys.exit(1)

    with open(TOKEN_PATH, 'w') as f:
        json.dump(token, f, indent=2)
    os.chmod(TOKEN_PATH, 0o600)
    print(f'Token saved to {TOKEN_PATH}')
