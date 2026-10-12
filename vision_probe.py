import json, re, urllib.request

xml = open('/data/data/com.riddle.diary/shared_prefs/riddle.xml', encoding='utf-8').read()
base = re.search(r'<string name="base">(.*?)</string>', xml).group(1)
key = re.search(r'<string name="key">(.*?)</string>', xml).group(1)
print('BASE=' + base, flush=True)

IMG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
MODELS = ['mimo-v2.5', 'deepseek-v4-flash', 'nemotron-3.5-lightning',
          'nv/kimi-k3', 'glm-5.3-flash', 'deepseek-v4.1-flash',
          'claude-opus-5', 'gpt-5.6-terra', 'grok-4.6', 'jev-1.13']

for m in MODELS:
    body = json.dumps({
        'model': m, 'stream': False, 'max_tokens': 300,
        'messages': [{'role': 'user', 'content': [
            {'type': 'text', 'text': 'describe the image in 3 words'},
            {'type': 'image_url', 'image_url': {'url': IMG}}]}]
    }).encode()
    req = urllib.request.Request(base.rstrip('/') + '/chat/completions', data=body,
        headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    try:
        r = urllib.request.urlopen(req, timeout=30)
        d = json.loads(r.read().decode()[:2000])
        try:
            txt = d['choices'][0]['message']['content'][:60]
        except Exception:
            txt = str(d)[:60]
        print(m + ' -> 200 ' + txt.replace(chr(10), ' '), flush=True)
    except urllib.error.HTTPError as e:
        print(m + ' -> HTTP ' + str(e.code) + ' ' + e.read().decode()[:160].replace(chr(10), ' '), flush=True)
    except Exception as e:
        print(m + ' -> ERR ' + str(e)[:120], flush=True)
